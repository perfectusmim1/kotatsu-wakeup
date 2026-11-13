package org.koitharu.kotatsu.details.ui.pager.bookmarks

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.BookmarkSortOrder
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.bookmarks.domain.matchesBookmarkQuery
import org.koitharu.kotatsu.bookmarks.domain.resolveBookmarkDownloadChapterIds
import org.koitharu.kotatsu.bookmarks.domain.sortedByOrder
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.model.getLocalizedTitle
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.requireValue
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.PageSaveHelper
import javax.inject.Inject

@HiltViewModel
class BookmarksViewModel @Inject constructor(
	@LocalizedAppContext private val appContext: Context,
	private val bookmarksRepository: BookmarksRepository,
	private val downloadScheduler: DownloadWorker.Scheduler,
	settings: AppSettings,
) : BaseViewModel(), FlowCollector<MangaDetails?> {

	private val manga = MutableStateFlow<Manga?>(null)
	val query = MutableStateFlow("")
	val sortOrder = MutableStateFlow(BookmarkSortOrder.CREATED_DESC)
	val onActionDone = MutableEventFlow<ReversibleAction>()
	val onDownloadStarted = MutableEventFlow<Unit>()

	val gridScale = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_GRID_SIZE_PAGES,
		valueProducer = { gridSizePages / 100f },
	)

	private val bookmarksFlow = manga.filterNotNull().flatMapLatest { m ->
		bookmarksRepository.observeBookmarks(m).map { m to it }
	}

	val hasBookmarks: StateFlow<Boolean> = bookmarksFlow
		.map { it.second.isNotEmpty() }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	val content: StateFlow<List<ListModel>> = combine(
		bookmarksFlow,
		query,
		sortOrder,
	) { (m, bookmarks), rawQuery, order ->
		mapList(m, bookmarks, rawQuery, order)
	}.withErrorHandling()
		.filterNotNull()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, listOf(LoadingState()))

	override suspend fun emit(value: MangaDetails?) {
		manga.value = value?.toManga()
	}

	fun setQuery(newQuery: String?) {
		query.value = newQuery?.trim().orEmpty()
	}

	fun setSortOrder(order: BookmarkSortOrder) {
		sortOrder.value = order
	}

	fun getMangaOrNull(): Manga? = manga.value

	fun removeBookmarks(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			val handle = bookmarksRepository.removeBookmarks(ids)
			onActionDone.call(ReversibleAction(R.string.bookmarks_removed, handle))
		}
	}

	fun savePages(pageSaveHelper: PageSaveHelper, ids: Set<Long>) {
		launchLoadingJob(Dispatchers.Default) {
			val m = manga.requireValue()
			val tasks = content.value.mapNotNull {
				if (it !is Bookmark || it.pageId !in ids) return@mapNotNull null
				PageSaveHelper.Task(
					manga = m,
					chapterId = it.chapterId,
					pageNumber = it.page + 1,
					page = it.toMangaPage(),
				)
			}
			val dest = pageSaveHelper.save(tasks)
			val msg = if (dest.size == 1) R.string.page_saved else R.string.pages_saved
			onActionDone.call(ReversibleAction(msg, null))
		}
	}

	fun downloadBookmarkedChapters(
		selectedIds: Set<Long>?,
		allowMeteredNetwork: Boolean,
		extraNextChapters: Int = 0,
	) {
		launchJob(Dispatchers.Default) {
			val m = manga.value ?: return@launchJob
			val sourceBookmarks = if (selectedIds != null) {
				content.value.filterIsInstance<Bookmark>().filter { it.pageId in selectedIds }
			} else {
				content.value.filterIsInstance<Bookmark>().ifEmpty {
					bookmarksRepository.getBookmarks(m)
				}
			}
			val chapterIds = resolveBookmarkDownloadChapterIds(m, sourceBookmarks, extraNextChapters)
			if (chapterIds.isEmpty()) {
				onActionDone.call(ReversibleAction(R.string.no_bookmarked_chapters_to_download, null))
				return@launchJob
			}
			val task = DownloadTask(
				mangaId = m.id,
				isPaused = false,
				isSilent = false,
				chaptersIds = chapterIds,
				destination = null,
				format = null,
				allowMeteredNetwork = allowMeteredNetwork,
			)
			downloadScheduler.schedule(setOf(m to task))
			onDownloadStarted.call(Unit)
		}
	}

	private fun mapList(
		manga: Manga,
		bookmarks: List<Bookmark>,
		rawQuery: String,
		order: BookmarkSortOrder,
	): List<ListModel>? {
		val chapters = manga.chapters
		if (bookmarks.isEmpty()) {
			if (chapters == null) {
				return null
			}
			return listOf(
				EmptyState(
					icon = 0,
					textPrimary = R.string.no_bookmarks_yet,
					textSecondary = R.string.no_bookmarks_summary,
					actionStringRes = 0,
				),
			)
		}
		val chaptersList = chapters.orEmpty()
		val chaptersById = chaptersList.associateBy(MangaChapter::id)
		val chapterOrderMap = chaptersList.withIndex().associate { (index, ch) -> ch.id to index }
		val normalizedQuery = rawQuery.trim()
		val filtered = if (normalizedQuery.isEmpty()) {
			bookmarks
		} else {
			bookmarks.filter { b ->
				val ch = chaptersById[b.chapterId] ?: return@filter false
				val idx = chapterOrderMap[b.chapterId] ?: -1
				ch.matchesBookmarkQuery(
					normalizedQuery = normalizedQuery,
					localizedTitle = ch.getLocalizedTitle(appContext.resources, idx),
				)
			}
		}
		if (filtered.isEmpty()) {
			return listOf(
				EmptyState(
					icon = 0,
					textPrimary = R.string.no_bookmarks_matching_query,
					textSecondary = R.string.no_bookmarks_matching_summary,
					actionStringRes = 0,
				),
			)
		}
		if (chaptersList.isEmpty()) {
			val sorted = filtered.sortedByOrder(order, chapterOrderMap)
			return ArrayList<ListModel>(sorted.size + 1).apply {
				add(ListHeader(R.string.bookmarks))
				addAll(sorted)
			}
		}
		val bookmarksMap = filtered.groupByTo(LinkedHashMap()) { it.chapterId }
		val matchedChapters = chaptersList.filter { it.id in bookmarksMap }
		val orderedChapters = when (order) {
			BookmarkSortOrder.CHAPTER_ASC -> matchedChapters
			BookmarkSortOrder.CREATED_DESC -> matchedChapters.sortedWith(
				compareByDescending<MangaChapter> { ch -> bookmarksMap[ch.id]?.maxOfOrNull { it.createdAt } }
					.thenBy { chapterOrderMap[it.id] ?: Int.MAX_VALUE },
			)

			BookmarkSortOrder.CREATED_ASC -> matchedChapters.sortedWith(
				compareBy<MangaChapter> { ch -> bookmarksMap[ch.id]?.minOfOrNull { it.createdAt } }
					.thenBy { chapterOrderMap[it.id] ?: Int.MAX_VALUE },
			)
		}
		val result = ArrayList<ListModel>(filtered.size + bookmarksMap.size + 1)
		for (chapter in orderedChapters) {
			val b = bookmarksMap.remove(chapter.id) ?: continue
			if (b.isEmpty()) {
				continue
			}
			result += ListHeader(chapter)
			result.addAll(b.sortedByOrder(order, chapterOrderMap))
		}
		if (bookmarksMap.isNotEmpty()) {
			result += ListHeader(R.string.bookmarks)
			val unmatched = bookmarksMap.values.flatten().sortedByOrder(order, chapterOrderMap)
			result.addAll(unmatched)
		}
		return result
	}
}
