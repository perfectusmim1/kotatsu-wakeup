package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.suggestions.domain.SuggestionRanking
import org.koitharu.kotatsu.suggestions.domain.SuggestionRepository
import org.koitharu.kotatsu.suggestions.domain.TagStatistics
import javax.inject.Inject

/**
 * What to read next, given this manga - for every manga, not only the ones somebody has linked.
 *
 * A source's own related list comes first - whatever that site chose to link. When that comes up
 * short the row is filled from this manga's tags: first the reader's own suggestions that share them,
 * which costs nothing, then, with nothing at all to show, one search of this manga's source for its
 * least common tags.
 */
class RelatedMangaUseCase @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val suggestionRepository: SuggestionRepository,
	private val dataRepository: MangaDataRepository,
	private val tagStatistics: TagStatistics,
	private val settings: AppSettings,
) {

	suspend operator fun invoke(seed: Manga): List<Manga>? {
		val excludeNsfw = settings.isNsfwContentDisabled
		val picked = LinkedHashMap<Long, Manga>()
		fun add(list: List<Manga>) = list.forEach {
			if (it.id != seed.id && !(excludeNsfw && it.isNsfw())) {
				picked.putIfAbsent(it.id, it)
			}
		}
		add(sourceRelated(seed).orEmpty())
		if (picked.size >= MIN_RESULTS) {
			return picked.values.take(LIMIT)
		}
		// The details may not have loaded - a source behind a challenge, offline - and then the manga
		// carries no tags; the ones stored when it last loaded are just as good for this.
		val tagged = if (seed.tags.isEmpty()) {
			runCatchingCancellable { dataRepository.findMangaById(seed.id, withChapters = false) }
				.getOrNull()?.takeIf { it.tags.isNotEmpty() } ?: seed
		} else {
			seed
		}
		val rarity = runCatchingCancellable { tagStatistics.snapshot() }.getOrNull()
			?.let { snapshot -> { title: String -> snapshot.rarity(title) } }
			?: { _: String -> 1.0 }
		val stored = runCatchingCancellable { suggestionRepository.getTopList(MAX_STORED) }
			.onFailure { it.printStackTraceDebug() }
			.getOrDefault(emptyList())
		add(likeByTags(tagged, stored, rarity))
		if (picked.isEmpty()) {
			add(sourceByTags(tagged, rarity))
		}
		return picked.values.take(LIMIT).ifEmpty { null }
	}

	private suspend fun sourceRelated(seed: Manga): List<Manga>? = runCatchingCancellable {
		mangaRepositoryFactory.create(seed.source).getRelated(seed)
	}.onFailure { it.printStackTraceDebug() }.getOrNull()

	/**
	 * The reader's stored suggestions that share this manga's tags, closest first - already
	 * resolved and on the device, so no request at all. Closeness weighs each shared tag by how
	 * rare it is, so sharing `villainess` beats sharing `action` and a heavily tagged title does not
	 * win by sheer number.
	 */
	private fun likeByTags(seed: Manga, stored: List<Manga>, rarity: (String) -> Double): List<Manga> {
		val seedTags = seed.tagTitles()
		if (seedTags.isEmpty()) {
			return emptyList()
		}
		// One shared tag is a coincidence unless the manga has hardly any
		val minShared = if (seedTags.size <= 2) 1 else 2
		return stored.mapNotNull { manga ->
			val tags = manga.tagTitles()
			if (tags.count { it in seedTags } < minShared) {
				null
			} else {
				manga to SuggestionRanking.weightedOverlap(seedTags, tags, rarity)
			}
		}.sortedByDescending { it.second }
			.map { it.first }
			.take(LIMIT)
	}

	/**
	 * One search of this manga's own source for its rarest tags, so every manga with tags gets a row.
	 *
	 * Rarity is measured against everything the app has stored: a tag nearly everything carries,
	 * `action` say, would bring back the source's best-sellers rather than anything like this manga.
	 * For the same reason the search is never sorted by popularity.
	 */
	private suspend fun sourceByTags(seed: Manga, rarity: (String) -> Double): List<Manga> = runCatchingCancellable {
		if (seed.tags.isEmpty()) {
			return@runCatchingCancellable emptyList()
		}
		val repository = mangaRepositoryFactory.create(seed.source)
		val byRarity = seed.tags.sortedByDescending { rarity(it.title) }
		val order = PREFERRED_ORDERS.firstOrNull { it in repository.sortOrders }
		val seedTags = seed.tagTitles()
		val tagCount = if (repository.filterCapabilities.isMultipleTagsSupported) 2 else 1
		var found = repository.getList(0, order, MangaListFilter(tags = byRarity.take(tagCount).toSet()))
		if (found.none { it.id != seed.id } && tagCount > 1) {
			// Two rare tags together can match nothing but the manga itself
			found = repository.getList(0, order, MangaListFilter(tags = setOf(byRarity.first())))
		}
		found.filter { it.id != seed.id }
			.sortedByDescending { SuggestionRanking.weightedOverlap(seedTags, it.tagTitles(), rarity) }
			.take(LIMIT)
	}.onFailure { it.printStackTraceDebug() }.getOrDefault(emptyList())

	private fun Manga.tagTitles(): Set<String> = tags.mapTo(HashSet()) { SuggestionRanking.normalizeTag(it.title) }

	private companion object {
		const val LIMIT = 20

		/** Fewer than this and the row looks empty, so the next source of answers is asked too. */
		const val MIN_RESULTS = 4

		/** The reader's suggestions to match tags against; they are stored best first. */
		const val MAX_STORED = 200

		/** Anything but popularity, which would hand back the same best-sellers for every manga. */
		val PREFERRED_ORDERS = listOf(SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.RATING, SortOrder.POPULARITY)
	}
}
