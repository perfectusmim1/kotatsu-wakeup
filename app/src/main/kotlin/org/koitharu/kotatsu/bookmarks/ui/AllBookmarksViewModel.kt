package org.koitharu.kotatsu.bookmarks.ui

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.BookmarkSortOrder
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.bookmarks.domain.matchesBookmarkQuery
import org.koitharu.kotatsu.bookmarks.domain.sortedByOrder
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.model.getLocalizedTitle
import org.koitharu.kotatsu.core.model.ids
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.LocaleStringComparator
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.toErrorState
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.PageSaveHelper
import javax.inject.Inject

@HiltViewModel
class AllBookmarksViewModel @Inject constructor(
	@LocalizedAppContext private val appContext: Context,
	private val repository: BookmarksRepository,
	private val downloadScheduler: DownloadWorker.Scheduler,
) : BaseViewModel() {

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val onDownloadStarted = MutableEventFlow<Unit>()

	val query = MutableStateFlow("")
	val sortOrder = MutableStateFlow(BookmarkSortOrder.CREATED_DESC)

	private val screenState: StateFlow<BookmarksScreenState> = combine(
		repository.observeBookmarksWithChapters(),
		query,
		sortOrder,
	) { rawMap, rawQuery, order ->
		buildScreenState(rawMap, rawQuery, order)
	}.catch { e ->
		emit(
			BookmarksScreenState(
				items = listOf(e.toErrorState(canRetry = false)),
				visibleByManga = emptyMap(),
				hasAnyBookmarks = false,
			),
		)
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		BookmarksScreenState(
			items = listOf(LoadingState()),
			visibleByManga = emptyMap(),
			hasAnyBookmarks = false,
		),
	)

	val content: StateFlow<List<ListModel>> = screenState
		.map { it.items }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState()))

	val hasBookmarks: StateFlow<Boolean> = screenState
		.map { it.hasAnyBookmarks }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	fun setQuery(newQuery: String?) {
		query.value = newQuery?.trim().orEmpty()
	}

	fun setSortOrder(order: BookmarkSortOrder) {
		sortOrder.value = order
	}

	fun removeBookmarks(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			val handle = repository.removeBookmarks(ids)
			onActionDone.call(ReversibleAction(R.string.bookmarks_removed, handle))
		}
	}

	fun savePages(pageSaveHelper: PageSaveHelper, ids: Set<Long>) {
		launchLoadingJob(Dispatchers.Default) {
			val tasks = content.value.mapNotNull {
				if (it !is Bookmark || it.pageId !in ids) return@mapNotNull null
				PageSaveHelper.Task(
					manga = it.manga,
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

	fun downloadBookmarkedChapters(selectedIds: Set<Long>?, allowMeteredNetwork: Boolean) {
		launchJob(Dispatchers.Default) {
			val visibleMap = screenState.value.visibleByManga
			val targetGroups: Map<Manga, List<Bookmark>> = if (selectedIds != null) {
				visibleMap.values.asSequence()
					.flatten()
					.filter { it.pageId in selectedIds }
					.groupBy { it.manga }
			} else {
				visibleMap
			}
			val tasks = targetGroups.mapNotNull { (manga, bookmarks) ->
				val validChapterIds = manga.chapters?.ids()
				val chapterIds = bookmarks.asSequence()
					.map { it.chapterId }
					.filter { validChapterIds == null || it in validChapterIds }
					.distinct()
					.toList()
					.toLongArray()
				if (chapterIds.isEmpty()) {
					null
				} else {
					manga to DownloadTask(
						mangaId = manga.id,
						isPaused = false,
						isSilent = false,
						chaptersIds = chapterIds,
						destination = null,
						format = null,
						allowMeteredNetwork = allowMeteredNetwork,
					)
				}
			}
			if (tasks.isEmpty()) {
				onActionDone.call(ReversibleAction(R.string.no_bookmarked_chapters_to_download, null))
				return@launchJob
			}
			downloadScheduler.schedule(tasks)
			onDownloadStarted.call(Unit)
		}
	}

	fun getMigratableSeries(selectedIds: Set<Long>? = null): List<Pair<Manga, Int>> {
		val visibleMap = screenState.value.visibleByManga
		if (selectedIds != null && selectedIds.isNotEmpty()) {
			return visibleMap.mapNotNull { (manga, list) ->
				val count = list.count { it.pageId in selectedIds }
				if (count > 0) manga to count else null
			}
		}
		return visibleMap.map { (manga, list) -> manga to list.size }
	}

	private fun buildScreenState(
		rawMap: Map<Manga, List<Bookmark>>,
		rawQuery: String,
		order: BookmarkSortOrder,
	): BookmarksScreenState {
		if (rawMap.isEmpty()) {
			return BookmarksScreenState(
				items = listOf(
					EmptyState(
						icon = R.drawable.ic_empty_favourites,
						textPrimary = R.string.no_bookmarks_yet,
						textSecondary = R.string.no_bookmarks_summary,
						actionStringRes = 0,
					),
				),
				visibleByManga = emptyMap(),
				hasAnyBookmarks = false,
			)
		}
		val normalizedQuery = rawQuery.trim()
		val filteredGroups = ArrayList<Pair<Manga, List<Bookmark>>>(rawMap.size)
		for ((manga, bookmarks) in rawMap) {
			val chapters = manga.chapters.orEmpty()
			val chaptersById = chapters.associateBy(MangaChapter::id)
			val chapterOrderMap = chapters.withIndex().associate { (index, ch) -> ch.id to index }
			val matched = if (normalizedQuery.isEmpty() || manga.matchesBookmarkQuery(normalizedQuery)) {
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
			if (matched.isNotEmpty()) {
				filteredGroups.add(manga to matched.sortedByOrder(order, chapterOrderMap))
			}
		}
		if (filteredGroups.isEmpty()) {
			return BookmarksScreenState(
				items = listOf(
					EmptyState(
						icon = R.drawable.ic_empty_favourites,
						textPrimary = R.string.no_bookmarks_matching_query,
						textSecondary = R.string.no_bookmarks_matching_summary,
						actionStringRes = 0,
					),
				),
				visibleByManga = emptyMap(),
				hasAnyBookmarks = true,
			)
		}
		val titleComparator = LocaleStringComparator()
		val sortedGroups = when (order) {
			BookmarkSortOrder.CREATED_DESC -> filteredGroups.sortedWith { a, b ->
				val cmp = b.second.maxOf { it.createdAt }.compareTo(a.second.maxOf { it.createdAt })
				if (cmp != 0) cmp else titleComparator.compare(a.first.title, b.first.title)
			}

			BookmarkSortOrder.CREATED_ASC -> filteredGroups.sortedWith { a, b ->
				val cmp = a.second.minOf { it.createdAt }.compareTo(b.second.minOf { it.createdAt })
				if (cmp != 0) cmp else titleComparator.compare(a.first.title, b.first.title)
			}

			BookmarkSortOrder.CHAPTER_ASC -> filteredGroups.sortedWith { a, b ->
				titleComparator.compare(a.first.title, b.first.title)
			}
		}
		val visibleMap = LinkedHashMap<Manga, List<Bookmark>>(sortedGroups.size)
		val items = ArrayList<ListModel>(sortedGroups.sumOf { it.second.size + 1 })
		for ((manga, list) in sortedGroups) {
			visibleMap[manga] = list
			items.add(ListHeader(manga.title, R.string.more, manga))
			items.addAll(list)
		}
		return BookmarksScreenState(
			items = items,
			visibleByManga = visibleMap,
			hasAnyBookmarks = true,
		)
	}

	private data class BookmarksScreenState(
		val items: List<ListModel>,
		val visibleByManga: Map<Manga, List<Bookmark>>,
		val hasAnyBookmarks: Boolean,
	)
}
