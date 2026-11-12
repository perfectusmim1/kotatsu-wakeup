package org.koitharu.kotatsu.details.ui.pager.pages

import android.net.Uri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.firstNotNull
import org.koitharu.kotatsu.core.util.ext.requireValue
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.reader.domain.ChaptersLoader
import org.koitharu.kotatsu.reader.ui.PageSaveHelper
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class PagesViewModel @Inject constructor(
	private val chaptersLoader: ChaptersLoader,
	private val bookmarksRepository: BookmarksRepository,
	settings: AppSettings,
) : BaseViewModel() {

	private var loadingJob: Job? = null
	private var loadingPrevJob: Job? = null
	private var loadingNextJob: Job? = null
	private var loadedBranch: String? = null

	private val state = MutableStateFlow<State?>(null)
	val thumbnails = MutableStateFlow<List<ListModel>>(emptyList())
	val isLoadingUp = MutableStateFlow(false)
	val isLoadingDown = MutableStateFlow(false)
	val onPageSaved = MutableEventFlow<Collection<Uri>>()
	val onActionDone = MutableEventFlow<ReversibleAction>()

	val gridScale = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_GRID_SIZE_PAGES,
		valueProducer = { gridSizePages / 100f },
	)

	init {
		launchJob(Dispatchers.Default) {
			state.filterNotNull()
				.collect {
					val prevJob = loadingJob
					loadingJob = launchLoadingJob(Dispatchers.Default) {
						prevJob?.cancelAndJoin()
						doInit(it)
					}
				}
		}
	}

	fun updateState(newState: State?) {
		if (newState != null) {
			state.value = newState
		}
	}

	fun loadPrevChapter() {
		if (loadingJob?.isActive == true || loadingPrevJob?.isActive == true) {
			return
		}
		loadingPrevJob = loadPrevNextChapter(isNext = false)
	}

	fun loadNextChapter() {
		if (loadingJob?.isActive == true || loadingNextJob?.isActive == true) {
			return
		}
		loadingNextJob = loadPrevNextChapter(isNext = true)
	}

	fun savePages(
		pageSaveHelper: PageSaveHelper,
		pages: Set<ReaderPage>,
	) {
		launchLoadingJob(Dispatchers.Default) {
			val manga = state.requireValue().details.toManga()
			val tasks = pages.map {
				PageSaveHelper.Task(
					manga = manga,
					chapterId = it.chapterId,
					pageNumber = it.index + 1,
					page = it.toMangaPage(),
				)
			}
			val dest = pageSaveHelper.save(tasks)
			onPageSaved.call(dest)
		}
	}

	fun addBookmarks(pages: Collection<ReaderPage>) {
		if (pages.isEmpty()) {
			return
		}
		launchJob(Dispatchers.Default) {
			val details = state.requireValue().details
			val manga = details.toManga()
			val chaptersByBranch = details.chapters
			val now = Instant.now()
			val sortedPages = pages.sortedWith(compareBy<ReaderPage> { it.chapterId }.thenBy { it.index })
			val bookmarks = sortedPages.map { readerPage ->
				Bookmark(
					manga = manga,
					pageId = readerPage.id,
					chapterId = readerPage.chapterId,
					page = readerPage.index,
					scroll = 0,
					imageUrl = readerPage.preview.ifNullOrEmpty { readerPage.url },
					createdAt = now,
					percent = computePercent(details, chaptersByBranch, readerPage.chapterId, readerPage.index),
				)
			}
			val handle = bookmarksRepository.addBookmarks(bookmarks)
			val msg = if (bookmarks.size == 1) R.string.bookmark_added else R.string.bookmark_added_many
			onActionDone.call(ReversibleAction(msg, handle))
		}
	}

	private fun computePercent(
		details: MangaDetails,
		chaptersByBranch: Map<String?, List<MangaChapter>>,
		chapterId: Long,
		pageIndex: Int,
	): Float {
		val branch = chaptersLoader.peekChapter(chapterId)?.branch
			?: details.allChapters.find { it.id == chapterId }?.branch
		val chapters = chaptersByBranch[branch].orEmpty()
		val chaptersCount = chapters.size
		val chapterIndex = chapters.indexOfFirst { it.id == chapterId }
		if (chapterIndex < 0 || chaptersCount == 0) {
			return PROGRESS_NONE
		}
		val pagesCount = chaptersLoader.getPagesCount(chapterId)
		val ppc = 1f / chaptersCount
		if (pagesCount <= 0) {
			return (ppc * chapterIndex).coerceIn(0f, 1f)
		}
		val pagePercent = (pageIndex + 1).coerceIn(1, pagesCount) / pagesCount.toFloat()
		return (ppc * chapterIndex + ppc * pagePercent).coerceIn(0f, 1f)
	}

	private suspend fun doInit(state: State) {
		if (chaptersLoader.size > 0 && chaptersLoader.hasLoadedPages() && thumbnails.value.isNotEmpty() && loadedBranch == state.branch) {
			chaptersLoader.init(state.details)
			updateCurrentPageIndicator(state.readerState)
			return
		}
		loadingPrevJob?.cancelAndJoin()
		loadingNextJob?.cancelAndJoin()
		chaptersLoader.init(state.details)
		loadedBranch = state.branch
		val initialChapterId = state.readerState?.chapterId?.takeIf {
			chaptersLoader.peekChapter(it) != null
		} ?: state.details.allChapters.firstOrNull()?.id ?: return
		if (!chaptersLoader.hasPages(initialChapterId)) {
			var hasPages = chaptersLoader.loadSingleChapter(initialChapterId)
			while (!hasPages) {
				if (chaptersLoader.loadPrevNextChapter(state.details, initialChapterId, isNext = true, trimPages = false)) {
					hasPages = chaptersLoader.snapshot().isNotEmpty()
				} else {
					break
				}
			}
		}
		updateList(state.readerState)
	}

	private fun loadPrevNextChapter(isNext: Boolean): Job = launchJob(Dispatchers.Default) {
		val indicator = if (isNext) isLoadingDown else isLoadingUp
		indicator.value = true
		try {
			if (!chaptersLoader.hasLoadedPages()) {
				return@launchJob
			}
			val currentState = state.firstNotNull()
			val currentId = (if (isNext) chaptersLoader.last() else chaptersLoader.first()).chapterId
			if (chaptersLoader.loadPrevNextChapter(currentState.details, currentId, isNext, trimPages = false)) {
				val readerState = this@PagesViewModel.state.value?.readerState ?: currentState.readerState
				updateList(readerState)
			}
		} finally {
			indicator.value = false
		}
	}

	private fun updateCurrentPageIndicator(readerState: ReaderState?) {
		thumbnails.update { items ->
			items.map { item ->
				if (item is PageThumbnail) {
					val isCurrent = readerState?.let {
						item.page.chapterId == it.chapterId && item.page.index == it.page
					} == true
					if (item.isCurrent != isCurrent) {
						item.copy(isCurrent = isCurrent)
					} else {
						item
					}
				} else {
					item
				}
			}
		}
	}

	private suspend fun updateList(readerState: ReaderState?) {
		val snapshot = chaptersLoader.snapshot()
		val pages = buildList(snapshot.size + chaptersLoader.size + 2) {
			var previousChapterId = 0L
			for (page in snapshot) {
				if (page.chapterId != previousChapterId) {
					chaptersLoader.peekChapter(page.chapterId)?.let {
						add(ListHeader(it))
					}
					previousChapterId = page.chapterId
				}
				this += PageThumbnail(
					isCurrent = readerState?.let {
						page.chapterId == it.chapterId && page.index == it.page
					} == true,
					page = page,
				)
			}
		}
		thumbnails.value = pages
	}

	data class State(
		val details: MangaDetails,
		val readerState: ReaderState?,
		val branch: String?
	)
}
