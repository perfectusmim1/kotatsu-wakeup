package org.koitharu.kotatsu.comments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.core.os.bundleOf
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.parcelable.ParcelableChapter
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.network.proxy.ProxyProvider
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.configureForParser
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.databinding.SheetSourceCommentsBinding
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext
import org.koitharu.kotatsu.settings.sources.auth.SourceAuthActivity
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class SourceCommentsSheet :
	BaseAdaptiveSheet<SheetSourceCommentsBinding>(),
	SourceCommentAdapter.Listener {

	@Inject
	lateinit var coil: ImageLoader

	@Inject
	lateinit var proxyProvider: ProxyProvider

	private val viewModel: SourceCommentsViewModel by viewModels()
	private var adapter: SourceCommentAdapter? = null
	private var widgetWebView: WebView? = null
	private var loadedWidgetSignature: String? = null
	private val activePopupDialogs = mutableListOf<AppCompatDialog>()
	private var isUpdatingToggleProgrammatically = false
	private var isUpdatingSortProgrammatically = false

	private val sourceAuthLauncher = registerForActivityResult(SourceAuthActivity.Contract()) { isAuthorized ->
		if (isAuthorized) {
			viewModel.onAuthCompleted()
			widgetWebView?.reload()
		}
	}

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	): SheetSourceCommentsBinding = SheetSourceCommentsBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: SheetSourceCommentsBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		disableFitToContents()
		setExpanded(isExpanded = true, isLocked = false)

		val subtitle = viewModel.effectiveChapter?.let { ch ->
			ch.title?.takeIf { it.isNotBlank() } ?: viewModel.manga.title
		} ?: viewModel.manga.title
		binding.textViewSheetSubtitle.textAndVisible = subtitle
		if (binding.headerBar.isTitleVisible) {
			binding.textViewSheetTitle.isVisible = false
		}

		setupModeSwitcher(binding)
		setupNativeViews(binding)
		setupWidgetViews(binding)
		observeViewModel(binding)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
		val bottom = insets.getInsets(typeMask).bottom
		viewBinding?.root?.updatePadding(bottom = bottom)
		return insets.consume(v, typeMask, bottom = true)
	}

	override fun onResume() {
		super.onResume()
		widgetWebView?.onResume()
	}

	override fun onPause() {
		widgetWebView?.onPause()
		super.onPause()
	}

	override fun onDestroyView() {
		activePopupDialogs.toList().forEach { it.dismiss() }
		activePopupDialogs.clear()
		widgetWebView?.let { wv ->
			wv.stopLoading()
			(wv.parent as? ViewGroup)?.removeView(wv)
			wv.destroy()
		}
		widgetWebView = null
		loadedWidgetSignature = null
		adapter = null
		super.onDestroyView()
	}

	override fun onToggleSpoiler(commentId: String) {
		viewModel.toggleSpoiler(commentId)
	}

	override fun onToggleLike(comment: SourceComment, isLike: Boolean) {
		viewModel.toggleLike(comment, isLike)
	}

	override fun onReplyClick(comment: SourceComment) {
		viewModel.startReply(comment)
		viewBinding?.editTextComment?.requestFocus()
		viewBinding?.editTextComment?.showKeyboard()
	}

	override fun onToggleReplies(comment: SourceComment) {
		viewModel.toggleReplies(comment)
	}

	override fun onEditClick(comment: SourceComment) {
		viewModel.startEdit(comment)
	}

	override fun onDeleteClick(comment: SourceComment) {
		buildAlertDialog(requireContext()) {
			setMessage(R.string.community_comment_delete_confirm)
			setPositiveButton(R.string.community_comment_delete) { _, _ ->
				viewModel.deleteComment(comment)
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}

	override fun onLoadMoreReplies(parentComment: SourceComment) {
		viewModel.loadMoreReplies(parentComment)
	}

	override fun onLoadMoreComments() {
		viewModel.loadMoreComments()
	}

	private fun setupModeSwitcher(binding: SheetSourceCommentsBinding) {
		binding.toggleGroupMode.isVisible = viewModel.showTabSwitcher
		binding.toggleGroupMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (!isChecked || isUpdatingToggleProgrammatically) return@addOnButtonCheckedListener
			when (checkedId) {
				R.id.button_mode_native -> viewModel.selectTab(CommentsTab.NATIVE)
				R.id.button_mode_widget -> viewModel.selectTab(CommentsTab.WIDGET)
			}
		}
	}

	private fun setupNativeViews(binding: SheetSourceCommentsBinding) {
		val commentAdapter = SourceCommentAdapter(
			source = viewModel.manga.source,
			coil = coil,
			listener = this,
		)
		adapter = commentAdapter
		binding.recyclerViewComments.adapter = commentAdapter
		binding.recyclerViewComments.addOnScrollListener(
			object : RecyclerView.OnScrollListener() {
				override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
					if (dy <= 0) return
					val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
					val total = commentAdapter.itemCount
					if (total > 0 && lm.findLastVisibleItemPosition() >= total - 3) {
						viewModel.loadMoreComments()
					}
				}
			},
		)

		binding.swipeRefreshLayout.setOnRefreshListener {
			viewModel.refresh()
		}

		val sorts = viewModel.capabilities.supportedSorts
		binding.scrollViewSort.isVisible = sorts.size > 1
		binding.chipSortNew.isVisible = SourceCommentSort.NEWEST in sorts
		binding.chipSortTop.isVisible = SourceCommentSort.TOP in sorts
		binding.chipSortOld.isVisible = SourceCommentSort.OLDEST in sorts
		binding.chipGroupSort.setOnCheckedStateChangeListener { _, checkedIds ->
			if (isUpdatingSortProgrammatically) return@setOnCheckedStateChangeListener
			val sort = when (checkedIds.firstOrNull()) {
				R.id.chip_sort_new -> SourceCommentSort.NEWEST
				R.id.chip_sort_top -> SourceCommentSort.TOP
				R.id.chip_sort_old -> SourceCommentSort.OLDEST
				else -> return@setOnCheckedStateChangeListener
			}
			viewModel.selectSort(sort)
		}

		binding.buttonAuthSignIn.setOnClickListener {
			sourceAuthLauncher.launch(viewModel.manga.source)
		}

		binding.buttonNativeFallbackWeb.isVisible = viewModel.hasWidgetTab
		binding.buttonNativeFallbackWeb.setOnClickListener {
			viewModel.selectTab(CommentsTab.WIDGET)
		}

		binding.cardCompose.isVisible = viewModel.capabilities.supportsCommentPosting
		binding.buttonCancelCompose.setOnClickListener {
			viewModel.cancelCompose()
		}
		binding.buttonSend.setOnClickListener {
			val text = binding.editTextComment.text?.toString().orEmpty()
			viewModel.submitComposer(text, binding.chipSpoiler.isChecked)
		}
	}

	private fun setupWidgetViews(binding: SheetSourceCommentsBinding) {
		val fallbackUrl = viewModel.effectiveChapter?.url?.takeIf { it.startsWith("http") }
			?: viewModel.manga.publicUrl
		binding.buttonWidgetRefresh.setOnClickListener {
			val wv = widgetWebView
			if (wv != null && wv.url != null) {
				binding.layoutWidgetError.isVisible = false
				wv.reload()
			} else {
				loadedWidgetSignature = null
				viewModel.loadWidgetContext(force = true)
			}
		}
		binding.buttonWidgetOpenBrowser.setOnClickListener {
			openWidgetInBrowser(fallbackUrl)
		}
		binding.buttonWidgetErrorRetry.setOnClickListener {
			binding.layoutWidgetError.isVisible = false
			val wv = widgetWebView
			if (wv != null && loadedWidgetSignature != null) {
				wv.reload()
			} else {
				loadedWidgetSignature = null
				viewModel.loadWidgetContext(force = true)
			}
		}
		binding.buttonWidgetErrorBrowser.setOnClickListener {
			openWidgetInBrowser(fallbackUrl)
		}
	}

	private fun observeViewModel(binding: SheetSourceCommentsBinding) {
		viewModel.selectedTab.observe(viewLifecycleOwner) { tab ->
			isUpdatingToggleProgrammatically = true
			binding.toggleGroupMode.check(
				if (tab == CommentsTab.NATIVE) R.id.button_mode_native else R.id.button_mode_widget,
			)
			isUpdatingToggleProgrammatically = false
			binding.containerNative.isVisible = tab == CommentsTab.NATIVE
			binding.containerWidget.isVisible = tab == CommentsTab.WIDGET
			if (tab == CommentsTab.WIDGET) {
				binding.editTextComment.hideKeyboard()
			}
			binding.root.post {
				org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.invalidateScrollTarget(binding.root)
			}
		}

		viewModel.nativeState.observe(viewLifecycleOwner) { state ->
			renderNativeState(binding, state)
		}

		viewModel.widgetState.observe(viewLifecycleOwner) { state ->
			renderWidgetState(binding, state)
		}

		viewModel.onAuthRequired.observeEvent(viewLifecycleOwner) { source ->
			Snackbar.make(binding.root, R.string.auth_required, Snackbar.LENGTH_LONG)
				.setAction(R.string.sign_in) {
					sourceAuthLauncher.launch(source)
				}
				.show()
		}

		viewModel.onError.observeEvent(viewLifecycleOwner) { error ->
			Snackbar.make(binding.root, error.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
		}

		viewModel.onCommentPosted.observeEvent(viewLifecycleOwner) {
			binding.editTextComment.text?.clear()
			binding.chipSpoiler.isChecked = false
			binding.editTextComment.hideKeyboard()
		}

		viewModel.onPrefillComposer.observeEvent(viewLifecycleOwner) { text ->
			binding.editTextComment.setText(text)
			binding.editTextComment.setSelection(text.length)
			binding.editTextComment.requestFocus()
			binding.editTextComment.showKeyboard()
		}

		viewModel.onMessage.observeEvent(viewLifecycleOwner) { msgRes ->
			Toast.makeText(requireContext(), msgRes, Toast.LENGTH_SHORT).show()
		}

		viewModel.onCommunityChanged.observeEvent(viewLifecycleOwner) {
			setFragmentResult(RESULT_KEY, bundleOf())
		}
	}

	private fun renderNativeState(binding: SheetSourceCommentsBinding, state: NativeCommentsUiState) {
		val info = state.info
		val statsParts = buildList {
			val scale = info?.rawScale?.takeIf { it > 0 } ?: viewModel.capabilities.maxRatingScale.coerceAtLeast(1)
			val score = when {
				info?.rawRating != null -> info.rawRating
				info?.averageRating != null -> if (info.averageRating!! <= 1f) info.averageRating!! * scale else info.averageRating!!
				else -> null
			}
			if (score != null) {
				val scoreText = if (scale > 5) {
					String.format(Locale.getDefault(), "%.1f/%d ★", score, scale)
				} else {
					String.format(Locale.getDefault(), "%.1f ★", score)
				}
				add(scoreText)
			}
			info?.ratingCount?.takeIf { it > 0 }?.let {
				add(getString(R.string.community_rating_count, formatCompactCount(it.toLong())))
			}
			val commentsCount = state.totalCount ?: if (viewModel.effectiveChapter != null) {
				info?.chapterCommentCount ?: info?.commentCount
			} else {
				info?.commentCount
			}
			commentsCount?.takeIf { it >= 0 }?.let {
				add(getString(R.string.community_comments_count, formatCompactCount(it.toLong())))
			}
			info?.viewCount?.takeIf { it > 0 }?.let {
				add(getString(R.string.community_views_count, formatCompactCount(it)))
			}
		}
		binding.textViewStats.textAndVisible = statsParts.joinToString(" · ").ifEmpty { null }

		val showAuthBanner = viewModel.capabilities.requiresAuthForComment &&
			info != null &&
			info.currentUsername == null &&
			!info.canCurrentUserComment
		binding.cardAuthBanner.isVisible = showAuthBanner

		isUpdatingSortProgrammatically = true
		binding.chipGroupSort.check(
			when (state.sort) {
				SourceCommentSort.NEWEST -> R.id.chip_sort_new
				SourceCommentSort.TOP -> R.id.chip_sort_top
				SourceCommentSort.OLDEST -> R.id.chip_sort_old
			},
		)
		isUpdatingSortProgrammatically = false

		binding.swipeRefreshLayout.isRefreshing = state.isRefreshing
		binding.progressIndicatorNative.isVisible = state.isLoading

		adapter?.submitList(state.items)

		val showEmptyOrError = !state.isLoading && state.items.isEmpty()
		binding.layoutEmptyNative.isVisible = showEmptyOrError
		if (showEmptyOrError) {
			val err = state.error
			if (err != null) {
				binding.textViewEmptyNative.text = err.getDisplayMessage(resources)
				binding.buttonNativeAction.isVisible = true
				if (err is AuthRequiredException) {
					binding.buttonNativeAction.setText(R.string.sign_in)
					binding.buttonNativeAction.setOnClickListener {
						sourceAuthLauncher.launch(err.source)
					}
				} else {
					binding.buttonNativeAction.setText(R.string.try_again)
					binding.buttonNativeAction.setOnClickListener {
						viewModel.refresh()
					}
				}
			} else {
				binding.textViewEmptyNative.setText(R.string.community_comments_empty)
				binding.buttonNativeAction.isVisible = false
			}
		}

		binding.buttonSend.isEnabled = !state.isSubmitting
		binding.editTextComment.isEnabled = !state.isSubmitting
		when (val mode = state.composerMode) {
			is ComposerMode.NewComment -> {
				binding.layoutComposeTarget.isVisible = false
				binding.chipSpoiler.isVisible = true
			}

			is ComposerMode.Reply -> {
				binding.layoutComposeTarget.isVisible = true
				binding.textViewComposeTarget.text = getString(
					R.string.community_replying_to,
					mode.parentComment.authorName,
				)
				binding.chipSpoiler.isVisible = true
			}

			is ComposerMode.Edit -> {
				binding.layoutComposeTarget.isVisible = true
				binding.textViewComposeTarget.setText(R.string.community_editing_comment)
				binding.chipSpoiler.isVisible = false
			}
		}
	}

	private fun renderWidgetState(binding: SheetSourceCommentsBinding, state: WidgetCommentsUiState) {
		binding.progressBarWidget.isVisible = state.isLoading
		if (state.isLoading) {
			binding.layoutWidgetError.isVisible = false
			return
		}
		val ctx = state.context
		if (ctx == null) {
			binding.textViewWidgetUrl.text = viewModel.manga.source.getTitle(requireContext())
			binding.layoutWidgetError.isVisible = true
			binding.textViewWidgetError.text = state.error?.getDisplayMessage(resources)
				?: getString(R.string.community_widget_load_error)
			return
		}
		val displayUrl = ctx.widgetUrl.ifBlank { ctx.pageUrl }
		binding.textViewWidgetUrl.text = displayUrl
		val webView = ensureWidgetWebView(binding) ?: return
		val signature = "${ctx.engine}:${ctx.widgetUrl}:${ctx.pageUrl}:${ctx.inlineHtml?.hashCode() ?: 0}"
		if (loadedWidgetSignature != signature) {
			loadedWidgetSignature = signature
			binding.layoutWidgetError.isVisible = false
			loadWidgetIntoWebView(webView, ctx)
		}
	}

	@SuppressLint("SetJavaScriptEnabled")
	private fun ensureWidgetWebView(binding: SheetSourceCommentsBinding): WebView? {
		widgetWebView?.let { return it }
		val ctx = context ?: return null
		return try {
			val wv = org.koitharu.kotatsu.core.ui.widgets.NestedScrollWebView(ctx).apply {
				layoutParams = ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT,
				)
				configureForParser(viewModel.getUserAgent())
				settings.javaScriptEnabled = true
				settings.domStorageEnabled = true
				settings.setSupportMultipleWindows(true)
				settings.javaScriptCanOpenWindowsAutomatically = true
				val cookieManager = CookieManager.getInstance()
				cookieManager.setAcceptCookie(true)
				cookieManager.setAcceptThirdPartyCookies(this, true)
				webViewClient = WidgetWebViewClient(binding)
				webChromeClient = WidgetWebChromeClient(binding)
			}
			binding.containerWebView.removeAllViews()
			binding.containerWebView.addView(wv)
			widgetWebView = wv
			viewLifecycleScope.launch {
				runCatching { proxyProvider.applyWebViewConfig() }
			}
			wv
		} catch (e: Throwable) {
			binding.layoutWidgetError.isVisible = true
			binding.textViewWidgetError.setText(R.string.community_widget_unavailable)
			null
		}
	}

	private fun loadWidgetIntoWebView(webView: WebView, ctx: SourceWidgetContext) {
		val inline = ctx.inlineHtml?.takeIf { it.isNotBlank() }
		if (inline != null) {
			val baseUrl = ctx.baseUrl?.takeIf { it.isNotBlank() }
				?: ctx.widgetUrl.ifBlank { ctx.pageUrl }
			val historyUrl = ctx.pageUrl.ifBlank { baseUrl }
			webView.loadDataWithBaseURL(baseUrl, inline, "text/html", "utf-8", historyUrl)
		} else {
			val targetUrl = ctx.widgetUrl.ifBlank { ctx.pageUrl }
			if (ctx.extraHeaders.isNotEmpty()) {
				webView.loadUrl(targetUrl, ctx.extraHeaders)
			} else {
				webView.loadUrl(targetUrl)
			}
		}
	}

	private fun openWidgetInBrowser(fallbackUrl: String) {
		val ctx = viewModel.widgetState.value.context
		val targetUrl = widgetWebView?.url?.takeIf { it.startsWith("http") }
			?: ctx?.pageUrl?.takeIf { it.startsWith("http") }
			?: ctx?.widgetUrl?.takeIf { it.startsWith("http") }
			?: fallbackUrl
		if (!router.openExternalBrowser(targetUrl)) {
			router.openBrowser(targetUrl, viewModel.manga.source, viewModel.manga.title)
		}
	}

	private fun formatCompactCount(value: Long): String {
		return when {
			value >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000.0)
			value >= 10_000 -> String.format(Locale.getDefault(), "%.1fK", value / 1_000.0)
			else -> NumberFormat.getIntegerInstance().format(value)
		}
	}

	private inner class WidgetWebViewClient(
		private val binding: SheetSourceCommentsBinding,
	) : WebViewClient() {

		override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
			super.onPageStarted(view, url, favicon)
			binding.progressBarWidget.isVisible = true
		}

		override fun onPageFinished(view: WebView?, url: String?) {
			super.onPageFinished(view, url)
			binding.progressBarWidget.isVisible = false
			CookieManager.getInstance().flush()
		}

		override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
			handler?.cancel()
			binding.progressBarWidget.isVisible = false
			binding.layoutWidgetError.isVisible = true
			binding.textViewWidgetError.setText(R.string.community_widget_ssl_error)
		}

		override fun onReceivedError(
			view: WebView?,
			request: WebResourceRequest?,
			error: WebResourceError?,
		) {
			super.onReceivedError(view, request, error)
			if (request?.isForMainFrame == true) {
				binding.progressBarWidget.isVisible = false
				binding.layoutWidgetError.isVisible = true
				binding.textViewWidgetError.setText(R.string.community_widget_load_error)
			}
		}

		override fun onReceivedHttpError(
			view: WebView?,
			request: WebResourceRequest?,
			errorResponse: WebResourceResponse?,
		) {
			super.onReceivedHttpError(view, request, errorResponse)
			if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
				binding.progressBarWidget.isVisible = false
				binding.layoutWidgetError.isVisible = true
				binding.textViewWidgetError.setText(R.string.community_widget_load_error)
			}
		}

		override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
			val uri = request?.url ?: return false
			val scheme = uri.scheme?.lowercase(Locale.ROOT)
			if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data") {
				return false
			}
			runCatching {
				startActivity(Intent(Intent.ACTION_VIEW, uri))
			}
			return true
		}
	}

	private inner class WidgetWebChromeClient(
		private val binding: SheetSourceCommentsBinding,
	) : WebChromeClient() {

		override fun onProgressChanged(view: WebView?, newProgress: Int) {
			binding.progressBarWidget.isVisible = newProgress in 1..99
		}

		@SuppressLint("SetJavaScriptEnabled")
		override fun onCreateWindow(
			view: WebView?,
			isDialog: Boolean,
			isUserGesture: Boolean,
			resultMsg: Message?,
		): Boolean {
			val ctx = context ?: return false
			val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
			val popupWebView = try {
				WebView(ctx).apply {
					configureForParser(viewModel.getUserAgent())
					settings.javaScriptEnabled = true
					settings.domStorageEnabled = true
					settings.setSupportMultipleWindows(true)
					settings.javaScriptCanOpenWindowsAutomatically = true
					settings.userAgentString = settings.userAgentString?.replace("; wv", "")
					val cookieManager = CookieManager.getInstance()
					cookieManager.setAcceptCookie(true)
					cookieManager.setAcceptThirdPartyCookies(this, true)
				}
			} catch (e: Throwable) {
				return false
			}

			val dialog = AppCompatDialog(ctx).apply {
				setContentView(popupWebView)
				window?.setLayout(
					ViewGroup.LayoutParams.MATCH_PARENT,
					(resources.displayMetrics.heightPixels * 0.85f).toInt(),
				)
			}

			popupWebView.webViewClient = object : WebViewClient() {
				override fun onPageFinished(view: WebView?, url: String?) {
					CookieManager.getInstance().flush()
				}

				override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
					handler?.cancel()
					dialog.dismiss()
				}
			}
			popupWebView.webChromeClient = object : WebChromeClient() {
				override fun onCloseWindow(window: WebView?) {
					CookieManager.getInstance().flush()
					dialog.dismiss()
					widgetWebView?.reload()
				}
			}
			dialog.setOnDismissListener {
				activePopupDialogs.remove(dialog)
				CookieManager.getInstance().flush()
				popupWebView.stopLoading()
				(popupWebView.parent as? ViewGroup)?.removeView(popupWebView)
				popupWebView.destroy()
			}
			activePopupDialogs.add(dialog)
			dialog.show()
			transport.webView = popupWebView
			resultMsg.sendToTarget()
			return true
		}
	}

	companion object {
		private const val TAG = "SourceCommentsSheet"
		const val RESULT_KEY = "source_comments_changed"

		fun show(
			fm: FragmentManager,
			manga: Manga,
			chapter: MangaChapter? = null,
		) {
			(fm.findFragmentByTag(TAG) as? SourceCommentsSheet)?.dismissAllowingStateLoss()
			SourceCommentsSheet().apply {
				arguments = Bundle(2).apply {
					putParcelable(SourceCommentsViewModel.ARG_MANGA, ParcelableManga(manga, withDescription = false))
					if (chapter != null) {
						putParcelable(SourceCommentsViewModel.ARG_CHAPTER, ParcelableChapter(chapter))
					}
				}
			}.show(fm, TAG)
		}
	}

	private fun View.showKeyboard() {
		requestFocus()
		val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
		imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
	}

	private fun View.hideKeyboard() {
		val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
		imm?.hideSoftInputFromWindow(windowToken, 0)
	}
}
