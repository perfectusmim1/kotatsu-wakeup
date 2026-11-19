package org.koitharu.kotatsu.reader.domain.upscale

import android.graphics.Bitmap
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.lifecycleScope
import org.koitharu.kotatsu.reader.domain.upscale.cache.UpscaledStripCache
import org.koitharu.kotatsu.reader.domain.upscale.engine.Anime4kUpscaleEngine
import org.koitharu.kotatsu.reader.domain.upscale.engine.RealEsrganOnnxEngine
import org.koitharu.kotatsu.reader.domain.upscale.model.StripPlan
import org.koitharu.kotatsu.reader.domain.upscale.model.UpscaleMode
import org.koitharu.kotatsu.reader.domain.upscale.model.UpscaleRuntimeStatus
import org.koitharu.kotatsu.reader.domain.upscale.model.UpscaleSettingsSnapshot
import org.koitharu.kotatsu.reader.domain.upscale.model.UpscaleStage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Dinamik öncelikli tekil şerit yürütme kapısı.
 * Bekleyen şeritlerden o an ekran merkezine en yakın olanı (`priorityProducer` en küçük değeri veren)
 * bir sonraki sırayı alır; iptal edilen işler kuyruktan anında düşer.
 */
internal class DynamicPriorityGate(private val maxPermits: Int = 1) {

	private class Waiter(
		val seq: Long,
		val priorityProducer: () -> Float,
		val gate: CompletableDeferred<Unit> = CompletableDeferred(),
		var cancelled: Boolean = false,
		var granted: Boolean = false,
	)

	private val lock = Any()
	private var availablePermits = maxPermits
	private val seqCounter = AtomicLong(0L)
	private val waiters = ArrayList<Waiter>()

	suspend fun <T> withPermit(
		priorityProducer: () -> Float,
		block: suspend () -> T,
	): T {
		acquire(priorityProducer)
		try {
			return block()
		} finally {
			release()
		}
	}

	private suspend fun acquire(priorityProducer: () -> Float) {
		currentCoroutineContext().ensureActive()
		val waiter: Waiter? = synchronized(lock) {
			if (availablePermits > 0) {
				availablePermits--
				null
			} else {
				Waiter(
					seq = seqCounter.incrementAndGet(),
					priorityProducer = priorityProducer,
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
			val iter = waiters.iterator()
			while (iter.hasNext()) {
				if (iter.next().cancelled) {
					iter.remove()
				}
			}
			if (waiters.isEmpty()) {
				availablePermits = (availablePermits + 1).coerceAtMost(maxPermits)
			} else {
				var bestIdx = 0
				var bestScore = safePriority(waiters[0])
				var bestSeq = waiters[0].seq
				for (i in 1 until waiters.size) {
					val candidate = waiters[i]
					val score = safePriority(candidate)
					if (score < bestScore || (score == bestScore && candidate.seq < bestSeq)) {
						bestIdx = i
						bestScore = score
						bestSeq = candidate.seq
					}
				}
				val chosen = waiters.removeAt(bestIdx)
				chosen.granted = true
				nextWaiter = chosen
			}
		}
		nextWaiter?.gate?.complete(Unit)
	}

	private fun safePriority(waiter: Waiter): Float = try {
		val p = waiter.priorityProducer()
		if (p.isFinite()) p else Float.MAX_VALUE
	} catch (_: Throwable) {
		Float.MAX_VALUE
	}

	fun hasWaiterWithPriorityBelow(threshold: Float): Boolean = synchronized(lock) {
		for (i in 0 until waiters.size) {
			val w = waiters[i]
			if (!w.cancelled && safePriority(w) < threshold) {
				return@synchronized true
			}
		}
		false
	}
}

@ActivityRetainedScoped
class ReaderUpscaleCoordinator private constructor(
	lifecycle: ActivityRetainedLifecycle,
	settingsFlow: Flow<UpscaleSettingsSnapshot>,
	initialSettings: UpscaleSettingsSnapshot,
	private val anime4kEngine: Anime4kUpscaleEngine,
	private val onnxEngine: RealEsrganOnnxEngine,
	private val stripCache: UpscaledStripCache,
) {

	@Inject
	constructor(
		lifecycle: ActivityRetainedLifecycle,
		appSettings: AppSettings,
		anime4kEngine: Anime4kUpscaleEngine,
		onnxEngine: RealEsrganOnnxEngine,
		stripCache: UpscaledStripCache,
	) : this(
		lifecycle = lifecycle,
		settingsFlow = appSettings.observeUpscaleSettings(),
		initialSettings = appSettings.getUpscaleSettingsSnapshot(),
		anime4kEngine = anime4kEngine,
		onnxEngine = onnxEngine,
		stripCache = stripCache,
	)

	private val scope = lifecycle.lifecycleScope + Dispatchers.Default
	private val priorityGate = DynamicPriorityGate(maxPermits = 1)
	private val generationCounter = AtomicLong(0L)
	private val activeJobsCount = AtomicInteger(0)
	private var manualDownloadJob: Job? = null

	val settingsSnapshot: StateFlow<UpscaleSettingsSnapshot> =
		settingsFlow.stateIn(scope, SharingStarted.Eagerly, initialSettings)

	private val _compareEnabled = MutableStateFlow(false)
	val compareEnabled: StateFlow<Boolean> = _compareEnabled.asStateFlow()

	private val _compareSplitRatio = MutableStateFlow(DEFAULT_SPLIT_RATIO)
	val compareSplitRatio: StateFlow<Float> = _compareSplitRatio.asStateFlow()

	private val _runtimeStatus = MutableStateFlow(UpscaleRuntimeStatus())
	val runtimeStatus: StateFlow<UpscaleRuntimeStatus> = _runtimeStatus.asStateFlow()

	private val _manualDownloadProgress = MutableStateFlow<Int?>(null)
	val manualDownloadProgress: StateFlow<Int?> = _manualDownloadProgress.asStateFlow()

	private val _cacheRevision = MutableStateFlow(0L)
	val cacheRevision: StateFlow<Long> = _cacheRevision.asStateFlow()

	init {
		runCatching { StripPlanner.runSelfCheck() }
		if (initialSettings.mode == UpscaleMode.AI) {
			scope.launch { onnxEngine.warmUpSessionIfModelReady() }
		}
		var prevMode = initialSettings.mode
		settingsSnapshot
			.drop(1)
			.onEach { snap ->
				if (snap.mode != prevMode) {
					prevMode = snap.mode
					generationCounter.incrementAndGet()
					if (snap.mode == UpscaleMode.OFF) {
						_compareEnabled.value = false
						_runtimeStatus.value = UpscaleRuntimeStatus()
					} else {
						_runtimeStatus.update { it.copy(errorMessage = null) }
						if (snap.mode == UpscaleMode.AI) {
							scope.launch { onnxEngine.warmUpSessionIfModelReady() }
						}
					}
				}
			}
			.launchIn(scope)
	}

	fun setCompareEnabled(enabled: Boolean) {
		val effective = enabled && settingsSnapshot.value.mode.isEnabled
		_compareEnabled.value = effective
	}

	fun setCompareSplitRatio(ratio: Float) {
		if (!ratio.isFinite()) return
		_compareSplitRatio.value = ratio.coerceIn(0.02f, 0.98f)
	}

	fun isAiModelReady(): Boolean = onnxEngine.isModelReady()

	fun downloadAiModel() {
		if (manualDownloadJob?.isActive == true) return
		manualDownloadJob = scope.launch {
			_manualDownloadProgress.value = 0
			_runtimeStatus.update {
				it.copy(
					stage = UpscaleStage.DOWNLOADING_MODEL,
					downloadPercent = 0,
					errorMessage = null,
				)
			}
			try {
				onnxEngine.ensureModel { percent ->
					val clamped = percent.coerceIn(0, 100)
					_manualDownloadProgress.value = clamped
					_runtimeStatus.update {
						it.copy(
							stage = UpscaleStage.DOWNLOADING_MODEL,
							downloadPercent = clamped,
						)
					}
				}
				_runtimeStatus.update { status ->
					status.copy(
						stage = if (status.activeJobs > 0) UpscaleStage.PROCESSING else UpscaleStage.IDLE,
						downloadPercent = 100,
						errorMessage = null,
					)
				}
			} catch (ce: CancellationException) {
				throw ce
			} catch (t: Throwable) {
				_runtimeStatus.update {
					it.copy(
						stage = UpscaleStage.ERROR,
						errorMessage = t.message,
					)
				}
			} finally {
				_manualDownloadProgress.value = null
			}
		}
	}

	suspend fun clearCache(): Long {
		val freed = stripCache.clearAll()
		generationCounter.incrementAndGet()
		_cacheRevision.update { it + 1L }
		return freed
	}

	suspend fun getCachedStripIfPresent(
		pageIdentity: String,
		strip: StripPlan,
		mode: UpscaleMode,
		srcWidth: Int,
	): Bitmap? {
		if (!mode.isEnabled || srcWidth <= 0) return null
		val maxInW = if (mode == UpscaleMode.AI) StripPlanner.AI_MAX_INPUT_WIDTH else StripPlanner.DEFAULT_MAX_TEX_SIZE / 2
		val sizeSpec = StripPlanner.computeStripOutputSize(
			srcW = srcWidth,
			validH = strip.validHeight,
			maxInputWidthOverride = maxInW,
		)
		val cacheKey = stripCache.buildStripKey(
			pageIdentity = pageIdentity,
			stripIndex = strip.index,
			mode = mode,
			outWidth = sizeSpec.outWidth,
			outHeight = sizeSpec.outHeight,
		)
		return stripCache.getCachedBitmap(cacheKey)
	}

	/**
	 * Yapay Zekâ (AI) modunda şerit kuyrukta/çıkarımda beklerken kullanıcının bulanık sayfa görmemesi için
	 * ~25ms içinde hızlı Anime4K önizleme şeridi üretir (priorityGate kilidine takılmaz).
	 */
	suspend fun renderFastPreviewStrip(
		pageIdentity: String,
		strip: StripPlan,
		srcWidth: Int,
		decodeSourceStrip: suspend () -> Bitmap?,
	): Bitmap? {
		if (srcWidth <= 0) return null
		val sizeSpec = StripPlanner.computeStripOutputSize(
			srcW = srcWidth,
			validH = strip.validHeight,
			maxInputWidthOverride = StripPlanner.AI_MAX_INPUT_WIDTH,
		)
		val previewCacheKey = stripCache.buildStripKey(
			pageIdentity = pageIdentity,
			stripIndex = strip.index,
			mode = UpscaleMode.FAST,
			outWidth = sizeSpec.outWidth,
			outHeight = sizeSpec.outHeight,
		)
		stripCache.getCachedBitmap(previewCacheKey)?.let { return it }

		val rawStripBitmap = decodeSourceStrip() ?: return null
		val scaledInputBitmap = if (sizeSpec.inputWidth < rawStripBitmap.width) {
			val targetInH = (rawStripBitmap.height * sizeSpec.inputScale).toInt().coerceAtLeast(1)
			Bitmap.createScaledBitmap(rawStripBitmap, sizeSpec.inputWidth, targetInH, true).also {
				if (it !== rawStripBitmap) {
					rawStripBitmap.recycle()
				}
			}
		} else {
			rawStripBitmap
		}
		val scaledCropTop = (strip.cropTop * sizeSpec.inputScale).toInt().coerceAtLeast(0)
		val scaledCropBottom = (strip.cropBottom * sizeSpec.inputScale).toInt().coerceAtLeast(0)
		return try {
			anime4kEngine.upscale(
				source = scaledInputBitmap,
				outWidth = sizeSpec.outWidth,
				outHeight = sizeSpec.outHeight,
				cropTop = scaledCropTop,
				cropBottom = scaledCropBottom,
			).also { bmp ->
				if (!bmp.isRecycled) {
					stripCache.putCachedBitmap(previewCacheKey, bmp)
				}
			}
		} catch (ce: CancellationException) {
			throw ce
		} catch (_: Throwable) {
			null
		} finally {
			if (!scaledInputBitmap.isRecycled) {
				scaledInputBitmap.recycle()
			}
		}
	}

	suspend fun upscaleStrip(
		pageIdentity: String,
		strip: StripPlan,
		mode: UpscaleMode,
		srcWidth: Int,
		priorityProducer: () -> Float,
		decodeSourceStrip: suspend () -> Bitmap?,
	): Bitmap? {
		if (!mode.isEnabled || srcWidth <= 0) return null
		val generation = generationCounter.get()
		val maxInW = if (mode == UpscaleMode.AI) StripPlanner.AI_MAX_INPUT_WIDTH else StripPlanner.DEFAULT_MAX_TEX_SIZE / 2
		val sizeSpec = StripPlanner.computeStripOutputSize(
			srcW = srcWidth,
			validH = strip.validHeight,
			maxInputWidthOverride = maxInW,
		)
		val cacheKey = stripCache.buildStripKey(
			pageIdentity = pageIdentity,
			stripIndex = strip.index,
			mode = mode,
			outWidth = sizeSpec.outWidth,
			outHeight = sizeSpec.outHeight,
		)

		// Disk önbellekte hazırsa kuyruk beklemeden doğrudan döndür (~3ms)
		stripCache.getCachedBitmap(cacheKey)?.let { cached ->
			return cached
		}

		val activeAfterInc = activeJobsCount.incrementAndGet()
		_runtimeStatus.update { current ->
			val nextStage = if (current.stage.isModelBusy()) current.stage else UpscaleStage.PROCESSING
			current.copy(activeJobs = activeAfterInc, stage = nextStage)
		}

		try {
			return priorityGate.withPermit(priorityProducer) {
				currentCoroutineContext().ensureActive()
				if (generation != generationCounter.get() || settingsSnapshot.value.mode != mode) {
					return@withPermit null
				}

				stripCache.getCachedBitmap(cacheKey)?.let { cached ->
					return@withPermit cached
				}

				// Eğer bu şerit ekran dışı (lookahead) bir şeritse ve kuyrukta ekranda görünür bir şerit bekliyorsa hemen sırayı devret
				if (priorityProducer() >= OFF_SCREEN_PRIORITY_PENALTY &&
					priorityGate.hasWaiterWithPriorityBelow(ON_SCREEN_PRIORITY_THRESHOLD)
				) {
					return@withPermit null
				}

				val rawStripBitmap = decodeSourceStrip() ?: return@withPermit null
				val scaledInputBitmap = if (sizeSpec.inputWidth < rawStripBitmap.width) {
					val targetInH = (rawStripBitmap.height * sizeSpec.inputScale).toInt().coerceAtLeast(1)
					Bitmap.createScaledBitmap(rawStripBitmap, sizeSpec.inputWidth, targetInH, true).also {
						if (it !== rawStripBitmap) {
							rawStripBitmap.recycle()
						}
					}
				} else {
					rawStripBitmap
				}

				val scaledCropTop = (strip.cropTop * sizeSpec.inputScale).toInt().coerceAtLeast(0)
				val scaledCropBottom = (strip.cropBottom * sizeSpec.inputScale).toInt().coerceAtLeast(0)

				val resultBitmap = try {
					when (mode) {
						UpscaleMode.OFF -> null
						UpscaleMode.FAST -> anime4kEngine.upscale(
							source = scaledInputBitmap,
							outWidth = sizeSpec.outWidth,
							outHeight = sizeSpec.outHeight,
							cropTop = scaledCropTop,
							cropBottom = scaledCropBottom,
						)

						UpscaleMode.AI -> onnxEngine.upscale(
							source = scaledInputBitmap,
							outWidth = sizeSpec.outWidth,
							outHeight = sizeSpec.outHeight,
							cropTop = scaledCropTop,
							cropBottom = scaledCropBottom,
							onDownloadProgress = { percent ->
								_runtimeStatus.update {
									it.copy(
										stage = UpscaleStage.DOWNLOADING_MODEL,
										downloadPercent = percent.coerceIn(0, 100),
									)
								}
							},
							onInitializing = {
								_runtimeStatus.update {
									it.copy(
										stage = UpscaleStage.INITIALIZING_MODEL,
										downloadPercent = 100,
									)
								}
							},
							onSessionReady = {
								_runtimeStatus.update { current ->
									if (current.stage.isModelBusy()) {
										current.copy(
											stage = if (activeJobsCount.get() > 0) UpscaleStage.PROCESSING else UpscaleStage.IDLE,
											downloadPercent = 100,
										)
									} else {
										current
									}
								}
							},
							shouldPreempt = {
								priorityProducer() >= OFF_SCREEN_PRIORITY_PENALTY &&
									priorityGate.hasWaiterWithPriorityBelow(ON_SCREEN_PRIORITY_THRESHOLD)
							},
						)
					}
				} finally {
					if (!scaledInputBitmap.isRecycled) {
						scaledInputBitmap.recycle()
					}
				}

				if (resultBitmap != null && !resultBitmap.isRecycled) {
					stripCache.putCachedBitmap(cacheKey, resultBitmap)
				}
				resultBitmap
			}
		} catch (ce: CancellationException) {
			throw ce
		} catch (t: Throwable) {
			_runtimeStatus.update {
				it.copy(
					stage = UpscaleStage.ERROR,
					errorMessage = t.message,
				)
			}
			return null
		} finally {
			val remaining = activeJobsCount.updateAndGet { (it - 1).coerceAtLeast(0) }
			_runtimeStatus.update { current ->
				val nextStage = when {
					remaining > 0 && !current.stage.isModelBusy() -> UpscaleStage.PROCESSING
					remaining == 0 && current.stage != UpscaleStage.ERROR -> UpscaleStage.IDLE
					else -> current.stage
				}
				current.copy(activeJobs = remaining, stage = nextStage)
			}
		}
	}

	companion object {
		const val DEFAULT_SPLIT_RATIO = 0.5f
		const val ON_SCREEN_PRIORITY_THRESHOLD = 50_000f
		const val OFF_SCREEN_PRIORITY_PENALTY = 100_000f
	}
}
