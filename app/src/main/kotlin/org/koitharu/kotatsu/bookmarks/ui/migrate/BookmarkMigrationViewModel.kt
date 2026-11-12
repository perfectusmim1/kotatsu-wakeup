package org.koitharu.kotatsu.bookmarks.ui.migrate

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.BookmarkMigrationCandidate
import org.koitharu.kotatsu.alternatives.domain.BookmarkMigrationSearchEvent
import org.koitharu.kotatsu.alternatives.domain.MigrateUseCase
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.toErrorState
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import javax.inject.Inject

@HiltViewModel
class BookmarkMigrationViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val bookmarksRepository: BookmarksRepository,
	private val migrateUseCase: MigrateUseCase,
) : BaseViewModel() {

	val sourceManga: Manga = checkNotNull(
		savedStateHandle.get<ParcelableManga>(BookmarkMigrationSheet.ARG_MANGA)?.manga,
	)

	val bookmarksCount = MutableStateFlow(0)
	val status = MutableStateFlow(BookmarkMigrationStatus())
	val items = MutableStateFlow<List<ListModel>>(listOf(LoadingState()))
	val isMigrating = MutableStateFlow(false)
	val onMigrated = MutableEventFlow<Manga>()

	private var searchJob: Job? = null

	init {
		rescan()
	}

	fun rescan() {
		searchJob?.cancel()
		searchJob = launchJob(Dispatchers.Default) {
			items.value = listOf(LoadingState())
			status.value = BookmarkMigrationStatus(isSearching = true)
			try {
				val bookmarks = bookmarksRepository.getBookmarks(sourceManga)
				bookmarksCount.value = bookmarks.size
				if (bookmarks.isEmpty()) {
					status.value = BookmarkMigrationStatus(isSearching = false)
					items.value = listOf(
						EmptyState(
							icon = R.drawable.ic_empty_favourites,
							textPrimary = R.string.bookmark_migration_no_bookmarks,
							textSecondary = 0,
							actionStringRes = 0,
						),
					)
					return@launchJob
				}
				val resolvedChapters = migrateUseCase.resolveCachedOrLoadedChapters(sourceManga)
				val chaptersById = resolvedChapters.associateBy(MangaChapter::id)
				val bookmarkedChapters = bookmarks.asSequence()
					.map { it.chapterId }
					.distinct()
					.mapNotNull { chaptersById[it] }
					.toList()
				val resolvedManga = if (sourceManga.chapters.isNullOrEmpty() && resolvedChapters.isNotEmpty()) {
					sourceManga.copy(chapters = resolvedChapters)
				} else {
					sourceManga
				}
				val collected = LinkedHashMap<Long, BookmarkMigrationCandidate>()
				migrateUseCase.findBookmarkMigrationCandidates(
					sourceManga = resolvedManga,
					bookmarkedChapters = bookmarkedChapters,
				).collect { event ->
					when (event) {
						is BookmarkMigrationSearchEvent.Progress -> {
							status.value = BookmarkMigrationStatus(
								completedSources = event.completedSources,
								totalSources = event.totalSources,
								resultsCount = collected.size,
								isSearching = event.completedSources < event.totalSources || event.totalSources == 0,
							)
						}

						is BookmarkMigrationSearchEvent.Candidate -> {
							val candidate = event.candidate
							val prev = collected[candidate.manga.id]
							if (prev == null || BookmarkMigrationCandidate.COMPARATOR.compare(candidate, prev) < 0) {
								collected[candidate.manga.id] = candidate
							}
							val sorted = collected.values
								.sortedWith(BookmarkMigrationCandidate.COMPARATOR)
								.map(::BookmarkMigrationCandidateItem)
							if (sorted.isNotEmpty()) {
								items.value = sorted
							}
							status.value = status.value.copy(resultsCount = collected.size)
						}
					}
				}
				status.value = status.value.copy(isSearching = false, resultsCount = collected.size)
				if (collected.isEmpty()) {
					items.value = listOf(
						EmptyState(
							icon = R.drawable.ic_empty_common,
							textPrimary = R.string.bookmark_migration_no_candidates,
							textSecondary = R.string.bookmark_migration_no_candidates_summary,
							actionStringRes = R.string.bookmark_migration_rescan,
						),
					)
				}
			} catch (e: Throwable) {
				status.value = status.value.copy(isSearching = false)
				if (items.value.none { it is BookmarkMigrationCandidateItem }) {
					items.value = listOf(e.toErrorState(canRetry = true))
				}
				throw e
			}
		}
	}

	fun migrate(candidate: BookmarkMigrationCandidate) {
		if (isMigrating.value) {
			return
		}
		launchJob(Dispatchers.Default) {
			isMigrating.value = true
			try {
				migrateUseCase.migrateBookmarksOnly(sourceManga, candidate.manga)
				onMigrated.call(candidate.manga)
			} finally {
				isMigrating.value = false
			}
		}
	}
}

data class BookmarkMigrationCandidateItem(
	val candidate: BookmarkMigrationCandidate,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is BookmarkMigrationCandidateItem && candidate.manga.id == other.candidate.manga.id
	}
}

data class BookmarkMigrationStatus(
	val completedSources: Int = 0,
	val totalSources: Int = 0,
	val resultsCount: Int = 0,
	val isSearching: Boolean = false,
)
