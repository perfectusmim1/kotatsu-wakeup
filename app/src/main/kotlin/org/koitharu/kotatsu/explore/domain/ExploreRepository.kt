package org.koitharu.kotatsu.explore.domain

import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.asArrayList
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.almostEquals
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.suggestions.domain.TagsBlacklist
import javax.inject.Inject

class ExploreRepository @Inject constructor(
	private val settings: AppSettings,
	private val sourcesRepository: MangaSourcesRepository,
	private val historyRepository: HistoryRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	suspend fun findRandomManga(tagsLimit: Int): Manga {
		val tagsBlacklist = TagsBlacklist(settings.suggestionsTagsBlacklist, 0.4f)
		val tags = historyRepository.getPopularTags(tagsLimit).mapNotNull {
			if (it in tagsBlacklist) null else it.title
		}
		val sources = sourcesRepository.getEnabledSources().shuffled()
		check(sources.isNotEmpty()) { "No sources available" }
		var lastError: Throwable? = null
		for (source in sources.take(MAX_ATTEMPTS)) {
			val details = tryPickFromSource(source, tags, tagsBlacklist, settings.isSuggestionsExcludeNsfw) {
				lastError = it
			}
			if (details != null) {
				return details
			}
		}
		throw lastError ?: NoSuchElementException()
	}

	suspend fun findRandomManga(source: MangaSource, tagsLimit: Int): Manga {
		val tagsBlacklist = TagsBlacklist(settings.suggestionsTagsBlacklist, 0.4f)
		val skipNsfw = settings.isSuggestionsExcludeNsfw && !source.isNsfw()
		val tags = historyRepository.getPopularTags(tagsLimit).mapNotNull {
			if (it in tagsBlacklist) null else it.title
		}
		var lastError: Throwable? = null
		for (attempt in 0 until MAX_ATTEMPTS) {
			val preferTag = attempt < 2
			val details = tryPickFromSource(
				source = source,
				tags = if (preferTag) tags else emptyList(),
				blacklist = tagsBlacklist,
				skipNsfw = skipNsfw,
				attemptIndex = attempt,
			) {
				lastError = it
			}
			if (details != null) {
				return details
			}
		}
		throw lastError ?: NoSuchElementException()
	}

	private suspend fun tryPickFromSource(
		source: MangaSource,
		tags: List<String>,
		blacklist: TagsBlacklist,
		skipNsfw: Boolean,
		attemptIndex: Int = 0,
		onError: (Throwable) -> Unit,
	): Manga? {
		val list = getList(source, tags, blacklist, skipNsfw, attemptIndex, onError)
		if (list.isEmpty()) {
			return null
		}
		val repository = mangaRepositoryFactory.create(source)
		for (manga in list.take(MAX_CANDIDATES_PER_LIST)) {
			val details = runCatchingCancellable {
				repository.getDetails(manga)
			}.onFailure {
				it.printStackTraceDebug()
				onError(it)
			}.getOrNull() ?: continue
			if ((skipNsfw && details.isNsfw()) || details in blacklist) {
				continue
			}
			return details
		}
		return null
	}

	private suspend fun getList(
		source: MangaSource,
		tags: List<String>,
		blacklist: TagsBlacklist,
		skipNsfw: Boolean,
		attemptIndex: Int,
		onError: (Throwable) -> Unit,
	): List<Manga> {
		val repository = mangaRepositoryFactory.create(source)
		val candidateOrders = buildList<SortOrder?> {
			val available = repository.sortOrders.toList()
			val defaultOrder = runCatchingCancellable { repository.defaultSortOrder }.getOrNull()
			val randomized = available.shuffled()
			if (attemptIndex == 0 && randomized.isNotEmpty()) {
				addAll(randomized)
			} else if (defaultOrder != null) {
				add(defaultOrder)
				addAll(randomized.filterNot { it == defaultOrder })
			} else {
				addAll(randomized)
			}
			if (isEmpty()) {
				add(null)
			}
		}
		val selectedTag = if (tags.isNotEmpty()) {
			val availableTags = runCatchingCancellable {
				repository.getFilterOptions().availableTags
			}.getOrNull().orEmpty()
			tags.shuffled().firstNotNullOfOrNull { title ->
				availableTags.find { x -> x.title.almostEquals(title, 0.4f) }
			}
		} else {
			null
		}
		val filters = buildList {
			if (selectedTag != null) {
				add(MangaListFilter(tags = setOf(selectedTag)))
			}
			add(MangaListFilter.EMPTY)
		}
		for (filter in filters) {
			for (order in candidateOrders.take(2)) {
				val fetched = runCatchingCancellable {
					repository.getList(
						offset = 0,
						order = order,
						filter = filter,
					)
				}.onFailure {
					it.printStackTraceDebug()
					onError(it)
				}.getOrNull() ?: continue
				val filtered = fetched.asArrayList()
				if (skipNsfw) {
					filtered.removeAll { it.isNsfw() }
				}
				if (blacklist.isNotEmpty()) {
					filtered.removeAll { manga -> manga in blacklist }
				}
				if (filtered.isNotEmpty()) {
					filtered.shuffle()
					return filtered
				}
			}
		}
		return emptyList()
	}

	private companion object {
		const val MAX_ATTEMPTS = 5
		const val MAX_CANDIDATES_PER_LIST = 4
	}
}
