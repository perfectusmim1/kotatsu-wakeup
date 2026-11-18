package org.koitharu.kotatsu.alternatives.domain

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.core.model.getLocale
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.explore.data.SourcePreset
import org.koitharu.kotatsu.explore.data.SourcePresetsRepository
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.ParserMangaSource
import org.koitharu.kotatsu.parsers.util.buildMangaSearchQueries
import org.koitharu.kotatsu.parsers.util.mangaTitleMatchDistance
import org.koitharu.kotatsu.parsers.util.normalizeSearchQuery
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.sanitizeMangaSearchTitle
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

private const val MAX_HTTP_PARALLELISM = 16
private const val MAX_DETAILS_PARALLELISM = 10
private const val MAX_PER_SOURCE_PARALLELISM = 2
private const val HTTP_QUERY_TIMEOUT_MS = 10_000L
private const val WEBVIEW_QUERY_TIMEOUT_MS = 20_000L
private const val HTTP_DETAILS_TIMEOUT_MS = 12_000L
private const val WEBVIEW_DETAILS_TIMEOUT_MS = 20_000L
private const val SOURCE_REPLACEMENT_TAG = "SourceReplacement"

class AlternativesUseCase @Inject constructor(
	private val sourcesRepository: MangaSourcesRepository,
	private val searchHelperFactory: SearchV2Helper.Factory,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val settings: AppSettings,
	private val presetsRepository: SourcePresetsRepository,
) {
	private val searchSemaphore = Semaphore(MAX_HTTP_PARALLELISM)
	private val detailsSemaphore = Semaphore(MAX_DETAILS_PARALLELISM)
	private val webViewSemaphore = Semaphore(1)
	private val sourceSemaphores = ConcurrentHashMap<String, Semaphore>()
	private val webViewSources = ConcurrentHashMap<String, Boolean>()

	suspend operator fun invoke(
		manga: Manga,
		sourceScope: AlternativeSourceScope,
		query: String = manga.title,
		loadDetails: Boolean = true,
	): Flow<Manga> {
		val sources = getCandidateSources(
			ref = manga.source,
			sourceScope = sourceScope,
			sameLanguageOnly = true,
		)
		return search(manga, sources, query, loadDetails)
	}

	/**
	 * @param loadDetails fetch each candidate's details page. Needed for chapter counts, but a caller
	 * that only wants what the search already returned - a cover, say - should pass `false`: it is one
	 * extra network round trip per result.
	 * @param emitSearchMatchesBeforeDetails emit each matched search result before loading its details,
	 * then re-emit once details load asynchronously if they differ from the initial search result.
	 */
	suspend operator fun invoke(
		manga: Manga,
		options: AlternativesSearchOptions,
		loadDetails: Boolean = true,
		emitSearchMatchesBeforeDetails: Boolean = false,
	): Flow<AlternativeSearchEvent> {
		val query = options.query.trim()
		if (query.isEmpty()) return emptyFlow()
		val sources = getCandidateSources(
			ref = manga.source,
			sourceScope = options.sourceScope,
			sameLanguageOnly = options.sameLanguageOnly,
			sameContentTypeOnly = options.sameContentTypeOnly,
		)
		if (sources.isEmpty()) return emptyFlow()
		val searchQueries = buildAlternativeSearchQueries(manga, query)
		if (searchQueries.isEmpty()) return emptyFlow()
		val seedTitles = buildSeedTitles(manga, query)
		return channelFlow {
			val detailsScope = this
			val completedSources = AtomicInteger()
			send(AlternativeSearchEvent.Progress(0, sources.size))
			runSourceWorkers(sources) { source ->
				try {
					searchSingleSource(
						manga = manga,
						source = source,
						searchQueries = searchQueries,
						seedTitles = seedTitles,
						emitImmediately = emitSearchMatchesBeforeDetails,
						onSourceFailure = {},
						onCandidate = { candidate ->
							if (!loadDetails) {
								send(AlternativeSearchEvent.Result(candidate))
							} else if (emitSearchMatchesBeforeDetails) {
								send(AlternativeSearchEvent.Result(candidate))
								detailsScope.launch {
									val detailed = loadDetails(candidate)
									if (detailed !== candidate && detailed != candidate) {
										send(AlternativeSearchEvent.Result(detailed))
									}
								}
							} else {
								detailsScope.launch {
									send(AlternativeSearchEvent.Result(loadDetails(candidate)))
								}
							}
						},
					)
				} finally {
					send(
						AlternativeSearchEvent.Progress(
							completedSources = completedSources.incrementAndGet(),
							totalSources = sources.size,
						),
					)
				}
			}
		}.flowOn(Dispatchers.IO)
	}

	suspend fun getCandidateSources(
		ref: MangaSource,
		sourceScope: AlternativeSourceScope,
		sameLanguageOnly: Boolean = true,
		sameContentTypeOnly: Boolean = false,
	): List<MangaSource> {
		val sources = when (sourceScope) {
			is AlternativeSourceScope.Preset -> presetsRepository.getById(sourceScope.id)
				?.let(::getPresetSources)
				.orEmpty()

			AlternativeSourceScope.Enabled -> sourcesRepository.getEnabledSources()
			AlternativeSourceScope.Disabled -> sourcesRepository.getDisabledSources().toList()
		}
		val skipNsfw = settings.isNsfwContentDisabled
		val refLanguage = if (sameLanguageOnly) ref.languageCode() else null
		val candidates = sources.asSequence()
			.distinctBy(MangaSource::name)
			.filter { it.name != ref.name }
			.filterNot { skipNsfw && it.isNsfw() }
			.filter { refLanguage == null || it.languageCode() == refLanguage }
			.filter { !sameContentTypeOnly || it.hasSameContentTypeAs(ref) }
			.toList()

		val defaultLocale = Locale.getDefault()
		val priorities = HashMap<String, Int>(candidates.size)
		for (candidate in candidates) {
			priorities[candidate.name] = candidate.priority(ref, defaultLocale)
		}
		return candidates.sortedWith(
			compareByDescending<MangaSource> { priorities[it.name] ?: 0 }.thenBy(MangaSource::name),
		)
	}

	suspend fun getSourceScopeOptions(
		ref: MangaSource,
		sameLanguageOnly: Boolean = false,
	): AlternativeSourceScopeOptions = coroutineScope {
		val skipNsfw = settings.isNsfwContentDisabled
		val refLanguage = if (sameLanguageOnly) ref.languageCode() else null
		fun MangaSource.isCandidate(): Boolean = name != ref.name &&
			(!skipNsfw || !isNsfw()) &&
			(refLanguage == null || languageCode() == refLanguage)

		// A preset with nothing searchable for this manga would only ever produce an empty screen.
		val presetsDeferred = async { presetsRepository.getAll() }
		val disabledDeferred = async { sourcesRepository.getDisabledSources() }
		val rawPresets = presetsDeferred.await()
		val presets = if (rawPresets.isEmpty()) {
			emptyList()
		} else {
			val sourcesByName = sourcesRepository.allMangaSources.associateBy(MangaSource::name)
			rawPresets
				.filter { preset -> preset.sources.any { name -> sourcesByName[name]?.isCandidate() == true } }
				.map { AlternativeSourceScopeOptions.PresetChoice(it.id, it.title) }
		}
		val activePresetId = settings.activeSourcePresetId
		AlternativeSourceScopeOptions(
			// Unchanged default: the active preset when it has candidates, otherwise enabled sources.
			defaultScope = if (presets.any { it.id == activePresetId }) {
				AlternativeSourceScope.Preset(activePresetId)
			} else {
				AlternativeSourceScope.Enabled
			},
			presets = presets,
			hasDisabledSources = disabledDeferred.await().any { it.isCandidate() },
		)
	}

	suspend fun searchSource(
		manga: Manga,
		source: MangaSource,
		query: String = manga.title,
		loadDetails: Boolean = true,
		onFailure: (() -> Unit)? = null,
	): Flow<Manga> = if (source.name == manga.source.name) {
		emptyFlow()
	} else {
		search(
			manga = manga,
			sources = listOf(source),
			query = query,
			loadDetails = loadDetails,
			onSourceFailure = { onFailure?.invoke() },
		)
	}

	private fun search(
		manga: Manga,
		sources: List<MangaSource>,
		query: String,
		loadDetails: Boolean,
		onSourceFailure: (MangaSource) -> Unit = {},
	): Flow<Manga> {
		if (sources.isEmpty()) {
			return emptyFlow()
		}
		val searchQueries = buildAlternativeSearchQueries(manga, query)
		if (searchQueries.isEmpty()) {
			return emptyFlow()
		}
		val seedTitles = buildSeedTitles(manga, query)
		return channelFlow {
			val detailsScope = this
			runSourceWorkers(sources) { source ->
				searchSingleSource(
					manga = manga,
					source = source,
					searchQueries = searchQueries,
					seedTitles = seedTitles,
					emitImmediately = false,
					onSourceFailure = onSourceFailure,
					onCandidate = { candidate ->
						if (loadDetails) {
							detailsScope.launch {
								send(loadDetails(candidate))
							}
						} else {
							send(candidate)
						}
					},
				)
			}
		}.flowOn(Dispatchers.IO)
	}

	private fun CoroutineScope.runSourceWorkers(
		sources: List<MangaSource>,
		onSource: suspend (MangaSource) -> Unit,
	) {
		val (webViewSourcesList, httpSourcesList) = sources.partition { it.usesWebView() }
		if (httpSourcesList.isNotEmpty()) {
			val httpIndex = AtomicInteger(0)
			val workerCount = minOf(MAX_HTTP_PARALLELISM, httpSourcesList.size)
			repeat(workerCount) {
				launch {
					while (true) {
						val idx = httpIndex.getAndIncrement()
						if (idx >= httpSourcesList.size) break
						onSource(httpSourcesList[idx])
					}
				}
			}
		}
		if (webViewSourcesList.isNotEmpty()) {
			val webViewIndex = AtomicInteger(0)
			launch {
				while (true) {
					val idx = webViewIndex.getAndIncrement()
					if (idx >= webViewSourcesList.size) break
					onSource(webViewSourcesList[idx])
				}
			}
		}
	}

	private suspend fun searchSingleSource(
		manga: Manga,
		source: MangaSource,
		searchQueries: List<String>,
		seedTitles: List<String>,
		emitImmediately: Boolean,
		onSourceFailure: (MangaSource) -> Unit,
		onCandidate: suspend (Manga) -> Unit,
	) {
		val isWebView = source.usesWebView()
		val queryTimeoutMs = if (isWebView) WEBVIEW_QUERY_TIMEOUT_MS else HTTP_QUERY_TIMEOUT_MS
		val searchHelper = searchHelperFactory.create(source)
		val matchedById = if (emitImmediately) null else LinkedHashMap<Long, Pair<Manga, Float>>()
		val seenIds = if (emitImmediately) HashSet<Long>() else null
		val seenKeys = HashSet<String>()
		var hadSuccessfulSearch = false
		var lastError: Throwable? = null

		for (candidateQuery in searchQueries) {
			var timedOut = false
			val searchResult = runCatchingCancellable {
				withSearchPermit(source, isWebView) {
					withTimeoutOrNull(queryTimeoutMs) {
						searchHelper(candidateQuery, SearchKind.SIMPLE)?.manga.orEmpty()
					}.also { result ->
						if (result == null) {
							timedOut = true
						}
					}
				}
			}
			if (timedOut) {
				lastError = SocketTimeoutException("Search timed out")
				Log.w(
					SOURCE_REPLACEMENT_TAG,
					"Search timed out: source=${source.name} query=\"${candidateQuery.take(120)}\" timeoutMs=$queryTimeoutMs",
				)
				break
			}
			val error = searchResult.exceptionOrNull()
			if (error != null) {
				lastError = error
				Log.w(
					SOURCE_REPLACEMENT_TAG,
					"Search failed: source=${source.name} query=\"${candidateQuery.take(120)}\"",
					error,
				)
				break
			}
			hadSuccessfulSearch = true
			val list = searchResult.getOrNull().orEmpty()
			var foundExactInBatch = false
			val batchById = if (emitImmediately) LinkedHashMap<Long, Pair<Manga, Float>>() else null
			for (m in list) {
				if (isSameManga(m, manga)) {
					continue
				}
				val dedupeKey = "${m.source.name}:${m.url}"
				if (!seenKeys.add(dedupeKey)) {
					continue
				}
				val score = mangaTitleMatchDistance(m.title, m.altTitles, seedTitles)
				if (!score.isFinite()) {
					continue
				}
				if (emitImmediately) {
					if (seenIds?.contains(m.id) != true) {
						val existing = batchById?.get(m.id)
						if (existing == null || score < existing.second) {
							batchById?.put(m.id, m to score)
						}
					}
				} else {
					val existing = matchedById?.get(m.id)
					if (existing == null || score < existing.second) {
						matchedById?.put(m.id, m to score)
					}
				}
				if (score == 0f) {
					foundExactInBatch = true
				}
			}
			if (batchById != null && batchById.isNotEmpty()) {
				seenIds?.addAll(batchById.keys)
				val sortedBatch = batchById.values.sortedBy { it.second }
				for ((matched, _) in sortedBatch) {
					onCandidate(matched)
				}
			}
			if (foundExactInBatch) {
				break
			}
		}

		if (!hadSuccessfulSearch && lastError != null) {
			onSourceFailure(source)
		}

		if (matchedById != null && matchedById.isNotEmpty()) {
			val sortedMatches = matchedById.values
				.sortedBy { it.second }
				.map { it.first }
			for (m in sortedMatches) {
				onCandidate(m)
			}
		}
	}

	private fun buildAlternativeSearchQueries(manga: Manga, rawQuery: String): List<String> {
		val normalizedRaw = rawQuery.normalizeSearchQuery()
		val normalizedTitle = manga.title.normalizeSearchQuery()
		val isSeedTitleQuery = rawQuery.equals(manga.title, ignoreCase = true) ||
			(normalizedRaw.isNotEmpty() && normalizedRaw.equals(normalizedTitle, ignoreCase = true))
		return if (isSeedTitleQuery) {
			buildMangaSearchQueries(manga.title, manga.altTitles)
		} else {
			val sanitized = rawQuery.sanitizeMangaSearchTitle().ifEmpty { normalizedRaw }
			if (sanitized.isBlank()) emptyList() else listOf(sanitized)
		}
	}

	private fun buildSeedTitles(manga: Manga, rawQuery: String): List<String> = buildList(2 + manga.altTitles.size) {
		add(rawQuery)
		add(manga.title)
		addAll(manga.altTitles)
	}

	private fun isSameManga(candidate: Manga, seed: Manga): Boolean {
		return candidate.id == seed.id ||
			(candidate.source.name == seed.source.name && candidate.url == seed.url)
	}

	private suspend fun loadDetails(manga: Manga): Manga {
		val isWebView = manga.source.usesWebView()
		val timeoutMs = if (isWebView) WEBVIEW_DETAILS_TIMEOUT_MS else HTTP_DETAILS_TIMEOUT_MS
		var timedOut = false
		val detailsResult = runCatchingCancellable {
			withDetailsPermit(manga.source, isWebView) {
				withTimeoutOrNull(timeoutMs) {
					mangaRepositoryFactory.create(manga.source).getDetails(manga)
				}.also { result ->
					if (result == null) {
						timedOut = true
					}
				}
			}
		}
		if (timedOut) {
			Log.w(
				SOURCE_REPLACEMENT_TAG,
				"Details load timed out; using search result: source=${manga.source.name} title=\"${manga.title}\" id=${manga.id} timeoutMs=$timeoutMs",
			)
		} else {
			detailsResult.exceptionOrNull()?.let { error ->
				Log.w(
					SOURCE_REPLACEMENT_TAG,
					"Details load failed; using search result: source=${manga.source.name} title=\"${manga.title}\" id=${manga.id}",
					error,
				)
			}
		}
		return detailsResult.getOrNull() ?: manga
	}

	private suspend fun <T> withSearchPermit(
		source: MangaSource,
		isWebView: Boolean = source.usesWebView(),
		block: suspend () -> T,
	): T {
		return source.requestSemaphore(isWebView).withPermit {
			if (isWebView) {
				webViewSemaphore.withPermit { block() }
			} else {
				searchSemaphore.withPermit { block() }
			}
		}
	}

	private suspend fun <T> withDetailsPermit(
		source: MangaSource,
		isWebView: Boolean = source.usesWebView(),
		block: suspend () -> T,
	): T {
		return source.requestSemaphore(isWebView).withPermit {
			if (isWebView) {
				webViewSemaphore.withPermit { block() }
			} else {
				detailsSemaphore.withPermit { block() }
			}
		}
	}

	fun isWebViewSource(source: MangaSource): Boolean = source.usesWebView()

	private fun MangaSource.requestSemaphore(isWebView: Boolean = usesWebView()): Semaphore {
		sourceSemaphores[name]?.let { return it }
		val created = Semaphore(if (isWebView) 1 else MAX_PER_SOURCE_PARALLELISM)
		return sourceSemaphores.putIfAbsent(name, created) ?: created
	}

	private fun MangaSource.usesWebView(): Boolean {
		webViewSources[name]?.let { return it }
		val repository = mangaRepositoryFactory.create(this)
		val uses = ((repository as? ParserMangaRepository)
			?.getConfigKeys()
			?.any { it is ConfigKey.DisableUpdateChecking && it.defaultValue }
			== true)
		webViewSources.putIfAbsent(name, uses)
		return uses
	}

	private fun getPresetSources(preset: SourcePreset): List<MangaSource> {
		if (preset.sources.isEmpty()) return emptyList()
		return sourcesRepository.allMangaSources.filter { it.name in preset.sources }
	}

	private fun MangaSource.languageCode(): String? = getLocale()?.language?.takeIf(String::isNotBlank)

	private fun MangaSource.hasSameContentTypeAs(ref: MangaSource): Boolean =
		this is ParserMangaSource && ref is ParserMangaSource && contentType == ref.contentType

	private fun MangaSource.priority(ref: MangaSource, defaultLocale: Locale): Int {
		var res = 0
		if (this is ParserMangaSource && ref is ParserMangaSource) {
			if (locale == ref.locale) {
				res += 4
			} else if (locale.toLocale() == defaultLocale) {
				res += 2
			}
			if (contentType == ref.contentType) {
				res++
			}
		}
		return res
	}
}
