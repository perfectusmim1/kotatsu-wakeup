package org.koitharu.kotatsu.browser.cloudflare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.browser.BaseBrowserActivity
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaAutoResolveCoordinator
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.network.cookies.AndroidCookieJar
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.webview.CF_STATE_JS
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import kotlin.coroutines.resume

@AndroidEntryPoint
open class CloudFlareActivity : BaseBrowserActivity(), CloudFlareCallback {
	protected open val isHiddenAutoResolveActivity = false


	private var pendingResult = RESULT_CANCELED
	private val isAutoResolve: Boolean by lazy { intent?.getBooleanExtra(EXTRA_AUTO_RESOLVE, false) == true }
	private val isEscalatedFromHidden: Boolean by lazy {
		intent?.getBooleanExtra(EXTRA_ESCALATED_FROM_HIDDEN, false) == true
	}
	private var resultNotified = false
	private var escalationRequested = false
	private var mangaSource: MangaSource = UnknownMangaSource
	private var repository: ParserMangaRepository? = null
	private var clearanceAtLaunch: String? = null
	private var clearanceUpdateObservedAt = 0L
	private var challengeStartedAt = 0L
	private var resolveJob: Job? = null
	private var isHiddenPresentation = false
	private var lastChallengeState: String? = null

	@Inject
	lateinit var cookieJar: MutableCookieJar

	@Inject
	lateinit var captchaHandler: CaptchaHandler

	@Inject
	lateinit var captchaAutoResolveCoordinator: CaptchaAutoResolveCoordinator

	private lateinit var cfClient: CloudFlareClient

	override fun onCreate2(savedInstanceState: Bundle?, source: MangaSource, repository: ParserMangaRepository?) {
		this.mangaSource = source
		this.repository = repository
		if (isHiddenAutoResolveActivity) {
			isHiddenPresentation = true
			// Keep the window focused and the WebView rendered: Turnstile treats a non-focused or
			// detached WebView differently. The translucent theme exposes the previous screen.
			viewBinding.appbar.isGone = true
			viewBinding.root.alpha = HIDDEN_RENDER_ALPHA
			window.setDimAmount(0f)
			window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
			(this as? CloudFlareHiddenActivity)?.let {
				captchaAutoResolveCoordinator.registerHiddenActivity(it)
			}
		} else {
			setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
			if (isAutoResolve) {
				captchaAutoResolveCoordinator.registerInteractiveActivity(this)
			}
		}
		val url = intent?.dataString
		if (url.isNullOrEmpty()) {
			finishAfterTransition()
			return
		}
		clearanceAtLaunch = CloudFlareHelper.getClearanceCookie(cookieJar, url)
		alignUserAgentWithEngine(repository)

		// Check if source needs header interception
		val needsInterception = shouldUseInterception(source, repository)
		Log.d(TAG, "Source: ${source.name}, needsInterception: $needsInterception")

		cfClient = if (needsInterception) {
			Log.d(TAG, "Using CloudFlareInterceptClient with header filtering")
			CloudFlareInterceptClient(cookieJar, this, url)
		} else {
			Log.d(TAG, "Using regular CloudFlareClient (no interception)")
			CloudFlareClient(cookieJar, this, url)
		}

		viewBinding.webView.webViewClient = cfClient
		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				Snackbar.make(viewBinding.webView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
			}
			if (savedInstanceState == null) {
				if (isAutoResolve && !isEscalatedFromHidden) {
					url.toHttpUrlOrNull()?.let {
						clearRejectedClearance(it)
						CookieManager.getInstance().flush()
						Log.d(TAG, "Removed rejected cf_clearance before automatic challenge")
					}
				}
				awaitChallengeViewport()
				onTitleChanged(getString(R.string.loading_), url)
				// Arm the resolve budget only now. applyWebViewConfig() and the viewport wait run on this
				// coroutine, so anchoring the deadlines on onCreate2 spent the whole allowance before the
				// challenge had even started loading.
				challengeStartedAt = System.currentTimeMillis()
				viewBinding.webView.loadUrl(url)
			} else {
				// Restored after a configuration change: the WebView kept its page, so the challenge is
				// already in flight.
				challengeStartedAt = System.currentTimeMillis()
			}
		}
		// A cf_clearance change is not a completion signal. Cloudflare can update it while the
		// interstitial is still running, so only the loaded page state may complete this flow.
		resolveJob = lifecycleScope.launch { runResolveLoop() }
	}

	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		if (isHiddenPresentation) return false
		menuInflater.inflate(R.menu.opt_captcha, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			viewBinding.webView.stopLoading()
			finishAfterTransition()
			true
		}

		R.id.action_retry -> {
			restartCheck()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun finish() {
		setResult(pendingResult)
		// In auto-resolve mode the originating Fragment / Activity may already be dead, so its
		// ActivityResultLauncher won't deliver the result. Notify the singleton coordinator instead so
		// the result reaches every screen that's still awaiting it. Do NOT report failure if we are
		// handing off to visible CloudFlareActivity.
		if (!escalationRequested && !resultNotified) {
			val targetSource = mangaSource.takeUnless { it == UnknownMangaSource }
				?: intent?.getStringExtra(AppRouter.KEY_SOURCE)?.let(::MangaSource)
			if (targetSource != null) {
				if (isAutoResolve) {
					resultNotified = true
					captchaAutoResolveCoordinator.notifyResolveResult(
						targetSource,
						pendingResult == RESULT_OK,
					)
				} else if (pendingResult == RESULT_OK) {
					resultNotified = true
					captchaAutoResolveCoordinator.notifyManualResolveSuccess(targetSource)
				}
			}
		}
		super.finish()
	}

	override fun onDestroy() {
		(this as? CloudFlareHiddenActivity)?.let {
			captchaAutoResolveCoordinator.unregisterHiddenActivity(it)
		}
		captchaAutoResolveCoordinator.unregisterInteractiveActivity(this)
		super.onDestroy()
	}

	/** True only while this same WebView should remain invisible and at the top of the task. */
	fun shouldStayHiddenAndFocused(): Boolean =
		isAutoResolve && isHiddenPresentation && !escalationRequested && !isFinishing && !isDestroyed

	/** Cancels the automatic session without leaving a hidden Activity or WebView behind. */
	fun cancelAutomaticResolve() {
		if (!isAutoResolve || isFinishing || isDestroyed) return
		resolveJob?.cancel()
		viewBinding.webView.stopLoading()
		finishAfterTransition()
	}

	override fun onLoadingStateChanged(isLoading: Boolean) = Unit

	/**
	 * Do not start Turnstile until this Activity has the same real viewport as a manual Solve launch.
	 * The focus wait is bounded: a hidden solver that never wins focus must still load its URL, otherwise
	 * the resolve loop would time out against a WebView that never navigated at all.
	 */
	private suspend fun awaitChallengeViewport() {
		val focusTimeout = if (isHiddenAutoResolveActivity) {
			HIDDEN_VIEWPORT_FOCUS_TIMEOUT_MS
		} else {
			VIEWPORT_FOCUS_TIMEOUT_MS
		}
		val focusDeadline = System.currentTimeMillis() + focusTimeout
		while (!viewBinding.webView.hasWindowFocus() && System.currentTimeMillis() < focusDeadline) {
			delay(VIEWPORT_POLL_INTERVAL_MS)
		}
		suspendCancellableCoroutine { cont ->
			viewBinding.webView.doOnLayout {
				if (cont.isActive) cont.resume(Unit)
			}
		}
		suspendCancellableCoroutine { cont ->
			viewBinding.webView.postOnAnimation {
				if (cont.isActive) cont.resume(Unit)
			}
		}
	}

	override fun onPageLoaded() {
		viewBinding.progressBar.isInvisible = true
	}

	override fun onLoopDetected() = Unit

	override fun onCheckPassed() {
		markClearanceUpdateObserved()
	}

	/**
	 * Drives the challenge solver loop:
	 * - In [CloudFlareHiddenActivity], polls cleanly for up to ~3.5s; if an interactive Turnstile /
	 *   checkbox challenge or error page appears, or if `cf_clearance` is not acquired in time,
	 *   escalates seamlessly to visible [CloudFlareActivity].
	 * - In visible [CloudFlareActivity], waits patiently for user completion without recreating or
	 *   timing out, and auto-completes as soon as the loaded page passes verification.
	 */
	private suspend fun runResolveLoop() {
		var consecutivePasses = 0
		var consecutiveCheckboxes = 0
		var consecutiveWidgets = 0
		var clearanceAcquiredAt = 0L
		while (true) {
			delay(RESOLVE_POLL_INTERVAL_MS)
			// Nothing is loading yet, so no deadline may run.
			val startedAt = challengeStartedAt
			if (startedAt == 0L) {
				continue
			}
			val challengeState = probeChallengeState()
			if (challengeState != lastChallengeState) {
				lastChallengeState = challengeState
				Log.d(
					TAG,
					"Challenge state: $challengeState, url=${viewBinding.webView.url}, title=${viewBinding.webView.title}",
				)
			}
			if (challengeState == CF_STATE_OK) {
				consecutivePasses++
				consecutiveCheckboxes = 0
				consecutiveWidgets = 0
				if (consecutivePasses >= REQUIRED_STABLE_PASSES) {
					finishSuccess()
					return
				}
				continue
			}
			consecutivePasses = 0

			if (!isHiddenAutoResolveActivity) {
				continue
			}

			if (challengeState == CF_STATE_ERROR) {
				escalateToInteractive("challenge error page")
				return
			}

			when (probeInteractiveChallenge()) {
				CF_INTERACTIVE_CHECKBOX -> {
					consecutiveCheckboxes++
					consecutiveWidgets = 0
					if (consecutiveCheckboxes >= REQUIRED_CHECKBOX_PROBES) {
						escalateToInteractive("interactive checkbox detected")
						return
					}
				}

				CF_INTERACTIVE_WIDGET -> {
					consecutiveWidgets++
					consecutiveCheckboxes = 0
					if (consecutiveWidgets >= REQUIRED_WIDGET_PROBES) {
						escalateToInteractive("interactive Turnstile widget stayed visible")
						return
					}
				}

				else -> {
					consecutiveCheckboxes = 0
					consecutiveWidgets = 0
				}
			}

			val now = System.currentTimeMillis()
			val elapsed = now - startedAt
			val hasClearance = hasClearanceCookie()
			if (hasClearance && clearanceAcquiredAt == 0L) {
				clearanceAcquiredAt = now
				markClearanceUpdateObserved()
			}
			if (elapsed >= HIDDEN_AUTO_RESOLVE_TIMEOUT_MS) {
				val withinRedirectGrace = hasClearance &&
					(now - clearanceAcquiredAt) < CLEARANCE_REDIRECT_GRACE_MS &&
					elapsed < HIDDEN_AUTO_RESOLVE_MAX_BUDGET_MS
				if (!withinRedirectGrace) {
					escalateToInteractive(
						if (hasClearance) "clearance redirect timeout" else "hidden auto-resolve timeout (${elapsed}ms)",
					)
					return
				}
			}
		}
	}

	private fun markClearanceUpdateObserved() {
		if (clearanceUpdateObservedAt == 0L) {
			clearanceUpdateObservedAt = System.currentTimeMillis()
		}
	}

	private fun escalateToInteractive(reason: String) {
		if (escalationRequested || pendingResult == RESULT_OK || isFinishing || isDestroyed) return
		escalationRequested = true
		resolveJob?.cancel()
		viewBinding.webView.stopLoading()
		CookieManager.getInstance().flush()
		Log.d(TAG, "Escalating hidden Cloudflare challenge to visible window: $reason")
		val sourceName = mangaSource.takeUnless { it == UnknownMangaSource }?.name
			?: intent?.getStringExtra(AppRouter.KEY_SOURCE)
		if (sourceName != null) {
			captchaAutoResolveCoordinator.markEscalatingToInteractive(MangaSource(sourceName))
		}
		val targetIntent = Intent(this, CloudFlareActivity::class.java).apply {
			data = intent?.data ?: intent?.dataString?.toUri()
			if (sourceName != null) {
				putExtra(AppRouter.KEY_SOURCE, sourceName)
			}
			intent?.getStringExtra(AppRouter.KEY_USER_AGENT)?.let {
				putExtra(AppRouter.KEY_USER_AGENT, it)
			}
			putExtra(EXTRA_AUTO_RESOLVE, isAutoResolve)
			putExtra(EXTRA_ESCALATED_FROM_HIDDEN, true)
			addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
		}
		try {
			startActivity(targetIntent)
			finish()
		} catch (e: Exception) {
			e.printStackTraceDebug()
			escalationRequested = false
			finishAfterTransition()
		}
	}

	private suspend fun probeChallengeState(): String = suspendCancellableCoroutine { cont ->
		viewBinding.webView.evaluateJavascript(CF_STATE_JS) { raw ->
			if (cont.isActive) {
				cont.resume(raw?.removeSurrounding("\"") ?: CF_STATE_WAIT)
			}
		}
	}

	private suspend fun probeInteractiveChallenge(): String = suspendCancellableCoroutine { cont ->
		viewBinding.webView.evaluateJavascript(CF_INTERACTIVE_JS) { raw ->
			if (cont.isActive) {
				cont.resume(raw?.removeSurrounding("\"") ?: CF_INTERACTIVE_NONE)
			}
		}
	}

	private fun hasClearanceCookie(): Boolean {
		val targetUrl = intent?.dataString ?: return false
		val jarClearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
		if (!jarClearance.isNullOrEmpty() && jarClearance != clearanceAtLaunch) {
			return true
		}
		val cm = CookieManager.getInstance()
		for (rawUrl in listOfNotNull(targetUrl, viewBinding.webView.url).distinct()) {
			val httpUrl = rawUrl.toHttpUrlOrNull() ?: continue
			val rawCookies = cm.getCookie(rawUrl) ?: continue
			val cfCookie = rawCookies.split(';').firstNotNullOfOrNull { part ->
				Cookie.parse(httpUrl, part.trim())?.takeIf {
					it.name == CLEARANCE_COOKIE_NAME && it.value.isNotEmpty()
				}
			}
			if (cfCookie != null && cfCookie.value != clearanceAtLaunch) {
				return true
			}
		}
		return false
	}

	private fun finishSuccess() {
		if (pendingResult == RESULT_OK || escalationRequested) return
		pendingResult = RESULT_OK
		resolveJob?.cancel()
		val webViewUserAgent = viewBinding.webView.settings.userAgentString?.nullIfEmpty()
		val urls = listOfNotNull(intent?.dataString, viewBinding.webView.url).distinct()
		lifecycleScope.launch {
			// Sync both WebView cookies and the exact WebView User-Agent before closing the Activity
			// so the retrying OkHttp request presents the same clearance + UA pair.
			persistCookiesAndUserAgent(urls, webViewUserAgent)
			val source = intent?.getStringExtra(AppRouter.KEY_SOURCE)
			if (source != null) {
				runCatchingCancellable {
					captchaHandler.discard(MangaSource(source))
				}.onFailure {
					it.printStackTraceDebug()
				}
			}
			finishAfterTransition()
		}
	}

	private suspend fun persistCookiesAndUserAgent(
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
		val targetSource = mangaSource.takeUnless { it == UnknownMangaSource }
		if (targetSource != null && !webViewUserAgent.isNullOrEmpty()) {
			runCatching {
				val config = repository?.getConfig() ?: SourceSettings(this@CloudFlareActivity, targetSource)
				config.cloudFlareUserAgent = webViewUserAgent
			}.onFailure {
				it.printStackTraceDebug()
			}
		}
	}

	override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
		setTitle(title)
		supportActionBar?.subtitle = subtitle?.toString()?.toHttpUrlOrNull()?.host.ifNullOrEmpty { subtitle }
	}

	private fun restartCheck() {
		lifecycleScope.launch {
			resolveJob?.cancel()
			viewBinding.webView.stopLoading()
			yield()
			cfClient.reset()
			val targetUrl = intent?.dataString?.toHttpUrlOrNull()
			if (targetUrl != null) {
				clearCfCookies(targetUrl)
				clearanceUpdateObservedAt = 0L
				challengeStartedAt = System.currentTimeMillis()
				viewBinding.webView.loadUrl(targetUrl.toString())
				resolveJob = lifecycleScope.launch { runResolveLoop() }
			}
		}
	}

	private suspend fun clearRejectedClearance(url: HttpUrl) = runInterruptible(Dispatchers.IO) {
		cookieJar.removeCookies(url) { cookie ->
			cookie.name == CLEARANCE_COOKIE_NAME
		}
		if (cookieJar !is AndroidCookieJar) {
			expireWebViewCookies(url) { it.name == CLEARANCE_COOKIE_NAME }
		}
	}

	private suspend fun clearCfCookies(url: HttpUrl) = runInterruptible(Dispatchers.IO) {
		cookieJar.removeCookies(url) { cookie ->
			CloudFlareHelper.isCloudFlareCookie(cookie.name)
		}
		if (cookieJar !is AndroidCookieJar) {
			expireWebViewCookies(url) { CloudFlareHelper.isCloudFlareCookie(it.name) }
		}
	}

	private fun expireWebViewCookies(url: HttpUrl, predicate: (Cookie) -> Boolean) {
		val cm = CookieManager.getInstance()
		val urlString = url.toString()
		val raw = cm.getCookie(urlString) ?: return
		val expiredAt = System.currentTimeMillis() - 100_000L
		for (part in raw.split(';')) {
			val cookie = Cookie.parse(url, part.trim()) ?: continue
			if (predicate(cookie)) {
				val expired = cookie.newBuilder().expiresAt(expiredAt).build()
				cm.setCookie(urlString, expired.toString())
			}
		}
		cm.flush()
	}

	/**
	 * Turnstile cannot be passed by a WebView that lies about what it is.
	 *
	 * Several parsers hard-code a desktop User-Agent as their [ConfigKey.UserAgent] default (HotComics
	 * ships `X11; Linux x86_64 … Chrome/114`), and [BaseBrowserActivity] pushes that string onto the
	 * solver WebView. Everything the challenge script actually measures then contradicts it:
	 * `navigator.userAgentData` reports Android and the real Chrome build, `navigator.platform` reports
	 * an ARM Linux, touch points and screen size are a phone's, and the WebGL renderer is a mobile GPU.
	 * That contradiction is the signature anti-bot checks exist to catch, so the challenge fails, the
	 * interstitial reloads itself with a fresh ray id, and the solver loops forever no matter how long
	 * it is given.
	 *
	 * The clearance cookie is bound to the User-Agent that earned it, so [finishSuccess] also records the
	 * WebView's actual User-Agent in [SourceSettings.cloudFlareUserAgent] for `CommonHeadersInterceptor`.
	 */
	private fun alignUserAgentWithEngine(repository: ParserMangaRepository?) {
		if (repository == null) return
		val engineUserAgent = runCatching { WebSettings.getDefaultUserAgent(this) }.getOrNull()
		if (engineUserAgent.isNullOrEmpty() || ANDROID_UA_TOKEN !in engineUserAgent) return
		val currentUserAgent = viewBinding.webView.settings.userAgentString
		if (currentUserAgent.isNullOrEmpty()) return
		val isNonAndroidUa = ANDROID_UA_TOKEN !in currentUserAgent
		val isNonChromiumMobileUa = "Firefox/" in currentUserAgent || "FxiOS" in currentUserAgent || "Gecko/" in currentUserAgent
		if (!isNonAndroidUa && !isNonChromiumMobileUa) return
		Log.w(
			TAG,
			"Configured User-Agent contradicts the WebView engine, realigning so the challenge can pass." +
				"\n  was: $currentUserAgent\n  now: $engineUserAgent",
		)
		viewBinding.webView.settings.userAgentString = engineUserAgent
		runCatching {
			repository.getConfig().cloudFlareUserAgent = engineUserAgent
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	private fun shouldUseInterception(source: MangaSource, repository: ParserMangaRepository?): Boolean {
		Log.d(TAG, "shouldUseInterception called for source: ${source.name}")
		Log.d(TAG, "Repository type: ${repository?.javaClass?.simpleName}")

		if (repository !is ParserMangaRepository) {
			Log.d(TAG, "Repository is not ParserMangaRepository, returning false")
			return false
		}

		// Check if parser has InterceptCloudflare ConfigKey
		val configKeys = repository.getConfigKeys()
		Log.d(TAG, "Config keys count: ${configKeys.size}")
		Log.d(TAG, "Config keys: ${configKeys.map { it.javaClass.simpleName }}")

		val interceptKey = configKeys.filterIsInstance<ConfigKey.InterceptCloudflare>().firstOrNull()
		Log.d(TAG, "InterceptCloudflare key found: ${interceptKey != null}")
		if (interceptKey != null) {
			Log.d(TAG, "InterceptCloudflare defaultValue: ${interceptKey.defaultValue}")
		}

		val result = interceptKey?.defaultValue == true
		Log.d(TAG, "Returning: $result")
		return result
	}

	class Contract : ActivityResultContract<CloudFlareProtectedException, Boolean>() {
		override fun createIntent(context: Context, input: CloudFlareProtectedException): Intent {
			return AppRouter.cloudFlareResolveIntent(context, input)
		}

		override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
			return resultCode == RESULT_OK
		}
	}

	companion object {

		const val TAG = "CloudFlareActivity"
		const val EXTRA_AUTO_RESOLVE = "auto_resolve"
		private const val EXTRA_ESCALATED_FROM_HIDDEN = "escalated_from_hidden"
		private const val VIEWPORT_POLL_INTERVAL_MS = 16L
		private const val VIEWPORT_FOCUS_TIMEOUT_MS = 2_000L
		private const val HIDDEN_VIEWPORT_FOCUS_TIMEOUT_MS = 500L
		private const val RESOLVE_POLL_INTERVAL_MS = 350L
		private const val HIDDEN_AUTO_RESOLVE_TIMEOUT_MS = 3_500L
		private const val CLEARANCE_REDIRECT_GRACE_MS = 1_200L
		private const val HIDDEN_AUTO_RESOLVE_MAX_BUDGET_MS =
			HIDDEN_AUTO_RESOLVE_TIMEOUT_MS + CLEARANCE_REDIRECT_GRACE_MS
		private const val REQUIRED_STABLE_PASSES = 2
		private const val REQUIRED_CHECKBOX_PROBES = 2
		private const val REQUIRED_WIDGET_PROBES = 6
		private const val CLEARANCE_COOKIE_NAME = "cf_clearance"
		private const val HIDDEN_RENDER_ALPHA = 0.01f
		private const val CF_STATE_OK = "ok"
		private const val CF_STATE_WAIT = "wait"
		private const val CF_STATE_ERROR = "error"
		private const val CF_INTERACTIVE_NONE = "none"
		private const val CF_INTERACTIVE_CHECKBOX = "checkbox"
		private const val CF_INTERACTIVE_WIDGET = "widget"
		private const val ANDROID_UA_TOKEN = "Android"

		private const val CF_INTERACTIVE_JS = """
			(function(){
				try {
					var checkboxes = document.querySelectorAll(
						'input[type="checkbox"], [role="checkbox"], .ctp-checkbox-label, .cf-turnstile-wrapper input'
					);
					for (var i = 0; i < checkboxes.length; i++) {
						var el = checkboxes[i];
						var style = window.getComputedStyle(el);
						if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') continue;
						var rect = el.getBoundingClientRect();
						if (rect.width > 0 && rect.height > 0) return 'checkbox';
					}
					var iframes = document.querySelectorAll('iframe');
					for (var j = 0; j < iframes.length; j++) {
						var frame = iframes[j];
						var fStyle = window.getComputedStyle(frame);
						if (fStyle.display === 'none' || fStyle.visibility === 'hidden' || fStyle.opacity === '0') continue;
						var fRect = frame.getBoundingClientRect();
						if (fRect.width < 120 || fRect.height < 40) continue;
						var src = (frame.src || '').toLowerCase();
						var title = (frame.title || '').toLowerCase();
						var inChallenge = frame.closest && frame.closest('#turnstile-wrapper, .cf-turnstile, #challenge-stage');
						if (src.indexOf('challenges.cloudflare.com') !== -1 ||
							src.indexOf('turnstile') !== -1 ||
							title.indexOf('cloudflare') !== -1 ||
							title.indexOf('checkbox') !== -1 ||
							title.indexOf('captcha') !== -1 ||
							inChallenge) {
							return 'widget';
						}
					}
					return 'none';
				} catch (e) { return 'none'; }
			})()
		"""
	}
}
