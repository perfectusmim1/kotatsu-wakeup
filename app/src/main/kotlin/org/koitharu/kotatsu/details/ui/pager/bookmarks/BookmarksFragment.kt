package org.koitharu.kotatsu.details.ui.pager.bookmarks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.BookmarkSortOrder
import org.koitharu.kotatsu.bookmarks.ui.BookmarksSelectionDecoration
import org.koitharu.kotatsu.bookmarks.ui.adapter.BookmarksAdapter
import org.koitharu.kotatsu.bookmarks.ui.migrate.BookmarkMigrationSheet
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.dismissParentDialog
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.PagerNestedScrollHelper
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.findAppCompatDelegate
import org.koitharu.kotatsu.core.util.ext.findParentCallback
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.FragmentMangaBookmarksBinding
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel
import org.koitharu.kotatsu.download.ui.worker.DownloadStartedObserver
import org.koitharu.kotatsu.list.ui.GridSpanResolver
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.reader.ui.PageSaveHelper
import org.koitharu.kotatsu.reader.ui.ReaderNavigationCallback
import javax.inject.Inject

@AndroidEntryPoint
class BookmarksFragment : BaseFragment<FragmentMangaBookmarksBinding>(),
	OnListItemClickListener<Bookmark>,
	RecyclerViewOwner,
	ListSelectionController.Callback {

	private val activityViewModel by ChaptersPagesViewModel.ActivityVMLazy(this)
	private val viewModel by viewModels<BookmarksViewModel>()

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var pageSaveHelperFactory: PageSaveHelper.Factory

	override val recyclerView: RecyclerView?
		get() = viewBinding?.recyclerView

	private lateinit var pageSaveHelper: PageSaveHelper
	private var bookmarksAdapter: BookmarksAdapter? = null
	private var spanResolver: GridSpanResolver? = null
	private var selectionController: ListSelectionController? = null
	private var lastSortOrder: BookmarkSortOrder? = null
	private var lastQuery: String? = null

	private val spanSizeLookup = SpanSizeLookup()
	private val listCommitCallback = Runnable {
		spanSizeLookup.invalidateCache()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		pageSaveHelper = pageSaveHelperFactory.create(this)
		activityViewModel.mangaDetails.observe(this, viewModel)
	}

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentMangaBookmarksBinding {
		return FragmentMangaBookmarksBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: FragmentMangaBookmarksBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		spanResolver = GridSpanResolver(binding.root.resources)
		selectionController = ListSelectionController(
			appCompatDelegate = checkNotNull(findAppCompatDelegate()),
			decoration = BookmarksSelectionDecoration(binding.root.context),
			registryOwner = this,
			callback = this,
		)
		bookmarksAdapter = BookmarksAdapter(
			clickListener = this@BookmarksFragment,
			headerClickListener = null,
		)
		viewModel.gridScale.observe(viewLifecycleOwner, ::onGridScaleChanged) // before rv initialization
		with(binding.recyclerView) {
			addItemDecoration(TypedListSpacingDecoration(context, false))
			setHasFixedSize(true)
			PagerNestedScrollHelper(this).bind(viewLifecycleOwner)
			adapter = bookmarksAdapter
			addOnLayoutChangeListener(spanResolver)
			(layoutManager as GridLayoutManager).let {
				it.spanSizeLookup = spanSizeLookup
				it.spanCount = checkNotNull(spanResolver).spanCount
			}
			selectionController?.attachToRecyclerView(this)
		}
		viewModel.content.observe(viewLifecycleOwner) { items ->
			if (items.singleOrNull() is LoadingState) {
				bookmarksAdapter?.setItems(items, listCommitCallback)
				return@observe
			}
			val currentOrder = viewModel.sortOrder.value
			val currentQuery = viewModel.query.value
			val sortChanged = lastSortOrder != null && lastSortOrder != currentOrder
			val queryChanged = lastQuery != null && lastQuery != currentQuery
			lastSortOrder = currentOrder
			lastQuery = currentQuery
			if (sortChanged || queryChanged) {
				bookmarksAdapter?.submitItemsImmediate(items) {
					listCommitCallback.run()
					val rv = viewBinding?.recyclerView ?: return@submitItemsImmediate
					rv.stopScroll()
					(rv.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(0, 0)
				}
			} else {
				bookmarksAdapter?.setItems(items, listCommitCallback)
			}
		}

		viewModel.onError.observeEvent(
			viewLifecycleOwner,
			SnackbarErrorObserver(binding.recyclerView, this),
		)
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.recyclerView))
		viewModel.onDownloadStarted.observeEvent(viewLifecycleOwner, DownloadStartedObserver(binding.recyclerView))
		viewModel.hasBookmarks.observe(viewLifecycleOwner) {
			parentFragment?.view?.findViewById<Toolbar>(R.id.toolbar)?.invalidateMenu()
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding?.recyclerView?.setPadding(
			barsInsets.left,
			barsInsets.top,
			barsInsets.right,
			barsInsets.bottom,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onDestroyView() {
		spanResolver = null
		bookmarksAdapter = null
		selectionController = null
		lastSortOrder = null
		lastQuery = null
		spanSizeLookup.invalidateCache()
		super.onDestroyView()
	}

	override fun onItemClick(item: Bookmark, view: View) {
		if (selectionController?.onItemClick(item.pageId) == true) {
			return
		}
		val listener = findParentCallback(ReaderNavigationCallback::class.java)
		if (listener != null && listener.onBookmarkSelected(item)) {
			dismissParentDialog()
		} else {
			val intent = ReaderIntent.Builder(view.context)
				.manga(activityViewModel.getMangaOrNull() ?: return)
				.bookmark(item)
				.incognito()
				.build()
			router.openReader(intent)
		}
	}

	override fun onItemLongClick(item: Bookmark, view: View): Boolean {
		return selectionController?.onItemLongClick(view, item.pageId) == true
	}

	override fun onItemContextClick(item: Bookmark, view: View): Boolean {
		return selectionController?.onItemContextClick(view, item.pageId) == true
	}

	override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
		requireViewBinding().recyclerView.invalidateItemDecorations()
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu,
	): Boolean {
		menuInflater.inflate(R.menu.mode_bookmarks, menu)
		if (menu.findItem(R.id.action_download_bookmarked) == null) {
			menu.addSubMenu(
				Menu.NONE,
				R.id.action_download_bookmarked,
				Menu.NONE,
				R.string.download_bookmarked_chapters,
			).apply {
				setIcon(R.drawable.ic_download)
				item.setIcon(R.drawable.ic_download)
				item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
				add(Menu.NONE, R.id.action_download_bookmarked_only, Menu.NONE, R.string.download_bookmarked_only)
				add(Menu.NONE, R.id.action_download_bookmarked_next_2, Menu.NONE, R.string.download_bookmarked_next_2)
				add(Menu.NONE, R.id.action_download_bookmarked_next_3, Menu.NONE, R.string.download_bookmarked_next_3)
			}
		}
		return true
	}

	override fun onActionItemClicked(
		controller: ListSelectionController,
		mode: ActionMode?,
		item: MenuItem,
	): Boolean {
		return when (item.itemId) {
			R.id.action_remove -> {
				val ids = selectionController?.snapshot() ?: return false
				viewModel.removeBookmarks(ids)
				mode?.finish()
				true
			}

			R.id.action_save -> {
				viewModel.savePages(pageSaveHelper, selectionController?.snapshot() ?: return false)
				mode?.finish()
				true
			}

			R.id.action_download_bookmarked_only -> {
				val ids = selectionController?.snapshot() ?: return false
				downloadBookmarkedChapters(ids, extraNextChapters = 0)
				mode?.finish()
				true
			}

			R.id.action_download_bookmarked_next_2 -> {
				val ids = selectionController?.snapshot() ?: return false
				downloadBookmarkedChapters(ids, extraNextChapters = 2)
				mode?.finish()
				true
			}

			R.id.action_download_bookmarked_next_3 -> {
				val ids = selectionController?.snapshot() ?: return false
				downloadBookmarkedChapters(ids, extraNextChapters = 3)
				mode?.finish()
				true
			}

			else -> false
		}
	}

	fun performBookmarksSearch(query: String?) {
		viewModel.setQuery(query)
	}

	fun getBookmarksQuery(): String = viewModel.query.value

	fun setBookmarksSortOrder(order: BookmarkSortOrder) {
		viewModel.setSortOrder(order)
	}

	fun getBookmarksSortOrder(): BookmarkSortOrder = viewModel.sortOrder.value

	fun hasBookmarks(): Boolean = viewModel.hasBookmarks.value

	fun downloadBookmarkedChapters(selectedIds: Set<Long>? = null, extraNextChapters: Int = 0) {
		router.askForDownloadOverMeteredNetwork { allow ->
			viewModel.downloadBookmarkedChapters(selectedIds, allow, extraNextChapters)
		}
	}

	fun openBookmarkMigrationSheet() {
		val manga = activityViewModel.getMangaOrNull() ?: viewModel.getMangaOrNull() ?: return
		BookmarkMigrationSheet.show(parentFragmentManager, manga)
	}

	private fun onGridScaleChanged(scale: Float) {
		spanSizeLookup.invalidateCache()
		spanResolver?.setGridSize(scale, requireViewBinding().recyclerView)
	}

	private inner class SpanSizeLookup : GridLayoutManager.SpanSizeLookup() {

		init {
			isSpanIndexCacheEnabled = true
			isSpanGroupIndexCacheEnabled = true
		}

		override fun getSpanSize(position: Int): Int {
			val total = (viewBinding?.recyclerView?.layoutManager as? GridLayoutManager)?.spanCount ?: return 1
			return when (bookmarksAdapter?.getItemViewType(position)) {
				ListItemType.PAGE_THUMB.ordinal -> 1
				else -> total
			}
		}

		fun invalidateCache() {
			invalidateSpanGroupIndexCache()
			invalidateSpanIndexCache()
		}
	}
}

