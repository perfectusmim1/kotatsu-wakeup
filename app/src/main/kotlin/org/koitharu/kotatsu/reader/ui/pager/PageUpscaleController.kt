package org.koitharu.kotatsu.reader.ui.pager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import android.view.ViewTreeObserver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.davemorrissey.labs.subscaleview.decoder.SkiaImageRegionDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.reader.domain.erase.PageMask
import org.koitharu.kotatsu.reader.domain.upscale.ReaderUpscaleCoordinator
import org.koitharu.kotatsu.reader.domain.upscale.StripPlanner
import org.koitharu.kotatsu.reader.domain.upscale.model.StripPlan
import org.koitharu.kotatsu.reader.domain.upscale.model.UpscaleMode
import org.koitharu.kotatsu.reader.ui.erase.MaskedRegionDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Bir okuyucu sayfası ([SubsamplingScaleImageView]) üzerinde gerçek görünür ekran alanını
 * ([View.getLocalVisibleRect]) takip ederek yalnızca ekranda görünen şeritleri en yüksek öncelikle
 * 2x iyileştirir (Anime4K / Real-ESRGAN) ve [View.getOverlay] katmanında orijinal görüntünün üzerine çizer.
 */
class PageUpscaleController(
	private val ssiv: SubsamplingScaleImageView,
	private val itemView: View,
	private val coordinator: ReaderUpscaleCoordinator,
	private val scope: CoroutineScope,
) : View.OnLayoutChangeListener,
	ViewTreeObserver.OnScrollChangedListener,
	View.OnAttachStateChangeListener {

	private data class SessionKey(
		val uri: Uri,
		val region: Rect?,
		val mask: PageMask?,
		val sWidth: Int,
		val sHeight: Int,
		val mode: UpscaleMode,
		val upscaleHighRes: Boolean,
		val cacheRevision: Long,
	)

	private val overlayDrawable = UpscaleOverlayDrawable()
	private var isOverlayAdded = false
	private var isScrollListenerAdded = false

	private var activeSessionKey: SessionKey? = null
	private var strips: List<StripPlan> = emptyList()
	private val activeStripBitmaps = ConcurrentHashMap<Int, Bitmap>()
	private val finalStripIndices = ConcurrentHashMap.newKeySet<Int>()
	private val inFlightJobs = HashMap<Int, Job>()

	private val decoderMutex = Mutex()
	private var activeDecoder: ImageRegionDecoder? = null
	private var activeDecoderKey: SessionKey? = null

	private val localVisRect = Rect()
	private val visPointA = PointF()
	private val visPointB = PointF()
	private val priorityPointA = PointF()
	private val priorityPointB = PointF()
	private val windowLoc = IntArray(2)
	private var lastCheckedVisY0Bucket = Int.MIN_VALUE
	private var lastCheckedVisY1Bucket = Int.MIN_VALUE

	private var currentUri: Uri? = null
	private var currentRegion: Rect? = null
	private var currentMask: PageMask? = null
	private var currentIsAnimated: Boolean = false
	private var currentIsResumed: Boolean = false

	private var pendingRetryCount = 0
	private var isRetryPosted = false
	private val retryWindowRunnable = Runnable {
		isRetryPosted = false
		if (activeSessionKey != null) {
			updateVisibleWindow()
		} else {
			refreshSession()
		}
	}

	init {
		itemView.addOnAttachStateChangeListener(this)

		coordinator.settingsSnapshot
			.onEach { refreshSession() }
			.launchIn(scope)

		coordinator.compareEnabled
			.onEach { ssiv.invalidate() }
			.launchIn(scope)

		coordinator.compareSplitRatio
			.onEach {
				if (coordinator.compareEnabled.value) {
					ssiv.invalidate()
				}
			}
			.launchIn(scope)

		coordinator.cacheRevision
			.onEach { refreshSession() }
			.launchIn(scope)
	}

	fun bindState(
		displayUri: Uri?,
		region: Rect?,
		mask: PageMask?,
		isAnimated: Boolean,
		isResumed: Boolean,
	) {
		currentUri = displayUri
		currentRegion = region?.let(::Rect)
		currentMask = mask
		currentIsAnimated = isAnimated
		currentIsResumed = isResumed
		pendingRetryCount = 0
		refreshSession()
	}

	fun onViewReady() {
		pendingRetryCount = 0
		refreshSession()
	}

	fun reset() {
		currentUri = null
		currentRegion = null
		currentMask = null
		currentIsAnimated = false
		pendingRetryCount = 0
		cancelWindowRetry()
		stopSession()
	}

	override fun onViewAttachedToWindow(v: View) {
		pendingRetryCount = 0
		refreshSession()
	}

	override fun onViewDetachedFromWindow(v: View) {
		cancelWindowRetry()
		cancelAllStripJobs()
	}

	override fun onLayoutChange(
		v: View,
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		oldLeft: Int,
		oldTop: Int,
		oldRight: Int,
		oldBottom: Int,
	) {
		overlayDrawable.setBounds(0, 0, right - left, bottom - top)
		if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
			refreshSession()
		} else {
			updateVisibleWindow()
		}
	}

	override fun onScrollChanged() {
		if (activeSessionKey != null) {
			updateVisibleWindow()
		} else if (currentUri != null && coordinator.settingsSnapshot.value.mode.isEnabled) {
			refreshSession()
		}
	}

	private fun refreshSession() {
		val uri = currentUri
		val snap = coordinator.settingsSnapshot.value
		if (
			currentIsAnimated ||
			uri == null ||
			!snap.mode.isEnabled
		) {
			stopSession()
			return
		}

		if (!itemView.isAttachedToWindow || !ssiv.isReady || ssiv.sWidth <= 0 || ssiv.sHeight <= 0) {
			scheduleWindowRetry()
			return
		}

		val screenPx = ssiv.width.takeIf { it > 0 } ?: itemView.resources.displayMetrics.widthPixels
		if (StripPlanner.shouldSkipHighResPage(ssiv.sWidth, screenPx, snap.upscaleHighRes)) {
			stopSession()
			return
		}

		val nextKey = SessionKey(
			uri = uri,
			region = currentRegion,
			mask = currentMask,
			sWidth = ssiv.sWidth,
			sHeight = ssiv.sHeight,
			mode = snap.mode,
			upscaleHighRes = snap.upscaleHighRes,
			cacheRevision = coordinator.cacheRevision.value,
		)

		if (activeSessionKey == nextKey) {
			ensureOverlayAttached()
			updateVisibleWindow()
			ssiv.invalidate()
			return
		}

		cancelAllStripJobs()
		activeStripBitmaps.clear()
		finalStripIndices.clear()
		activeSessionKey = nextKey
		strips = StripPlanner.planStripsForMode(nextKey.sHeight, nextKey.mode)
		lastCheckedVisY0Bucket = Int.MIN_VALUE
		lastCheckedVisY1Bucket = Int.MIN_VALUE

		ensureOverlayAttached()
		updateVisibleWindow()
		ssiv.invalidate()
	}

	private fun stopSession() {
		cancelWindowRetry()
		val hadSession = activeSessionKey != null || activeStripBitmaps.isNotEmpty() || inFlightJobs.isNotEmpty()
		activeSessionKey = null
		strips = emptyList()
		cancelAllStripJobs()
		activeStripBitmaps.clear()
		finalStripIndices.clear()
		lastCheckedVisY0Bucket = Int.MIN_VALUE
		lastCheckedVisY1Bucket = Int.MIN_VALUE
		detachOverlay()
		closeDecoderAsync()
		if (hadSession) {
			ssiv.invalidate()
		}
	}

	private fun scheduleWindowRetry() {
		if (isRetryPosted || pendingRetryCount >= MAX_WINDOW_RETRIES) return
		if (currentUri == null || !coordinator.settingsSnapshot.value.mode.isEnabled) return
		isRetryPosted = true
		pendingRetryCount++
		ssiv.postOnAnimation(retryWindowRunnable)
	}

	private fun cancelWindowRetry() {
		if (isRetryPosted) {
			ssiv.removeCallbacks(retryWindowRunnable)
			isRetryPosted = false
		}
	}

	private fun cancelAllStripJobs() {
		for (job in inFlightJobs.values) {
			job.cancel()
		}
		inFlightJobs.clear()
	}

	private fun ensureOverlayAttached() {
		if (!isOverlayAdded) {
			isOverlayAdded = true
			overlayDrawable.setBounds(0, 0, ssiv.width, ssiv.height)
			ssiv.addOnLayoutChangeListener(this)
			ssiv.overlay.add(overlayDrawable)
		}
		if (!isScrollListenerAdded && itemView.viewTreeObserver.isAlive) {
			isScrollListenerAdded = true
			itemView.viewTreeObserver.addOnScrollChangedListener(this)
		}
	}

	private fun detachOverlay() {
		if (isOverlayAdded) {
			isOverlayAdded = false
			ssiv.removeOnLayoutChangeListener(this)
			ssiv.overlay.remove(overlayDrawable)
		}
		if (isScrollListenerAdded && itemView.viewTreeObserver.isAlive) {
			isScrollListenerAdded = false
			itemView.viewTreeObserver.removeOnScrollChangedListener(this)
		}
	}

	/**
	 * Sayfanın gerçekte ekranda görünen kısmının kaynak görüntü Y aralığını (`visY0..visY1`) döndürür.
	 * Sayfa henüz ölçümlenmemişse frame-retry planlar; sayfa ekran dışındaysa (ViewPager2 yan sayfası
	 * veya WebtoonRecyclerView ekran dışı prefetch sayfası) `null` döndürür.
	 */
	private fun computeVisibleSourceRange(): Pair<Float, Float>? {
		val key = activeSessionKey ?: return null
		if (!itemView.isAttachedToWindow || !ssiv.isAttachedToWindow || !ssiv.isReady) {
			return null
		}
		if (ssiv.width <= 0 || ssiv.height <= 0) {
			scheduleWindowRetry()
			return null
		}

		if (!ssiv.getLocalVisibleRect(localVisRect)) {
			// Sayfa şu an ekranın tamamen dışında (yan sayfa veya aşağı/yukarı prefetch)
			return null
		}

		val visLeft = localVisRect.left.coerceIn(0, ssiv.width)
		val visRight = localVisRect.right.coerceIn(0, ssiv.width)
		val visViewTop = localVisRect.top.coerceIn(0, ssiv.height)
		val visViewBottom = localVisRect.bottom.coerceIn(0, ssiv.height)
		if (visRight <= visLeft || visViewBottom <= visViewTop) {
			return null
		}

		val pTop = ssiv.viewToSourceCoord(visLeft.toFloat(), visViewTop.toFloat(), visPointA)
		val pBot = ssiv.viewToSourceCoord(visRight.toFloat(), visViewBottom.toFloat(), visPointB)
		if (pTop == null || pBot == null) {
			scheduleWindowRetry()
			return null
		}

		pendingRetryCount = 0
		val rawY0 = min(pTop.y, pBot.y)
		val rawY1 = max(pTop.y, pBot.y)
		val visY0 = rawY0.coerceIn(0f, key.sHeight.toFloat())
		val visY1 = rawY1.coerceIn(visY0, key.sHeight.toFloat())
		if (visY1 <= visY0) {
			scheduleWindowRetry()
			return null
		}
		return visY0 to visY1
	}

	private fun updateVisibleWindow() {
		val key = activeSessionKey ?: return
		val currentStrips = strips
		if (currentStrips.isEmpty()) return

		val range = computeVisibleSourceRange()
		if (range == null) {
			// Sayfa ekran dışındaysa bekleyen/çalışan şerit işlerini iptal et ki ekrandaki sayfayı bekletmesin
			if (ssiv.width > 0 && ssiv.height > 0 && !ssiv.getLocalVisibleRect(localVisRect)) {
				lastCheckedVisY0Bucket = Int.MIN_VALUE
				lastCheckedVisY1Bucket = Int.MIN_VALUE
				if (inFlightJobs.isNotEmpty()) {
					cancelAllStripJobs()
				}
			}
			return
		}

		val (visY0, visY1) = range
		lastCheckedVisY0Bucket = visY0.toInt() / VIEWPORT_BUCKET_PX
		lastCheckedVisY1Bucket = visY1.toInt() / VIEWPORT_BUCKET_PX

		val isAiMode = key.mode == UpscaleMode.AI
		val keepY0 = (visY0 - EVICT_BEHIND_SOURCE_PX).coerceAtLeast(0f)
		val keepY1 = (visY1 + EVICT_AHEAD_SOURCE_PX).coerceAtMost(key.sHeight.toFloat())

		// 1. Önce pencere dışına çıkmış şeritleri iptal et ve bellekten temizle
		var evictedAny = false
		for (strip in currentStrips) {
			if (strip.validY1 < keepY0 || strip.validY0 > keepY1) {
				inFlightJobs.remove(strip.index)?.cancel()
				finalStripIndices.remove(strip.index)
				if (activeStripBitmaps.remove(strip.index) != null) {
					evictedAny = true
				}
			}
		}

		// 2. Doğrudan ekranda görünen şeritleri (on-screen) her zaman ilk sıraya koy
		var allVisibleReady = true
		val visibleStripsToSchedule = ArrayList<StripPlan>(4)
		for (strip in currentStrips) {
			val isOnScreen = strip.validY1 > visY0 && strip.validY0 < visY1
			if (!isOnScreen) continue
			val isFinalReady = finalStripIndices.contains(strip.index) && activeStripBitmaps.containsKey(strip.index)
			if (!isFinalReady) {
				allVisibleReady = false
				if (inFlightJobs[strip.index]?.isActive != true) {
					visibleStripsToSchedule.add(strip)
				}
			}
		}

		// Ekrandaki şeritleri ekran merkezine en yakın olandan başlayarak kuyruğa sok
		if (visibleStripsToSchedule.isNotEmpty()) {
			val visCenter = (visY0 + visY1) * 0.5f
			visibleStripsToSchedule.sortBy { abs(((it.validY0 + it.validY1) * 0.5f) - visCenter) }
			for (strip in visibleStripsToSchedule) {
				scheduleStripJob(key, strip, isInitiallyOnScreen = true)
			}
		}

		// 3. İleri-okuma (lookahead): AI modunda yalnızca ekrandaki tüm görünür şeritler %100 bittikten sonra
		// hemen alttaki 1 şeridi hazırla; Hızlı (Anime4K) modda doğrudan lookahead penceresini hazırla.
		if (!isAiMode || allVisibleReady) {
			val lookbehindPx = if (isAiMode) 0f else LOOKBEHIND_FAST_SOURCE_PX
			val lookaheadPx = if (isAiMode) LOOKAHEAD_AI_SOURCE_PX else LOOKAHEAD_FAST_SOURCE_PX
			val windowY0 = (visY0 - lookbehindPx).coerceAtLeast(0f)
			val windowY1 = (visY1 + lookaheadPx).coerceAtMost(key.sHeight.toFloat())

			for (strip in currentStrips) {
				if (strip.validY1 >= windowY0 && strip.validY0 <= windowY1) {
					val isFinalReady = finalStripIndices.contains(strip.index) && activeStripBitmaps.containsKey(strip.index)
					if (!isFinalReady && inFlightJobs[strip.index]?.isActive != true) {
						val isOnScreen = strip.validY1 > visY0 && strip.validY0 < visY1
						scheduleStripJob(key, strip, isInitiallyOnScreen = isOnScreen)
					}
				} else if (isAiMode && !(strip.validY1 > visY0 && strip.validY0 < visY1)) {
					// AI modunda lookahead penceresinin dışına kalan aktif iş varsa hemen iptal et
					inFlightJobs.remove(strip.index)?.cancel()
				}
			}
		} else {
			// AI modunda ekranda hâlâ bitmemiş görünür şerit varken ekran dışı (lookahead) çalışan işleri iptal et
			for (strip in currentStrips) {
				val isOnScreen = strip.validY1 > visY0 && strip.validY0 < visY1
				if (!isOnScreen) {
					inFlightJobs.remove(strip.index)?.cancel()
				}
			}
		}

		if (evictedAny) {
			ssiv.invalidate()
		}
	}

	/**
	 * Şeridin gerçek ekran (pencere) koordinatlarındaki önceliğini hesaplar:
	 * - Ekranda görünen şeritler: ekranın dikey merkezine olan piksel uzaklığı (`0 .. ~3000f`).
	 * - Ekran dışı (lookahead) şeritler: `100_000f + ekrana uzaklık`.
	 * - Sayfa ekran dışına çıktıysa: `Float.MAX_VALUE`.
	 */
	private fun computeStripScreenPriority(key: SessionKey, strip: StripPlan): Float {
		if (activeSessionKey != key || !itemView.isAttachedToWindow || !ssiv.isReady || ssiv.width <= 0 || ssiv.height <= 0) {
			return Float.MAX_VALUE
		}
		if (!ssiv.getLocalVisibleRect(localVisRect)) {
			return Float.MAX_VALUE
		}
		val visTop = localVisRect.top.coerceIn(0, ssiv.height).toFloat()
		val visBottom = localVisRect.bottom.coerceIn(0, ssiv.height).toFloat()
		if (visBottom <= visTop) {
			return Float.MAX_VALUE
		}

		val sTop = ssiv.sourceToViewCoord(0f, strip.validY0.toFloat(), priorityPointA)?.y ?: return Float.MAX_VALUE
		val sBot = ssiv.sourceToViewCoord(key.sWidth.toFloat(), strip.validY1.toFloat(), priorityPointB)?.y ?: return Float.MAX_VALUE
		val stripViewY0 = min(sTop, sBot)
		val stripViewY1 = max(sTop, sBot)

		val isStripOnScreen = stripViewY1 > visTop && stripViewY0 < visBottom
		if (isStripOnScreen) {
			ssiv.getLocationInWindow(windowLoc)
			val screenHeight = itemView.resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
			val screenCenterY = screenHeight * 0.5f
			val clampedViewY0 = stripViewY0.coerceIn(visTop, visBottom)
			val clampedViewY1 = stripViewY1.coerceIn(clampedViewY0, visBottom)
			val stripScreenCenterY = windowLoc[1].toFloat() + (clampedViewY0 + clampedViewY1) * 0.5f
			return abs(stripScreenCenterY - screenCenterY).coerceAtMost(ReaderUpscaleCoordinator.ON_SCREEN_PRIORITY_THRESHOLD - 1f)
		}

		val distToVisible = if (stripViewY0 >= visBottom) {
			stripViewY0 - visBottom
		} else {
			visTop - stripViewY1
		}
		return ReaderUpscaleCoordinator.OFF_SCREEN_PRIORITY_PENALTY + distToVisible.coerceAtLeast(0f)
	}

	private fun scheduleStripJob(
		key: SessionKey,
		strip: StripPlan,
		isInitiallyOnScreen: Boolean,
	) {
		val pageIdentity = buildString {
			append(key.uri.toString())
			key.region?.let { r ->
				append("|r=").append(r.left).append(',').append(r.top).append(',').append(r.right).append(',').append(r.bottom)
			}
			key.mask?.let { m ->
				append("|m=").append(m.hashCode())
			}
		}

		val job = scope.launch {
			var previewJob: Job? = null
			try {
				if (key.mode == UpscaleMode.AI) {
					val cachedAi = coordinator.getCachedStripIfPresent(
						pageIdentity = pageIdentity,
						strip = strip,
						mode = UpscaleMode.AI,
						srcWidth = key.sWidth,
					)
					if (cachedAi != null && !cachedAi.isRecycled) {
						if (activeSessionKey == key) {
							activeStripBitmaps[strip.index] = cachedAi
							finalStripIndices.add(strip.index)
							ssiv.invalidate()
						}
						return@launch
					}

					// Ekranda görünen şerit için AI çıkarımı beklenirken ~25ms içinde anında Anime4K keskin önizleme göster
					if (isInitiallyOnScreen && !activeStripBitmaps.containsKey(strip.index)) {
						previewJob = launch {
							val previewBmp = coordinator.renderFastPreviewStrip(
								pageIdentity = pageIdentity,
								strip = strip,
								srcWidth = key.sWidth,
								decodeSourceStrip = { decodeStripRegion(key, strip) },
							)
							if (
								activeSessionKey == key &&
								previewBmp != null &&
								!previewBmp.isRecycled &&
								!finalStripIndices.contains(strip.index)
							) {
								activeStripBitmaps[strip.index] = previewBmp
								ssiv.invalidate()
							}
						}
					}
				}

				val bitmap = coordinator.upscaleStrip(
					pageIdentity = pageIdentity,
					strip = strip,
					mode = key.mode,
					srcWidth = key.sWidth,
					priorityProducer = { computeStripScreenPriority(key, strip) },
					decodeSourceStrip = { decodeStripRegion(key, strip) },
				)
				previewJob?.cancel()
				if (activeSessionKey == key && bitmap != null && !bitmap.isRecycled) {
					activeStripBitmaps[strip.index] = bitmap
					finalStripIndices.add(strip.index)
					ssiv.invalidate()
				}
			} catch (ce: CancellationException) {
				previewJob?.cancel()
				throw ce
			} finally {
				previewJob?.cancel()
				if (activeSessionKey == key) {
					inFlightJobs.remove(strip.index)
					if (key.mode == UpscaleMode.AI && finalStripIndices.contains(strip.index)) {
						updateVisibleWindow()
					}
				}
			}
		}
		inFlightJobs[strip.index] = job
	}

	private suspend fun decodeStripRegion(
		key: SessionKey,
		strip: StripPlan,
	): Bitmap? = withContext(Dispatchers.IO) {
		decoderMutex.withLock {
			if (activeSessionKey != key) return@withLock null
			val decoder = getOrInitDecoderLocked(key) ?: return@withLock null
			val offsetX = key.region?.left ?: 0
			val offsetY = key.region?.top ?: 0
			val srcRect = Rect(
				offsetX,
				offsetY + strip.y0,
				offsetX + key.sWidth,
				offsetY + strip.y1,
			)
			runCatching {
				decoder.decodeRegion(srcRect, 1)
			}.getOrNull()
		}
	}

	private fun getOrInitDecoderLocked(key: SessionKey): ImageRegionDecoder? {
		val existing = activeDecoder
		if (existing != null && existing.isReady && activeDecoderKey == key) {
			return existing
		}
		runCatching { existing?.recycle() }
		activeDecoder = null
		activeDecoderKey = null

		return runCatching {
			val base: ImageRegionDecoder = SkiaImageRegionDecoder(Bitmap.Config.ARGB_8888)
			val wrapped: ImageRegionDecoder = if (key.mask != null) {
				MaskedRegionDecoder(base, key.mask)
			} else {
				base
			}
			wrapped.init(ssiv.context, ImageSource.uri(key.uri))
			activeDecoder = wrapped
			activeDecoderKey = key
			wrapped
		}.getOrNull()
	}

	private fun closeDecoderAsync() {
		scope.launch(Dispatchers.IO) {
			decoderMutex.withLock {
				if (activeSessionKey == null) {
					runCatching { activeDecoder?.recycle() }
					activeDecoder = null
					activeDecoderKey = null
				}
			}
		}
	}

	private inner class UpscaleOverlayDrawable : Drawable() {

		private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
		private val drawTopLeft = PointF()
		private val drawBottomRight = PointF()
		private val stripTopLeft = PointF()
		private val stripBottomRight = PointF()
		private val dstRect = RectF()
		private val drawWindowLoc = IntArray(2)

		override fun draw(canvas: Canvas) {
			val key = activeSessionKey ?: return
			if (!ssiv.isReady || ssiv.sWidth != key.sWidth || ssiv.sHeight != key.sHeight) {
				return
			}

			val range = computeVisibleSourceRange()
			if (range != null) {
				val b0 = range.first.toInt() / VIEWPORT_BUCKET_PX
				val b1 = range.second.toInt() / VIEWPORT_BUCKET_PX
				if (b0 != lastCheckedVisY0Bucket || b1 != lastCheckedVisY1Bucket ||
					(activeStripBitmaps.isEmpty() && inFlightJobs.isEmpty())
				) {
					updateVisibleWindow()
				}
			}

			if (activeStripBitmaps.isEmpty()) {
				return
			}

			val imgTopLeft = ssiv.sourceToViewCoord(0f, 0f, drawTopLeft) ?: return
			val imgBottomRight = ssiv.sourceToViewCoord(
				key.sWidth.toFloat(),
				key.sHeight.toFloat(),
				drawBottomRight,
			) ?: return
			if (imgBottomRight.x <= imgTopLeft.x || imgBottomRight.y <= imgTopLeft.y) {
				return
			}

			val checkpoint = canvas.save()
			var clipLeft = imgTopLeft.x.coerceAtLeast(0f)
			val clipTop = imgTopLeft.y.coerceAtLeast(0f)
			val clipRight = imgBottomRight.x.coerceAtMost(ssiv.width.toFloat())
			val clipBottom = imgBottomRight.y.coerceAtMost(ssiv.height.toFloat())

			if (coordinator.compareEnabled.value) {
				val ratio = coordinator.compareSplitRatio.value
				val screenW = itemView.resources.displayMetrics.widthPixels
				val splitX = if (screenW > 0 && ssiv.width < screenW * 0.75f) {
					ssiv.getLocationInWindow(drawWindowLoc)
					screenW.toFloat() * ratio - drawWindowLoc[0].toFloat()
				} else {
					ssiv.width.toFloat() * ratio
				}
				clipLeft = max(clipLeft, splitX)
			}

			if (clipRight <= clipLeft || clipBottom <= clipTop) {
				canvas.restoreToCount(checkpoint)
				return
			}
			canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)

			bitmapPaint.colorFilter = ssiv.colorFilter

			val currentStrips = strips
			val lastIdx = currentStrips.lastIndex
			for (strip in currentStrips) {
				val bmp = activeStripBitmaps[strip.index] ?: continue
				if (bmp.isRecycled) continue

				val sTop = ssiv.sourceToViewCoord(0f, strip.validY0.toFloat(), stripTopLeft) ?: continue
				val sBot = ssiv.sourceToViewCoord(key.sWidth.toFloat(), strip.validY1.toFloat(), stripBottomRight) ?: continue
				if (sBot.y <= clipTop || sTop.y >= clipBottom) continue

				val topY = if (strip.index == 0) sTop.y else floor(sTop.y)
				val botY = if (strip.index == lastIdx) sBot.y else ceil(sBot.y)
				dstRect.set(imgTopLeft.x, topY, imgBottomRight.x, botY)
				canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)
			}

			canvas.restoreToCount(checkpoint)
		}

		override fun setAlpha(alpha: Int) = Unit

		override fun setColorFilter(colorFilter: ColorFilter?) = Unit

		@Deprecated("Deprecated in Java")
		override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
	}

	private companion object {
		const val MAX_WINDOW_RETRIES = 12
		const val VIEWPORT_BUCKET_PX = 64
		const val LOOKBEHIND_FAST_SOURCE_PX = 512f
		const val LOOKAHEAD_FAST_SOURCE_PX = 2048f
		const val LOOKAHEAD_AI_SOURCE_PX = 320f
		const val EVICT_BEHIND_SOURCE_PX = 2048f
		const val EVICT_AHEAD_SOURCE_PX = 3072f
	}
}
