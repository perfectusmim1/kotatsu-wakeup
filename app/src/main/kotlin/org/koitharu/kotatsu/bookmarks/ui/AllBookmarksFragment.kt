package org.koitharu.kotatsu.bookmarks.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.BookmarkSortOrder
import org.koitharu.kotatsu.bookmarks.ui.adapter.BookmarksAdapter
import org.koitharu.kotatsu.bookmarks.ui.migrate.BookmarkMigrationSheet
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.list.fastscroll.FastScroller
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.findAppCompatDelegate
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.FragmentListSimpleBinding
import org.koitharu.kotatsu.download.ui.worker.DownloadStartedObserver
import org.koitharu.kotatsu.list.ui.GridSpanResolver
import org.koitharu.kotatsu.list.ui.adapter.ListHeaderClickListener
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.ListStateHolderListener
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.reader.ui.PageSaveHelper
import javax.inject.Inject

@AndroidEntryPoint
class AllBookmarksFragment :
	BaseFragment<FragmentListSimpleBinding>(),
	ListStateHolderListener,
	OnListItemClickListener<Bookmark>,
	ListSelectionController.Callback,
	FastScroller.FastScrollListener, ListHeaderClickListener {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var pageSaveHelperFactory: PageSaveHelper.Factory

	private lateinit var pageSaveHelper: PageSaveHelper
	private val viewModel by viewModels<AllBookmarksViewModel>()
	private var bookmarksAdapter: BookmarksAdapter? = null
	private var selectionController: ListSelectionController? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		pageSaveHelper = pageSaveHelperFactory.create(this)
	}

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	): FragmentListSimpleBinding {
		return FragmentListSimpleBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(
		binding: FragmentListSimpleBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		selectionController = ListSelectionController(
			appCompatDelegate = checkNotNull(findAppCompatDelegate()),
			decoration = BookmarksSelectionDecoration(binding.root.context),
			registryOwner = this,
			callback = this,
		)
		bookmarksAdapter = BookmarksAdapter(
			clickListener = this,
			headerClickListener = this,
		)
		val spanSizeLookup = SpanSizeLookup()
		with(binding.recyclerView) {
			setHasFixedSize(true)
			val spanResolver = GridSpanResolver(resources)
			addItemDecoration(TypedListSpacingDecoration(context, false))
			adapter = bookmarksAdapter
			addOnLayoutChangeListener(spanResolver)
			spanResolver.setGridSize(settings.gridSize / 100f, this)
			val lm = GridLayoutManager(context, spanResolver.spanCount)
			lm.spanSizeLookup = spanSizeLookup
			layoutManager = lm
			selectionController?.attachToRecyclerView(this)
		}
		viewModel.content.observe(viewLifecycleOwner) {
			bookmarksAdapter?.setItems(it, spanSizeLookup)
		}
		viewModel.onError.observeEvent(
			viewLifecycleOwner,
			SnackbarErrorObserver(binding.recyclerView, this),
		)
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.recyclerView))
		viewModel.onDownloadStarted.observeEvent(viewLifecycleOwner, DownloadStartedObserver(binding.recyclerView))
		addMenuProvider(BookmarksMenuProvider())
		viewModel.hasBookmarks.observe(viewLifecycleOwner, MenuInvalidator(requireActivity()))
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		val basePadding = resources.getDimensionPixelOffset(R.dimen.list_spacing_normal)
		viewBinding?.recyclerView?.setPadding(
			barsInsets.left + basePadding,
			barsInsets.top + basePadding,
			barsInsets.right + basePadding,
			barsInsets.bottom + basePadding,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onDestroyView() {
		super.onDestroyView()
		bookmarksAdapter = null
		selectionController = null
	}

	override fun onItemClick(item: Bookmark, view: View) {
		if (selectionController?.onItemClick(item.pageId) != true) {
			val intent = ReaderIntent.Builder(view.context)
				.bookmark(item)
				.incognito()
				.build()
			router.openReader(intent)
			Toast.makeText(view.context, R.string.incognito_mode, Toast.LENGTH_SHORT).show()
		}
	}

	override fun onListHeaderClick(item: ListHeader, view: View) {
		val manga = item.payload as? Manga ?: return
		router.openDetails(manga)
	}

	override fun onItemLongClick(item: Bookmark, view: View): Boolean {
		return selectionController?.onItemLongClick(view, item.pageId) == true
	}

	override fun onItemContextClick(item: Bookmark, view: View): Boolean {
		return selectionController?.onItemContextClick(view, item.pageId) == true
	}

	override fun onRetryClick(error: Throwable) = Unit

	override fun onEmptyActionClick() = Unit

	override fun onFastScrollStart(fastScroller: FastScroller) {
		(activity as? AppBarOwner)?.appBar?.setExpanded(false, true)
	}

	override fun onFastScrollStop(fastScroller: FastScroller) = Unit

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
			menu.add(
				Menu.NONE,
				R.id.action_download_bookmarked,
				Menu.NONE,
				R.string.download_bookmarked_chapters,
			).setIcon(R.drawable.ic_download)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
		}
		if (menu.findItem(R.id.action_migrate_bookmarks) == null) {
			menu.add(
				Menu.NONE,
				R.id.action_migrate_bookmarks,
				Menu.NONE,
				R.string.migrate_bookmarks,
			).setIcon(R.drawable.ic_replace)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
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

			R.id.action_download_bookmarked -> {
				val ids = selectionController?.snapshot() ?: return false
				downloadBookmarkedChapters(ids)
				mode?.finish()
				true
			}

			R.id.action_migrate_bookmarks -> {
				val ids = selectionController?.snapshot() ?: return false
				openMigrationPicker(ids)
				mode?.finish()
				true
			}

			else -> false
		}
	}

	private fun downloadBookmarkedChapters(selectedIds: Set<Long>?) {
		router.askForDownloadOverMeteredNetwork { allow ->
			viewModel.downloadBookmarkedChapters(selectedIds, allow)
		}
	}

	private fun openMigrationPicker(selectedIds: Set<Long>?) {
		val candidates = viewModel.getMigratableSeries(selectedIds)
		if (candidates.isEmpty()) {
			return
		}
		if (candidates.size == 1) {
			BookmarkMigrationSheet.show(childFragmentManager, candidates.first().first)
			return
		}
		val context = context ?: return
		val labels = candidates.map { (manga, count) ->
			"${manga.title} (${manga.source.getTitle(context)} · ${getString(R.string.bookmarks_count_badge, count)})"
		}.toTypedArray()
		buildAlertDialog(context, isCentered = true) {
			setTitle(R.string.bookmark_migration_choose_series)
			setItems(labels) { _, which ->
				candidates.getOrNull(which)?.first?.let { target ->
					BookmarkMigrationSheet.show(childFragmentManager, target)
				}
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}

	private inner class BookmarksMenuProvider :
		MenuProvider,
		SearchView.OnQueryTextListener,
		MenuItem.OnActionExpandListener {

		override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
			menuInflater.inflate(R.menu.opt_bookmarks, menu)
			val searchItem = menu.findItem(R.id.action_bookmarks_search)
			searchItem?.setOnActionExpandListener(this)
			val searchView = searchItem?.actionView as? SearchView
			if (searchView != null) {
				searchView.setOnQueryTextListener(this)
				searchView.setIconifiedByDefault(false)
				searchView.queryHint = getString(R.string.search_bookmarks_hint)
				val currentQuery = viewModel.query.value
				if (currentQuery.isNotEmpty() && !searchItem.isActionViewExpanded) {
					searchItem.expandActionView()
					searchView.setQuery(currentQuery, false)
				}
			}
		}

		override fun onPrepareMenu(menu: Menu) {
			super.onPrepareMenu(menu)
			val hasAny = viewModel.hasBookmarks.value
			menu.findItem(R.id.action_bookmarks_search)?.isVisible = hasAny
			menu.findItem(R.id.action_bookmarks_sort)?.isVisible = hasAny
			menu.findItem(R.id.action_download_bookmarked)?.isVisible = hasAny
			menu.findItem(R.id.action_migrate_bookmarks)?.isVisible = hasAny
			val checkedSortId = when (viewModel.sortOrder.value) {
				BookmarkSortOrder.CREATED_DESC -> R.id.action_bookmarks_sort_newest
				BookmarkSortOrder.CREATED_ASC -> R.id.action_bookmarks_sort_oldest
				BookmarkSortOrder.CHAPTER_ASC -> R.id.action_bookmarks_sort_chapter
			}
			menu.findItem(checkedSortId)?.isChecked = true
		}

		override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
			R.id.action_bookmarks_sort_newest -> {
				menuItem.isChecked = true
				viewModel.setSortOrder(BookmarkSortOrder.CREATED_DESC)
				true
			}

			R.id.action_bookmarks_sort_oldest -> {
				menuItem.isChecked = true
				viewModel.setSortOrder(BookmarkSortOrder.CREATED_ASC)
				true
			}

			R.id.action_bookmarks_sort_chapter -> {
				menuItem.isChecked = true
				viewModel.setSortOrder(BookmarkSortOrder.CHAPTER_ASC)
				true
			}

			R.id.action_download_bookmarked -> {
				downloadBookmarkedChapters(selectedIds = null)
				true
			}

			R.id.action_migrate_bookmarks -> {
				openMigrationPicker(selectedIds = null)
				true
			}

			else -> false
		}

		override fun onMenuItemActionExpand(item: MenuItem): Boolean {
			(activity as? AppBarOwner)?.appBar?.setExpanded(false, true)
			return true
		}

		override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
			(item.actionView as? SearchView)?.setQuery("", false)
			viewModel.setQuery(null)
			return true
		}

		override fun onQueryTextSubmit(query: String?): Boolean {
			viewModel.setQuery(query)
			return true
		}

		override fun onQueryTextChange(newText: String?): Boolean {
			viewModel.setQuery(newText)
			return true
		}
	}

	private inner class SpanSizeLookup : GridLayoutManager.SpanSizeLookup(), Runnable {

		init {
			isSpanIndexCacheEnabled = true
			isSpanGroupIndexCacheEnabled = true
		}

		override fun getSpanSize(position: Int): Int {
			val total = (viewBinding?.recyclerView?.layoutManager as? GridLayoutManager)?.spanCount
				?: return 1
			return when (bookmarksAdapter?.getItemViewType(position)) {
				ListItemType.PAGE_THUMB.ordinal -> 1
				else -> total
			}
		}

		override fun run() {
			invalidateSpanGroupIndexCache()
			invalidateSpanIndexCache()
		}
	}
}
