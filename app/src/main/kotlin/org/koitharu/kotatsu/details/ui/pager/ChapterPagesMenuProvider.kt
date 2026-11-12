package org.koitharu.kotatsu.details.ui.pager

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.slider.TickVisibilityMode
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.BookmarkSortOrder
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.findCurrentPagerFragment
import org.koitharu.kotatsu.core.util.ext.setValueRounded
import org.koitharu.kotatsu.core.util.progress.IntPercentLabelFormatter
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_BOOKMARKS
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_CHAPTERS
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_PAGES
import org.koitharu.kotatsu.details.ui.pager.bookmarks.BookmarksFragment
import java.lang.ref.WeakReference

class ChapterPagesMenuProvider(
	private val viewModel: ChaptersPagesViewModel,
	private val sheet: BaseAdaptiveSheet<*>,
	private val pager: ViewPager2,
	private val settings: AppSettings,
) : OnBackPressedCallback(false), MenuProvider, SearchView.OnQueryTextListener, MenuItem.OnActionExpandListener,
	Slider.OnChangeListener {

	private var expandedItemRef: WeakReference<MenuItem>? = null
	private var cachedBookmarkSortOrder: BookmarkSortOrder = BookmarkSortOrder.CREATED_DESC

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		val tab = getCurrentTab()
		when (tab) {
			TAB_CHAPTERS -> {
				menuInflater.inflate(R.menu.opt_chapters, menu)
				menu.findItem(R.id.action_search)?.run {
					setOnActionExpandListener(this@ChapterPagesMenuProvider)
					(actionView as? SearchView)?.setupSearchView(R.string.search_chapters)
				}
				menu.findItem(R.id.action_search)?.isVisible = viewModel.emptyReason.value == null
				menu.findItem(R.id.action_reversed)?.isChecked = viewModel.isChaptersReversed.value == true
				menu.findItem(R.id.action_grid_view)?.isChecked = viewModel.isChaptersInGridView.value == true
				menu.findItem(R.id.action_downloaded)?.let { menuItem ->
					menuItem.isVisible = viewModel.mangaDetails.value?.local != null
					menuItem.isChecked = viewModel.isDownloadedOnly.value == true
				}
			}

			TAB_PAGES -> {
				menuInflater.inflate(R.menu.opt_pages, menu)
				menu.findItem(R.id.action_grid_size)?.run {
					setOnActionExpandListener(this@ChapterPagesMenuProvider)
					(actionView as? Slider)?.setupPagesSizeSlider()
				}
			}

			TAB_BOOKMARKS -> {
				menuInflater.inflate(R.menu.opt_bookmarks_tab, menu)
				val bookmarksFragment = currentBookmarksFragment()
				val hasBookmarks = bookmarksFragment?.hasBookmarks() ?: true
				menu.findItem(R.id.action_search)?.run {
					isVisible = hasBookmarks
					setOnActionExpandListener(this@ChapterPagesMenuProvider)
					(actionView as? SearchView)?.let { searchView ->
						searchView.setupSearchView(R.string.search_bookmarked_chapters_hint)
						val existingQuery = bookmarksFragment?.getBookmarksQuery().orEmpty()
						if (existingQuery.isNotEmpty() && !isActionViewExpanded) {
							expandActionView()
							searchView.setQuery(existingQuery, false)
						}
					}
				}
				menu.findItem(R.id.action_grid_size)?.run {
					setOnActionExpandListener(this@ChapterPagesMenuProvider)
					(actionView as? Slider)?.setupPagesSizeSlider()
				}
				menu.findItem(R.id.action_bookmarks_sort)?.isVisible = hasBookmarks
				menu.findItem(R.id.action_download_bookmarked)?.isVisible = hasBookmarks
				menu.findItem(R.id.action_migrate_bookmarks)?.isVisible = hasBookmarks
				val currentSort = bookmarksFragment?.getBookmarksSortOrder() ?: cachedBookmarkSortOrder
				cachedBookmarkSortOrder = currentSort
				val checkedSortId = when (currentSort) {
					BookmarkSortOrder.CREATED_DESC -> R.id.action_bookmarks_sort_newest
					BookmarkSortOrder.CREATED_ASC -> R.id.action_bookmarks_sort_oldest
					BookmarkSortOrder.CHAPTER_ASC -> R.id.action_bookmarks_sort_chapter
				}
				menu.findItem(checkedSortId)?.isChecked = true
			}
		}
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_reversed -> {
			viewModel.setChaptersReversed(!menuItem.isChecked)
			true
		}

		R.id.action_grid_view -> {
			viewModel.setChaptersInGridView(!menuItem.isChecked)
			true
		}

		R.id.action_downloaded -> {
			viewModel.isDownloadedOnly.value = !menuItem.isChecked
			true
		}

		R.id.action_bookmarks_sort_newest -> {
			updateBookmarksSort(menuItem, BookmarkSortOrder.CREATED_DESC)
			true
		}

		R.id.action_bookmarks_sort_oldest -> {
			updateBookmarksSort(menuItem, BookmarkSortOrder.CREATED_ASC)
			true
		}

		R.id.action_bookmarks_sort_chapter -> {
			updateBookmarksSort(menuItem, BookmarkSortOrder.CHAPTER_ASC)
			true
		}

		R.id.action_download_bookmarked -> {
			currentBookmarksFragment()?.downloadBookmarkedChapters()
			true
		}

		R.id.action_migrate_bookmarks -> {
			currentBookmarksFragment()?.openBookmarkMigrationSheet()
			true
		}

		else -> false
	}

	override fun handleOnBackPressed() {
		expandedItemRef?.get()?.collapseActionView()
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		expandedItemRef = WeakReference(item)
		sheet.expandAndLock()
		isEnabled = true
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		expandedItemRef = null
		isEnabled = false
		if (item.itemId == R.id.action_search) {
			(item.actionView as? SearchView)?.setQuery("", false)
			when (getCurrentTab()) {
				TAB_CHAPTERS -> viewModel.performChapterSearch(null)
				TAB_BOOKMARKS -> currentBookmarksFragment()?.performBookmarksSearch(null)
			}
		}
		sheet.unlock()
		return true
	}

	override fun onQueryTextSubmit(query: String?): Boolean = false

	override fun onQueryTextChange(newText: String?): Boolean {
		when (getCurrentTab()) {
			TAB_CHAPTERS -> viewModel.performChapterSearch(newText)
			TAB_BOOKMARKS -> currentBookmarksFragment()?.performBookmarksSearch(newText)
		}
		return true
	}

	override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
		if (fromUser) {
			settings.gridSizePages = value.toInt()
		}
	}

	private fun updateBookmarksSort(menuItem: MenuItem, order: BookmarkSortOrder) {
		menuItem.isChecked = true
		cachedBookmarkSortOrder = order
		currentBookmarksFragment()?.setBookmarksSortOrder(order)
	}

	private fun currentBookmarksFragment(): BookmarksFragment? {
		return sheet.childFragmentManager.findCurrentPagerFragment(pager) as? BookmarksFragment
	}

	private fun SearchView.setupSearchView(hintRes: Int) {
		setOnQueryTextListener(this@ChapterPagesMenuProvider)
		setIconifiedByDefault(false)
		queryHint = context.getString(hintRes)
	}

	private fun Slider.setupPagesSizeSlider() {
		valueFrom = 50f
		valueTo = 150f
		stepSize = 5f
		tickVisibilityMode = TickVisibilityMode.TICK_VISIBILITY_HIDDEN
		labelBehavior = LabelFormatter.LABEL_FLOATING
		setLabelFormatter(IntPercentLabelFormatter(context))
		setValueRounded(settings.gridSizePages.toFloat())
		addOnChangeListener(this@ChapterPagesMenuProvider)
	}

	private fun getCurrentTab(): Int {
		var page = pager.currentItem
		if (page > 0 && pager.adapter?.itemCount == 2) { // no Pages page
			page++ // shift
		}
		return page
	}
}
