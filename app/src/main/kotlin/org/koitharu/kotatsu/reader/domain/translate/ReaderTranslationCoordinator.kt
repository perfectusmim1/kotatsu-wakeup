package org.koitharu.kotatsu.reader.domain.translate

import android.graphics.Bitmap
import android.net.Uri
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.lifecycleScope
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.domain.translate.api.InvalidGeminiApiKeyException
import org.koitharu.kotatsu.reader.domain.translate.api.MissingGeminiApiKeyException
import org.koitharu.kotatsu.reader.domain.translate.model.LocalOcrEngine
import org.koitharu.kotatsu.reader.domain.translate.model.OcrSourceLanguage
import org.koitharu.kotatsu.reader.domain.translate.model.PageTranslationState
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationCacheStore
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationProvider
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationSettingsSnapshot
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationStage
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import java.io.File
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

sealed interface TranslationUiEvent {
	data object PromptMissingApiKey : TranslationUiEvent
	data class Error(
		val message: String?,
		val openSettingsAction: Boolean,
	) : TranslationUiEvent
}

internal class PrioritySemaphore(private val maxPermits: Int) {

	init {
		require(maxPermits > 0) { "maxPermits must be > 0, was $maxPermits" }
	}

	private class Waiter(
		val key: Long,
		val seq: Long,
		@Volatile var highPriority: Boolean,
		val gate: CompletableDeferred<Unit> = CompletableDeferred(),
		var cancelled: Boolean = false,
		var granted: Boolean = false,
	) : Comparable<Waiter> {
		override fun compareTo(other: Waiter): Int {
			if (highPriority != other.highPriority) {
				return if (highPriority) -1 else 1
			}
			return seq.compareTo(other.seq)
		}
	}

	private val lock = Any()
	private var availablePermits = maxPermits
	private val seqCounter = AtomicLong(0L)
	private val waiters = PriorityQueue<Waiter>()

	fun pendingWaitersCount(): Int = synchronized(lock) {
		waiters.count { !it.cancelled }
	}

	fun promote(key: Long) {
		synchronized(lock) {
			val toPromote = waiters.filter { it.key == key && !it.highPriority && !it.cancelled }
			if (toPromote.isEmpty()) return
			for (waiter in toPromote) {
				waiters.remove(waiter)
				waiter.highPriority = true
				waiters.add(waiter)
			}
		}
	}

	suspend fun <T> withPermit(
		key: Long,
		highPriority: Boolean,
		block: suspend () -> T,
	): T {
		acquire(key = key, highPriority = highPriority)
		try {
			return block()
		} finally {
			release()
		}
	}

	fun asPipelinePermit(
		key: Long,
		isHighPriority: () -> Boolean,
	): PipelinePermit = object : PipelinePermit {
		override suspend fun <T> withPermit(block: suspend () -> T): T =
			this@PrioritySemaphore.withPermit(key = key, highPriority = isHighPriority(), block = block)
	}

	private suspend fun acquire(key: Long, highPriority: Boolean) {
		currentCoroutineContext().ensureActive()
		val waiter: Waiter? = synchronized(lock) {
			if (availablePermits > 0) {
				availablePermits--
				null
			} else {
				Waiter(
					key = key,
					seq = seqCounter.incrementAndGet(),
					highPriority = highPriority,
				).also { waiters.add(it) }
			}
		}
		if (waiter == null) return

		try {
			waiter.gate.await()
		} catch (ce: CancellationException) {
			val mustRelease = synchronized(lock) {
				waiter.cancelled = true
				val removed = waiters.remove(waiter)
				!removed && waiter.granted
			}
			if (mustRelease) {
				release()
			}
			throw ce
		}
	}

	private fun release() {
		var nextWaiter: Waiter? = null
		synchronized(lock) {
			while (true) {
				val candidate = waiters.poll()
				if (candidate == null) {
					availablePermits++
					check(availablePermits <= maxPermits) {
						"PrioritySemaphore permit overflow ($availablePermits > $maxPermits)"
					}
					break
				}
				if (!candidate.cancelled) {
					candidate.granted = true
					nextWaiter = candidate
					break
				}
			}
		}
		nextWaiter?.gate?.complete(Unit)
	}
}

@ActivityRetainedScoped
class ReaderTranslationCoordinator private constructor(
	lifecycle: ActivityRetainedLifecycle,
	settingsFlow: Flow<TranslationSettingsSnapshot>,
	initialSettings: TranslationSettingsSnapshot,
	preboundSettingsState: StateFlow<TranslationSettingsSnapshot>?,
	private val translatePageUseCase: TranslatePageUseCase,
	private val ocrEngine: LocalOcrEngine,
	private val cacheStore: TranslationCacheStore,
) {

	@Inject
	constructor(
		lifecycle: ActivityRetainedLifecycle,
		appSettings: AppSettings,
		translatePageUseCase: TranslatePageUseCase,
		ocrEngine: LocalOcrEngine,
		cacheStore: TranslationCacheStore,
	) : this(
		lifecycle = lifecycle,
		settingsFlow = appSettings.observeTranslationSettings(),
		initialSettings = appSettings.getTranslationSettingsSnapshot(),
		preboundSettingsState = null,
		translatePageUseCase = translatePageUseCase,
		ocrEngine = ocrEngine,
		cacheStore = cacheStore,
	)

	internal constructor(
		lifecycle: ActivityRetainedLifecycle,
		settingsState: StateFlow<TranslationSettingsSnapshot>,
		translatePageUseCase: TranslatePageUseCase,
		ocrEngine: LocalOcrEngine,
		cacheStore: TranslationCacheStore,
	) : this(
		lifecycle = lifecycle,
		settingsFlow = settingsState,
		initialSettings = settingsState.value,
		preboundSettingsState = settingsState,
		translatePageUseCase = translatePageUseCase,
		ocrEngine = ocrEngine,
		cacheStore = cacheStore,
	)

	private class InFlightEntry(
		val pageId: Long,
		@Volatile var isPrefetch: Boolean,
		@Volatile var isFromPreload: Boolean,
		@Volatile var waitingOnPrefetchGate: Boolean = false,
	) {
		lateinit var job: Job
	}

	private val scope = lifecycle.lifecycleScope + Dispatchers.Default
	private val generationCounter = AtomicLong(0L)
	private val settingsSheetDepth = AtomicInteger(0)
	private val jobsLock = Any()
	private val inFlightJobs = HashMap<Long, InFlightEntry>()
	private val pendingActiveOcrIds = HashSet<Long>()
	private val _hasPendingActiveOcr = MutableStateFlow(false)
	private val ocrGate = PrioritySemaphore(OCR_CONCURRENCY)
	private val translateGate = PrioritySemaphore(TRANSLATE_CONCURRENCY)
	private val prefetchGate = PrioritySemaphore(MAX_CONCURRENT_JOBS)

	@Volatile
	private var pendingRescheduleAfterSettings = false

	@Volatile
	private var pageUriProvider: (suspend (MangaPage, isPrefetch: Boolean) -> Uri)? = null

	@Volatile
	private var pageBitmapProvider: (suspend (MangaPage) -> Bitmap)? = null

	private val windowLock = Any()

	private var activePagesWindow: List<MangaPage> = emptyList()
	private var prefetchPagesWindow: List<MangaPage> = emptyList()
	private var activePageIds: Set<Long> = emptySet()
	private val preloadedPages = ArrayDeque<MangaPage>()

	private var manualDownloadJob: Job? = null
	private var lastErrorEventKey: String? = null
	private var lastErrorEventTimeMs: Long = 0L

	val settingsSnapshot: StateFlow<TranslationSettingsSnapshot> = preboundSettingsState
		?: settingsFlow.stateIn(scope, SharingStarted.Eagerly, initialSettings)

	private val _isEnabled = MutableStateFlow(shouldAutoEnable(settingsSnapshot.value))
	val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

	private val _pageStates = MutableStateFlow<Map<Long, PageTranslationState>>(emptyMap())
	val pageStates: StateFlow<Map<Long, PageTranslationState>> = _pageStates.asStateFlow()

	private val _activePageIdsFlow = MutableStateFlow<Set<Long>>(emptySet())

	val activeTranslatedPages: StateFlow<Map<Long, File>> = combine(
		isEnabled,
		pageStates,
	) { enabled, states ->
		if (!enabled) {
			emptyMap()
		} else {
			buildMap(states.size) {
				for ((pageId, state) in states) {
					if (state.stage == TranslationStage.DONE) {
						val path = state.translatedFilePath ?: continue
						val file = File(path)
						if (file.isFile && file.length() > 0L) {
							put(pageId, file)
						}
					}
				}
			}
		}
	}.stateIn(scope, SharingStarted.Eagerly, emptyMap())

	val activeBannerState: StateFlow<PageTranslationState?> = combine(
		isEnabled,
		pageStates,
		_activePageIdsFlow,
	) { enabled, states, visibleIds ->
		if (!enabled) {
			null
		} else {
			val activeState = visibleIds.asSequence()
				.mapNotNull { states[it] }
				.firstOrNull { it.stage.isInProgress() }
			activeState ?: states.values.firstOrNull { it.stage == TranslationStage.DOWNLOADING_MODELS }
		}
	}.stateIn(scope, SharingStarted.Eagerly, null)

	private val _manualDownloadProgress = MutableStateFlow<Int?>(null)
	val manualDownloadProgress: StateFlow<Int?> = _manualDownloadProgress.asStateFlow()

	private val _uiEvents = MutableSharedFlow<TranslationUiEvent>(extraBufferCapacity = 8)
	val uiEvents: SharedFlow<TranslationUiEvent> = _uiEvents.asSharedFlow()

	init {
		var previousSnapshot = initialSettings
		settingsSnapshot
			.drop(1)
			.onEach { snap ->
				TranslatePipelineLog.d(
					LOG_TAG,
					"Settings changed: provider=${snap.provider} source=${snap.sourceLang} " +
						"target=${snap.targetLang} prefetch=${snap.prefetchNextPage}",
				)
				val cacheAffectingChanged =
					previousSnapshot.provider != snap.provider ||
						previousSnapshot.sourceLang != snap.sourceLang ||
						previousSnapshot.targetLang != snap.targetLang ||
						previousSnapshot.skipPureSfx != snap.skipPureSfx ||
						previousSnapshot.filterPromoAndCredits != snap.filterPromoAndCredits ||
						previousSnapshot.apiKeys != snap.apiKeys
				val prefetchTurnedOff = previousSnapshot.prefetchNextPage && !snap.prefetchNextPage
				val prefetchTurnedOn = !previousSnapshot.prefetchNextPage && snap.prefetchNextPage
				previousSnapshot = snap

				if (cacheAffectingChanged) {
					resetSessionState()
				} else if (prefetchTurnedOff) {
					cancelPrefetchJobsAndClearProgress()
				}

				if (_isEnabled.value && (cacheAffectingChanged || prefetchTurnedOn)) {
					if (settingsSheetDepth.get() > 0) {
						pendingRescheduleAfterSettings = true
					} else {
						scheduleCurrentWindow()
					}
				}
			}
			.launchIn(scope)
	}

	fun setSettingsSheetVisible(visible: Boolean) {
		if (visible) {
			settingsSheetDepth.incrementAndGet()
		} else {
			val remaining = settingsSheetDepth.updateAndGet { (it - 1).coerceAtLeast(0) }
			if (remaining == 0 && pendingRescheduleAfterSettings) {
				pendingRescheduleAfterSettings = false
				if (_isEnabled.value) {
					val snap = settingsSnapshot.value
					TranslatePipelineLog.d(
						LOG_TAG,
						"Settings sheet closed; scheduling window (${snap.sourceLang} -> ${snap.targetLang}, ${snap.provider})",
					)
					scheduleCurrentWindow()
				}
			}
		}
	}

	internal fun attachPageUriProvider(provider: suspend (MangaPage) -> Uri) {
		attachPrefetchAwarePageUriProvider { page, _ -> provider(page) }
	}

	internal fun attachPrefetchAwarePageUriProvider(
		provider: suspend (MangaPage, isPrefetch: Boolean) -> Uri,
	) {
		pageUriProvider = provider
		if (_isEnabled.value && settingsSheetDepth.get() == 0) {
			scheduleCurrentWindow()
		}
	}

	internal fun attachPageBitmapProvider(provider: suspend (MangaPage) -> Bitmap) {
		pageBitmapProvider = provider
		if (_isEnabled.value && settingsSheetDepth.get() == 0) {
			scheduleCurrentWindow()
		}
	}

	internal fun isPrefetchTranslationEligible(): Boolean {
		return _isEnabled.value &&
			settingsSheetDepth.get() == 0 &&
			settingsSnapshot.value.prefetchNextPage
	}

	internal fun pendingOcrWaitersCount(): Int = ocrGate.pendingWaitersCount()

	internal fun pendingTranslateWaitersCount(): Int = translateGate.pendingWaitersCount()

	/**
	 * Toggles translation from user interaction. If Gemini is selected without an API key,
	 * emits [TranslationUiEvent.PromptMissingApiKey] instead of enabling.
	 */
	fun toggle(): Boolean {
		return setEnabled(!_isEnabled.value, fromUserTap = true)
	}

	fun setEnabled(enable: Boolean, fromUserTap: Boolean = false): Boolean {
		if (!enable) {
			TranslatePipelineLog.i(LOG_TAG, "Translation disabled (fromUserTap=$fromUserTap)")
			pendingRescheduleAfterSettings = false
			_isEnabled.value = false
			generationCounter.incrementAndGet()
			synchronized(windowLock) {
				preloadedPages.clear()
			}
			cancelAllJobs()
			clearInProgressStates()
			return false
		}

		val snapshot = settingsSnapshot.value
		if (snapshot.provider == TranslationProvider.GEMINI && snapshot.apiKeys.isEmpty()) {
			TranslatePipelineLog.w(LOG_TAG, "Cannot enable Gemini translation: no API keys configured")
			_isEnabled.value = false
			if (fromUserTap) {
				_uiEvents.tryEmit(TranslationUiEvent.PromptMissingApiKey)
			} else {
				emitErrorOnce(
					key = "missing_api_key",
					message = null,
					openSettingsAction = true,
				)
			}
			return false
		}

		TranslatePipelineLog.i(
			LOG_TAG,
			"Translation enabled (fromUserTap=$fromUserTap, source=${snapshot.sourceLang}, " +
				"target=${snapshot.targetLang}, provider=${snapshot.provider})",
		)
		if (!_isEnabled.value) {
			_isEnabled.value = true
		}
		if (settingsSheetDepth.get() > 0) {
			pendingRescheduleAfterSettings = true
		} else {
			scheduleCurrentWindow()
		}
		return true
	}

	fun updateReadingWindow(
		activePages: List<ReaderPage>,
		prefetchPages: List<ReaderPage>,
	) {
		val activeMangaPages = activePages.map { it.toMangaPage() }
		val prefetchMangaPages = prefetchPages.take(PREFETCH_PAGE_COUNT).map { it.toMangaPage() }
		val visibleIds = activeMangaPages.mapTo(HashSet(activeMangaPages.size)) { it.id }
		val windowIds = HashSet<Long>(activeMangaPages.size + prefetchMangaPages.size).apply {
			addAll(visibleIds)
			for (page in prefetchMangaPages) {
				add(page.id)
			}
		}
		val isDisjointJump: Boolean
		val prevWindowIds: Set<Long>

		synchronized(windowLock) {
			prevWindowIds = HashSet<Long>(activePagesWindow.size + prefetchPagesWindow.size).apply {
				for (page in activePagesWindow) add(page.id)
				for (page in prefetchPagesWindow) add(page.id)
			}
			isDisjointJump = prevWindowIds.isNotEmpty() && windowIds.none { it in prevWindowIds }

			activePagesWindow = activeMangaPages
			prefetchPagesWindow = prefetchMangaPages
			activePageIds = visibleIds

			val iter = preloadedPages.iterator()
			while (iter.hasNext()) {
				val preloaded = iter.next()
				if (preloaded.id in visibleIds || isDisjointJump || preloaded.id in prevWindowIds) {
					iter.remove()
				}
			}
		}
		_activePageIdsFlow.value = visibleIds

		val staleEntries = synchronized(jobsLock) {
			inFlightJobs.values.filter { entry ->
				entry.isPrefetch &&
					entry.pageId !in windowIds &&
					(!entry.isFromPreload || isDisjointJump || entry.pageId in prevWindowIds)
			}
		}
		cancelEntriesAndClearInProgress(staleEntries)

		if (_isEnabled.value) {
			val preloadedCopy = synchronized(windowLock) { preloadedPages.toList() }
			schedulePages(activeMangaPages, prefetchMangaPages, preloadedCopy)
		}
	}

	fun onPagePreloaded(page: MangaPage) {
		if (!isPrefetchTranslationEligible()) return
		val snapshot = settingsSnapshot.value
		val generation = generationCounter.get()
		var launchAsPrefetch = true
		var evictedPreloadId: Long? = null
		var keepEvictedInWindow = false

		synchronized(windowLock) {
			if (page.id in activePageIds) {
				launchAsPrefetch = false
			} else if (preloadedPages.none { it.id == page.id }) {
				preloadedPages.addLast(page)
				while (preloadedPages.size > MAX_PRELOADED_TRACKED) {
					val evicted = preloadedPages.removeFirst().id
					if (evicted != page.id) {
						evictedPreloadId = evicted
						keepEvictedInWindow =
							evicted in activePageIds || prefetchPagesWindow.any { it.id == evicted }
					}
				}
			}
		}

		evictedPreloadId?.takeIf { !keepEvictedInWindow }?.let { evictedId ->
			val staleEvicted = synchronized(jobsLock) {
				val entry = inFlightJobs[evictedId]
				if (entry != null && entry.isPrefetch && entry.isFromPreload) listOf(entry) else emptyList()
			}
			cancelEntriesAndClearInProgress(staleEvicted)
		}

		TranslatePipelineLog.d(
			LOG_TAG,
			"Preloaded page=${page.id} queued for auto-translation (prefetch=$launchAsPrefetch)",
		)
		launchPageTranslation(
			page = page,
			snapshot = snapshot,
			generation = generation,
			isPrefetch = launchAsPrefetch,
			isFromPreload = launchAsPrefetch,
		)
	}

	fun ensurePageTranslated(page: MangaPage) {
		if (!_isEnabled.value) return
		schedulePages(listOf(page), emptyList(), emptyList())
	}

	suspend fun isOcrModelReady(sourceLang: OcrSourceLanguage): Boolean {
		return ocrEngine.isModelReady(sourceLang)
	}

	fun downloadOcrModel(sourceLang: OcrSourceLanguage) {
		if (manualDownloadJob?.isActive == true) return
		TranslatePipelineLog.i(LOG_TAG, "Manual OCR download requested for $sourceLang")
		manualDownloadJob = scope.launch {
			_manualDownloadProgress.value = 0
			try {
				ocrEngine.ensureModels(sourceLang) { percent ->
					_manualDownloadProgress.value = percent.coerceIn(0, 100)
				}
				TranslatePipelineLog.i(LOG_TAG, "Manual OCR download completed for $sourceLang")
			} catch (ce: CancellationException) {
				throw ce
			} catch (t: Throwable) {
				TranslatePipelineLog.e(LOG_TAG, "Manual OCR download failed for $sourceLang", t)
				emitErrorOnce(
					key = "model_download:${t.message}",
					message = t.message,
					openSettingsAction = false,
				)
			} finally {
				_manualDownloadProgress.value = null
			}
		}
	}

	suspend fun clearCache(): Long {
		val freed = cacheStore.clearAll()
		resetSessionState()
		if (_isEnabled.value) {
			scheduleCurrentWindow()
		}
		return freed
	}

	private fun scheduleCurrentWindow() {
		val (active, prefetch, preloaded) = synchronized(windowLock) {
			Triple(activePagesWindow, prefetchPagesWindow, preloadedPages.toList())
		}
		schedulePages(active, prefetch, preloaded)
	}

	private fun schedulePages(
		active: List<MangaPage>,
		prefetch: List<MangaPage>,
		preloaded: List<MangaPage> = emptyList(),
	) {
		if (!_isEnabled.value) return
		val snapshot = settingsSnapshot.value
		val generation = generationCounter.get()
		val activeIds = active.mapTo(HashSet(active.size)) { it.id }
		val scheduledPrefetchIds = HashSet<Long>(prefetch.size)
		for (page in active) {
			launchPageTranslation(
				page = page,
				snapshot = snapshot,
				generation = generation,
				isPrefetch = false,
				isFromPreload = false,
			)
		}
		if (!snapshot.prefetchNextPage) {
			return
		}
		for (page in prefetch) {
			if (page.id in activeIds) continue
			scheduledPrefetchIds.add(page.id)
			launchPageTranslation(
				page = page,
				snapshot = snapshot,
				generation = generation,
				isPrefetch = true,
				isFromPreload = false,
			)
		}
		for (page in preloaded) {
			if (page.id in activeIds || page.id in scheduledPrefetchIds) continue
			launchPageTranslation(
				page = page,
				snapshot = snapshot,
				generation = generation,
				isPrefetch = true,
				isFromPreload = true,
			)
		}
	}

	private fun launchPageTranslation(
		page: MangaPage,
		snapshot: TranslationSettingsSnapshot,
		generation: Long,
		isPrefetch: Boolean,
		isFromPreload: Boolean,
	) {
		var jobToCancelOnPromotion: Job? = null
		synchronized(jobsLock) {
			val existingState = _pageStates.value[page.id]
			if (existingState?.stage == TranslationStage.DONE) {
				return
			}
			val existingEntry = inFlightJobs[page.id]
			if (existingEntry != null && existingEntry.job.isActive) {
				if (!isPrefetch && existingEntry.isPrefetch) {
					if (existingEntry.waitingOnPrefetchGate) {
						// Has not entered OCR or emitted progress yet: replace immediately without blocking on prefetchGate
						inFlightJobs.remove(page.id)
						jobToCancelOnPromotion = existingEntry.job
					} else {
						existingEntry.isPrefetch = false
						existingEntry.isFromPreload = false
						val stage = existingState?.stage
						if (stage == null || stage == TranslationStage.IDLE || stage == TranslationStage.DOWNLOADING_MODELS || stage == TranslationStage.RUNNING_OCR) {
							setActiveOcrPendingLocked(page.id, true)
						}
						ocrGate.promote(page.id)
						translateGate.promote(page.id)
						TranslatePipelineLog.d(LOG_TAG, "Promoted in-flight prefetch page=${page.id} to active priority")
						return
					}
				} else {
					return
				}
			}

			val entry = InFlightEntry(
				pageId = page.id,
				isPrefetch = isPrefetch,
				isFromPreload = isFromPreload,
			)
			val job = scope.launch(start = CoroutineStart.LAZY) {
				try {
					if (!_isEnabled.value || generation != generationCounter.get()) {
						return@launch
					}
					if (entry.isPrefetch) {
						if (!settingsSnapshot.value.prefetchNextPage) {
							return@launch
						}
						entry.waitingOnPrefetchGate = true
						_hasPendingActiveOcr.first {
							!it || !entry.isPrefetch || !_isEnabled.value || generation != generationCounter.get()
						}
						if (!_isEnabled.value || generation != generationCounter.get()) {
							return@launch
						}
						prefetchGate.withPermit(key = page.id, highPriority = false) {
							entry.waitingOnPrefetchGate = false
							executePageJob(entry, page, snapshot, generation)
						}
					} else {
						executePageJob(entry, page, snapshot, generation)
					}
				} catch (ce: CancellationException) {
					throw ce
				} catch (e: MissingGeminiApiKeyException) {
					TranslatePipelineLog.w(LOG_TAG, "Translation stopped on page=${page.id}: MissingGeminiApiKeyException")
					if (generation == generationCounter.get()) {
						_isEnabled.value = false
						generationCounter.incrementAndGet()
						cancelAllJobs()
						clearInProgressStates()
						emitErrorOnce(
							key = "missing_api_key",
							message = null,
							openSettingsAction = true,
						)
					}
				} catch (e: InvalidGeminiApiKeyException) {
					TranslatePipelineLog.w(
						LOG_TAG,
						"Translation failed on page=${page.id}: InvalidGeminiApiKeyException: ${e.message}",
					)
					if (generation == generationCounter.get()) {
						updatePageState(
							PageTranslationState(
								pageId = page.id,
								stage = TranslationStage.ERROR,
								errorMessage = e.message,
							),
						)
						emitErrorOnce(
							key = "invalid_api_key",
							message = e.message,
							openSettingsAction = true,
						)
					}
				} catch (t: Throwable) {
					TranslatePipelineLog.e(LOG_TAG, "Translation failed on page=${page.id}", t)
					if (generation == generationCounter.get() && _isEnabled.value) {
						updatePageState(
							PageTranslationState(
								pageId = page.id,
								stage = TranslationStage.ERROR,
								errorMessage = t.message,
							),
						)
						if (!entry.isPrefetch || page.id in activePageIds) {
							emitErrorOnce(
								key = "err:${t.javaClass.simpleName}:${t.message}",
								message = t.message,
								openSettingsAction = false,
							)
						}
					}
				} finally {
					val wasCancelled = !isActive
					val removedOwnEntry = synchronized(jobsLock) {
						setActiveOcrPendingLocked(page.id, false)
						if (inFlightJobs[page.id] === entry) {
							inFlightJobs.remove(page.id)
							true
						} else {
							false
						}
					}
					if (removedOwnEntry && wasCancelled) {
						clearInProgressStateForPage(page.id)
					}
				}
			}
			entry.job = job
			inFlightJobs[page.id] = entry
			if (!entry.isPrefetch) {
				setActiveOcrPendingLocked(page.id, true)
			}
			job.start()
		}
		jobToCancelOnPromotion?.cancel()
	}

	private suspend fun executePageJob(
		entry: InFlightEntry,
		page: MangaPage,
		snapshot: TranslationSettingsSnapshot,
		generation: Long,
	) {
		if (!_isEnabled.value || generation != generationCounter.get()) {
			return
		}
		val ocrPermit = ocrGate.asPipelinePermit(page.id) { !entry.isPrefetch }
		val translatePermit = translateGate.asPipelinePermit(page.id) { !entry.isPrefetch }
		val onProgress: suspend (TranslationStage, Int) -> Unit = { stage, percent ->
			if (stage != TranslationStage.DOWNLOADING_MODELS && stage != TranslationStage.RUNNING_OCR) {
				markActiveOcrDone(page.id)
			}
			if (generation == generationCounter.get() &&
				(_isEnabled.value || stage == TranslationStage.IDLE)
			) {
				updatePageState(
					PageTranslationState(
						pageId = page.id,
						stage = stage,
						progressPercent = percent,
					),
				)
			}
		}

		TranslatePipelineLog.d(
			LOG_TAG,
			"Starting translation job page=${page.id} (prefetch=${entry.isPrefetch}, " +
				"preload=${entry.isFromPreload}, source=${snapshot.sourceLang} -> ${snapshot.targetLang})",
		)

		val bitmapProvider = pageBitmapProvider
		val result = if (bitmapProvider != null) {
			translatePageUseCase.invokeWithBitmap(
				page = page,
				settings = snapshot,
				loadSourceBitmap = { bitmapProvider(page) },
				onProgress = onProgress,
				ocrPermit = ocrPermit,
				translatePermit = translatePermit,
			)
		} else {
			val uriProvider = pageUriProvider
			if (uriProvider == null) {
				TranslatePipelineLog.w(LOG_TAG, "Skipping page=${page.id}: pageUriProvider is not attached yet")
				return
			}
			translatePageUseCase(
				page = page,
				settings = snapshot,
				loadSourceUri = { uriProvider(page, entry.isPrefetch) },
				onProgress = onProgress,
				ocrPermit = ocrPermit,
				translatePermit = translatePermit,
			)
		}

		if (generation == generationCounter.get() && _isEnabled.value) {
			markActiveOcrDone(page.id)
			val filePath = (result as? PageTranslationResult.Translated)?.file?.absolutePath
			TranslatePipelineLog.d(
				LOG_TAG,
				"Finished page=${page.id}: result=${if (filePath != null) "Translated" else "NoText"}",
			)
			updatePageState(
				PageTranslationState(
					pageId = page.id,
					stage = TranslationStage.DONE,
					progressPercent = 100,
					translatedFilePath = filePath,
				),
			)
		}
	}

	private fun resetSessionState() {
		generationCounter.incrementAndGet()
		cancelAllJobs()
		_pageStates.value = emptyMap()
	}

	private fun cancelPrefetchJobsAndClearProgress() {
		val prefetchEntries = synchronized(jobsLock) {
			inFlightJobs.values.filter { it.isPrefetch }
		}
		cancelEntriesAndClearInProgress(prefetchEntries)
	}

	private fun cancelEntriesAndClearInProgress(entries: List<InFlightEntry>) {
		if (entries.isEmpty()) return
		val cancelledIds = HashSet<Long>(entries.size)
		synchronized(jobsLock) {
			for (entry in entries) {
				setActiveOcrPendingLocked(entry.pageId, false)
				if (inFlightJobs[entry.pageId] === entry) {
					inFlightJobs.remove(entry.pageId)
				}
				cancelledIds.add(entry.pageId)
			}
		}
		for (entry in entries) {
			entry.job.cancel()
		}
		_pageStates.update { current ->
			current.filterKeys { pageId ->
				pageId !in cancelledIds || current[pageId]?.stage?.isInProgress() != true
			}
		}
	}

	private fun cancelAllJobs() {
		val entries = synchronized(jobsLock) {
			pendingActiveOcrIds.clear()
			_hasPendingActiveOcr.value = false
			inFlightJobs.values.toList().also { inFlightJobs.clear() }
		}
		for (entry in entries) {
			entry.job.cancel()
		}
	}

	private fun setActiveOcrPendingLocked(pageId: Long, pending: Boolean) {
		val changed = if (pending) pendingActiveOcrIds.add(pageId) else pendingActiveOcrIds.remove(pageId)
		if (changed) {
			_hasPendingActiveOcr.value = pendingActiveOcrIds.isNotEmpty()
		}
	}

	private fun markActiveOcrDone(pageId: Long) {
		synchronized(jobsLock) {
			setActiveOcrPendingLocked(pageId, false)
		}
	}

	private fun clearInProgressStates() {
		_pageStates.update { current ->
			current.filterValues { !it.stage.isInProgress() && it.stage != TranslationStage.ERROR }
		}
	}

	private fun clearInProgressStateForPage(pageId: Long) {
		_pageStates.update { current ->
			val state = current[pageId]
			if (state != null && state.stage.isInProgress()) {
				current - pageId
			} else {
				current
			}
		}
	}

	private fun updatePageState(newState: PageTranslationState) {
		_pageStates.update { current ->
			current + (newState.pageId to newState)
		}
	}

	private fun emitErrorOnce(key: String, message: String?, openSettingsAction: Boolean) {
		val now = System.currentTimeMillis()
		if (key == lastErrorEventKey && now - lastErrorEventTimeMs < ERROR_DEBOUNCE_MS) {
			return
		}
		lastErrorEventKey = key
		lastErrorEventTimeMs = now
		_uiEvents.tryEmit(TranslationUiEvent.Error(message = message, openSettingsAction = openSettingsAction))
	}

	private fun shouldAutoEnable(snapshot: TranslationSettingsSnapshot): Boolean {
		return snapshot.autoTranslate &&
			(snapshot.provider != TranslationProvider.GEMINI || snapshot.apiKeys.isNotEmpty())
	}

	private fun TranslationStage.isInProgress(): Boolean = when (this) {
		TranslationStage.DOWNLOADING_MODELS,
		TranslationStage.RUNNING_OCR,
		TranslationStage.TRANSLATING_TEXT,
		TranslationStage.RENDERING_PAGE,
		-> true

		else -> false
	}

	companion object {
		private const val LOG_TAG = "KotatsuTranslate"
		private const val OCR_CONCURRENCY = 1
		private const val TRANSLATE_CONCURRENCY = 4
		private const val MAX_CONCURRENT_JOBS = 4
		const val PREFETCH_PAGE_COUNT = 6
		private const val MAX_PRELOADED_TRACKED = 10
		private const val ERROR_DEBOUNCE_MS = 4_000L
	}
}
