package org.koitharu.kotatsu.core.network.webview

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AndroidRuntimeException
import android.util.Base64
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
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
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler
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
	private val captchaHandlerProvider: Provider<CaptchaHandler>,
) {

	private var webViewCached: WeakReference<WebView>? = null
	private val mutex = Mutex()

	// host -> epoch ms until which we skip new resolve attempts for that host (set after a recent failure).
	// Avoids the "burst of failed images each triggers its own 12 s WebView attempt" cascade.
	private val recentFailureUntil = ConcurrentHashMap<String, Long>()
	private val recentSolvedHostAt = ConcurrentHashMap<String, Long>()
	private val redirectedOriginByHost = ConcurrentHashMap<String, RedirectOrigin>()

	fun hasRecentlySolvedHost(host: String?): Boolean = lastSolvedHostAt(host) > 0L

	fun lastSolvedHostAt(host: String?): Long {
		val normalized = host?.lowercase() ?: return 0L
		val solvedAt = recentSolvedHostAt[normalized] ?: return 0L
		if (System.currentTimeMillis() - solvedAt <= SOLVED_HOST_TTL_MS) {
			return solvedAt
		}
		recentSolvedHostAt.remove(normalized)
		redirectedOriginByHost.remove(normalized)
		return 0L
	}

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
                            val scheme = url.scheme?.lowercase()
                            if (scheme != "http" && scheme != "https") {
                                return true
                            }
                            val requestHost = url.host?.lowercase() ?: return true
                            val origHost = originalHost?.lowercase()
                            if (origHost != null && isAllowedEvaluateNavigation(origHost, requestHost, request.isForMainFrame)) {
                                return false
                            }
                            Log.d(TAG, "Blocked redirect to external domain in evaluateJs: $url")
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
				val activity = foregroundActivityHolder.current ?: foregroundActivityHolder.awaitCurrent(1_500L)
				val webView: WebView
				val host: ViewGroup?
				val isThrowaway: Boolean
				if (activity != null) {
					webView = TouchPassThroughWebView(activity).apply { configureForParser(null) }
					host = attachToInvisibleHost(webView, activity)
					isThrowaway = true
				} else {
					webView = obtainWebView()
					host = null
					isThrowaway = false
				}
				try {
					webView.webChromeClient = createDiagnosticWebChromeClient()
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
					if (host != null) {
						awaitVisibleLayout(webView)
					}
					clearRejectedClearance(exception.url)
					val resolved = loadAndPassChallenge(
						webView = webView,
						originUrl = exception.url,
						source = exception.source,
						timeoutMs = timeout,
					)
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
			cooldownHost?.let { recentSolvedHostAt[it] = System.currentTimeMillis() }
			persistSolvedState(exception.source, syncedUrls, effectiveUserAgent)
			if (exception.source != UnknownMangaSource) {
				captchaHandlerProvider.get().markSourceResolved(exception.source)
			}
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
		var consecutiveErrors = 0
		// Cookie changes are intentionally ignored here. They occur between managed-challenge stages and
		// accepting the first one destroys the WebView before Cloudflare has finished verification.
		val check = object : Runnable {
			override fun run() {
				if (!continuation.isActive) return
				webView.evaluateJavascript(CF_STATE_JS) { raw ->
					if (!continuation.isActive) return@evaluateJavascript
					when (raw?.removeSurrounding("\"")) {
						"ok" -> {
							consecutiveErrors = 0
							consecutivePasses++
							if (consecutivePasses >= REQUIRED_STABLE_PASSES) {
								resumeOnce(true)
								return@evaluateJavascript
							}
						}

						"error" -> {
							consecutivePasses = 0
							consecutiveErrors++
							if (consecutiveErrors >= REQUIRED_STABLE_ERRORS) {
								resumeOnce(false)
								return@evaluateJavascript
							}
						}

						else -> {
							consecutivePasses = 0
							consecutiveErrors = 0
						}
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
	 * Executes an HTTP request inside an invisible, touch-pass-through WebView using `fetch()` so
	 * requests blocked by OkHttp TLS/HTTP2 fingerprinting (even with a valid `cf_clearance`) succeed
	 * in the background without opening a browser window or blocking user input.
	 */
	suspend fun executeCloudFlareRequest(
		request: Request,
		source: MangaSource?,
		allowChallenge: Boolean = true,
		timeoutMs: Long = BRIDGE_CHALLENGE_TIMEOUT_MS,
	): Response? {
		val host = request.url.host.lowercase()
		val isRecentlySolved = hasRecentlySolvedHost(host)
		if (!allowChallenge && !isRecentlySolved) {
			return null
		}
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
			val solvedFast = hasRecentlySolvedHost(host)
			if (!allowChallenge && !solvedFast) {
				return@withLock null
			}
			runCatchingCancellable { proxyProvider.applyWebViewConfig() }.onFailure { it.printStackTraceDebug() }
			withContext(Dispatchers.Main.immediate) {
				val originUrl = "${request.url.scheme}://${request.url.host}/"
				val requestUserAgent = request.header(CommonHeaders.USER_AGENT)
					?: targetSource?.getUserAgent()
				val alignedUserAgent = requestUserAgent?.let(::alignUserAgentWithEngine)
				val stripRequestedWith = targetSource != null && needsCloudFlareInterception(targetSource)

				// Fast-path for recently solved hosts: reuse headless WebView without attaching any view
				if (solvedFast) {
					val redirectedOrigin = getRedirectedOrigin(host)
					val fastRequest = if (redirectedOrigin != null) {
						request.rewriteOrigin(redirectedOrigin.scheme, redirectedOrigin.host, redirectedOrigin.port)
					} else {
						request
					}
					val fastOriginUrl = fastRequest.url.newBuilder()
						.encodedPath("/")
						.query(null)
						.fragment(null)
						.build()
						.toString()
					val headless = obtainWebView()
					try {
						headless.webChromeClient = WebChromeClient()
						headless.onResume()
						alignedUserAgent?.let { headless.settings.userAgentString = it }
						applyCloudFlareRequestedWithPolicy(headless.settings, stripRequestedWith)
						ensureOriginContext(headless, fastOriginUrl)
						val fastResponse = executeFetchInWebView(headless, fastRequest, targetSource, requestBodyBytes)
						if (fastResponse != null && !isCloudFlareChallengeResponse(fastResponse)) {
							val nowMs = System.currentTimeMillis()
							recentSolvedHostAt[host] = nowMs
							val finalHost = fastResponse.request.url.host.lowercase()
							if (finalHost != host) {
								recentSolvedHostAt[finalHost] = nowMs
							}
							syncedUrls = listOfNotNull(
								originUrl,
								fastOriginUrl,
								request.url.toString(),
								fastRequest.url.toString(),
								headless.url,
							).distinct()
							effectiveUserAgent = headless.settings.userAgentString
								?.sanitizeHeaderValue()
								?.trim()
								?.nullIfEmpty()
							return@withContext fastResponse
						}
						fastResponse?.close()
						recentSolvedHostAt.remove(host)
						redirectedOriginByHost.remove(host)
					} finally {
						runCatching { headless.onPause() }
						headless.webChromeClient = WebChromeClient()
					}
					if (!allowChallenge) {
						return@withContext null
					}
				}

				val activity = foregroundActivityHolder.current ?: foregroundActivityHolder.awaitCurrent(1_500L)
				val webView: WebView
				val hostView: ViewGroup?
				val isThrowaway: Boolean
				if (activity != null) {
					webView = TouchPassThroughWebView(activity).apply { configureForParser(null) }
					hostView = attachToInvisibleHost(webView, activity)
					isThrowaway = true
				} else {
					webView = obtainWebView()
					hostView = null
					isThrowaway = false
				}
				try {
					webView.webChromeClient = createDiagnosticWebChromeClient()
					webView.onResume()
					alignedUserAgent?.let {
						webView.settings.userAgentString = it
					}
					applyCloudFlareRequestedWithPolicy(webView.settings, stripRequestedWith)
					if (hostView != null) {
						awaitVisibleLayout(webView)
					}

					val challengeUrl = if (request.method.equals("GET", ignoreCase = true)) {
						request.url.toString()
					} else {
						originUrl
					}
					clearRejectedClearance(challengeUrl)
					val challengePassed = loadAndPassChallenge(
						webView = webView,
						originUrl = challengeUrl,
						source = resolvedSource,
						timeoutMs = timeoutMs,
					)
					if (!challengePassed) {
						Log.w(TAG, "Background WebView challenge did not pass for $challengeUrl (hostView=${hostView != null})")
						return@withContext null
					}

					val fetchRequest = alignRequestWithWebViewOrigin(request, webView.url?.toHttpUrlOrNull())
					var bridged = executeFetchInWebView(webView, fetchRequest, targetSource, requestBodyBytes)
					// If originUrl passed (e.g. cached homepage) but the actual POST/API endpoint still requires its own clearance,
					// solve directly on the request URL once and retry fetch.
					if ((bridged == null || isCloudFlareChallengeResponse(bridged)) && challengeUrl != fetchRequest.url.toString()) {
						bridged?.close()
						val endpointPassed = loadAndPassChallenge(
							webView = webView,
							originUrl = fetchRequest.url.toString(),
							source = resolvedSource,
							timeoutMs = timeoutMs,
						)
						if (endpointPassed) {
							val retryFetchRequest = alignRequestWithWebViewOrigin(fetchRequest, webView.url?.toHttpUrlOrNull())
							bridged = executeFetchInWebView(webView, retryFetchRequest, targetSource, requestBodyBytes)
						} else {
							bridged = null
						}
					}
					if (bridged != null && !isCloudFlareChallengeResponse(bridged)) {
						val nowMs = System.currentTimeMillis()
						recentSolvedHostAt[host] = nowMs
						val finalUrl = bridged.request.url
						val finalHost = finalUrl.host.lowercase()
						if (finalHost != host) {
							recentSolvedHostAt[finalHost] = nowMs
							redirectedOriginByHost[host] = RedirectOrigin(
								scheme = finalUrl.scheme,
								host = finalUrl.host,
								port = finalUrl.port,
								recordedAtMs = nowMs,
							)
						} else {
							redirectedOriginByHost.remove(host)
						}
						syncedUrls = listOfNotNull(
							originUrl,
							request.url.toString(),
							fetchRequest.url.toString(),
							finalUrl.toString(),
							webView.url,
						).distinct()
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
				captchaHandlerProvider.get().markSourceResolved(resolvedSource)
			}
			recentFailureUntil.remove(host)
		} else if (allowChallenge) {
			recentFailureUntil[host] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
		}
		return response
	}

	@MainThread
	private suspend fun ensureOriginContext(webView: WebView, originUrl: String) {
		val currentUrl = webView.url
		if (currentUrl != null && currentUrl.startsWith(originUrl, ignoreCase = true)) {
			return
		}
		withTimeoutOrNull(2_000L) {
			suspendCancellableCoroutine<Unit> { cont ->
				webView.webViewClient = object : WebViewClient() {
					override fun onPageFinished(view: WebView?, url: String?) {
						if (cont.isActive) cont.resume(Unit)
					}
				}
				webView.loadDataWithBaseURL(originUrl, "<!DOCTYPE html><html><body></body></html>", "text/html", "utf-8", null)
			}
		}
	}

	@MainThread
	private fun attachToInvisibleHost(webView: WebView, activity: android.app.Activity): ViewGroup? {
		val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
		runCatching {
			(webView.parent as? ViewGroup)?.removeView(webView)
			webView.setBackgroundColor(Color.TRANSPARENT)
			webView.alpha = 0.01f
			webView.visibility = View.VISIBLE
			webView.isClickable = false
			webView.isFocusable = true
			webView.isFocusableInTouchMode = false
			webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
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

	private fun createDiagnosticWebChromeClient(): WebChromeClient = object : WebChromeClient() {
		override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
			if (consoleMessage != null && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
				Log.w(TAG, "WebView console [${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}]: ${consoleMessage.message()}")
			}
			return super.onConsoleMessage(consoleMessage)
		}
	}

	private fun clearRejectedClearance(rawUrl: String) {
		val httpUrl = rawUrl.toHttpUrlOrNull() ?: return
		runCatching {
			cookieJar.removeCookies(httpUrl) { it.name == "cf_clearance" }
			val cm = CookieManager.getInstance()
			val rawCookies = cm.getCookie(rawUrl) ?: return@runCatching
			val expiredAt = System.currentTimeMillis() - 100_000L
			for (part in rawCookies.split(';')) {
				val cookie = Cookie.parse(httpUrl, part.trim()) ?: continue
				if (cookie.name == "cf_clearance") {
					cm.setCookie(rawUrl, cookie.newBuilder().expiresAt(expiredAt).build().toString())
				}
			}
			cm.flush()
		}.onFailure { it.printStackTraceDebug() }
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
		val startTime = System.currentTimeMillis()
		val resolved = withTimeoutOrNull(timeoutMs) {
			kotlinx.coroutines.coroutineScope {
				val challengeDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
				val mainJob = launch(Dispatchers.Main.immediate) {
					val res = suspendCancellableCoroutine<Boolean> { cont ->
						webView.webViewClient = createCloudFlareClient(webView, dummyException, cont)
						webView.loadUrl(originUrl)
					}
					challengeDeferred.complete(res)
				}
				val tapJob = launch(Dispatchers.Main.immediate) {
					// Give managed JS/Turnstile challenges 6s to auto-complete before attempting a synthetic tap
					delay(6_000L)
					var attempts = 0
					while (!challengeDeferred.isCompleted && attempts < 2) {
						val elapsed = System.currentTimeMillis() - startTime
						if (elapsed + 1_200L >= timeoutMs) break
						val coords = probeTurnstileWidgetCenter(webView)
						if (coords != null) {
							attempts++
							dispatchSyntheticTap(webView, coords.first, coords.second)
							delay(2_000L)
						} else {
							delay(700L)
						}
					}
				}
				val result = challengeDeferred.await()
				tapJob.cancel()
				mainJob.cancel()
				result
			}
		}
		return resolved == true
	}

	@MainThread
	private suspend fun probeTurnstileWidgetCenter(webView: WebView): Pair<Float, Float>? {
		val raw = withTimeoutOrNull(400L) {
			suspendCancellableCoroutine<String?> { cont ->
				webView.evaluateJavascript(CF_TURNSTILE_TAP_PROBE_JS) { value ->
					if (cont.isActive) cont.resume(value)
				}
			}
		} ?: return null
		val cleaned = unquoteJsString(raw) ?: return null
		val parts = cleaned.split(',')
		if (parts.size != 2) return null
		val x = parts[0].toFloatOrNull() ?: return null
		val y = parts[1].toFloatOrNull() ?: return null
		val density = webView.resources.displayMetrics.density
		return (x * density) to (y * density)
	}

	@MainThread
	private fun dispatchSyntheticTap(webView: WebView, xPx: Float, yPx: Float) {
		val touchWebView = webView as? TouchPassThroughWebView
		val downTime = SystemClock.uptimeMillis()
		val props = arrayOf(
			MotionEvent.PointerProperties().apply {
				id = 0
				toolType = MotionEvent.TOOL_TYPE_FINGER
			},
		)
		val coords = arrayOf(
			MotionEvent.PointerCoords().apply {
				x = xPx
				y = yPx
				pressure = 0.8f
				size = 0.2f
				touchMajor = 24f
				touchMinor = 24f
			},
		)
		val down = MotionEvent.obtain(
			downTime, downTime, MotionEvent.ACTION_DOWN, 1, props, coords,
			0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
		)
		val up = MotionEvent.obtain(
			downTime, downTime + 65L, MotionEvent.ACTION_UP, 1, props, coords,
			0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
		)
		try {
			if (touchWebView != null) {
				touchWebView.dispatchSyntheticTouch(down)
				touchWebView.dispatchSyntheticTouch(up)
			} else {
				webView.dispatchTouchEvent(down)
				webView.dispatchTouchEvent(up)
			}
		} finally {
			down.recycle()
			up.recycle()
		}
	}

	private class TouchPassThroughWebView(context: Context) : WebView(context) {
		override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
		override fun onTouchEvent(event: MotionEvent?): Boolean = false
		override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false
		fun dispatchSyntheticTouch(ev: MotionEvent): Boolean = super.dispatchTouchEvent(ev)
	}

	@MainThread
	private suspend fun executeFetchInWebView(
		webView: WebView,
		request: Request,
		source: MangaSource?,
		bodyBytes: ByteArray?,
	): Response? {
		val headersJson = JSONObject().apply {
			if (source != null) {
				runCatching {
					val repo = mangaRepositoryFactoryProvider.get().create(source) as? ParserMangaRepository
					repo?.getRequestHeaders()?.let { parserHeaders ->
						for (i in 0 until parserHeaders.size) {
							val name = parserHeaders.name(i)
							if (name.lowercase() !in FORBIDDEN_FETCH_HEADERS) {
								put(name, parserHeaders.value(i))
							}
						}
					}
				}
			}
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
							url: resp.url || '',
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
		val finalRequest = json.optString("url", "")
			.toHttpUrlOrNull()
			?.takeIf { it != request.url }
			?.let { resolvedUrl -> request.newBuilder().url(resolvedUrl).build() }
			?: request
		return Response.Builder()
			.request(finalRequest)
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

	private fun alignRequestWithWebViewOrigin(request: Request, webUrl: HttpUrl?): Request {
		webUrl ?: return request
		if (
			webUrl.scheme == request.url.scheme &&
			webUrl.host.equals(request.url.host, ignoreCase = true) &&
			webUrl.port == request.url.port
		) {
			return request
		}
		return request.rewriteOrigin(webUrl.scheme, webUrl.host, webUrl.port)
	}

	private fun Request.rewriteOrigin(scheme: String, host: String, port: Int): Request {
		val newUrl = url.newBuilder()
			.scheme(scheme)
			.host(host)
			.port(port)
			.build()
		return newBuilder().url(newUrl).build()
	}

	private fun getRedirectedOrigin(host: String): RedirectOrigin? {
		val entry = redirectedOriginByHost[host] ?: return null
		if (System.currentTimeMillis() - entry.recordedAtMs <= SOLVED_HOST_TTL_MS) {
			return entry
		}
		redirectedOriginByHost.remove(host)
		return null
	}

	private fun isCloudFlareChallengeResponse(response: Response): Boolean {
		if (response.code != 403 && response.code != 503 && response.code != 429) return false
		val cfMitigated = response.header("cf-mitigated")
		if (cfMitigated.equals("challenge", ignoreCase = true)) return true
		val bodyPreview = runCatching { response.peekBody(32768L).string() }.getOrDefault("")
		return isCloudFlareChallengeBody(bodyPreview)
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

	private data class RedirectOrigin(
		val scheme: String,
		val host: String,
		val port: Int,
		val recordedAtMs: Long,
	)

	private companion object {

		const val TAG = "WebViewExecutor"
		const val CHALLENGE_POLL_INTERVAL_MS = 350L
		const val REQUIRED_STABLE_PASSES = 2
		const val REQUIRED_STABLE_ERRORS = 4
		const val BRIDGE_CHALLENGE_TIMEOUT_MS = 20_000L
		const val BRIDGE_FETCH_TIMEOUT_MS = 15_000L
		const val SOLVED_HOST_TTL_MS = 120_000L

		private const val CF_TURNSTILE_TAP_PROBE_JS = """
			(function(){
				try {
					var cbs = document.querySelectorAll('input[type="checkbox"], [role="checkbox"], .ctp-checkbox-label');
					for (var i = 0; i < cbs.length; i++) {
						var r = cbs[i].getBoundingClientRect();
						if (r.width > 0 && r.height > 0) {
							return (r.left + r.width / 2) + ',' + (r.top + r.height / 2);
						}
					}
					var frames = document.querySelectorAll('iframe');
					for (var j = 0; j < frames.length; j++) {
						var f = frames[j];
						var src = (f.src || '').toLowerCase();
						var title = (f.title || '').toLowerCase();
						if (src.indexOf('challenges.cloudflare.com') !== -1 || src.indexOf('turnstile') !== -1 || title.indexOf('cloudflare') !== -1) {
							var fr = f.getBoundingClientRect();
							if (fr.width >= 100 && fr.height >= 40) {
								// Checkbox inside Turnstile widget sits on the left (~28px from left edge, vertically centered)
								return (fr.left + Math.min(32, fr.width * 0.15)) + ',' + (fr.top + fr.height / 2);
							}
						}
					}
					return null;
				} catch (e) { return null; }
			})()
		"""

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
		const val FAILURE_COOLDOWN_MS = 10_000L
	}
}
