package org.koitharu.kotatsu.core.network.webview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AndroidRuntimeException
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.core.view.doOnLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.koitharu.kotatsu.browser.cloudflare.CloudFlareCallback
import org.koitharu.kotatsu.browser.cloudflare.CloudFlareClient
import org.koitharu.kotatsu.core.exceptions.CloudFlareException
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.network.cookies.AndroidCookieJar
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.proxy.ProxyProvider
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.ui.util.ForegroundActivityHolder
import org.koitharu.kotatsu.core.util.ext.configureForParser
import org.koitharu.kotatsu.core.util.ext.prepareDetachedParserViewport
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.sanitizeHeaderValue
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.lang.ref.WeakReference
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.ranges.contains

@Singleton
class WebViewExecutor @Inject constructor(
	@ApplicationContext private val context: Context,
	private val proxyProvider: ProxyProvider,
	private val cookieJar: MutableCookieJar,
	private val foregroundActivityHolder: ForegroundActivityHolder,
	private val mangaRepositoryFactoryProvider: Provider<MangaRepository.Factory>,
) {

	private var webViewCached: WeakReference<WebView>? = null
	private val mutex = Mutex()

	// host -> epoch ms until which we skip new resolve attempts for that host (set after a recent failure).
	// Avoids the "burst of failed images each triggers its own 12 s WebView attempt" cascade.
	private val recentFailureUntil = ConcurrentHashMap<String, Long>()

	val defaultUserAgent: String? by lazy {
		try {
			WebSettings.getDefaultUserAgent(context)
		} catch (e: AndroidRuntimeException) {
			e.printStackTraceDebug()
			// Probably WebView is not available
			null
		}
	}

    suspend fun evaluateJs(
        baseUrl: String?,
        script: String,
        timeoutMs: Long = 15000L,
        preserveCookies: Boolean = false
    ): String? = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val webView = obtainWebView()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            try {
                if (baseUrl.isNullOrEmpty()) {
					return@withContext suspendCancellableCoroutine { cont ->
						webView.evaluateJavascript(script) { result ->
							if (cont.isActive) {
								cont.resume(result.takeUnless { it == "null" })
							}
						}
					}
                }

                val baseUri = android.net.Uri.parse(baseUrl)
                val originalHost = baseUri.host

				suspendCancellableCoroutine { continuation ->
                    var hasResumed = false

                    val resumeOnce: (String?) -> Unit = { result ->
                        if (!hasResumed) {
                            hasResumed = true
                            handler.removeCallbacksAndMessages(null)
                            // Immediately stop further loading/polling
                            webView.stopLoading()
							if (continuation.isActive) {
								continuation.resume(result)
							}
                        }
                    }

					continuation.invokeOnCancellation {
						hasResumed = true
						handler.removeCallbacksAndMessages(null)
						webView.post { webView.stopLoading() }
					}
					if (!continuation.isActive) return@suspendCancellableCoroutine

                    val contentPoller = object : Runnable {
                        val startTime = System.currentTimeMillis()
                        override fun run() {
                            if (hasResumed) return
                            if (System.currentTimeMillis() - startTime >= timeoutMs) {
                                return
                            }
                            webView.evaluateJavascript(script) { result ->
                                if (hasResumed) return@evaluateJavascript
                                val content = result?.takeUnless { it == "null" }
                                if (!content.isNullOrBlank()) {
                                    println("DEBUG: Content found via polling. Returning immediately.")
                                    resumeOnce(content)
                                } else {
                                    handler.postDelayed(this, 1000)
                                }
                            }
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url ?: return false
                            val requestHost = url.host
                            if (originalHost != null && requestHost != null && requestHost.contains(originalHost)) {
                                return false
                            }
                            println("DEBUG: Blocked redirect to external domain: $url")
                            return true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (hasResumed || url == "about:blank") return
                            println("DEBUG: onPageFinished. Checking content...")
                            view?.evaluateJavascript(script) { result ->
                                if (hasResumed) return@evaluateJavascript
                                val content = result?.takeUnless { it == "null" }
                                if (!content.isNullOrBlank()) {
                                    println("DEBUG: Content found on pageFinished. Returning immediately.")
                                    resumeOnce(content)
                                }
                            }
                        }
                    }

                    val headers = mapOf("Accept-Language" to "en-EN,en;q=0.9")
                    if (preserveCookies) {
                        webView.loadDataWithBaseURL(baseUrl, " ", "text/html", null, null)
                    } else {
                        webView.loadUrl(baseUrl, headers)
                    }

                    handler.postDelayed(contentPoller, 1000)

                    handler.postDelayed({
                        if (!hasResumed) {
                            println("ERROR: Overall operation timed out.")
                            resumeOnce(null)
                        }
                    }, timeoutMs)
                }
            } finally {
				handler.removeCallbacksAndMessages(null)
				destroyEvaluatedWebView(webView)
            }
        }
    }

    suspend fun tryResolveCaptcha(exception: CloudFlareException, timeout: Long): Boolean {
		val cooldownHost = runCatching { URI(exception.url).host?.lowercase() }.getOrNull()
		if (cooldownHost != null) {
			val now = System.currentTimeMillis()
			val skipUntil = recentFailureUntil[cooldownHost]
			if (skipUntil != null) {
				if (skipUntil > now) {
					Log.d(TAG, "Skipping captcha auto-resolve for $cooldownHost (cooled down for ${skipUntil - now}ms)")
					return false
				}
				recentFailureUntil.remove(cooldownHost)
			}
		}
		var syncedUrls: List<String> = emptyList()
		var effectiveUserAgent: String? = null
		val resolved = mutex.withLock {
			// Re-check cooldown after acquiring the mutex: a previous waiter may have just failed for the same host.
			if (cooldownHost != null) {
				val skipUntil = recentFailureUntil[cooldownHost]
				if (skipUntil != null && skipUntil > System.currentTimeMillis()) {
					return@withLock false
				}
			}
			runCatchingCancellable { proxyProvider.applyWebViewConfig() }.onFailure { it.printStackTraceDebug() }
			withContext(Dispatchers.Main.immediate) {
				// Build a fresh WebView against the foreground Activity (NOT the cached app-context one), so it
				// matches CloudFlareActivity's WebView — Cloudflare/Turnstile fingerprints differ between
				// app-context and Activity-context WebViews. Falls back to the cached one if no Activity is
				// available (e.g. background path).
				val activity = foregroundActivityHolder.current
				val webView: WebView
				val host: ViewGroup?
				val isThrowaway: Boolean
				if (activity != null) {
					webView = WebView(activity).apply { configureForParser(null) }
					host = attachToHost(webView, activity)
					isThrowaway = true
				} else {
					webView = obtainWebView()
					host = null
					isThrowaway = false
				}
				try {
					webView.webChromeClient = WebChromeClient()
					webView.onResume()
					val requestUserAgent = (exception as? CloudFlareProtectedException)
						?.headers
						?.get(CommonHeaders.USER_AGENT)
						?: exception.source.getUserAgent()
					val alignedUserAgent = requestUserAgent?.let(::alignUserAgentWithEngine)
					alignedUserAgent?.let {
						webView.settings.userAgentString = it
					}
					val stripRequestedWith = needsCloudFlareInterception(exception.source)
					applyCloudFlareRequestedWithPolicy(webView.settings, stripRequestedWith)
					val resolved = withTimeoutOrNull(timeout) {
						awaitVisibleLayout(webView)
						webView.requestFocus()
						suspendCancellableCoroutine { cont ->
							webView.webViewClient = createCloudFlareClient(webView, exception, cont)
							webView.loadUrl(exception.url)
						}
					}
					if (resolved == true) {
						syncedUrls = listOfNotNull(exception.url, webView.url).distinct()
						effectiveUserAgent = webView.settings.userAgentString
							?.sanitizeHeaderValue()
							?.trim()
							?.nullIfEmpty()
					} else if (resolved == null) {
						Log.w(TAG, "Captcha auto-resolve timed out for ${exception.url}, dumping page HTML:")
						dumpPageHtml(webView)
					}
					resolved == true
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					exception.addSuppressed(e)
					e.printStackTraceDebug()
					false
				} finally {
					runCatching { webView.onPause() }
					webView.webChromeClient = WebChromeClient()
					if (isThrowaway) {
						runCatching { webView.stopLoading() }
						webView.webViewClient = WebViewClient()
						host?.let { detachFromHost(webView, it) }
						runCatching { webView.destroy() }
					} else {
						webView.reset()
					}
				}
			}
		}
		if (resolved) {
			persistSolvedState(exception.source, syncedUrls, effectiveUserAgent)
		}
		if (cooldownHost != null) {
			if (resolved) {
				recentFailureUntil.remove(cooldownHost)
			} else {
				recentFailureUntil[cooldownHost] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
			}
		}
		return resolved
	}

	private fun alignUserAgentWithEngine(candidate: String): String {
		val aligned = alignUserAgentWithEngine(candidate, defaultUserAgent) ?: candidate
		if (aligned != candidate) {
			Log.w(
				TAG,
				"Configured User-Agent contradicts the WebView engine, realigning so the challenge can pass." +
					"\n  was: $candidate\n  now: $aligned",
			)
		}
		return aligned
	}

	private suspend fun persistSolvedState(
		source: MangaSource,
		urls: List<String>,
		webViewUserAgent: String?,
	) = runInterruptible(Dispatchers.IO) {
		val cookieManager = CookieManager.getInstance()
		cookieManager.flush()
		if (cookieJar !is AndroidCookieJar) {
			for (rawUrl in urls) {
				val httpUrl = rawUrl.toHttpUrlOrNull() ?: continue
				val rawCookies = cookieManager.getCookie(rawUrl) ?: continue
				val cookies = rawCookies.split(';').mapNotNull { part ->
					Cookie.parse(httpUrl, part.trim())
				}
				if (cookies.isNotEmpty()) {
					cookieJar.saveFromResponse(httpUrl, cookies)
				}
			}
		}
		val targetSource = source.takeUnless { it == UnknownMangaSource }
		if (targetSource != null && !webViewUserAgent.isNullOrEmpty()) {
			runCatching {
				SourceSettings(context, targetSource).cloudFlareUserAgent = webViewUserAgent
			}.onFailure {
				it.printStackTraceDebug()
			}
		}
	}

	/**
	 * Cloudflare Turnstile only completes when the WebView is actually attached to a real window — a detached
	 * WebView reliably returns the `rBxl / GPZI` anti-bot rejection. Attach the WebView to the foreground
	 * Activity's content view (full size, on top) for the duration of the auto-resolve so it has a real
	 * Surface, real layout, and is visible to the user while the challenge runs.
	 *
	 * @return the container we attached to (so we can remove the WebView again), or `null` if no Activity was
	 *         available. In that case we fall back to the detached-WebView behaviour.
	 */
	@MainThread
	private fun attachToHost(webView: WebView, activity: android.app.Activity): ViewGroup? {
		val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
		runCatching {
			(webView.parent as? ViewGroup)?.removeView(webView)
			webView.alpha = 1f
			webView.visibility = View.VISIBLE
			content.addView(
				webView,
				ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
			)
			webView.bringToFront()
		}.onFailure {
			it.printStackTraceDebug()
			return null
		}
		return content
	}

	/** Wait until Cloudflare sees a real viewport that has reached the screen at least once. */
	@MainThread
	private suspend fun awaitVisibleLayout(webView: WebView) {
		suspendCancellableCoroutine { cont ->
			webView.doOnLayout {
				if (cont.isActive) cont.resume(Unit)
			}
		}
		suspendCancellableCoroutine { cont ->
			webView.postOnAnimation {
				if (cont.isActive) cont.resume(Unit)
			}
		}
	}

	@MainThread
	private fun detachFromHost(webView: WebView, host: ViewGroup) {
		runCatching { host.removeView(webView) }.onFailure { it.printStackTraceDebug() }
	}

	/**
	 * Builds the very same WebViewClient that [org.koitharu.kotatsu.browser.cloudflare.CloudFlareActivity] uses,
	 * just driven by a continuation instead of an Activity UI.
	 */
	@MainThread
	private fun createCloudFlareClient(
		webView: WebView,
		exception: CloudFlareException,
		continuation: CancellableContinuation<Boolean>,
	): CloudFlareClient {
		val handler = Handler(Looper.getMainLooper())
		val resumeOnce: (Boolean) -> Unit = { result ->
			handler.removeCallbacksAndMessages(null)
			if (result) {
				CookieManager.getInstance().flush()
			}
			if (continuation.isActive) continuation.resume(result)
		}
		var consecutivePasses = 0
		// Cookie changes are intentionally ignored here. They occur between managed-challenge stages and
		// accepting the first one destroys the WebView before Cloudflare has finished verification.
		val check = object : Runnable {
			override fun run() {
				if (!continuation.isActive) return
				webView.evaluateJavascript(CF_STATE_JS) { raw ->
					if (!continuation.isActive) return@evaluateJavascript
					if (raw?.removeSurrounding("\"") == "ok") {
						consecutivePasses++
						if (consecutivePasses >= REQUIRED_STABLE_PASSES) {
							resumeOnce(true)
							return@evaluateJavascript
						}
					} else {
						consecutivePasses = 0
					}
					handler.removeCallbacks(this)
					handler.postDelayed(this, CHALLENGE_POLL_INTERVAL_MS)
				}
			}
		}
		val callback = object : CloudFlareCallback {
			override fun onLoadingStateChanged(isLoading: Boolean) = Unit
			override fun onHistoryChanged() = Unit

			override fun onPageLoaded() {
				if (!continuation.isActive) return
				// Re-probe immediately on every navigation so a redirect to the real page is detected at once.
				handler.removeCallbacks(check)
				handler.postDelayed(check, 100L)
			}

			override fun onCheckPassed() {
				// A cookie changed. Probe the DOM sooner, but do not finish until the page is stable.
				handler.removeCallbacks(check)
				handler.postDelayed(check, 100L)
			}

			// CloudFlareClient gives up after a few reloads; headlessly we'd rather keep going until the
			// page-state poll sees the real page (or the overall timeout fires).
			override fun onLoopDetected() = Unit
		}
		return CloudFlareClient(cookieJar, callback, exception.url)
	}

	/**
	 * Executes an HTTP request inside an invisible WebView using `fetch()` so requests blocked by
	 * OkHttp TLS/HTTP2 fingerprinting (even with a valid `cf_clearance`) succeed in the background
	 * without opening a browser window.
	 */
	suspend fun executeCloudFlareRequest(
		request: Request,
		source: MangaSource?,
		timeoutMs: Long = BRIDGE_CHALLENGE_TIMEOUT_MS,
	): Response? {
		val host = request.url.host.lowercase()
		val now = System.currentTimeMillis()
		val skipUntil = recentFailureUntil[host]
		if (skipUntil != null) {
			if (skipUntil > now) {
				Log.d(TAG, "Skipping WebView HTTP bridge for $host (cooled down for ${skipUntil - now}ms)")
				return null
			}
			recentFailureUntil.remove(host)
		}

		val targetSource = source ?: request.tag(MangaSource::class.java)
		val resolvedSource = targetSource ?: UnknownMangaSource
		val requestBodyBytes = request.body?.let { body ->
			runCatching {
				Buffer().also { body.writeTo(it) }.readByteArray()
			}.getOrNull()
		}

		var syncedUrls: List<String> = emptyList()
		var effectiveUserAgent: String? = null
		val response = mutex.withLock {
			val skipAfterLock = recentFailureUntil[host]
			if (skipAfterLock != null && skipAfterLock > System.currentTimeMillis()) {
				return@withLock null
			}
			runCatchingCancellable { proxyProvider.applyWebViewConfig() }.onFailure { it.printStackTraceDebug() }
			withContext(Dispatchers.Main.immediate) {
				val activity = foregroundActivityHolder.current
				val webView: WebView
				val hostView: ViewGroup?
				val isThrowaway: Boolean
				if (activity != null) {
					webView = WebView(activity).apply { configureForParser(null) }
					hostView = attachToInvisibleHost(webView, activity)
					isThrowaway = true
				} else {
					webView = obtainWebView()
					hostView = null
					isThrowaway = false
				}
				try {
					webView.webChromeClient = WebChromeClient()
					webView.onResume()
					val requestUserAgent = request.header(CommonHeaders.USER_AGENT)
						?: targetSource?.getUserAgent()
					val alignedUserAgent = requestUserAgent?.let(::alignUserAgentWithEngine)
					alignedUserAgent?.let {
						webView.settings.userAgentString = it
					}
					if (targetSource != null) {
						applyCloudFlareRequestedWithPolicy(webView.settings, needsCloudFlareInterception(targetSource))
					}
					if (hostView != null) {
						awaitVisibleLayout(webView)
					}

					val originUrl = "${request.url.scheme}://${request.url.host}/"
					val challengePassed = loadAndPassChallenge(
						webView = webView,
						originUrl = originUrl,
						source = resolvedSource,
						timeoutMs = timeoutMs,
					)
					if (!challengePassed) {
						Log.w(TAG, "Background WebView challenge did not pass for $originUrl")
						return@withContext null
					}

					val bridged = executeFetchInWebView(webView, request, requestBodyBytes)
					if (bridged != null && !isCloudFlareChallengeResponse(bridged)) {
						syncedUrls = listOfNotNull(originUrl, request.url.toString(), webView.url).distinct()
						effectiveUserAgent = webView.settings.userAgentString
							?.sanitizeHeaderValue()
							?.trim()
							?.nullIfEmpty()
						bridged
					} else {
						bridged?.close()
						null
					}
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					e.printStackTraceDebug()
					null
				} finally {
					runCatching { webView.onPause() }
					webView.webChromeClient = WebChromeClient()
					if (isThrowaway) {
						runCatching { webView.stopLoading() }
						webView.webViewClient = WebViewClient()
						hostView?.let { detachFromHost(webView, it) }
						runCatching { webView.destroy() }
					} else {
						webView.reset()
					}
				}
			}
		}

		if (response != null) {
			if (resolvedSource != UnknownMangaSource) {
				persistSolvedState(resolvedSource, syncedUrls, effectiveUserAgent)
			}
			recentFailureUntil.remove(host)
		} else {
			recentFailureUntil[host] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
		}
		return response
	}

	@MainThread
	private fun attachToInvisibleHost(webView: WebView, activity: android.app.Activity): ViewGroup? {
		val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
		runCatching {
			(webView.parent as? ViewGroup)?.removeView(webView)
			webView.alpha = 0.01f
			webView.visibility = View.VISIBLE
			webView.isClickable = false
			webView.isFocusable = false
			webView.isFocusableInTouchMode = false
			content.addView(
				webView,
				ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
			)
		}.onFailure {
			it.printStackTraceDebug()
			return null
		}
		return content
	}

	@MainThread
	private suspend fun loadAndPassChallenge(
		webView: WebView,
		originUrl: String,
		source: MangaSource,
		timeoutMs: Long,
	): Boolean {
		val dummyException = CloudFlareProtectedException(
			url = originUrl,
			source = source,
			headers = Headers.headersOf(),
		)
		val resolved = withTimeoutOrNull(timeoutMs) {
			suspendCancellableCoroutine { cont ->
				webView.webViewClient = createCloudFlareClient(webView, dummyException, cont)
				webView.loadUrl(originUrl)
			}
		}
		return resolved == true
	}

	@MainThread
	private suspend fun executeFetchInWebView(
		webView: WebView,
		request: Request,
		bodyBytes: ByteArray?,
	): Response? {
		val headersJson = JSONObject().apply {
			for (i in 0 until request.headers.size) {
				val name = request.headers.name(i)
				val lower = name.lowercase()
				if (lower in FORBIDDEN_FETCH_HEADERS) continue
				put(name, request.headers.value(i))
			}
			request.body?.contentType()?.let { ct ->
				if (!has("Content-Type") && !has("content-type")) {
					put("Content-Type", ct.toString())
				}
			}
		}
		val bodyBase64 = bodyBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
		val urlJson = JSONObject.quote(request.url.toString())
		val methodJson = JSONObject.quote(request.method)
		val bodyJson = if (bodyBase64 != null) JSONObject.quote(bodyBase64) else "null"

		val script = """
			(function() {
				delete window.__cfBridgeResult;
				(async function() {
					try {
						var opts = {
							method: $methodJson,
							headers: $headersJson,
							credentials: 'include'
						};
						var b64Body = $bodyJson;
						if (b64Body !== null && $methodJson !== 'GET' && $methodJson !== 'HEAD') {
							var bin = atob(b64Body);
							var arr = new Uint8Array(bin.length);
							for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
							opts.body = arr;
						}
						var resp = await fetch($urlJson, opts);
						var buf = await resp.arrayBuffer();
						var bytes = new Uint8Array(buf);
						var chunks = [];
						var chunkSize = 0x8000;
						for (var j = 0; j < bytes.length; j += chunkSize) {
							chunks.push(String.fromCharCode.apply(null, bytes.subarray(j, j + chunkSize)));
						}
						var hdrs = {};
						resp.headers.forEach(function(v, k) { hdrs[k] = v; });
						window.__cfBridgeResult = JSON.stringify({
							status: resp.status,
							statusText: resp.statusText || '',
							headers: hdrs,
							bodyBase64: btoa(chunks.join(''))
						});
					} catch (e) {
						window.__cfBridgeResult = JSON.stringify({ error: String(e) });
					}
				})();
			})();
		""".trimIndent()

		webView.evaluateJavascript(script, null)

		val rawResult = withTimeoutOrNull(BRIDGE_FETCH_TIMEOUT_MS) {
			while (true) {
				delay(50L)
				val polled = suspendCancellableCoroutine<String?> { cont ->
					webView.evaluateJavascript("window.__cfBridgeResult || null") { value ->
						if (cont.isActive) cont.resume(value)
					}
				}
				if (polled != null && polled != "null") {
					return@withTimeoutOrNull unquoteJsString(polled)
				}
			}
			@Suppress("UNREACHABLE_CODE")
			null
		} ?: return null

		val json = runCatching { JSONObject(rawResult) }.getOrNull() ?: return null
		if (json.has("error")) {
			Log.w(TAG, "WebView fetch error for ${request.url}: ${json.optString("error")}")
			return null
		}
		val status = json.optInt("status", 0)
		if (status <= 0) return null
		val statusText = json.optString("statusText", "OK").ifEmpty { "OK" }
		val responseHeadersBuilder = Headers.Builder()
		val hdrsObj = json.optJSONObject("headers")
		var contentType: String? = null
		if (hdrsObj != null) {
			val keys = hdrsObj.keys()
			while (keys.hasNext()) {
				val key = keys.next()
				val lower = key.lowercase()
				if (lower == "content-encoding" || lower == "content-length" || lower == "transfer-encoding") {
					continue
				}
				val value = hdrsObj.optString(key)
				if (lower == "content-type") {
					contentType = value
				}
				runCatching { responseHeadersBuilder.add(key, value) }
			}
		}
		val decodedBytes = Base64.decode(json.optString("bodyBase64", ""), Base64.DEFAULT)
		return Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(status)
			.message(statusText)
			.headers(responseHeadersBuilder.build())
			.body(decodedBytes.toResponseBody(contentType?.toMediaTypeOrNull()))
			.build()
	}

	private fun unquoteJsString(raw: String): String? {
		if (raw == "null") return null
		return runCatching { JSONObject("{\"v\":$raw}").getString("v") }.getOrNull()
	}

	private fun isCloudFlareChallengeResponse(response: Response): Boolean {
		if (response.code != 403 && response.code != 503) return false
		val cfMitigated = response.header("cf-mitigated")
		if (cfMitigated.equals("challenge", ignoreCase = true)) return true
		val bodyPreview = runCatching { response.peekBody(8192L).string() }.getOrDefault("")
		return bodyPreview.contains("challenge-error-title") ||
			bodyPreview.contains("challenge-error-text") ||
			bodyPreview.contains("Just a moment...")
	}

	private fun needsCloudFlareInterception(source: MangaSource): Boolean = runCatching {
		val repository = mangaRepositoryFactoryProvider.get().create(source) as? ParserMangaRepository ?: return false
		repository.getConfigKeys().filterIsInstance<ConfigKey.InterceptCloudflare>().firstOrNull()?.defaultValue == true
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrDefault(false)

	@MainThread
	private suspend fun dumpPageHtml(webView: WebView) {
		runCatchingCancellable {
			val html = withTimeoutOrNull(2_000L) {
				suspendCancellableCoroutine<String?> { cont ->
					webView.evaluateJavascript("document.documentElement.outerHTML") { result ->
						if (cont.isActive) cont.resume(result)
					}
				}
			}
			Log.w(TAG, html.orEmpty())
		}.onFailure { it.printStackTraceDebug() }
	}

    @MainThread
    private fun obtainWebView(): WebView = webViewCached?.get() ?: WebView(context).also {
        it.configureForParser(null)
        it.prepareDetachedParserViewport()
        webViewCached = WeakReference(it)
    }

	/**
	 * Evaluation WebViews are intentionally one-shot. Keeping the loaded document alive after returning lets
	 * media, animation frames, workers, and page timers continue consuming power in the background.
	 */
	@MainThread
	private fun destroyEvaluatedWebView(webView: WebView) {
		if (webViewCached?.get() === webView) {
			webViewCached = null
		}
		runCatching { webView.stopLoading() }
		runCatching { webView.onPause() }
		webView.webViewClient = WebViewClient()
		webView.webChromeClient = null
		runCatching { webView.loadUrl("about:blank") }
		runCatching { webView.clearHistory() }
		runCatching { webView.removeAllViews() }
		runCatching { webView.destroy() }
	}

	private fun MangaSource.getUserAgent(): String? {
		val repository = mangaRepositoryFactoryProvider.get().create(this) as? ParserMangaRepository
		return repository?.getRequestHeaders()?.get(CommonHeaders.USER_AGENT)
	}

    @MainThread
    fun getDefaultUserAgentSync() = runCatching {
        obtainWebView().settings.userAgentString.sanitizeHeaderValue().trim().nullIfEmpty()
    }.onFailure { e ->
        e.printStackTraceDebug()
    }.getOrNull()

	@MainThread
	private fun WebView.reset() {
		stopLoading()
		webViewClient = WebViewClient()
		settings.userAgentString = defaultUserAgent
		loadDataWithBaseURL(null, " ", "text/html", null, null)
		clearHistory()
	}

	private companion object {

		const val TAG = "WebViewExecutor"
		const val CHALLENGE_POLL_INTERVAL_MS = 700L
		const val REQUIRED_STABLE_PASSES = 3
		const val BRIDGE_CHALLENGE_TIMEOUT_MS = 12_000L
		const val BRIDGE_FETCH_TIMEOUT_MS = 15_000L

		private val FORBIDDEN_FETCH_HEADERS = setOf(
			"cookie",
			"host",
			"content-length",
			"user-agent",
			"accept-encoding",
			"connection",
			"origin",
			"referer",
		)

		// After a failed auto-resolve for a host, skip new attempts for this long so a burst of failing
		// requests (favicon + catalog + chapters + …) doesn't queue up multiple full timeouts.
		const val FAILURE_COOLDOWN_MS = 30_000L
	}
}
