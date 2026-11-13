package org.koitharu.kotatsu.settings.sources.auth

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.browser.BaseBrowserActivity
import org.koitharu.kotatsu.browser.BrowserCallback
import org.koitharu.kotatsu.browser.BrowserClient
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@AndroidEntryPoint
class SourceAuthActivity : BaseBrowserActivity(), BrowserCallback {

	@Inject
	lateinit var loaderContext: MangaLoaderContext

	private lateinit var authProvider: MangaParserAuthProvider
	private var targetSource: MangaSource = UnknownMangaSource
	private var targetRepository: ParserMangaRepository? = null

	private var authCheckJob: Job? = null
	private var pendingCallbackUrl: String? = null
	private var isAuthFinished = false

	override fun onCreate2(savedInstanceState: Bundle?, source: MangaSource, repository: ParserMangaRepository?) {
		if (repository == null) {
			finishAfterTransition()
			return
		}
		targetSource = source
		targetRepository = repository
		authProvider = repository.getAuthProvider() ?: run {
			Toast.makeText(
				this,
				getString(R.string.auth_not_supported_by, source.getTitle(this)),
				Toast.LENGTH_SHORT,
			).show()
			finishAfterTransition()
			return
		}
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		viewBinding.webView.webViewClient = AuthWebViewClient()
		invalidateOptionsMenu()
		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				Snackbar.make(viewBinding.webView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
			}
			if (savedInstanceState == null) {
				val startSignUp = intent?.getBooleanExtra(EXTRA_SIGN_UP, false) == true
				val url = if (startSignUp) {
					authProvider.signUpUrl ?: authProvider.authUrl
				} else {
					authProvider.authUrl
				}
				onTitleChanged(
					source.getTitle(this@SourceAuthActivity),
					getString(R.string.loading_),
				)
				viewBinding.webView.loadUrl(url)
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		super.onCreateOptionsMenu(menu)
		menuInflater.inflate(R.menu.opt_source_auth, menu)
		return true
	}

	override fun onPrepareOptionsMenu(menu: Menu): Boolean {
		menu.findItem(R.id.action_sign_up)?.isVisible =
			::authProvider.isInitialized && authProvider.signUpUrl != null
		menu.findItem(R.id.action_sign_in)?.isVisible = ::authProvider.isInitialized
		return super.onPrepareOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			viewBinding.webView.stopLoading()
			setResult(RESULT_CANCELED)
			finishAfterTransition()
			true
		}

		R.id.action_sign_in -> {
			if (::authProvider.isInitialized) {
				viewBinding.webView.loadUrl(authProvider.authUrl)
			}
			true
		}

		R.id.action_sign_up -> {
			if (::authProvider.isInitialized) {
				authProvider.signUpUrl?.let { viewBinding.webView.loadUrl(it) }
			}
			true
		}

		R.id.action_verify -> {
			checkAuthorization(showFailureFeedback = true)
			true
		}

		R.id.action_refresh -> {
			viewBinding.webView.reload()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun onLoadingStateChanged(isLoading: Boolean) {
		super.onLoadingStateChanged(isLoading)
		if (!isLoading) {
			checkAuthorization(showFailureFeedback = false)
		}
	}

	private fun checkAuthorization(showFailureFeedback: Boolean) {
		if (isAuthFinished || isFinishing || isDestroyed || !::authProvider.isInitialized) {
			return
		}
		val callbackUrl = pendingCallbackUrl
		val prevJob = authCheckJob
		authCheckJob = lifecycleScope.launch {
			prevJob?.join()
			if (isAuthFinished || isFinishing || isDestroyed) {
				return@launch
			}
			if (callbackUrl != null) {
				loaderContext.setAuthValue(targetSource, KEY_AUTH_CALLBACK_URL, callbackUrl)
				if (pendingCallbackUrl == callbackUrl) {
					pendingCallbackUrl = null
				}
			}
			withContext(Dispatchers.IO) {
				CookieManager.getInstance().flush()
			}
			val isAuthorized = runCatchingCancellable {
				authProvider.isAuthorized()
			}.getOrDefault(false)
			if (isAuthorized) {
				isAuthFinished = true
				(targetRepository as? CachingMangaRepository)?.invalidateCache()
				Toast.makeText(this@SourceAuthActivity, R.string.auth_complete, Toast.LENGTH_SHORT).show()
				setResult(RESULT_OK)
				finishAfterTransition()
			} else if (showFailureFeedback && !isFinishing && !isDestroyed) {
				Snackbar.make(viewBinding.webView, R.string.auth_not_verified_yet, Snackbar.LENGTH_SHORT).show()
			}
		}
	}

	private fun handleNavigationUrl(rawUrl: String?): Boolean {
		val url = rawUrl ?: return false
		val uri = url.toUriOrNull() ?: return false
		if (isInterceptedCallbackUri(uri)) {
			pendingCallbackUrl = url
			checkAuthorization(showFailureFeedback = false)
			return true
		}
		if (isWebCallbackUri(uri)) {
			pendingCallbackUrl = url
		}
		return false
	}

	private fun isInterceptedCallbackUri(uri: Uri): Boolean {
		val scheme = uri.scheme?.lowercase() ?: return false
		if (scheme == "kotatsu") {
			return true
		}
		if (scheme != "http" && scheme != "https") {
			return scheme !in NON_CALLBACK_SCHEMES
		}
		val host = uri.host?.lowercase()
		return host == "localhost" || host == "127.0.0.1" || host == "[::1]"
	}

	private fun isWebCallbackUri(uri: Uri): Boolean {
		val scheme = uri.scheme?.lowercase()
		if (scheme != "http" && scheme != "https") {
			return false
		}
		val path = uri.encodedPath?.lowercase().orEmpty()
		if ("callback" in path || "/oauth" in path && (uri.getQueryParameter("code") != null || uri.getQueryParameter("token") != null)) {
			return true
		}
		val fragment = uri.encodedFragment?.lowercase().orEmpty()
		return uri.getQueryParameter("code") != null ||
			uri.getQueryParameter("access_token") != null ||
			"access_token=" in fragment ||
			"code=" in fragment
	}

	private inner class AuthWebViewClient : BrowserClient(this@SourceAuthActivity) {

		override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
			return handleNavigationUrl(request.url?.toString())
		}

		@Suppress("OVERRIDE_DEPRECATION")
		override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
			return handleNavigationUrl(url)
		}

		override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
			val uri = url?.toUriOrNull()
			if (uri != null && (isInterceptedCallbackUri(uri) || isWebCallbackUri(uri))) {
				pendingCallbackUrl = url
			}
			super.onPageStarted(view, url, favicon)
		}
	}

	class Contract : ActivityResultContract<MangaSource, Boolean>() {

		override fun createIntent(context: Context, input: MangaSource) = AppRouter.sourceAuthIntent(context, input)

		override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == RESULT_OK
	}

	companion object {
		const val TAG = "SourceAuthActivity"
		const val EXTRA_SIGN_UP = AppRouter.KEY_SIGN_UP
		private const val KEY_AUTH_CALLBACK_URL = "auth_callback_url"
		private val NON_CALLBACK_SCHEMES = setOf("about", "javascript", "data", "blob", "mailto", "tel", "intent")
	}
}
