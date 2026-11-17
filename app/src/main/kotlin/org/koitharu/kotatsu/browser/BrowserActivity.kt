package org.koitharu.kotatsu.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.InteractiveActionRequiredException
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaAutoResolveCoordinator
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.network.cookies.AndroidCookieJar
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.webview.WebViewExecutor
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.core.util.ext.sanitizeHeaderValue
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@AndroidEntryPoint
class BrowserActivity : BaseBrowserActivity() {

	@Inject
	lateinit var cookieJar: MutableCookieJar

	@Inject
	lateinit var captchaHandler: CaptchaHandler

	@Inject
	lateinit var captchaAutoResolveCoordinator: CaptchaAutoResolveCoordinator

	@Inject
	lateinit var sourcesRepository: MangaSourcesRepository

	@Inject
	lateinit var webViewExecutor: WebViewExecutor

	private var targetSource: MangaSource = UnknownMangaSource
	private var sessionCompletedNotified = false

	override fun onCreate2(savedInstanceState: Bundle?, source: MangaSource, repository: ParserMangaRepository?) {
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		targetSource = source
		viewBinding.webView.webViewClient = object : BrowserClient(this) {
			override fun onPageFinished(webView: WebView, url: String) {
				super.onPageFinished(webView, url)
				if (url.startsWith("http://") || url.startsWith("https://")) {
					syncBrowserState(url = url, markSessionCompleted = false)
				}
			}
		}
		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				e.printStackTraceDebug()
				Snackbar.make(viewBinding.webView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
			}
			if (savedInstanceState == null) {
				val url = intent?.dataString
				if (url.isNullOrEmpty()) {
					finishAfterTransition()
				} else {
					onTitleChanged(
						intent?.getStringExtra(AppRouter.KEY_TITLE) ?: getString(R.string.loading_),
						url,
					)
					viewBinding.webView.loadUrl(url)
				}
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		super.onCreateOptionsMenu(menu)
		menuInflater.inflate(R.menu.opt_browser, menu)
		return true
	}

	override fun onResume() {
		super.onResume()
		sessionCompletedNotified = false
	}

	override fun onPause() {
		syncBrowserState(markSessionCompleted = true)
		super.onPause()
	}

	override fun finish() {
		syncBrowserState(markSessionCompleted = true)
		super.finish()
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			syncBrowserState(markSessionCompleted = true)
			viewBinding.webView.stopLoading()
			finishAfterTransition()
			true
		}

		R.id.action_browser -> {
			if (!router.openExternalBrowser(viewBinding.webView.url.orEmpty(), item.title)) {
				Snackbar.make(viewBinding.webView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
			}
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	private fun syncBrowserState(url: String? = null, markSessionCompleted: Boolean) {
		if (!hasViewBinding()) return
		CookieManager.getInstance().flush()
		val webViewUserAgent = viewBinding.webView.settings.userAgentString
			?.sanitizeHeaderValue()
			?.trim()
			?.nullIfEmpty()
		val urls = listOfNotNull(url, viewBinding.webView.url, intent?.dataString)
			.filter { it.startsWith("http://") || it.startsWith("https://") }
			.distinct()
		if (urls.isNotEmpty()) {
			captchaHandler.markHostsResolved(urls)
			if (markSessionCompleted) {
				webViewExecutor.markHostsSolved(urls)
			}
		}
		val source = targetSource.takeUnless { it == UnknownMangaSource }
		if (source != null) {
			if (!webViewUserAgent.isNullOrEmpty()) {
				runCatching {
					SourceSettings(this, source).cloudFlareUserAgent = webViewUserAgent
				}.onFailure {
					it.printStackTraceDebug()
				}
			}
			captchaAutoResolveCoordinator.notifyManualResolveSuccess(source)
			if (markSessionCompleted && !sessionCompletedNotified) {
				sessionCompletedNotified = true
				captchaHandler.markBrowserSessionCompleted(source)
			}
			setResult(RESULT_OK)
			processLifecycleScope.launch {
				persistCookiesToJar(urls)
				runCatchingCancellable {
					captchaHandler.discard(source)
				}.onFailure {
					it.printStackTraceDebug()
				}
			}
		} else {
			if (markSessionCompleted && !sessionCompletedNotified) {
				sessionCompletedNotified = true
				captchaHandler.markAnyBrowserSessionCompleted()
			}
			setResult(RESULT_OK)
			if (urls.isNotEmpty()) {
				processLifecycleScope.launch(Dispatchers.Default) {
					persistCookiesToJar(urls)
					if (markSessionCompleted) {
						matchSourcesByUrls(urls).forEach { matchedSource ->
							if (!webViewUserAgent.isNullOrEmpty()) {
								runCatching {
									SourceSettings(this@BrowserActivity, matchedSource).cloudFlareUserAgent = webViewUserAgent
								}.onFailure {
									it.printStackTraceDebug()
								}
							}
							captchaAutoResolveCoordinator.notifyManualResolveSuccess(matchedSource)
							captchaHandler.markBrowserSessionCompleted(matchedSource)
							runCatchingCancellable {
								captchaHandler.discard(matchedSource)
							}.onFailure {
								it.printStackTraceDebug()
							}
						}
					}
				}
			}
		}
	}

	private suspend fun matchSourcesByUrls(urls: List<String>): List<MangaSource> {
		val hosts = urls.mapNotNull { it.toHttpUrlOrNull()?.host?.lowercase() }.toSet()
		if (hosts.isEmpty()) return emptyList()
		val enabled = runCatchingCancellable { sourcesRepository.getEnabledSources() }.getOrDefault(emptyList())
		val matched = ArrayList<MangaSource>(2)
		for (source in enabled) {
			val unwrapped = source.unwrap()
			val repo = runCatching { mangaRepositoryFactory.create(unwrapped) as? ParserMangaRepository }.getOrNull()
				?: continue
			val candidateHosts = buildList {
				add(repo.domain.lowercase())
				repo.domains.forEach { add(it.lowercase()) }
			}
			val isMatch = hosts.any { visited ->
				val strippedVisited = visited.removePrefix("www.")
				candidateHosts.any { candidate ->
					val strippedCandidate = candidate.removePrefix("www.")
					visited == candidate ||
						strippedVisited == strippedCandidate ||
						visited.endsWith(".$strippedCandidate") ||
						candidate.endsWith(".$strippedVisited")
				}
			}
			if (isMatch) {
				matched.add(unwrapped)
			}
		}
		return matched
	}

	private suspend fun persistCookiesToJar(urls: List<String>) {
		if (cookieJar is AndroidCookieJar || urls.isEmpty()) return
		runInterruptible(Dispatchers.IO) {
			val cookieManager = CookieManager.getInstance()
			cookieManager.flush()
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
	}

	data class BrowserTarget(
		val url: String,
		val source: MangaSource?,
		val title: String?,
	)

	class UrlContract : ActivityResultContract<BrowserTarget, Boolean>() {
		override fun createIntent(context: Context, input: BrowserTarget): Intent = AppRouter.browserIntent(
			context = context,
			url = input.url,
			source = input.source,
			title = input.title,
		)

		override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == RESULT_OK
	}

	class Contract : ActivityResultContract<InteractiveActionRequiredException, Unit>() {
		override fun createIntent(
			context: Context,
			input: InteractiveActionRequiredException
		): Intent = AppRouter.browserIntent(
			context = context,
			url = input.url,
			source = input.source,
			title = null,
		)

		override fun parseResult(resultCode: Int, intent: Intent?): Unit = Unit
	}

	companion object {

		const val TAG = "BrowserActivity"
	}
}
