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
import org.koitharu.kotatsu.bookmarks.domain.BookmarkMatcher
import org.koitharu.kotatsu.bookmarks.domain.BookmarkSortOrder
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.bookmarks.domain.matchesBookmarkQuery
import org.koitharu.kotatsu.bookmarks.domain.pickRandomBookmark
import org.koitharu.kotatsu.bookmarks.domain.resolveBookmarkDownloadChapterIds
import org.koitharu.kotatsu.bookmarks.domain.sortedByOrder
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.model.getLocalizedTitle
import org.koitharu.kotatsu.core.model.getPreferredBranch
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
		combine(
			bookmarksRepository.observeBookmarks(m),
			bookmarksRepository.observeCachedChapters(m),
		) { bookmarks, cachedChapters ->
			Triple(m, bookmarks, cachedChapters)
		}
	}

	val hasBookmarks: StateFlow<Boolean> = bookmarksFlow
		.map { it.second.isNotEmpty() }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	val content: StateFlow<List<ListModel>> = combine(
		bookmarksFlow,
		query,
		sortOrder,
	) { (m, bookmarks, cachedChapters), rawQuery, order ->
		mapList(m, bookmarks, cachedChapters, rawQuery, order)
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

	fun pickRandomBookmark(excludePageId: Long? = null): Bookmark? {
		return content.value.filterIsInstance<Bookmark>().pickRandomBookmark(excludePageId)
	}

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
		cachedChapters: List<MangaChapter>,
		rawQuery: String,
		order: BookmarkSortOrder,
	): List<ListModel>? {
		if (bookmarks.isEmpty()) {
			if (manga.chapters == null) {
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
		return buildBookmarkListModels(
			manga = manga,
			bookmarks = bookmarks,
			cachedChapters = cachedChapters,
			rawQuery = rawQuery,
			order = order,
			localizedTitleProvider = { ch, idx -> ch.getLocalizedTitle(appContext.resources, idx) },
		)
	}
}

internal fun buildBookmarkListModels(
	manga: Manga,
	bookmarks: List<Bookmark>,
	cachedChapters: List<MangaChapter>,
	rawQuery: String,
	order: BookmarkSortOrder,
	localizedTitleProvider: (MangaChapter, Int) -> String? = { ch, _ -> ch.title },
): List<ListModel> {
	val liveChapters = manga.chapters.orEmpty()
	val preferredBranch = manga.getPreferredBranch(null)
	val cachedChaptersById = cachedChapters.associateBy(MangaChapter::id)
	val liveOrderMap = liveChapters.withIndex().associate { (index, ch) -> ch.id to index }
	val cachedOrderMap = cachedChapters.withIndex().associate { (index, ch) -> ch.id to index }
	val combinedOrderMap = HashMap<Long, Int>(liveOrderMap.size + cachedOrderMap.size).apply {
		for ((id, idx) in cachedOrderMap) {
			put(id, idx)
		}
		for ((id, idx) in liveOrderMap) {
			put(id, idx)
		}
	}

	val resolvedEntries = bookmarks.map { bookmark ->
		val chapter = BookmarkMatcher.resolveDisplayChapter(
			bookmark = bookmark,
			liveChapters = liveChapters,
			cachedChaptersById = cachedChaptersById,
			preferredBranch = preferredBranch,
		)
		bookmark to chapter
	}

	val normalizedQuery = rawQuery.trim()
	val filtered = if (normalizedQuery.isEmpty()) {
		resolvedEntries
	} else {
		resolvedEntries.filter { (b, ch) ->
			if (ch != null) {
				val idx = combinedOrderMap[ch.id] ?: b.chapterIndex
				ch.matchesBookmarkQuery(
					normalizedQuery = normalizedQuery,
					localizedTitle = localizedTitleProvider(ch, idx),
				)
			} else {
				b.chapterTitle?.contains(normalizedQuery, ignoreCase = true) == true ||
					(b.chapterNumber >= 0f && b.chapterNumber.toString().contains(normalizedQuery, ignoreCase = true)) ||
					b.chapterBranch?.contains(normalizedQuery, ignoreCase = true) == true
			}
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

	val chapterByKey = LinkedHashMap<Long, MangaChapter>()
	val grouped = LinkedHashMap<Long, MutableList<Bookmark>>()
	val unmatched = ArrayList<Bookmark>()
	for ((bookmark, chapter) in filtered) {
		if (chapter != null) {
			chapterByKey.putIfAbsent(chapter.id, chapter)
			grouped.getOrPut(chapter.id) { ArrayList() }.add(bookmark)
		} else {
			unmatched.add(bookmark)
		}
	}

	val groupEntries = grouped.map { (chapterId, items) ->
		val chapter = checkNotNull(chapterByKey[chapterId])
		val liveIdx = liveOrderMap[chapterId]
		val cachedIdx = cachedOrderMap[chapterId]
		val tier = when {
			liveIdx != null -> 0
			cachedIdx != null -> 1
			else -> 2
		}
		val orderIdx = liveIdx
			?: cachedIdx
			?: items.minOfOrNull { it.chapterIndex.takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE }
			?: Int.MAX_VALUE
		val sortNumber = chapter.number.takeIf { it > 0f }
			?: items.minOfOrNull { it.chapterNumber.takeIf { n -> n > 0f } ?: Float.MAX_VALUE }
			?: Float.MAX_VALUE
		val minPercent = items.minOfOrNull { it.percent } ?: Float.MAX_VALUE
		val maxCreatedAt = items.maxOf { it.createdAt }
		val minCreatedAt = items.minOf { it.createdAt }
		BookmarkChapterGroup(
			chapter = chapter,
			bookmarks = items,
			tier = tier,
			orderIndex = orderIdx,
			volume = chapter.volume,
			number = sortNumber,
			minPercent = minPercent,
			maxCreatedAt = maxCreatedAt,
			minCreatedAt = minCreatedAt,
		)
	}

	val chapterOrderComparator = compareBy<BookmarkChapterGroup> { it.tier }
		.thenBy { it.orderIndex }
		.thenBy { it.volume }
		.thenBy { it.number }
		.thenBy { it.minPercent }
		.thenBy { it.minCreatedAt }

	val orderedGroups = when (order) {
		BookmarkSortOrder.CHAPTER_ASC -> groupEntries.sortedWith(chapterOrderComparator)
		BookmarkSortOrder.CREATED_DESC -> groupEntries.sortedWith(
			compareByDescending<BookmarkChapterGroup> { it.maxCreatedAt }
				.then(chapterOrderComparator),
		)

		BookmarkSortOrder.CREATED_ASC -> groupEntries.sortedWith(
			compareBy<BookmarkChapterGroup> { it.minCreatedAt }
				.then(chapterOrderComparator),
		)
	}

	val result = ArrayList<ListModel>(filtered.size + orderedGroups.size + 1)
	for (group in orderedGroups) {
		result += ListHeader(group.chapter)
		result.addAll(group.bookmarks.sortedByOrder(order, combinedOrderMap))
	}
	if (unmatched.isNotEmpty()) {
		result += ListHeader(R.string.bookmarks)
		result.addAll(unmatched.sortedByOrder(order, combinedOrderMap))
	}
	return result
}

private class BookmarkChapterGroup(
	val chapter: MangaChapter,
	val bookmarks: List<Bookmark>,
	val tier: Int,
	val orderIndex: Int,
	val volume: Int,
	val number: Float,
	val minPercent: Float,
	val maxCreatedAt: java.time.Instant,
	val minCreatedAt: java.time.Instant,
)
