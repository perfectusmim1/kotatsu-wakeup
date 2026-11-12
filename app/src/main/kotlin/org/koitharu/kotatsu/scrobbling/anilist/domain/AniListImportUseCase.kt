package org.koitharu.kotatsu.scrobbling.anilist.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.getLocale
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.mangaTitleMatchDistance
import org.koitharu.kotatsu.parsers.util.normalizeSearchQuery
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.sanitizeMangaSearchTitle
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListMediaListEntry
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import org.koitharu.kotatsu.sourcescore.domain.SourceRanker
import java.util.Locale
import javax.inject.Inject

private const val MAX_SOURCE_CONCURRENCY = 4
private const val MAX_SOURCES_PER_ENTRY = 20
private const val MAX_QUERIES_PER_ENTRY = 3
private const val SOURCE_SEARCH_TIMEOUT_MS = 10_000L
private const val DETAILS_TIMEOUT_MS = 8_000L
private const val MATCH_THRESHOLD = 0.2f
private const val TITLE_DUPLICATE_THRESHOLD = 0.05f
private const val WAVE_DELAY_MS = 80L

sealed interface AniListImportTarget {

	data class ByStatus(
		val categoryTitles: Map<ScrobblingStatus, String>,
	) : AniListImportTarget

	data class SingleCategory(
		val categoryId: Long,
	) : AniListImportTarget

	data class NewCategory(
		val title: String,
	) : AniListImportTarget
}

data class AniListImportProgress(
	val isFetchingList: Boolean,
	val isCompleted: Boolean,
	val currentIndex: Int,
	val totalCount: Int,
	val currentTitle: String?,
	val importedCount: Int,
	val existingCount: Int,
	val unmatchedTitles: List<String>,
)

class NoEnabledSourcesException : IllegalStateException("No enabled remote sources")

class AniListImportUseCase @Inject constructor(
	private val aniListRepository: AniListRepository,
	private val favouritesRepository: FavouritesRepository,
	private val sourcesRepository: MangaSourcesRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mangaDataRepository: MangaDataRepository,
	private val searchHelperFactory: SearchV2Helper.Factory,
	private val sourceRanker: SourceRanker,
	private val settings: AppSettings,
	private val db: MangaDatabase,
) {

	private val searchSemaphore = Semaphore(MAX_SOURCE_CONCURRENCY)

	operator fun invoke(target: AniListImportTarget): Flow<AniListImportProgress> = flow {
		emit(
			AniListImportProgress(
				isFetchingList = true,
				isCompleted = false,
				currentIndex = 0,
				totalCount = 0,
				currentTitle = null,
				importedCount = 0,
				existingCount = 0,
				unmatchedTitles = emptyList(),
			),
		)

		val sources = getOrderedSources()
		if (sources.isEmpty()) {
			throw NoEnabledSourcesException()
		}

		val entries = aniListRepository.fetchUserMediaList()
		if (entries.isEmpty()) {
			emit(
				AniListImportProgress(
					isFetchingList = false,
					isCompleted = true,
					currentIndex = 0,
					totalCount = 0,
					currentTitle = null,
					importedCount = 0,
					existingCount = 0,
					unmatchedTitles = emptyList(),
				),
			)
			return@flow
		}

		val categoriesByLowerTitle = HashMap<String, Long>()
		for (cat in favouritesRepository.observeCategories().first()) {
			categoriesByLowerTitle[cat.title.trim().lowercase(Locale.ROOT)] = cat.id
		}
		val categoryMangaIds = HashMap<Long, MutableSet<Long>>()
		val categoryMangas = HashMap<Long, MutableList<Manga>>()
		val linkedScrobblesByTargetId = db.getScrobblingDao()
			.observe(ScrobblerService.ANILIST.id)
			.first()
			.groupBy { it.targetId }

		suspend fun getCategoryIds(categoryId: Long): MutableSet<Long> {
			return categoryMangaIds[categoryId] ?: run {
				val existing = favouritesRepository.getManga(categoryId)
				categoryMangas[categoryId] = existing.toMutableList()
				existing.mapTo(HashSet()) { it.id }.also { categoryMangaIds[categoryId] = it }
			}
		}

		suspend fun resolveOrCreateCategoryId(desiredTitle: String): Long {
			val trimmed = desiredTitle.trim()
			val key = trimmed.lowercase(Locale.ROOT)
			categoriesByLowerTitle[key]?.let { return it }
			val created = favouritesRepository.createCategory(
				title = trimmed,
				sortOrder = ListSortOrder.NEWEST,
				isTrackerEnabled = true,
				isVisibleOnShelf = true,
			)
			categoriesByLowerTitle[key] = created.id
			categoryMangaIds[created.id] = HashSet()
			categoryMangas[created.id] = ArrayList()
			return created.id
		}

		suspend fun resolveTargetCategoryId(entry: AniListMediaListEntry): Long = when (target) {
			is AniListImportTarget.SingleCategory -> target.categoryId
			is AniListImportTarget.NewCategory -> resolveOrCreateCategoryId(target.title)
			is AniListImportTarget.ByStatus -> {
				val title = target.categoryTitles[entry.scrobblingStatus]
					?: entry.status.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
				resolveOrCreateCategoryId(title)
			}
		}

		suspend fun findExistingCategoryIdForSingleTarget(): Long? = when (target) {
			is AniListImportTarget.SingleCategory -> target.categoryId
			is AniListImportTarget.NewCategory -> categoriesByLowerTitle[target.title.trim().lowercase(Locale.ROOT)]
			is AniListImportTarget.ByStatus -> null
		}

		var importedCount = 0
		var existingCount = 0
		val unmatchedTitles = ArrayList<String>()

		for ((index, entry) in entries.withIndex()) {
			emit(
				AniListImportProgress(
					isFetchingList = false,
					isCompleted = false,
					currentIndex = index + 1,
					totalCount = entries.size,
					currentTitle = entry.titleUserPreferred,
					importedCount = importedCount,
					existingCount = existingCount,
					unmatchedTitles = unmatchedTitles.toList(),
				),
			)

			val preResolvedCategoryId = findExistingCategoryIdForSingleTarget()
				?: (target as? AniListImportTarget.ByStatus)?.let { byStatus ->
					val title = byStatus.categoryTitles[entry.scrobblingStatus]
						?: entry.status.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
					categoriesByLowerTitle[title.trim().lowercase(Locale.ROOT)]
				}

			val existingIds = if (preResolvedCategoryId != null) {
				getCategoryIds(preResolvedCategoryId)
			} else {
				null
			}
			val existingList = if (preResolvedCategoryId != null) {
				categoryMangas[preResolvedCategoryId].orEmpty()
			} else {
				emptyList()
			}
			val seedTitles = entry.searchTitles()
			val alreadyLinkedInCategory = existingIds != null && (
				linkedScrobblesByTargetId[entry.mediaId].orEmpty().any { it.mangaId in existingIds } ||
					existingList.any {
						mangaTitleMatchDistance(it.title, it.altTitles, seedTitles, TITLE_DUPLICATE_THRESHOLD) < TITLE_DUPLICATE_THRESHOLD
					}
				)

			if (alreadyLinkedInCategory) {
				existingCount++
				continue
			}

			val matched = findBestMatchAcrossSources(entry, seedTitles, sources)
			if (matched == null) {
				unmatchedTitles.add(entry.titleUserPreferred)
				continue
			}

			val detailedManga = withTimeoutOrNull(DETAILS_TIMEOUT_MS) {
				runCatchingCancellable {
					mangaRepositoryFactory.create(matched.source).getDetails(matched)
				}.getOrNull()
			} ?: matched

			val targetCategoryId = resolveTargetCategoryId(entry)
			val targetIds = getCategoryIds(targetCategoryId)
			if (targetIds.add(detailedManga.id)) {
				mangaDataRepository.storeManga(
					detailedManga,
					replaceExisting = !detailedManga.chapters.isNullOrEmpty(),
				)
				favouritesRepository.addToCategory(targetCategoryId, listOf(detailedManga))
				categoryMangas[targetCategoryId]?.add(detailedManga)
				importedCount++
			} else {
				existingCount++
			}

			upsertAniListScrobble(detailedManga.id, entry)
			saveInitialHistoryIfMissing(detailedManga, entry)
		}

		emit(
			AniListImportProgress(
				isFetchingList = false,
				isCompleted = true,
				currentIndex = entries.size,
				totalCount = entries.size,
				currentTitle = null,
				importedCount = importedCount,
				existingCount = existingCount,
				unmatchedTitles = unmatchedTitles.toList(),
			),
		)
	}

	private suspend fun getOrderedSources(): List<MangaSource> {
		val skipNsfw = settings.isNsfwContentDisabled
		val candidates = sourcesRepository.getEnabledSources()
			.asSequence()
			.filter { source ->
				val raw = source.unwrap()
				raw is MangaParserSource && !raw.isBroken && (!skipNsfw || !source.isNsfw())
			}
			.distinctBy(MangaSource::name)
			.toList()
		if (candidates.isEmpty()) return emptyList()

		val appLang = Locale.getDefault().language
		val snapshot = runCatchingCancellable { sourceRanker.snapshot() }.getOrNull()
			?: return candidates.sortedByDescending { source ->
				val lang = source.getLocale()?.language
				when {
					lang == appLang -> 2
					lang == "en" -> 1
					else -> 0
				}
			}
		return snapshot.order(
			sources = candidates,
			affinityOf = { source ->
				val lang = source.getLocale()?.language
				when {
					lang == appLang -> 1.0
					lang == "en" -> 0.65
					else -> 0.25
				}
			},
		).map { it.source }
	}

	private suspend fun findBestMatchAcrossSources(
		entry: AniListMediaListEntry,
		seedTitles: List<String>,
		sources: List<MangaSource>,
	): Manga? {
		val queries = buildSearchQueries(entry)
		if (queries.isEmpty()) return null
		val searchPool = sources.take(MAX_SOURCES_PER_ENTRY)
		var bestManga: Manga? = null
		var bestScore = Float.POSITIVE_INFINITY

		for (query in queries) {
			for (wave in searchPool.chunked(MAX_SOURCE_CONCURRENCY)) {
				val waveResults = coroutineScope {
					wave.map { source ->
						async {
							searchSemaphore.withPermit {
								withTimeoutOrNull(SOURCE_SEARCH_TIMEOUT_MS) {
									runCatchingCancellable {
										searchHelperFactory.create(source)(query, SearchKind.SIMPLE)?.manga
									}.getOrNull()
								}.orEmpty()
							}
						}
					}.awaitAll()
				}

				for (candidates in waveResults) {
					for (candidate in candidates) {
						val score = mangaTitleMatchDistance(
							candidateTitle = candidate.title,
							candidateAltTitles = candidate.altTitles,
							seedTitles = seedTitles,
							threshold = MATCH_THRESHOLD,
						)
						if (score < bestScore) {
							bestScore = score
							bestManga = candidate
							if (score == 0f) {
								return bestManga
							}
						}
					}
				}
				delay(WAVE_DELAY_MS)
			}
		}
		return bestManga
	}

	private fun buildSearchQueries(entry: AniListMediaListEntry): List<String> {
		val seen = HashSet<String>()
		val queries = ArrayList<String>(MAX_QUERIES_PER_ENTRY)
		for (raw in entry.searchTitles()) {
			val sanitized = raw.sanitizeMangaSearchTitle().ifEmpty { raw.normalizeSearchQuery() }
			if (sanitized.isBlank()) continue
			if (seen.add(sanitized.lowercase(Locale.ROOT))) {
				queries.add(sanitized)
				if (queries.size >= MAX_QUERIES_PER_ENTRY) break
			}
		}
		return queries
	}

	private suspend fun upsertAniListScrobble(mangaId: Long, entry: AniListMediaListEntry) {
		if (entry.listEntryId <= 0) return
		val entity = ScrobblingEntity(
			scrobbler = ScrobblerService.ANILIST.id,
			id = entry.listEntryId,
			mangaId = mangaId,
			targetId = entry.mediaId,
			status = entry.status,
			chapter = entry.progress,
			comment = null,
			rating = (entry.scorePoint100 / 100f).coerceIn(0f, 1f),
		)
		db.getScrobblingDao().upsert(entity)
	}

	private suspend fun saveInitialHistoryIfMissing(manga: Manga, entry: AniListMediaListEntry) {
		val progress = entry.progress
		val allChapters = manga.chapters
		if (progress <= 0 || allChapters.isNullOrEmpty()) return
		if (db.getHistoryDao().find(manga.id) != null) return

		val primaryBranch = if (allChapters.any { it.branch == null }) null else allChapters.first().branch
		val branchChapters = allChapters.filter { it.branch == primaryBranch }
		if (branchChapters.isEmpty()) return

		val targetIndex = branchChapters.indexOfLast { chapter ->
			chapter.number > 0f && chapter.number <= progress.toFloat()
		}.takeIf { it >= 0 }
			?: (progress - 1).coerceIn(0, branchChapters.lastIndex)

		val targetChapter = branchChapters[targetIndex]
		val percent = ((targetIndex + 1).toFloat() / branchChapters.size.toFloat()).coerceIn(0f, 1f)
		val now = System.currentTimeMillis()
		db.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = manga.id,
				createdAt = now,
				updatedAt = now,
				chapterId = targetChapter.id,
				page = 0,
				scroll = 0f,
				percent = percent,
				deletedAt = 0L,
				chaptersCount = branchChapters.size,
			),
		)
	}
}
