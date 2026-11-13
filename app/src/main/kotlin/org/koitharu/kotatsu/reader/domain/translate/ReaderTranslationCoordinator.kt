package org.koitharu.kotatsu.reader.domain.translate

import android.net.Uri
import android.util.Log
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

sealed interface TranslationUiEvent {
	data object PromptMissingApiKey : TranslationUiEvent
	data class Error(
		val message: String?,
		val openSettingsAction: Boolean,
	) : TranslationUiEvent
}

@ActivityRetainedScoped
class ReaderTranslationCoordinator @Inject constructor(
	lifecycle: ActivityRetainedLifecycle,
	private val appSettings: AppSettings,
	private val translatePageUseCase: TranslatePageUseCase,
	private val ocrEngine: LocalOcrEngine,
	private val cacheStore: TranslationCacheStore,
) {

	private val scope = lifecycle.lifecycleScope + Dispatchers.Default
	private val generationCounter = AtomicLong(0L)
	private val settingsSheetDepth = AtomicInteger(0)
	private val jobsLock = Any()
	private val inFlightJobs = HashMap<Long, Job>()
	private val executionSemaphore = Semaphore(MAX_CONCURRENT_JOBS)

	@Volatile
	private var pendingRescheduleAfterSettings = false

	@Volatile
	private var pageUriProvider: (suspend (MangaPage) -> Uri)? = null

	@Volatile
	private var windowLock = Any()

	private var activePagesWindow: List<MangaPage> = emptyList()
	private var prefetchPagesWindow: List<MangaPage> = emptyList()
	private var activePageIds: Set<Long> = emptySet()

	private var manualDownloadJob: Job? = null
	private var lastErrorEventKey: String? = null
	private var lastErrorEventTimeMs: Long = 0L

	val settingsSnapshot: StateFlow<TranslationSettingsSnapshot> = appSettings.observeTranslationSettings()
		.stateIn(scope, SharingStarted.Eagerly, appSettings.getTranslationSettingsSnapshot())

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
		settingsSnapshot
			.drop(1)
			.onEach { snap ->
				Log.d(
					LOG_TAG,
					"Settings changed: provider=${snap.provider} source=${snap.sourceLang} target=${snap.targetLang}",
				)
				resetSessionState()
				if (_isEnabled.value) {
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
					Log.d(
						LOG_TAG,
						"Settings sheet closed; scheduling window (${snap.sourceLang} -> ${snap.targetLang}, ${snap.provider})",
					)
					scheduleCurrentWindow()
				}
			}
		}
	}

	internal fun attachPageUriProvider(provider: suspend (MangaPage) -> Uri) {
		pageUriProvider = provider
		if (_isEnabled.value && settingsSheetDepth.get() == 0) {
			scheduleCurrentWindow()
		}
	}

	/**
	 * Toggles translation from user interaction. If Gemini is selected without an API key,
	 * emits [TranslationUiEvent.PromptMissingApiKey] instead of enabling.
	 */
	fun toggle(): Boolean {
		return setEnabled(!_isEnabled.value, fromUserTap = true)
	}

	fun setEnabled(enable: Boolean, fromUserTap: Boolean = false): Boolean {
		if (!enable) {
			Log.i(LOG_TAG, "Translation disabled (fromUserTap=$fromUserTap)")
			pendingRescheduleAfterSettings = false
			_isEnabled.value = false
			generationCounter.incrementAndGet()
			cancelAllJobs()
			clearInProgressStates()
			return false
		}

		val snapshot = settingsSnapshot.value
		if (snapshot.provider == TranslationProvider.GEMINI && snapshot.apiKeys.isEmpty()) {
			Log.w(LOG_TAG, "Cannot enable Gemini translation: no API keys configured")
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

		Log.i(
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

		synchronized(windowLock) {
			activePagesWindow = activeMangaPages
			prefetchPagesWindow = prefetchMangaPages
			activePageIds = visibleIds
		}
		_activePageIdsFlow.value = visibleIds

		if (_isEnabled.value) {
			schedulePages(activeMangaPages, prefetchMangaPages)
		}
	}

	fun ensurePageTranslated(page: MangaPage) {
		if (!_isEnabled.value) return
		schedulePages(listOf(page), emptyList())
	}

	suspend fun isOcrModelReady(sourceLang: OcrSourceLanguage): Boolean {
		return ocrEngine.isModelReady(sourceLang)
	}

	fun downloadOcrModel(sourceLang: OcrSourceLanguage) {
		if (manualDownloadJob?.isActive == true) return
		Log.i(LOG_TAG, "Manual OCR download requested for $sourceLang")
		manualDownloadJob = scope.launch {
			_manualDownloadProgress.value = 0
			try {
				ocrEngine.ensureModels(sourceLang) { percent ->
					_manualDownloadProgress.value = percent.coerceIn(0, 100)
				}
				Log.i(LOG_TAG, "Manual OCR download completed for $sourceLang")
			} catch (ce: CancellationException) {
				throw ce
			} catch (t: Throwable) {
				Log.e(LOG_TAG, "Manual OCR download failed for $sourceLang", t)
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
		val (active, prefetch) = synchronized(windowLock) {
			activePagesWindow to prefetchPagesWindow
		}
		schedulePages(active, prefetch)
	}

	private fun schedulePages(
		active: List<MangaPage>,
		prefetch: List<MangaPage>,
	) {
		val snapshot = settingsSnapshot.value
		val generation = generationCounter.get()
		for (page in active) {
			launchPageTranslation(page, snapshot, generation, isPrefetch = false)
		}
		for (page in prefetch) {
			if (active.any { it.id == page.id }) continue
			launchPageTranslation(page, snapshot, generation, isPrefetch = true)
		}
	}

	private fun launchPageTranslation(
		page: MangaPage,
		snapshot: TranslationSettingsSnapshot,
		generation: Long,
		isPrefetch: Boolean,
	) {
		synchronized(jobsLock) {
			val existingState = _pageStates.value[page.id]
			if (existingState?.stage == TranslationStage.DONE ||
				existingState?.stage?.isInProgress() == true
			) {
				return
			}
			val existingJob = inFlightJobs[page.id]
			if (existingJob?.isActive == true) {
				return
			}

			val job = scope.launch {
				try {
					if (!_isEnabled.value || generation != generationCounter.get()) {
						return@launch
					}
					executionSemaphore.withPermit {
						if (!_isEnabled.value || generation != generationCounter.get()) {
							return@withPermit
						}
						val uriProvider = pageUriProvider
						if (uriProvider == null) {
							Log.w(LOG_TAG, "Skipping page=${page.id}: pageUriProvider is not attached yet")
							return@withPermit
						}
						Log.d(
							LOG_TAG,
							"Starting translation job page=${page.id} (prefetch=$isPrefetch, " +
								"source=${snapshot.sourceLang} -> ${snapshot.targetLang})",
						)
						val result = translatePageUseCase(
							page = page,
							settings = snapshot,
							loadSourceUri = { uriProvider(page) },
							onProgress = { stage, percent ->
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
							},
						)
						if (generation == generationCounter.get() && _isEnabled.value) {
							val filePath = (result as? PageTranslationResult.Translated)?.file?.absolutePath
							Log.d(
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
				} catch (ce: CancellationException) {
					throw ce
				} catch (e: MissingGeminiApiKeyException) {
					Log.w(LOG_TAG, "Translation stopped on page=${page.id}: MissingGeminiApiKeyException")
					if (generation == generationCounter.get()) {
						_isEnabled.value = false
						clearInProgressStates()
						emitErrorOnce(
							key = "missing_api_key",
							message = null,
							openSettingsAction = true,
						)
					}
				} catch (e: InvalidGeminiApiKeyException) {
					Log.w(LOG_TAG, "Translation failed on page=${page.id}: InvalidGeminiApiKeyException: ${e.message}")
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
					Log.e(LOG_TAG, "Translation failed on page=${page.id}", t)
					if (generation == generationCounter.get() && _isEnabled.value) {
						updatePageState(
							PageTranslationState(
								pageId = page.id,
								stage = TranslationStage.ERROR,
								errorMessage = t.message,
							),
						)
						if (!isPrefetch || page.id in activePageIds) {
							emitErrorOnce(
								key = "err:${t.javaClass.simpleName}:${t.message}",
								message = t.message,
								openSettingsAction = false,
							)
						}
					}
				} finally {
					synchronized(jobsLock) {
						inFlightJobs.remove(page.id)
					}
				}
			}
			inFlightJobs[page.id] = job
		}
	}

	private fun resetSessionState() {
		generationCounter.incrementAndGet()
		cancelAllJobs()
		_pageStates.value = emptyMap()
	}

	private fun cancelAllJobs() {
		val jobs = synchronized(jobsLock) {
			inFlightJobs.values.toList().also { inFlightJobs.clear() }
		}
		for (job in jobs) {
			job.cancel()
		}
	}

	private fun clearInProgressStates() {
		_pageStates.update { current ->
			current.filterValues { !it.stage.isInProgress() && it.stage != TranslationStage.ERROR }
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
		private const val MAX_CONCURRENT_JOBS = 2
		const val PREFETCH_PAGE_COUNT = 2
		private const val ERROR_DEBOUNCE_MS = 4_000L
	}
}
