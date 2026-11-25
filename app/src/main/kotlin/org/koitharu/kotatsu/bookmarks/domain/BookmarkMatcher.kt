package org.koitharu.kotatsu.bookmarks.domain

import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.bookmarks.data.toEntity
import org.koitharu.kotatsu.core.model.findSameChapter
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

data class ChapterSnapshot(
	val chapterNumber: Float,
	val chapterVolume: Int,
	val chapterTitle: String?,
	val chapterUrl: String?,
	val chapterBranch: String?,
	val chapterIndex: Int,
	val chaptersCount: Int,
)

object BookmarkMatcher {

	private val SCHEME_HOST_REGEX = Regex("""^[a-z][a-z0-9+.-]*://[^/]+""", RegexOption.IGNORE_CASE)
	private val SERIES_PREFIX_REGEX = Regex(
		"""^(?:manga|webtoon|webtoons|serie|series|comic|comics|manhwa|manhua|title|titles|read|book|hentai|doujin|doujinshi)/+""",
		RegexOption.IGNORE_CASE,
	)
	private val TRAILING_HEX_HASH_REGEX = Regex("""-[0-9a-f]{5,12}$""", RegexOption.IGNORE_CASE)
	private val TITLE_NORMALIZE_REGEX = Regex("""[^\p{L}\p{N}]+""")
	private val DIACRITICS_REGEX = Regex("""\p{Mn}+""")
	private val CHAPTER_NUMBER_REGEX = Regex(
		"""(?:^|(?<![\p{L}\p{N}]))(?:ch(?:apter)?|ep(?:isode)?|bölüm|bolum|part|#)\s*\.?\s*(\d+(?:\.\d+)?)""",
		RegexOption.IGNORE_CASE,
	)
	private val URL_CHAPTER_NUMBER_REGEX = Regex(
		"""(?:^|[/\-_])(?:ch(?:ap(?:ter)?)?|ep(?:isode)?|cap(?:itulo|ítulo)?|bolum|bölüm)[-_.\s]*(\d+(?:[._\-]\d+)?)(?:$|[/\-_?])""",
		RegexOption.IGNORE_CASE,
	)
	private val IMAGE_CHAPTER_NUMBER_REGEX = Regex(
		"""/(?:ch(?:ap(?:ter)?)?|ep(?:isode)?|cap(?:itulo|ítulo)?|bolum|bölüm)[-_.\s]*(\d+(?:[._\-]\d+)?)(?:/|$)""",
		RegexOption.IGNORE_CASE,
	)
	private val CHAPTER_SLUG_SUFFIX_REGEX = Regex("""(?:-(?:eng|en|tr|raw|li|v\d+))+$""", RegexOption.IGNORE_CASE)

	fun extractChapterSnapshot(
		chapters: List<MangaChapter>?,
		chapterId: Long,
	): ChapterSnapshot? {
		if (chapters.isNullOrEmpty()) {
			return null
		}
		val chapter = chapters.findById(chapterId) ?: return null
		val branchChapters = chapters.filter { it.branch == chapter.branch }
		val branchIndex = branchChapters.indexOfFirst { it.id == chapterId }
		return ChapterSnapshot(
			chapterNumber = chapter.number,
			chapterVolume = chapter.volume,
			chapterTitle = chapter.title?.nullIfEmpty(),
			chapterUrl = chapter.url.nullIfEmpty(),
			chapterBranch = chapter.branch,
			chapterIndex = branchIndex,
			chaptersCount = branchChapters.size,
		)
	}

	fun BookmarkEntity.withChapterSnapshot(
		chapter: MangaChapter,
		chapters: List<MangaChapter>,
	): BookmarkEntity {
		val branchChapters = chapters.filter { it.branch == chapter.branch }
		val branchIndex = branchChapters.indexOfFirst { it.id == chapter.id }
		return copy(
			chapterId = chapter.id,
			chapterNumber = chapter.number,
			chapterVolume = chapter.volume,
			chapterTitle = chapter.title?.nullIfEmpty() ?: chapterTitle,
			chapterUrl = chapter.url.nullIfEmpty() ?: chapterUrl,
			chapterBranch = chapter.branch,
			chapterIndex = if (branchIndex >= 0) branchIndex else chapterIndex,
			chaptersCount = if (branchChapters.isNotEmpty()) branchChapters.size else chaptersCount,
		)
	}

	fun BookmarkEntity.needsChapterMetadataBackfill(): Boolean {
		return (chapterUrl.isNullOrEmpty() && chapterTitle.isNullOrEmpty() && chapterNumber < 0f) ||
			chapterIndex < 0 ||
			chaptersCount <= 0
	}

	/**
	 * Reconstructs a [MangaChapter] representing a bookmarked chapter even when the remote source
	 * currently returns 0 chapters or no longer exposes that chapter ID.
	 */
	fun reconstructChapter(
		bookmark: Bookmark,
		cachedChapter: MangaChapter? = null,
	): MangaChapter? = reconstructChapter(
		chapterId = bookmark.chapterId,
		chapterTitle = bookmark.chapterTitle,
		chapterNumber = bookmark.chapterNumber,
		chapterVolume = bookmark.chapterVolume,
		chapterUrl = bookmark.chapterUrl,
		chapterBranch = bookmark.chapterBranch,
		imageUrl = bookmark.imageUrl,
		source = bookmark.manga.source,
		cachedChapter = cachedChapter,
	)

	fun reconstructChapter(
		bookmark: BookmarkEntity,
		source: MangaSource,
		cachedChapter: MangaChapter? = null,
	): MangaChapter? = reconstructChapter(
		chapterId = bookmark.chapterId,
		chapterTitle = bookmark.chapterTitle,
		chapterNumber = bookmark.chapterNumber,
		chapterVolume = bookmark.chapterVolume,
		chapterUrl = bookmark.chapterUrl,
		chapterBranch = bookmark.chapterBranch,
		imageUrl = bookmark.imageUrl,
		source = source,
		cachedChapter = cachedChapter,
	)

	private fun reconstructChapter(
		chapterId: Long,
		chapterTitle: String?,
		chapterNumber: Float,
		chapterVolume: Int,
		chapterUrl: String?,
		chapterBranch: String?,
		imageUrl: String?,
		source: MangaSource,
		cachedChapter: MangaChapter?,
	): MangaChapter? {
		if (cachedChapter != null) {
			return cachedChapter
		}
		val knownTitle = chapterTitle?.nullIfEmpty()
		val knownUrl = chapterUrl?.nullIfEmpty()
		val knownVolume = chapterVolume.coerceAtLeast(0)
		val effectiveNumber = chapterNumber.takeIf { it > 0f }
			?: knownTitle?.let(::extractChapterNumber)
			?: knownUrl?.let(::extractChapterNumberFromUrl)
			?: extractChapterNumberFromImageUrl(imageUrl)
		if (knownTitle == null && effectiveNumber == null && knownVolume <= 0 && knownUrl == null) {
			return null
		}
		return MangaChapter(
			id = chapterId,
			title = knownTitle,
			number = effectiveNumber ?: 0f,
			volume = knownVolume,
			url = knownUrl.orEmpty(),
			scanlator = null,
			uploadDate = 0L,
			branch = chapterBranch,
			source = source,
		)
	}

	/**
	 * Resolves the best chapter to display for [bookmark] in the Bookmarks UI:
	 * 1) Direct match in [liveChapters]
	 * 2) Healed match in [liveChapters] via [findMatchingChapter] (when chapter IDs drifted)
	 * 3) Cached DB chapter from [cachedChaptersById]
	 * 4) Reconstructed chapter from [bookmark]'s own saved metadata or legacy image URL
	 */
	fun resolveDisplayChapter(
		bookmark: Bookmark,
		liveChapters: List<MangaChapter>,
		cachedChaptersById: Map<Long, MangaChapter> = emptyMap(),
		preferredBranch: String? = null,
	): MangaChapter? {
		if (liveChapters.isNotEmpty()) {
			liveChapters.findById(bookmark.chapterId)?.let { return it }
			findMatchingChapter(
				bookmark = bookmark.toEntity(),
				cachedChapter = cachedChaptersById[bookmark.chapterId],
				newChapters = liveChapters,
				preferredBranch = preferredBranch,
			)?.let { return it }
		}
		return reconstructChapter(bookmark, cachedChaptersById[bookmark.chapterId])
	}

	fun isSameSeriesOnSameSource(stored: Manga, candidate: Manga): Boolean {
		if (stored.source.name != candidate.source.name) {
			return false
		}
		if (stored.id == candidate.id) {
			return true
		}
		val storedPaths = collectNormalizedPaths(stored.url, stored.publicUrl)
		val candidatePaths = collectNormalizedPaths(candidate.url, candidate.publicUrl)
		if (storedPaths.isNotEmpty() && candidatePaths.isNotEmpty() && storedPaths.any { it in candidatePaths }) {
			return true
		}

		val storedTitles = collectNormalizedTitles(stored.title, stored.altTitles)
		val candidateTitles = collectNormalizedTitles(candidate.title, candidate.altTitles)
		val storedPrimary = normalizeTitle(stored.title)
		val candidatePrimary = normalizeTitle(candidate.title)
		val titlesMatch = (storedPrimary.length >= 3 && storedPrimary == candidatePrimary) ||
			storedTitles.any { it.length >= 4 && it in candidateTitles }

		val storedSlugs = storedPaths.mapNotNull(::extractSeriesSlug).toSet()
		val candidateSlugs = candidatePaths.mapNotNull(::extractSeriesSlug).toSet()
		if (storedSlugs.isNotEmpty() && candidateSlugs.isNotEmpty()) {
			if (storedSlugs.any { it.length >= 4 && it in candidateSlugs }) {
				return true
			}
			val storedBaseSlugs = storedSlugs.map(::stripTrailingHash).filter { it.length >= 4 }.toSet()
			val candidateBaseSlugs = candidateSlugs.map(::stripTrailingHash).filter { it.length >= 4 }.toSet()
			if (titlesMatch && storedBaseSlugs.any { it in candidateBaseSlugs }) {
				return true
			}
		}

		return titlesMatch
	}

	fun findMatchingChapter(
		bookmark: BookmarkEntity,
		cachedChapter: MangaChapter?,
		newChapters: List<MangaChapter>,
		preferredBranch: String? = null,
	): MangaChapter? {
		if (newChapters.isEmpty()) {
			return null
		}
		newChapters.findById(bookmark.chapterId)?.let { return it }

		val knownUrl = cachedChapter?.url?.nullIfEmpty() ?: bookmark.chapterUrl?.nullIfEmpty()
		val knownTitle = cachedChapter?.title?.nullIfEmpty() ?: bookmark.chapterTitle?.nullIfEmpty()
		val knownNumber = cachedChapter?.number?.takeIf { it > 0f }
			?: bookmark.chapterNumber.takeIf { it > 0f }
			?: -1f
		val knownVolume = cachedChapter?.volume?.takeIf { it > 0 }
			?: bookmark.chapterVolume.takeIf { it > 0 }
			?: 0
		val knownBranch = cachedChapter?.branch ?: bookmark.chapterBranch
		val knownIndex = bookmark.chapterIndex
		val knownCount = bookmark.chaptersCount

		val sameBranchPool = newChapters.filter { it.branch == knownBranch }
		val singleBranchPool = if (newChapters.hasSingleBranch()) newChapters else emptyList()
		val preferredPool = if (preferredBranch != null) {
			newChapters.filter { it.branch == preferredBranch }.ifEmpty { newChapters }
		} else {
			newChapters
		}
		val primaryPool = sameBranchPool.ifEmpty { singleBranchPool }

		// Tier 1a: Exact normalized chapter URL match (handles host/www/query/trailing-slash drift)
		val normKnownUrl = knownUrl?.let(::normalizeChapterUrl)?.nullIfEmpty()
		if (normKnownUrl != null) {
			primaryPool.singleOrNull { normalizeChapterUrl(it.url) == normKnownUrl }?.let { return it }
			newChapters.singleOrNull { normalizeChapterUrl(it.url) == normKnownUrl }?.let { return it }
		}

		// Tier 1b: Chapter URL last path segment slug match (handles /webtoon/<slug>/ch-1 -> /serie/<slug>-<hash>/ch-1)
		val knownSlug = knownUrl?.let(::extractChapterUrlSlug)?.nullIfEmpty()
		if (knownSlug != null) {
			primaryPool.singleOrNull { extractChapterUrlSlug(it.url) == knownSlug }?.let { return it }
			preferredPool.singleOrNull { extractChapterUrlSlug(it.url) == knownSlug }?.let { return it }
			newChapters.singleOrNull { extractChapterUrlSlug(it.url) == knownSlug }?.let { return it }

			val strippedKnownSlug = stripChapterSlugSuffix(knownSlug)
			if (strippedKnownSlug.isNotEmpty() && strippedKnownSlug != knownSlug) {
				primaryPool.singleOrNull { stripChapterSlugSuffix(extractChapterUrlSlug(it.url)) == strippedKnownSlug }
					?.let { return it }
				preferredPool.singleOrNull { stripChapterSlugSuffix(extractChapterUrlSlug(it.url)) == strippedKnownSlug }
					?.let { return it }
			}
		}

		// Tier 1c: Upstream findSameChapter using cachedChapter or reconstructed synthetic chapter
		val referenceChapter = cachedChapter ?: if (knownUrl != null || knownTitle != null || knownNumber > 0f) {
			MangaChapter(
				id = bookmark.chapterId,
				title = knownTitle,
				number = if (knownNumber > 0f) knownNumber else 0f,
				volume = knownVolume,
				url = knownUrl.orEmpty(),
				scanlator = null,
				uploadDate = 0L,
				branch = knownBranch,
				source = newChapters.first().source,
			)
		} else {
			null
		}
		if (referenceChapter != null) {
			newChapters.findSameChapter(referenceChapter)?.let { return it }
		}

		// Tier 1d: Chapter number + volume (from explicit number, title, or URL)
		val normKnownTitle = normalizeTitle(knownTitle)
		val effectiveNumber = knownNumber.takeIf { it > 0f }
			?: knownTitle?.let(::extractChapterNumber)
			?: knownUrl?.let(::extractChapterNumberFromUrl)
		if (effectiveNumber != null && effectiveNumber > 0f) {
			matchByNumber(primaryPool, effectiveNumber, knownVolume, normKnownTitle)?.let { return it }
			if (preferredPool !== primaryPool) {
				matchByNumber(preferredPool, effectiveNumber, knownVolume, normKnownTitle)?.let { return it }
			}
			if (newChapters !== primaryPool && newChapters !== preferredPool) {
				matchByNumber(newChapters, effectiveNumber, knownVolume, normKnownTitle)?.let { return it }
			}
		}

		// Tier 1e: Normalized chapter title match
		if (normKnownTitle.isNotEmpty()) {
			matchByTitle(primaryPool, normKnownTitle, knownVolume)?.let { return it }
			if (preferredPool !== primaryPool) {
				matchByTitle(preferredPool, normKnownTitle, knownVolume)?.let { return it }
			}
			if (newChapters !== primaryPool && newChapters !== preferredPool) {
				matchByTitle(newChapters, normKnownTitle, knownVolume)?.let { return it }
			}
		}

		// Tier 1f: Exact saved chapter index when branch chapter count is unchanged
		if (knownIndex >= 0 && knownCount > 0) {
			val indexPool = primaryPool.ifEmpty { preferredPool }
			if (indexPool.size == knownCount && knownIndex in indexPool.indices) {
				return indexPool[knownIndex]
			}
		}

		// Tier 2a: Legacy recovery from bookmark imageUrl path segment (e.g. .../chapter-42/05.jpg)
		val imageChapterNumber = extractChapterNumberFromImageUrl(bookmark.imageUrl)
		if (imageChapterNumber != null && imageChapterNumber > 0f) {
			val recoveryPool = sameBranchPool.ifEmpty { preferredPool }
			val byImageNum = matchByNumber(recoveryPool, imageChapterNumber, knownVolume, "")
			if (byImageNum != null) {
				if (isConsistentWithPercent(byImageNum, recoveryPool, bookmark.percent)) {
					return byImageNum
				}
			}
		}

		// Tier 2b: Legacy recovery from saved reading percent (exact when chapter count is unchanged)
		if (bookmark.percent.isFinite() && bookmark.percent >= 0f && bookmark.percent <= 1.01f) {
			val clampedPercent = bookmark.percent.coerceIn(0f, 1f)
			val recoveryPool = sameBranchPool.ifEmpty { preferredPool }
			if (recoveryPool.isNotEmpty()) {
				val suspiciouslyTruncated = when {
					knownCount > 0 -> recoveryPool.size < (knownCount * 0.75).toInt().coerceAtLeast(1)
					else -> recoveryPool.size < 2 && clampedPercent > 0f && clampedPercent < 0.5f
				}
				if (!suspiciouslyTruncated) {
					val idx = estimateChapterIndexFromPercent(clampedPercent, recoveryPool.size)
					return recoveryPool[idx]
				}
			}
		}

		return null
	}

	internal fun estimateChapterIndexFromPercent(percent: Float, chaptersCount: Int): Int {
		if (chaptersCount <= 1 || percent <= 0f) {
			return 0
		}
		if (percent >= 1f) {
			return chaptersCount - 1
		}
		return (ceil(percent.toDouble() * chaptersCount - 1e-4).toInt() - 1)
			.coerceIn(0, chaptersCount - 1)
	}

	internal fun normalizeSeriesPath(raw: String?): String {
		if (raw.isNullOrBlank()) return ""
		var s = raw.trim()
		s = SCHEME_HOST_REGEX.replace(s, "")
		s = s.substringBefore('?').substringBefore('#')
		s = s.trim().trim('/').lowercase(Locale.ROOT)
		return s
	}

	internal fun extractSeriesSlug(normalizedPath: String): String? {
		if (normalizedPath.isEmpty()) return null
		val withoutPrefix = SERIES_PREFIX_REGEX.replace(normalizedPath, "").trim('/')
		if (withoutPrefix.isEmpty()) return null
		return withoutPrefix.substringBefore('/').takeIf { it.isNotEmpty() }
	}

	internal fun stripTrailingHash(slug: String): String {
		return TRAILING_HEX_HASH_REGEX.replace(slug, "")
	}

	internal fun normalizeChapterUrl(raw: String?): String {
		return normalizeSeriesPath(raw)
	}

	internal fun extractChapterUrlSlug(raw: String?): String {
		val path = normalizeSeriesPath(raw)
		if (path.isEmpty()) return ""
		return path.substringAfterLast('/')
	}

	internal fun normalizeTitle(raw: String?): String {
		if (raw.isNullOrBlank()) return ""
		val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKC)
		val withoutDiacritics = DIACRITICS_REGEX.replace(decomposed, "")
		return TITLE_NORMALIZE_REGEX.replace(withoutDiacritics.lowercase(Locale.ROOT), " ").trim()
	}

	internal fun extractChapterNumber(title: String?): Float? {
		if (title.isNullOrBlank()) return null
		return CHAPTER_NUMBER_REGEX.find(title)?.groupValues?.getOrNull(1)?.toFloatOrNull()
	}

	internal fun extractChapterNumberFromUrl(url: String?): Float? {
		val path = normalizeSeriesPath(url)
		if (path.isEmpty()) return null
		val raw = URL_CHAPTER_NUMBER_REGEX.find(path)?.groupValues?.getOrNull(1) ?: return null
		return parseChapterFloat(raw)
	}

	internal fun extractChapterNumberFromImageUrl(imageUrl: String?): Float? {
		if (imageUrl.isNullOrBlank()) return null
		val path = "/" + normalizeSeriesPath(imageUrl)
		val raw = IMAGE_CHAPTER_NUMBER_REGEX.find(path)?.groupValues?.getOrNull(1) ?: return null
		return parseChapterFloat(raw)
	}

	private fun parseChapterFloat(raw: String): Float? {
		val normalized = raw.replace('_', '.').replace('-', '.')
		if (normalized.count { it == '.' } > 1) return null
		return normalized.toFloatOrNull()
	}

	private fun stripChapterSlugSuffix(slug: String): String {
		return CHAPTER_SLUG_SUFFIX_REGEX.replace(slug, "")
	}

	private fun matchByNumber(
		pool: List<MangaChapter>,
		targetNumber: Float,
		targetVolume: Int,
		normTargetTitle: String,
	): MangaChapter? {
		if (pool.isEmpty()) return null
		val exactNumber = pool.filter { it.number == targetNumber }
		if (exactNumber.size == 1) return exactNumber.first()
		if (exactNumber.size > 1) {
			if (targetVolume > 0) {
				exactNumber.singleOrNull { it.volume == targetVolume }?.let { return it }
			}
			if (normTargetTitle.isNotEmpty()) {
				exactNumber.singleOrNull { normalizeTitle(it.title) == normTargetTitle }?.let { return it }
			}
			return null
		}
		val byParsedTitleOrUrl = pool.filter { ch ->
			extractChapterNumber(ch.title) == targetNumber ||
				extractChapterNumberFromUrl(ch.url) == targetNumber
		}
		if (byParsedTitleOrUrl.size == 1) return byParsedTitleOrUrl.first()
		if (byParsedTitleOrUrl.size > 1) {
			if (targetVolume > 0) {
				byParsedTitleOrUrl.singleOrNull { it.volume == targetVolume }?.let { return it }
			}
			if (normTargetTitle.isNotEmpty()) {
				byParsedTitleOrUrl.singleOrNull { normalizeTitle(it.title) == normTargetTitle }?.let { return it }
			}
		}
		return null
	}

	private fun matchByTitle(
		pool: List<MangaChapter>,
		normTitle: String,
		targetVolume: Int,
	): MangaChapter? {
		if (pool.isEmpty() || normTitle.isEmpty()) return null
		val byTitle = pool.filter { normalizeTitle(it.title) == normTitle }
		if (byTitle.size == 1) return byTitle.first()
		if (byTitle.size > 1 && targetVolume > 0) {
			byTitle.singleOrNull { it.volume == targetVolume }?.let { return it }
		}
		return null
	}

	private fun isConsistentWithPercent(
		candidate: MangaChapter,
		pool: List<MangaChapter>,
		percent: Float,
	): Boolean {
		if (!percent.isFinite() || percent < 0f || percent > 1.01f || pool.size < 4) {
			return true
		}
		val candidateIdx = pool.indexOfFirst { it.id == candidate.id }
		if (candidateIdx < 0) {
			return false
		}
		val expectedIdx = estimateChapterIndexFromPercent(percent.coerceIn(0f, 1f), pool.size)
		val tolerance = maxOf(4, pool.size / 5)
		return abs(candidateIdx - expectedIdx) <= tolerance
	}

	private fun collectNormalizedPaths(url: String, publicUrl: String): Set<String> {
		val result = LinkedHashSet<String>(2)
		normalizeSeriesPath(url).takeIf { it.isNotEmpty() }?.let(result::add)
		normalizeSeriesPath(publicUrl).takeIf { it.isNotEmpty() }?.let(result::add)
		return result
	}

	private fun collectNormalizedTitles(title: String, altTitles: Set<String>): Set<String> {
		val result = LinkedHashSet<String>(1 + altTitles.size)
		normalizeTitle(title).takeIf { it.isNotEmpty() }?.let(result::add)
		for (alt in altTitles) {
			normalizeTitle(alt).takeIf { it.isNotEmpty() }?.let(result::add)
		}
		return result
	}

	private fun List<MangaChapter>.hasSingleBranch(): Boolean {
		if (isEmpty()) return true
		val firstBranch = first().branch
		return all { it.branch == firstBranch }
	}
}
