package org.koitharu.kotatsu.alternatives.domain

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toMangaChapters
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.details.domain.ProgressUpdateUseCase
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.toMangaHistory
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.mangaTitleMatchDistance
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.tracker.data.TrackEntity
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.math.abs

class MigrateUseCase
@Inject
constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mangaDataRepository: MangaDataRepository,
	private val database: MangaDatabase,
	private val progressUpdateUseCase: ProgressUpdateUseCase,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
	private val alternativesUseCase: AlternativesUseCase,
) {
	suspend operator fun invoke(
		oldManga: Manga,
		newManga: Manga,
	) {
		Log.i(TAG, "Migration started: ${oldManga.debugRef()} -> ${newManga.debugRef()}")
		try {
			migrateInternal(oldManga, newManga)
			Log.i(TAG, "Migration completed: ${oldManga.debugRef()} -> ${newManga.debugRef()}")
		} catch (e: CancellationException) {
			Log.d(TAG, "Migration cancelled: ${oldManga.debugRef()} -> ${newManga.debugRef()}")
			throw e
		} catch (e: Throwable) {
			Log.e(TAG, "Migration failed: ${oldManga.debugRef()} -> ${newManga.debugRef()}", e)
			throw e
		}
	}

	private suspend fun migrateInternal(
		oldManga: Manga,
		newManga: Manga,
	) {
		Log.d(TAG, "Migration stage=load_old_details old=${oldManga.debugRef()}")
		val oldDetails = resolveOldMangaDetails(oldManga)
		Log.d(TAG, "Migration stage=load_target_details target=${newManga.debugRef()}")
		val newDetails = if (newManga.chapters.isNullOrEmpty()) {
			mangaRepositoryFactory.create(newManga.source).getDetails(newManga)
		} else {
			newManga
		}
		Log.d(TAG, "Migration stage=store_target target=${newDetails.debugRef()} chapters=${newDetails.chapters?.size ?: 0}")
		mangaDataRepository.storeManga(newDetails, replaceExisting = true)
		Log.d(TAG, "Migration stage=database_transaction oldId=${oldDetails.id} targetId=${newDetails.id}")
		database.withTransaction {
			// replace favorites
			Log.d(TAG, "Migration stage=favorites oldId=${oldDetails.id} targetId=${newDetails.id}")
			val favoritesDao = database.getFavouritesDao()
			val oldFavourites = favoritesDao.findAllRaw(oldDetails.id)
			if (oldFavourites.isNotEmpty()) {
				favoritesDao.delete(oldManga.id)
				for (f in oldFavourites) {
					val e =
						f.copy(
							mangaId = newManga.id,
						)
					favoritesDao.upsert(e)
				}
			}
			// replace history
			Log.d(TAG, "Migration stage=history oldId=${oldDetails.id} targetId=${newDetails.id}")
			val historyDao = database.getHistoryDao()
			val oldHistory = historyDao.find(oldDetails.id)
			val newHistory =
				if (oldHistory != null) {
					val newHistory = makeNewHistory(oldDetails, newDetails, oldHistory)
					database.replaceHistory(oldDetails.id, newHistory)
					newHistory
				} else {
					null
				}
			// track
			Log.d(TAG, "Migration stage=tracking oldId=${oldDetails.id} targetId=${newDetails.id}")
			val tracksDao = database.getTracksDao()
			val oldTrack = tracksDao.find(oldDetails.id)
			if (oldTrack != null) {
				val lastChapter = newDetails.chapters?.lastOrNull()
				val newTrack =
					TrackEntity(
						mangaId = newDetails.id,
						lastChapterId = lastChapter?.id ?: 0L,
						newChapters = 0,
						lastCheckTime = System.currentTimeMillis(),
						lastChapterDate = lastChapter?.uploadDate ?: 0L,
						lastResult = TrackEntity.RESULT_EXTERNAL_MODIFICATION,
						lastError = null,
					)
				tracksDao.delete(oldDetails.id)
				tracksDao.upsert(newTrack)
			}
			// scrobbling
			Log.d(TAG, "Migration stage=scrobbling oldId=${oldDetails.id} targetId=${newDetails.id}")
			for (scrobbler in scrobblers) {
				if (!scrobbler.isEnabled) {
					continue
				}
				val prevInfo = scrobbler.getScrobblingInfoOrNull(oldDetails.id) ?: continue
				scrobbler.unregisterScrobbling(oldDetails.id)
				scrobbler.linkManga(newDetails.id, prevInfo.targetId)
				scrobbler.updateScrobblingInfo(
					mangaId = newDetails.id,
					rating = prevInfo.rating,
					status =
						prevInfo.status ?: when {
							newHistory == null -> ScrobblingStatus.PLANNED
							newHistory.percent == 1f -> ScrobblingStatus.COMPLETED
							else -> ScrobblingStatus.READING
						},
					comment = prevInfo.comment,
				)
				if (newHistory != null) {
					scrobbler.scrobble(
						manga = newDetails,
						chapterId = newHistory.chapterId,
					)
				}
			}
			// bookmarks
			Log.d(TAG, "Migration stage=bookmarks oldId=${oldDetails.id} targetId=${newDetails.id}")
			migrateBookmarksInTransaction(oldDetails, newDetails)
		}
		Log.d(TAG, "Migration stage=progress_update target=${newManga.debugRef()}")
		progressUpdateUseCase(newManga)
	}

	suspend fun findBookmarkMigrationCandidates(
		sourceManga: Manga,
		bookmarkedChapters: List<MangaChapter>,
		activeSources: List<MangaSource>? = null,
	): Flow<BookmarkMigrationSearchEvent> {
		val distinctBookmarked = bookmarkedChapters.distinctBy { it.id }
		val oldChapters = resolveCachedOrLoadedChapters(sourceManga)
		val seedTitles = buildList(1 + sourceManga.altTitles.size) {
			add(sourceManga.title)
			addAll(sourceManga.altTitles)
		}
		val searchFlow = if (activeSources == null) {
			alternativesUseCase(
				manga = sourceManga,
				options = AlternativesSearchOptions(
					query = sourceManga.title,
					sourceScope = AlternativeSourceScope.Enabled,
					sameLanguageOnly = false,
					sameContentTypeOnly = false,
				),
				loadDetails = true,
			)
		} else {
			val sources = activeSources
				.filter { it.name != sourceManga.source.name }
				.distinctBy(MangaSource::name)
			channelFlow {
				send(AlternativeSearchEvent.Progress(0, sources.size))
				val completed = AtomicInteger()
				for (source in sources) {
					launch {
						try {
							alternativesUseCase.searchSource(
								manga = sourceManga,
								source = source,
								query = sourceManga.title,
								loadDetails = true,
							).collect { candidate ->
								send(AlternativeSearchEvent.Result(candidate))
							}
						} finally {
							send(AlternativeSearchEvent.Progress(completed.incrementAndGet(), sources.size))
						}
					}
				}
			}
		}
		return searchFlow.map { event ->
			when (event) {
				is AlternativeSearchEvent.Progress -> BookmarkMigrationSearchEvent.Progress(
					completedSources = event.completedSources,
					totalSources = event.totalSources,
				)

				is AlternativeSearchEvent.Result -> BookmarkMigrationSearchEvent.Candidate(
					scoreBookmarkCandidate(
						sourceChaptersCount = oldChapters.size,
						bookmarkedChapters = distinctBookmarked,
						candidate = event.manga,
						seedTitles = seedTitles,
					),
				)
			}
		}
	}

	suspend fun migrateBookmarksOnly(
		oldManga: Manga,
		newManga: Manga,
	): Int {
		val oldDetails = if (oldManga.chapters.isNullOrEmpty()) {
			val cachedChapters = database.getChaptersDao().findAll(oldManga.id).toMangaChapters()
			if (cachedChapters.isNotEmpty()) {
				oldManga.copy(chapters = cachedChapters)
			} else {
				resolveOldMangaDetails(oldManga)
			}
		} else {
			oldManga
		}
		val newDetails = if (newManga.chapters.isNullOrEmpty()) {
			mangaRepositoryFactory.create(newManga.source).getDetails(newManga)
		} else {
			newManga
		}
		mangaDataRepository.storeManga(newDetails, replaceExisting = true)
		return database.withTransaction {
			migrateBookmarksInTransaction(oldDetails, newDetails)
		}
	}

	suspend fun resolveCachedOrLoadedChapters(manga: Manga): List<MangaChapter> {
		val direct = manga.chapters
		if (!direct.isNullOrEmpty()) {
			return direct
		}
		val cached = database.getChaptersDao().findAll(manga.id).toMangaChapters()
		if (cached.isNotEmpty()) {
			return cached
		}
		return resolveOldMangaDetails(manga).chapters.orEmpty()
	}

	private suspend fun resolveOldMangaDetails(oldManga: Manga): Manga {
		if (!oldManga.chapters.isNullOrEmpty()) {
			return oldManga
		}
		val result = runCatchingCancellable {
			mangaRepositoryFactory.create(oldManga.source).getDetails(oldManga)
		}
		result.exceptionOrNull()?.let {
			Log.w(TAG, "Could not load old details; using cached manga: ${oldManga.debugRef()}", it)
		}
		val remote = result.getOrDefault(oldManga)
		if (!remote.chapters.isNullOrEmpty()) {
			return remote
		}
		val cached = database.getChaptersDao().findAll(oldManga.id).toMangaChapters()
		return if (cached.isNotEmpty()) remote.copy(chapters = cached) else remote
	}

	private suspend fun migrateBookmarksInTransaction(
		oldDetails: Manga,
		newDetails: Manga,
	): Int {
		val bookmarksDao = database.getBookmarksDao()
		val oldBookmarks = bookmarksDao.findAllByManga(oldDetails.id)
		if (oldBookmarks.isEmpty()) {
			return 0
		}
		val newChaptersAll = newDetails.chapters.orEmpty()
		if (newChaptersAll.isEmpty()) {
			return 0
		}
		val oldChapters = oldDetails.chapters.orEmpty().ifEmpty {
			database.getChaptersDao().findAll(oldDetails.id).toMangaChapters()
		}
		val oldChaptersById = oldChapters.associateBy { it.id }
		val preferredBranch = newDetails.getPreferredBranch(null)
		val fallbackPool = newDetails.getChapters(preferredBranch).ifEmpty { newChaptersAll }
		val migrated = ArrayList<BookmarkEntity>(oldBookmarks.size)
		for (bookmark in oldBookmarks) {
			val oldChapter = oldChaptersById[bookmark.chapterId]
			val matchedChapter = findMatchingTargetChapter(
				oldChapter = oldChapter,
				newDetails = newDetails,
				newChaptersAll = newChaptersAll,
			) ?: run {
				val oldIndex = if (oldChapter != null) oldChapters.indexOfFirst { it.id == oldChapter.id } else -1
				when {
					oldIndex >= 0 && oldChapters.size > 1 -> {
						val ratio = oldIndex.toFloat() / oldChapters.lastIndex.coerceAtLeast(1)
						val targetIdx = (ratio * fallbackPool.lastIndex).toInt().coerceIn(0, fallbackPool.lastIndex)
						fallbackPool[targetIdx]
					}

					bookmark.percent in 0f..1f -> {
						val targetIdx = (bookmark.percent * fallbackPool.lastIndex).toInt().coerceIn(0, fallbackPool.lastIndex)
						fallbackPool[targetIdx]
					}

					else -> fallbackPool.first()
				}
			}
			val updatedPercent = if (bookmark.percent in 0f..1f) {
				bookmark.percent
			} else {
				val targetBranchChapters = newDetails.getChapters(matchedChapter.branch).ifEmpty { newChaptersAll }
				val idx = targetBranchChapters.indexOfFirst { it.id == matchedChapter.id }.coerceAtLeast(0)
				idx.toFloat() / targetBranchChapters.size.coerceAtLeast(1)
			}
			migrated.add(
				bookmark.copy(
					mangaId = newDetails.id,
					chapterId = matchedChapter.id,
					percent = updatedPercent,
				),
			)
		}
		if (oldDetails.id != newDetails.id) {
			for (old in oldBookmarks) {
				bookmarksDao.delete(old)
			}
		}
		bookmarksDao.upsert(migrated)
		return migrated.size
	}

	private fun scoreBookmarkCandidate(
		sourceChaptersCount: Int,
		bookmarkedChapters: List<MangaChapter>,
		candidate: Manga,
		seedTitles: List<String>,
	): BookmarkMigrationCandidate {
		val newChapters = candidate.chapters.orEmpty()
		val mapping = LinkedHashMap<Long, MangaChapter>(bookmarkedChapters.size)
		for (oldChapter in bookmarkedChapters) {
			val match = findMatchingTargetChapter(
				oldChapter = oldChapter,
				newDetails = candidate,
				newChaptersAll = newChapters,
			)
			if (match != null) {
				mapping[oldChapter.id] = match
			}
		}
		val distance = mangaTitleMatchDistance(candidate.title, candidate.altTitles, seedTitles)
		val diff = if (sourceChaptersCount > 0) newChapters.size - sourceChaptersCount else 0
		return BookmarkMigrationCandidate(
			manga = candidate,
			totalChaptersCount = newChapters.size,
			sourceChaptersCount = sourceChaptersCount,
			matchedBookmarkedChapters = mapping.size,
			totalBookmarkedChapters = bookmarkedChapters.size,
			titleMatchDistance = if (distance.isFinite()) distance else Float.MAX_VALUE,
			chapterCountDiff = diff,
			chapterMapping = mapping,
		)
	}

	private fun findMatchingTargetChapter(
		oldChapter: MangaChapter?,
		newDetails: Manga,
		newChaptersAll: List<MangaChapter>,
	): MangaChapter? {
		if (oldChapter == null || newChaptersAll.isEmpty()) {
			return null
		}
		val preferredBranch = newDetails.getPreferredBranch(null)
		val preferredPool = newChaptersAll.filter { it.branch == preferredBranch }.ifEmpty { newChaptersAll }
		val sameBranchPool = oldChapter.branch?.let { br ->
			newChaptersAll.filter { it.branch == br }
		}.orEmpty().ifEmpty { preferredPool }
		if (oldChapter.number > 0f) {
			sameBranchPool.findByNumber(oldChapter.volume, oldChapter.number)?.let { return it }
			preferredPool.findByNumber(oldChapter.volume, oldChapter.number)?.let { return it }
			newChaptersAll.findByNumber(oldChapter.volume, oldChapter.number)?.let { return it }
			sameBranchPool.firstOrNull { it.number == oldChapter.number }?.let { return it }
			preferredPool.firstOrNull { it.number == oldChapter.number }?.let { return it }
			newChaptersAll.firstOrNull { it.number == oldChapter.number }?.let { return it }
		}
		val normalizedTitle = oldChapter.title.normalizeChapterTitle()
		if (normalizedTitle.isNotEmpty()) {
			sameBranchPool.firstOrNull { it.title.normalizeChapterTitle() == normalizedTitle }?.let { return it }
			preferredPool.firstOrNull { it.title.normalizeChapterTitle() == normalizedTitle }?.let { return it }
			newChaptersAll.firstOrNull { it.title.normalizeChapterTitle() == normalizedTitle }?.let { return it }
		}
		val parsedNumber = oldChapter.title?.let(::extractChapterNumber)
		if (parsedNumber != null && parsedNumber > 0f) {
			sameBranchPool.firstOrNull { it.number == parsedNumber || extractChapterNumber(it.title) == parsedNumber }
				?.let { return it }
			preferredPool.firstOrNull { it.number == parsedNumber || extractChapterNumber(it.title) == parsedNumber }
				?.let { return it }
			newChaptersAll.firstOrNull { it.number == parsedNumber || extractChapterNumber(it.title) == parsedNumber }
				?.let { return it }
		}
		return null
	}

	private fun String?.normalizeChapterTitle(): String {
		if (isNullOrBlank()) return ""
		return lowercase(Locale.ROOT)
			.replace(CHAPTER_TITLE_NORMALIZE_REGEX, " ")
			.trim()
	}

	private fun extractChapterNumber(title: String?): Float? {
		if (title.isNullOrBlank()) return null
		return CHAPTER_NUMBER_REGEX.find(title)?.groupValues?.getOrNull(1)?.toFloatOrNull()
	}

	private fun Manga.debugRef(): String = "title=\"$title\" id=$id source=${source.name}"

	private fun makeNewHistory(
		oldManga: Manga,
		newManga: Manga,
		history: HistoryEntity,
	): HistoryEntity {
		if (oldManga.chapters.isNullOrEmpty()) { // probably broken manga/source
			val branch = newManga.getPreferredBranch(null)
			val chapters = checkNotNull(newManga.getChapters(branch))
			val currentChapter =
				if (history.percent in 0f..1f) {
					chapters[(chapters.lastIndex * history.percent).toInt()]
				} else {
					chapters.first()
				}
			return HistoryEntity(
				mangaId = newManga.id,
				createdAt = history.createdAt,
				updatedAt = history.updatedAt,
				chapterId = currentChapter.id,
				page = history.page,
				scroll = history.scroll,
				percent = history.percent,
				deletedAt = 0,
				chaptersCount = chapters.count { it.branch == currentChapter.branch },
			)
		}
		val branch = oldManga.getPreferredBranch(history.toMangaHistory())
		val oldChapters = checkNotNull(oldManga.getChapters(branch))
		var index = oldChapters.indexOfFirst { it.id == history.chapterId }
		if (index < 0) {
			index =
				if (history.percent in 0f..1f) {
					(oldChapters.lastIndex * history.percent).toInt()
				} else {
					0
				}
		}
		val newChapters = checkNotNull(newManga.chapters).groupBy { it.branch }
		val newBranch =
			if (newChapters.containsKey(branch)) {
				branch
			} else {
				newManga.getPreferredBranch(null)
			}
		val newChapterId =
			checkNotNull(newChapters[newBranch])
				.let {
					val oldChapter = oldChapters[index]
					it.findByNumber(oldChapter.volume, oldChapter.number) ?: it.getOrNull(index) ?: it.last()
				}.id

		return HistoryEntity(
			mangaId = newManga.id,
			createdAt = history.createdAt,
			updatedAt = history.updatedAt,
			chapterId = newChapterId,
			page = history.page,
			scroll = history.scroll,
			percent = PROGRESS_NONE,
			deletedAt = 0,
			chaptersCount = checkNotNull(newChapters[newBranch]).size,
		)
	}

	private fun List<MangaChapter>.findByNumber(
		volume: Int,
		number: Float,
	): MangaChapter? =
		if (number <= 0f) {
			null
		} else {
			firstOrNull { it.volume == volume && it.number == number }
		}

	private companion object {
		const val TAG = "SourceReplacement"
		val CHAPTER_TITLE_NORMALIZE_REGEX = Regex("""[^\p{L}\p{N}]+""")
		val CHAPTER_NUMBER_REGEX = Regex("""(?:^|(?<![\p{L}\p{N}]))(?:ch(?:apter)?|ep(?:isode)?|bölüm|bolum|part|#)\s*\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
	}
}

data class BookmarkMigrationCandidate(
	val manga: Manga,
	val totalChaptersCount: Int,
	val sourceChaptersCount: Int,
	val matchedBookmarkedChapters: Int,
	val totalBookmarkedChapters: Int,
	val titleMatchDistance: Float,
	val chapterCountDiff: Int,
	val chapterMapping: Map<Long, MangaChapter>,
) {
	val isCompatible: Boolean
		get() = totalChaptersCount > 0 && (totalBookmarkedChapters == 0 || matchedBookmarkedChapters > 0)

	companion object {
		val COMPARATOR: Comparator<BookmarkMigrationCandidate> =
			compareByDescending<BookmarkMigrationCandidate> { it.isCompatible }
				.thenByDescending { it.matchedBookmarkedChapters }
				.thenBy {
					if (it.sourceChaptersCount > 0) abs(it.totalChaptersCount - it.sourceChaptersCount) else Int.MAX_VALUE
				}
				.thenBy { it.titleMatchDistance }
				.thenByDescending { it.totalChaptersCount }
	}
}

sealed interface BookmarkMigrationSearchEvent {
	data class Progress(
		val completedSources: Int,
		val totalSources: Int,
	) : BookmarkMigrationSearchEvent

	data class Candidate(
		val candidate: BookmarkMigrationCandidate,
	) : BookmarkMigrationSearchEvent
}

/**
 * Puts [newHistory] in place of the history of [oldMangaId], and with it the time and pages counted while reading.
 *
 * Those are kept under the id of the manga and hang on its history. Left where they were they stopped showing
 * the moment the old history was removed, and were deleted for good when it was cleaned up later: someone who
 * moved a manga to another source lost everything they had read of it from their statistics.
 *
 * To be called inside a transaction.
 */
internal suspend fun MangaDatabase.replaceHistory(oldMangaId: Long, newHistory: HistoryEntity) {
	val historyDao = getHistoryDao()
	historyDao.delete(oldMangaId)
	historyDao.upsert(newHistory)
	if (oldMangaId != newHistory.mangaId) {
		getStatsDao().moveAll(oldMangaId, newHistory.mangaId)
	}
}
