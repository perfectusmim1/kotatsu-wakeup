package org.koitharu.kotatsu.details.ui

import android.app.assist.AssistContent
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannedString
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.graphics.ColorUtils
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.transition.TransitionManager
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.request.lifecycle
import coil3.request.transformations
import coil3.size.Precision
import coil3.target.ImageViewTarget
import coil3.transform.RoundedCornersTransformation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.comments.SourceCommentsSheet
import org.koitharu.kotatsu.core.image.CoilMemoryCacheKey
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.model.getSummary
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.image.FaviconDrawable
import org.koitharu.kotatsu.core.ui.image.TextDrawable
import org.koitharu.kotatsu.core.ui.image.TextViewTarget
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.sheet.BottomSheetCollapseCallback
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.LocaleUtils
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.end
import org.koitharu.kotatsu.core.util.ext.enqueueWith
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.isTextTruncated
import org.koitharu.kotatsu.core.util.ext.joinToStringWithLimit
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.parentView
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.core.util.ext.start
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import org.koitharu.kotatsu.databinding.ActivityDetailsBinding
import org.koitharu.kotatsu.databinding.DialogCommunityRatingBinding
import org.koitharu.kotatsu.databinding.LayoutDetailsTableBinding
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.data.ReadingTime
import org.koitharu.kotatsu.details.service.MangaPrefetchService
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.details.ui.scrobbling.ScrobblingItemDecoration
import org.koitharu.kotatsu.details.ui.scrobbling.ScrollingInfoAdapter
import org.koitharu.kotatsu.download.ui.worker.DownloadStartedObserver
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.mangaGridItemAD
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.StaticItemSizeResolver
import org.koitharu.kotatsu.main.ui.owners.BottomSheetOwner
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.settings.sources.auth.SourceAuthActivity
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

@AndroidEntryPoint
class DetailsActivity :
	BaseActivity<ActivityDetailsBinding>(),
	View.OnClickListener,
	View.OnLayoutChangeListener,
	ViewTreeObserver.OnDrawListener,
	ChipsView.OnChipClickListener,
	OnListItemClickListener<Bookmark>,
	SwipeRefreshLayout.OnRefreshListener,
	AuthorSpan.OnAuthorClickListener,
	BottomSheetOwner {

	@Inject
	lateinit var shortcutManager: AppShortcutManager

	@Inject
	lateinit var coil: ImageLoader

	@Inject
	lateinit var settings: AppSettings

	private val viewModel: DetailsViewModel by viewModels()
	private lateinit var menuProvider: DetailsMenuProvider
	private lateinit var infoBinding: LayoutDetailsTableBinding

	private val communityAuthLauncher = registerForActivityResult(SourceAuthActivity.Contract()) { isAuthorized ->
		if (isAuthorized) {
			viewModel.refreshCommunity(force = true)
		}
	}

	override val bottomSheet: View?
		get() = viewBinding.containerBottomSheet

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityDetailsBinding.inflate(layoutInflater))
		infoBinding = LayoutDetailsTableBinding.bind(viewBinding.root)
		configureTransparentAppBar()
		viewBinding.loadingIndicatorCommunity?.isVisible = false
		viewBinding.groupCommunityStars?.isVisible = false
		viewBinding.groupCommunityRating.isVisible = false
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		supportActionBar?.setDisplayShowTitleEnabled(false)
		viewBinding.chipFavorite.setOnClickListener(this)
		infoBinding.textViewLocal.setOnClickListener(this)
		infoBinding.textViewSource.setOnClickListener(this)
		viewBinding.imageViewCover.setOnClickListener(this)
		viewBinding.textViewTitle.setOnClickListener(this)
		viewBinding.buttonDescriptionMore.setOnClickListener(this)
		viewBinding.buttonScrobblingMore.setOnClickListener(this)
		viewBinding.buttonRelatedMore.setOnClickListener(this)
		viewBinding.buttonCommunityRating?.setOnClickListener(this)
		viewBinding.buttonCommunityComments.setOnClickListener(this)
		viewBinding.textViewCommunityRatingSummary?.setOnClickListener(this)
		viewBinding.ratingBarCommunity?.setOnClickListener(this)
		viewBinding.ratingBarCommunity?.setOnRatingBarChangeListener { _, rating, fromUser ->
			if (fromUser) {
				val caps = viewModel.communityCapabilities.value
				if (caps != null && caps.supportsRating && caps.maxRatingScale <= 5) {
					val score = rating.roundToInt().coerceIn(1, caps.maxRatingScale)
					viewModel.submitCommunityRating(score)
				}
			}
		}
		communityStarButtons().forEach { it.setOnClickListener(this) }
		supportFragmentManager.setFragmentResultListener(SourceCommentsSheet.RESULT_KEY, this) { _, _ ->
			viewModel.refreshCommunity(force = true)
		}
		viewBinding.textViewDescription.addOnLayoutChangeListener(this)
		viewBinding.swipeRefreshLayout.setOnRefreshListener(this)
		viewBinding.textViewDescription.viewTreeObserver.addOnDrawListener(this)
		infoBinding.textViewAuthor.movementMethod = LinkMovementMethodCompat.getInstance()
		viewBinding.textViewDescription.movementMethod = LinkMovementMethodCompat.getInstance()
		viewBinding.chipsTags.onChipClickListener = this
		TitleScrollCoordinator(viewBinding.textViewTitle).attach(viewBinding.scrollView)
		if (settings.isDescriptionExpanded) {
			viewBinding.textViewDescription.maxLines = Int.MAX_VALUE - 1
		}
		viewBinding.containerBottomSheet?.let { sheet ->
			sheet.setOnClickListener(this)
			sheet.addOnLayoutChangeListener(this)
			onBackPressedDispatcher.addCallback(BottomSheetCollapseCallback(sheet))
			BottomSheetBehavior.from(sheet).addBottomSheetCallback(
				DetailsBottomSheetCallback(viewBinding.swipeRefreshLayout, checkNotNull(viewBinding.navbarDim)),
			)
		}

		val appRouter = router
		viewModel.mangaDetails.filterNotNull().observe(this, ::onMangaUpdated)
		viewModel.coverUrl.observe(this, ::loadCover)
		viewModel.onMangaRemoved.observeEvent(this, ::onMangaRemoved)
		viewModel.onError
			.filterNot { appRouter.isChapterPagesSheetShown() }
			.observeEvent(this, DetailsErrorObserver(this, viewModel, exceptionResolver))
		viewModel.onActionDone
			.filterNot { appRouter.isChapterPagesSheetShown() }
			.observeEvent(this, ReversibleActionObserver(viewBinding.scrollView))
		combine(viewModel.historyInfo, viewModel.isLoading, ::Pair).observe(this) {
			onHistoryChanged(it.first, it.second)
		}
		viewModel.isLoading.observe(this, ::onLoadingStateChanged)
		viewModel.scrobblingInfo.observe(this, ::onScrobblingInfoChanged)
		viewModel.localSize.observe(this, ::onLocalSizeChanged)
		viewModel.relatedManga.observe(this, ::onRelatedMangaChanged)
		viewModel.favouriteCategories.observe(this, ::onFavoritesChanged)
		val menuInvalidator = MenuInvalidator(this)
		viewModel.isStatsAvailable.observe(this, menuInvalidator)
		viewModel.remoteManga.observe(this, menuInvalidator)
		viewModel.tags.observe(this, ::onTagsChanged)
		viewModel.chapters.observe(this, PrefetchObserver(this))
		viewModel.onDownloadStarted
			.filterNot { appRouter.isChapterPagesSheetShown() }
			.observeEvent(this, DownloadStartedObserver(viewBinding.scrollView))
		combine(
			viewModel.communityCapabilities,
			viewModel.communityInfo,
			viewModel.isCommunityLoading,
		) { caps, info, isLoading ->
			Triple(caps, info, isLoading)
		}.observe(this) { (caps, info, isLoading) ->
			onCommunityChanged(caps, info, isLoading)
		}
		viewModel.onCommunityAuthRequired.observeEvent(this, ::onCommunityAuthRequired)
		viewModel.onCommunityMessage.observeEvent(this) { messageRes ->
			Snackbar.make(viewBinding.scrollView, messageRes, Snackbar.LENGTH_SHORT).show()
		}
		menuProvider = DetailsMenuProvider(
			activity = this,
			viewModel = viewModel,
			snackbarHost = viewBinding.scrollView,
			appShortcutManager = shortcutManager,
		)
		addMenuProvider(menuProvider)
	}

	override fun onProvideAssistContent(outContent: AssistContent) {
		super.onProvideAssistContent(outContent)
		viewModel.getMangaOrNull()?.publicUrl?.toUriOrNull()?.let { outContent.webUri = it }
	}

	override fun isNsfwContent(): Flow<Boolean> = viewModel.manga.map { it?.contentRating == ContentRating.ADULT }

	override fun onClick(v: View) {
		when (v.id) {
			R.id.textView_source -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.openList(manga.source, null, null)
			}

			R.id.textView_local -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.showLocalInfoDialog(manga)
			}

			R.id.chip_favorite -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.showFavoriteDialog(manga)
			}

			R.id.imageView_cover -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.openImage(
					url = viewModel.coverUrl.value ?: return,
					source = manga.source,
					preview = CoilMemoryCacheKey.from(viewBinding.imageViewCover),
					anchor = v,
				)
			}

			R.id.button_description_more -> {
				val tv = viewBinding.textViewDescription
				if (tv.context.isAnimationsEnabled) {
					tv.parentView?.let {
						TransitionManager.beginDelayedTransition(it)
					}
				}
				if (tv.maxLines in 1 until Integer.MAX_VALUE) {
					tv.maxLines = Integer.MAX_VALUE
				} else {
					tv.maxLines = resources.getInteger(R.integer.details_description_lines)
				}
			}

			R.id.button_scrobbling_more -> {
				router.showScrobblingSelectorSheet(
					manga = viewModel.getMangaOrNull() ?: return,
					scrobblerService = viewModel.scrobblingInfo.value.firstOrNull()?.scrobbler,
				)
			}

			R.id.button_related_more -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.openRelated(manga)
			}

			R.id.textView_title -> {
				val title = viewModel.getMangaOrNull()?.title?.nullIfEmpty() ?: return
				buildAlertDialog(this) {
					setMessage(title)
					setNegativeButton(R.string.close, null)
					setPositiveButton(androidx.preference.R.string.copy) { _, _ ->
						copyToClipboard(getString(R.string.content_type_manga), title)
					}
				}.show()
			}

			R.id.button_community_rating,
			R.id.ratingBar_community,
			R.id.textView_community_rating_summary -> {
				val caps = viewModel.communityCapabilities.value ?: return
				val info = viewModel.communityInfo.value
				val manga = viewModel.getMangaOrNull() ?: return
				if (v.id == R.id.button_community_rating &&
					caps.supportsRating &&
					caps.maxRatingScale <= 5 &&
					viewBinding.groupCommunityStars != null
				) {
					val starsGroup = viewBinding.groupCommunityStars ?: return
					if (isAnimationsEnabled) {
						starsGroup.parentView?.let { TransitionManager.beginDelayedTransition(it) }
					}
					starsGroup.isVisible = !starsGroup.isVisible
				} else if (caps.supportsRating || caps.supportsStats) {
					showCommunityRatingDialog(manga, caps, info)
				}
			}

			R.id.button_star_1 -> submitInlineStarRating(1)
			R.id.button_star_2 -> submitInlineStarRating(2)
			R.id.button_star_3 -> submitInlineStarRating(3)
			R.id.button_star_4 -> submitInlineStarRating(4)
			R.id.button_star_5 -> submitInlineStarRating(5)

			R.id.button_community_comments -> {
				val manga = viewModel.getMangaOrNull() ?: return
				SourceCommentsSheet.show(supportFragmentManager, manga, null)
			}
		}
	}

	override fun onAuthorClick(author: String) {
		router.showAuthorDialog(author, viewModel.getMangaOrNull()?.source ?: return)
	}

	override fun onChipClick(chip: Chip, data: Any?) {
		val tag = data as? MangaTag ?: return
		router.showTagDialog(tag)
	}

	override fun onItemClick(item: Bookmark, view: View) {
		router.openReader(ReaderIntent.Builder(view.context).bookmark(item).incognito().build())
		Toast.makeText(view.context, R.string.incognito_mode, Toast.LENGTH_SHORT).show()
	}

	override fun onRefresh() {
		viewModel.reload()
	}

	override fun onDraw() {
		viewBinding.run {
			buttonDescriptionMore.isVisible = textViewDescription.maxLines == Int.MAX_VALUE ||
				textViewDescription.isTextTruncated
		}
	}

	override fun onLayoutChange(
		v: View?,
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		oldLeft: Int,
		oldTop: Int,
		oldRight: Int,
		oldBottom: Int
	) {
		with(viewBinding) {
			containerBottomSheet?.let { sheet ->
				val peekHeight = BottomSheetBehavior.from(sheet).peekHeight
				if (scrollView.paddingBottom != peekHeight) {
					scrollView.updatePadding(bottom = peekHeight)
				}
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		if (viewBinding.cardChapters != null) {
			// landscape
			viewBinding.cardChapters?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
				topMargin = barsInsets.top + resources.getDimensionPixelOffset(R.dimen.grid_spacing_outer)
				marginEnd = barsInsets.end(v) + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
				bottomMargin = barsInsets.bottom + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
			}
			viewBinding.scrollView.updatePaddingRelative(
				bottom = barsInsets.bottom,
				start = barsInsets.start(v),
			)
			viewBinding.appbar.updatePaddingRelative(
				start = barsInsets.start(v),
			)
			return insets.consume(v, typeMask, bottom = true, end = true)
		} else {
			viewBinding.navbarDim?.updateLayoutParams {
				height = barsInsets.bottom
			}
			return insets
		}
	}

	private fun onFavoritesChanged(categories: Set<FavouriteCategory>) {
		val chip = viewBinding.chipFavorite
		chip.setChipIconResource(if (categories.isEmpty()) R.drawable.ic_heart_outline else R.drawable.ic_heart)
		chip.text = if (categories.isEmpty()) {
			getString(R.string.add_to_favourites)
		} else {
			categories.joinToStringWithLimit(this, FAV_LABEL_LIMIT) { it.title }
		}
	}

	private fun onLocalSizeChanged(size: Long) {
		if (size == 0L) {
			infoBinding.textViewLocal.isVisible = false
			infoBinding.textViewLocalLabel.isVisible = false
		} else {
			infoBinding.textViewLocal.text = FileSize.BYTES.format(this, size)
			infoBinding.textViewLocal.isVisible = true
			infoBinding.textViewLocalLabel.isVisible = true
		}
	}

	private fun onRelatedMangaChanged(related: List<MangaListModel>) {
		if (related.isEmpty()) {
			viewBinding.groupRelated.isVisible = false
			return
		}
		val rv = viewBinding.recyclerViewRelated

		@Suppress("UNCHECKED_CAST")
		val adapter = (rv.adapter as? BaseListAdapter<ListModel>) ?: BaseListAdapter<ListModel>()
			.addDelegate(
				ListItemType.MANGA_GRID,
				mangaGridItemAD(
					sizeResolver = StaticItemSizeResolver(resources.getDimensionPixelSize(R.dimen.smaller_grid_width)),
				) { item, view ->
					router.openDetails(item.toMangaWithOverride())
				},
			).also { rv.adapter = it }
		adapter.items = related
		viewBinding.groupRelated.isVisible = true
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.swipeRefreshLayout.isRefreshing = isLoading
	}

	private fun onScrobblingInfoChanged(scrobblings: List<ScrobblingInfo>) {
		var adapter = viewBinding.recyclerViewScrobbling.adapter as? ScrollingInfoAdapter
		viewBinding.groupScrobbling.isGone = scrobblings.isEmpty()
		if (adapter != null) {
			adapter.items = scrobblings
		} else {
			adapter = ScrollingInfoAdapter(router)
			adapter.items = scrobblings
			viewBinding.recyclerViewScrobbling.adapter = adapter
			viewBinding.recyclerViewScrobbling.addItemDecoration(ScrobblingItemDecoration())
		}
	}

	private fun onMangaUpdated(details: MangaDetails) {
		val manga = details.toManga()
		with(viewBinding) {
			textViewTitle.text = manga.title
			// Alternative titles are hidden here. Sources often carry a translation per language, so
			// this ran to several lines of text nobody reads before the header even reaches the
			// favourite button. They are still on the manga object for search and matching.
			textViewSubtitle.textAndVisible = null
			textViewNsfw16.isVisible = manga.contentRating == ContentRating.SUGGESTIVE
			textViewNsfw18.isVisible = manga.contentRating == ContentRating.ADULT
			textViewDescription.text = details.description.ifNullOrEmpty { getString(R.string.no_description) }
		}
		with(infoBinding) {
			val translation = details.getLocale()
			infoBinding.textViewTranslation.textAndVisible = translation?.getDisplayLanguage(translation)
				?.toTitleCase(translation)
			infoBinding.textViewTranslation.drawableStart = translation?.let {
				LocaleUtils.getEmojiFlag(it)
			}?.let {
				TextDrawable.compound(infoBinding.textViewTranslation, it)
			}
			infoBinding.textViewTranslationLabel.isVisible = infoBinding.textViewTranslation.isVisible
			textViewAuthor.textAndVisible = manga.getAuthorsString()
			textViewAuthorLabel.isVisible = textViewAuthor.isVisible
			if (manga.hasRating) {
				ratingBarRating.rating = manga.rating * ratingBarRating.numStars
				ratingBarRating.isVisible = true
				textViewRatingLabel.isVisible = true
			} else {
				ratingBarRating.isVisible = false
				textViewRatingLabel.isVisible = false
			}
			manga.state?.let { state ->
				textViewState.textAndVisible = resources.getString(state.titleResId)
				textViewStateLabel.isVisible = textViewState.isVisible
			} ?: run {
				textViewState.isVisible = false
				textViewStateLabel.isVisible = false
			}

			if (manga.source == LocalMangaSource || manga.source == UnknownMangaSource) {
				textViewSource.isVisible = false
				textViewSourceLabel.isVisible = false
			} else {
				textViewSource.textAndVisible = manga.source.getTitle(this@DetailsActivity)
				textViewSource.setTooltipCompat(manga.source.getSummary(this@DetailsActivity))
				textViewSourceLabel.isVisible = textViewSource.isVisible == true
			}
			val faviconPlaceholderFactory = FaviconDrawable.Factory(R.style.FaviconDrawable_Chip)
			ImageRequest.Builder(this@DetailsActivity)
				.data(manga.source.faviconUri())
				.lifecycle(this@DetailsActivity)
				.crossfade(false)
				.precision(Precision.EXACT)
				.size(resources.getDimensionPixelSize(materialR.dimen.m3_chip_icon_size))
				.target(TextViewTarget(textViewSource, Gravity.START))
				.placeholder(faviconPlaceholderFactory)
				.error(faviconPlaceholderFactory)
				.fallback(faviconPlaceholderFactory)
				.mangaSourceExtra(manga.source)
				.transformations(RoundedCornersTransformation(resources.getDimension(R.dimen.chip_icon_corner)))
				.allowRgb565(true)
				.enqueueWith(coil)
		}
		title = manga.title
		invalidateOptionsMenu()
	}

	private fun onMangaRemoved(manga: Manga) {
		Toast.makeText(
			this,
			getString(R.string._s_deleted_from_local_storage, manga.title),
			Toast.LENGTH_SHORT,
		).show()
		finishAfterTransition()
	}

	private fun onHistoryChanged(info: HistoryInfo, isLoading: Boolean) = with(infoBinding) {
		textViewChapters.text = when {
			isLoading -> getString(R.string.loading_)
			info.currentChapter >= 0 -> getString(
				R.string.chapter_d_of_d,
				info.currentChapter + 1,
				info.totalChapters,
			).withEstimatedTime(info.estimatedTime)

			info.totalChapters == 0 -> getString(R.string.no_chapters)
			info.totalChapters == -1 -> getString(R.string.error_occurred)
			else -> resources.getQuantityStringSafe(R.plurals.chapters, info.totalChapters, info.totalChapters)
				.withEstimatedTime(info.estimatedTime)
		}
		textViewProgress.textAndVisible = if (info.percent <= 0f) {
			null
		} else {
			val displayPercent = if (ReadingProgress.isCompleted(info.percent)) 100 else (info.percent * 100f).toInt()
			getString(R.string.percent_string_pattern, displayPercent.toString())
		}

		progress.setProgressCompat(
			(progress.max * info.percent.coerceIn(0f, 1f)).roundToInt(),
			true,
		)
		textViewProgressLabel.isVisible = info.history != null
		textViewProgress.isVisible = info.history != null
		progress.isVisible = info.history != null
	}

	private fun onTagsChanged(tags: Collection<ChipsView.ChipModel>) {
		viewBinding.chipsTags.isVisible = tags.isNotEmpty()
		viewBinding.chipsTags.setChips(tags)
	}

	private fun loadCover(imageUrl: String?) {
		viewBinding.imageViewCover.setImageAsync(imageUrl, viewModel.getMangaOrNull())
		loadCoverBackground(imageUrl)
	}

	private fun loadCoverBackground(imageUrl: String?) {
		val bgView = viewBinding.imageViewCoverBg
		if (imageUrl == null) {
			bgView.isVisible = false
			return
		}
		val manga = viewModel.getMangaOrNull()
		ImageRequest.Builder(this)
			.data(imageUrl)
			.lifecycle(this)
			.crossfade(true)
			.allowRgb565(true)
			.target(ImageViewTarget(bgView))
			.mangaSourceExtra(manga?.source)
			.enqueueWith(coil)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			bgView.setRenderEffect(
				RenderEffect.createBlurEffect(16f, 16f, Shader.TileMode.CLAMP),
			)
		}
		val surfaceColor = getThemeColor(materialR.attr.colorSurface)
		val halfAlpha = ColorUtils.setAlphaComponent(surfaceColor, 128)
		bgView.foreground = GradientDrawable(
			GradientDrawable.Orientation.TOP_BOTTOM,
			intArrayOf(surfaceColor, halfAlpha, Color.TRANSPARENT, Color.TRANSPARENT, halfAlpha, surfaceColor),
		)
		configureTransparentAppBar()
		viewBinding.scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
			bgView.translationY = -scrollY.toFloat()
		}
		bgView.isVisible = true
	}

	private fun configureTransparentAppBar() {
		viewBinding.appbar.setBackgroundColor(Color.TRANSPARENT)
		viewBinding.appbar.setStatusBarForegroundColor(Color.TRANSPARENT)
		viewBinding.toolbar.setBackgroundColor(Color.TRANSPARENT)
	}

	private fun String.withEstimatedTime(time: ReadingTime?): String {
		if (time == null) {
			return this
		}
		val timeFormatted = time.formatShort(resources)
		return getString(R.string.chapters_time_pattern, this, timeFormatted)
	}

	private fun Manga.getAuthorsString(): SpannedString? {
		if (authors.isEmpty()) {
			return null
		}
		return buildSpannedString {
			authors.forEach { a ->
				if (a.isNotEmpty()) {
					if (isNotEmpty()) {
						append(", ")
					}
					inSpans(AuthorSpan(this@DetailsActivity)) {
						append(a)
					}
				}
			}
		}.nullIfEmpty()
	}

	private fun communityStarButtons(): List<MaterialButton> = listOfNotNull(
		viewBinding.buttonStar1,
		viewBinding.buttonStar2,
		viewBinding.buttonStar3,
		viewBinding.buttonStar4,
		viewBinding.buttonStar5,
	)

	private fun submitInlineStarRating(score: Int) {
		val maxScale = viewModel.communityCapabilities.value?.maxRatingScale ?: 5
		if (score > maxScale) return
		viewBinding.groupCommunityStars?.isVisible = false
		viewModel.submitCommunityRating(score)
	}

	private fun onCommunityChanged(
		caps: SourceCommunityCapabilities?,
		info: SourceMangaCommunityInfo?,
		isLoading: Boolean,
	) {
		if (caps == null) {
			hideAllCommunityControls()
			return
		}
		val showRating = caps.supportsRating || caps.supportsStats
		val showComments = caps.supportsNativeComments || caps.supportsWidgetFallback
		if (!showRating && !showComments) {
			hideAllCommunityControls()
			return
		}
		val ratingButton = viewBinding.buttonCommunityRating
		if (ratingButton != null && isLoading && info == null) {
			viewBinding.loadingIndicatorCommunity?.isVisible = true
			viewBinding.groupCommunityStars?.isVisible = false
			viewBinding.groupCommunityRating.isVisible = false
			ratingButton.isVisible = false
			viewBinding.buttonCommunityComments.isVisible = false
			return
		}
		viewBinding.loadingIndicatorCommunity?.isVisible = false

		val visibleIds = ArrayList<Int>(3)
		if (ratingButton != null) {
			if (showRating) {
				ratingButton.text = formatCommunityRatingButtonText(caps, info)
				ratingButton.isEnabled = !isLoading
				visibleIds.add(R.id.button_community_rating)
				updateStarButtons(caps, info)
				if (!caps.supportsRating || caps.maxRatingScale > 5) {
					viewBinding.groupCommunityStars?.isVisible = false
				}
			} else {
				ratingButton.isVisible = false
				viewBinding.groupCommunityStars?.isVisible = false
			}
		} else {
			if (showRating) {
				viewBinding.ratingBarCommunity?.let { bar ->
					val maxScale = caps.maxRatingScale.coerceAtLeast(1)
					val normalized = info?.userRating?.let { it * 5f / maxScale }
						?: info?.averageRating?.let {
							if (info.rawScale > 5 && it > 5f) it * 5f / info.rawScale else it
						}
						?: info?.rawRating?.let { it * 5f / info.rawScale.coerceAtLeast(1) }
						?: 0f
					bar.setIsIndicator(!caps.supportsRating || caps.maxRatingScale > 5 || isLoading)
					bar.rating = normalized.coerceIn(0f, 5f)
					visibleIds.add(R.id.ratingBar_community)
				}
				viewBinding.textViewCommunityRatingSummary?.let { tv ->
					tv.text = formatCommunitySummaryText(caps, info)
					visibleIds.add(R.id.textView_community_rating_summary)
				}
			} else {
				viewBinding.ratingBarCommunity?.isVisible = false
				viewBinding.textViewCommunityRatingSummary?.isVisible = false
			}
		}

		if (showComments) {
			if (ratingButton == null) {
				val commentCount = info?.commentCount
				viewBinding.buttonCommunityComments.text = if (commentCount != null && commentCount > 0) {
					getString(R.string.community_comments_count, formatCompactCount(commentCount.toLong()))
				} else {
					getString(R.string.community_comments)
				}
			}
			visibleIds.add(R.id.button_community_comments)
		} else {
			viewBinding.buttonCommunityComments.isVisible = false
		}

		viewBinding.groupCommunityRating.referencedIds = visibleIds.toIntArray()
		viewBinding.groupCommunityRating.isVisible = visibleIds.isNotEmpty()
	}

	private fun hideAllCommunityControls() {
		viewBinding.loadingIndicatorCommunity?.isVisible = false
		viewBinding.groupCommunityStars?.isVisible = false
		viewBinding.groupCommunityRating.isVisible = false
		viewBinding.buttonCommunityRating?.isVisible = false
		viewBinding.buttonCommunityComments.isVisible = false
		viewBinding.ratingBarCommunity?.isVisible = false
		viewBinding.textViewCommunityRatingSummary?.isVisible = false
	}

	private fun updateStarButtons(caps: SourceCommunityCapabilities, info: SourceMangaCommunityInfo?) {
		val buttons = communityStarButtons()
		if (buttons.isEmpty()) return
		val userScore = info?.userRating ?: 0
		val activeTint = ColorStateList.valueOf(getThemeColor(appcompatR.attr.colorPrimary))
		val inactiveTint = ColorStateList.valueOf(getThemeColor(materialR.attr.colorOnSurfaceVariant))
		buttons.forEachIndexed { index, button ->
			val starValue = index + 1
			button.isVisible = starValue <= caps.maxRatingScale
			val filled = starValue <= userScore
			button.setIconResource(if (filled) R.drawable.ic_star_rate else R.drawable.ic_star_rate_outline)
			button.iconTint = if (filled) activeTint else inactiveTint
		}
	}

	private fun showCommunityRatingDialog(
		manga: Manga,
		caps: SourceCommunityCapabilities,
		info: SourceMangaCommunityInfo?,
	) {
		val dialogBinding = DialogCommunityRatingBinding.inflate(layoutInflater)
		val statsSummary = buildCommunityStatsSummary(caps, info)
		dialogBinding.textViewStatsSummary.textAndVisible = statsSummary

		val maxScale = caps.maxRatingScale.coerceAtLeast(1)
		var selectedScore = (info?.userRating ?: ((maxScale + 1) / 2)).coerceIn(1, maxScale)

		if (caps.supportsRating) {
			dialogBinding.layoutRatingPicker.isVisible = true
			dialogBinding.textViewSelectedScore.text = "$selectedScore / $maxScale"
			dialogBinding.sliderRating.valueFrom = 1f
			dialogBinding.sliderRating.valueTo = maxScale.toFloat()
			dialogBinding.sliderRating.stepSize = 1f
			dialogBinding.sliderRating.value = selectedScore.toFloat()

			var isSyncing = false
			val scoreChips = ArrayList<Chip>(maxScale)
			dialogBinding.chipGroupQuickScores.removeAllViews()
			for (score in 1..maxScale) {
				val chip = Chip(this).apply {
					id = View.generateViewId()
					text = score.toString()
					isCheckable = true
					isChecked = score == selectedScore
					setEnsureMinTouchTargetSize(false)
					setOnClickListener {
						if (isSyncing) return@setOnClickListener
						isSyncing = true
						selectedScore = score
						dialogBinding.sliderRating.value = score.toFloat()
						dialogBinding.textViewSelectedScore.text = "$score / $maxScale"
						isSyncing = false
					}
				}
				scoreChips.add(chip)
				dialogBinding.chipGroupQuickScores.addView(chip)
			}

			dialogBinding.sliderRating.addOnChangeListener { _, value, fromUser ->
				if (!fromUser || isSyncing) return@addOnChangeListener
				isSyncing = true
				selectedScore = value.roundToInt().coerceIn(1, maxScale)
				dialogBinding.textViewSelectedScore.text = "$selectedScore / $maxScale"
				scoreChips.getOrNull(selectedScore - 1)?.isChecked = true
				isSyncing = false
			}

			if (caps.requiresAuthForRating && info != null && !info.canCurrentUserRate && info.currentUsername == null) {
				dialogBinding.textViewAuthHint.textAndVisible =
					getString(R.string.community_sign_in_to_rate, manga.source.getTitle(this))
			} else {
				dialogBinding.textViewAuthHint.isVisible = false
			}
		} else {
			dialogBinding.layoutRatingPicker.isVisible = false
			dialogBinding.textViewAuthHint.isVisible = false
		}

		buildAlertDialog(this) {
			setTitle(R.string.community_rating_title)
			setView(dialogBinding.root)
			setNegativeButton(R.string.close, null)
			if (caps.supportsRating) {
				setPositiveButton(R.string.community_submit_rating) { _, _ ->
					viewModel.submitCommunityRating(selectedScore)
				}
			}
		}.show()
	}

	private fun onCommunityAuthRequired(source: MangaSource) {
		Snackbar.make(
			viewBinding.scrollView,
			getString(R.string.community_sign_in_to_rate, source.getTitle(this)),
			Snackbar.LENGTH_LONG,
		).setAction(R.string.sign_in) {
			communityAuthLauncher.launch(source)
		}.show()
	}

	private fun formatCommunityRatingButtonText(
		caps: SourceCommunityCapabilities,
		info: SourceMangaCommunityInfo?,
	): String {
		if (info == null) {
			return if (caps.supportsRating) getString(R.string.community_rate) else getString(R.string.community_rating_none)
		}
		val displayScore = info.rawRating ?: info.averageRating
		val viewCount = info.viewCount
		val followCount = info.followCount
		val base = when {
			displayScore != null -> {
				val scoreFormatted = String.format(Locale.getDefault(), "%.1f", displayScore)
				val ratingCount = info.ratingCount
				if (ratingCount != null && ratingCount > 0) {
					"$scoreFormatted (${formatCompactCount(ratingCount.toLong())})"
				} else {
					scoreFormatted
				}
			}
			info.userRating != null -> getString(R.string.community_your_rating, info.userRating, caps.maxRatingScale)
			viewCount != null && viewCount > 0L -> getString(R.string.community_views_count, formatCompactCount(viewCount))
			followCount != null && followCount > 0L -> getString(R.string.community_follows_count, formatCompactCount(followCount))
			caps.supportsRating -> getString(R.string.community_rate)
			else -> getString(R.string.community_rating_none)
		}
		val userRating = info.userRating
		return if (displayScore != null && userRating != null) {
			"$base · ${getString(R.string.community_your_rating_short, userRating)}"
		} else {
			base
		}
	}

	private fun formatCommunitySummaryText(
		caps: SourceCommunityCapabilities,
		info: SourceMangaCommunityInfo?,
	): String {
		return buildCommunityStatsSummary(caps, info)
			?: if (caps.supportsRating) getString(R.string.community_rate) else getString(R.string.community_rating_none)
	}

	private fun buildCommunityStatsSummary(
		caps: SourceCommunityCapabilities,
		info: SourceMangaCommunityInfo?,
	): String? {
		if (info == null) return null
		val parts = ArrayList<String>(5)
		val displayScore = info.rawRating ?: info.averageRating
		if (displayScore != null) {
			val scale = if (info.rawRating != null) info.rawScale else caps.maxRatingScale
			parts.add("${String.format(Locale.getDefault(), "%.1f", displayScore)} / $scale")
		}
		info.userRating?.let { userScore ->
			parts.add(getString(R.string.community_your_rating, userScore, caps.maxRatingScale))
		}
		info.ratingCount?.takeIf { it > 0 }?.let { count ->
			parts.add(getString(R.string.community_rating_count, formatCompactCount(count.toLong())))
		}
		info.viewCount?.takeIf { it > 0L }?.let { count ->
			parts.add(getString(R.string.community_views_count, formatCompactCount(count)))
		}
		info.followCount?.takeIf { it > 0L }?.let { count ->
			parts.add(getString(R.string.community_follows_count, formatCompactCount(count)))
		}
		info.commentCount?.takeIf { it > 0 }?.let { count ->
			parts.add(getString(R.string.community_comments_count, formatCompactCount(count.toLong())))
		}
		return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
	}

	private fun formatCompactCount(value: Long): String = when {
		value >= 1_000_000L -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000.0)
		value >= 1_000L -> String.format(Locale.getDefault(), "%.1fK", value / 1_000.0)
		else -> value.toString()
	}

	private class PrefetchObserver(
		private val context: Context,
	) : FlowCollector<List<ChapterListItem>?> {

		private var isCalled = false

		override suspend fun emit(value: List<ChapterListItem>?) {
			if (value.isNullOrEmpty()) {
				return
			}
			if (!isCalled) {
				isCalled = true
				val item = value.find { it.isCurrent } ?: value.first()
				MangaPrefetchService.prefetchPages(context, item.chapter)
			}
		}
	}

	companion object {

		private const val FAV_LABEL_LIMIT = 16
	}

}
