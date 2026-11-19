package org.koitharu.kotatsu.reader.ui.upscale

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.view.isVisible
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.resolveDp
import org.koitharu.kotatsu.core.util.ext.resolveSp
import java.util.Collections
import kotlin.math.abs

/**
 * Okuyucu üzerinde Öncesi / Sonrası (Orijinal | İyileştirilmiş) karşılaştırma sürgüsünü çizer.
 * Yalnızca dikey ayırıcı çizgi veya orta tutamaç üzerinden başlayan dokunuşları yakalar;
 * ekranın geri kalanındaki dikey/yatay kaydırma ve yakınlaştırma hareketlerine müdahale etmez.
 */
class UpscaleCompareOverlayView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

	var splitRatio: Float = 0.5f
		set(value) {
			val clamped = value.coerceIn(0.02f, 0.98f)
			if (field != clamped) {
				field = clamped
				updateGestureExclusion()
				invalidate()
			}
		}

	var topInsetPx: Int = 0
		set(value) {
			if (field != value) {
				field = value
				invalidate()
			}
		}

	var onSplitRatioChanged: ((Float) -> Unit)? = null

	private var isDragging = false

	private val lineTouchTargetHalfPx = context.resources.resolveDp(36f)
	private val handleRadiusPx = context.resources.resolveDp(18f)
	private val handleTouchRadiusPx = context.resources.resolveDp(44f)
	private val exclusionRect = Rect()
	private val badgeTopOffsetPx = context.resources.resolveDp(52f)
	private val badgeSideMarginPx = context.resources.resolveDp(14f)
	private val badgePadHPx = context.resources.resolveDp(10f)
	private val badgePadVPx = context.resources.resolveDp(5f)
	private val badgeCornerPx = context.resources.resolveDp(14f)

	private val lineShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = context.resources.resolveDp(4f)
		color = Color.argb(140, 0, 0, 0)
	}

	private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = context.resources.resolveDp(2f)
		color = Color.argb(235, 255, 255, 255)
	}

	private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		color = Color.argb(225, 22, 24, 30)
	}

	private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = context.resources.resolveDp(1.8f)
		color = Color.argb(235, 255, 255, 255)
	}

	private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = context.resources.resolveDp(1.8f)
		strokeCap = Paint.Cap.ROUND
		strokeJoin = Paint.Join.ROUND
		color = Color.WHITE
	}

	private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		color = Color.argb(205, 18, 20, 26)
	}

	private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = context.resources.resolveDp(1f)
		color = Color.argb(90, 255, 255, 255)
	}

	private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		textSize = context.resources.resolveSp(11.5f)
		typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
		color = Color.WHITE
	}

	private val arrowPath = Path()
	private val badgeRect = RectF()

	private val originalLabel: String
		get() = context.getString(R.string.upscale_compare_original)

	private val enhancedLabel: String
		get() = context.getString(R.string.upscale_compare_enhanced)

	fun onInterceptOrDispatchTouch(ev: MotionEvent): Boolean {
		if (!isVisible || width <= 0 || height <= 0) {
			isDragging = false
			return false
		}
		val localX = ev.x
		val localY = ev.y
		val splitX = width * splitRatio
		val handleY = height * 0.5f

		when (ev.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				val dx = abs(localX - splitX)
				val dy = abs(localY - handleY)
				val onHandle = dx <= handleTouchRadiusPx && dy <= handleTouchRadiusPx
				val onLine = dx <= lineTouchTargetHalfPx
				if (onHandle || onLine) {
					isDragging = true
					parent?.requestDisallowInterceptTouchEvent(true)
					updateSplitFromX(localX)
					return true
				}
				return false
			}

			MotionEvent.ACTION_MOVE -> {
				if (!isDragging) return false
				updateSplitFromX(localX)
				return true
			}

			MotionEvent.ACTION_UP,
			MotionEvent.ACTION_CANCEL -> {
				if (!isDragging) return false
				isDragging = false
				parent?.requestDisallowInterceptTouchEvent(false)
				return true
			}
		}
		return isDragging
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		return onInterceptOrDispatchTouch(event)
	}

	private fun updateSplitFromX(x: Float) {
		if (width <= 0) return
		val next = (x / width.toFloat()).coerceIn(0.02f, 0.98f)
		splitRatio = next
		onSplitRatioChanged?.invoke(next)
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		updateGestureExclusion()
	}

	private fun updateGestureExclusion() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
		if (!isVisible || width <= 0 || height <= 0) {
			systemGestureExclusionRects = emptyList()
			return
		}
		val splitX = (width * splitRatio).toInt()
		val centerY = height / 2
		val halfW = handleTouchRadiusPx.toInt()
		val halfH = (handleTouchRadiusPx * 2f).toInt()
		exclusionRect.set(
			(splitX - halfW).coerceAtLeast(0),
			(centerY - halfH).coerceAtLeast(0),
			(splitX + halfW).coerceAtMost(width),
			(centerY + halfH).coerceAtMost(height),
		)
		systemGestureExclusionRects = Collections.singletonList(exclusionRect)
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		if (width <= 0 || height <= 0) return

		val splitX = width * splitRatio
		val centerY = height * 0.5f

		// Dikey ayırıcı çizgi
		canvas.drawLine(splitX, 0f, splitX, height.toFloat(), lineShadowPaint)
		canvas.drawLine(splitX, 0f, splitX, height.toFloat(), linePaint)

		// Orta yuvarlak tutamaç
		canvas.drawCircle(splitX, centerY, handleRadiusPx, handleFillPaint)
		canvas.drawCircle(splitX, centerY, handleRadiusPx, handleStrokePaint)

		// Sol-sağ ok ikonu (< >)
		val arrowOffset = handleRadiusPx * 0.44f
		val arrowWing = handleRadiusPx * 0.24f
		arrowPath.reset()
		// Sol ok
		arrowPath.moveTo(splitX - arrowOffset + arrowWing, centerY - arrowWing)
		arrowPath.lineTo(splitX - arrowOffset, centerY)
		arrowPath.lineTo(splitX - arrowOffset + arrowWing, centerY + arrowWing)
		// Sağ ok
		arrowPath.moveTo(splitX + arrowOffset - arrowWing, centerY - arrowWing)
		arrowPath.lineTo(splitX + arrowOffset, centerY)
		arrowPath.lineTo(splitX + arrowOffset - arrowWing, centerY + arrowWing)
		canvas.drawPath(arrowPath, arrowPaint)

		// Üst etiket rozetleri: Orijinal | İyileştirilmiş
		val badgeTop = topInsetPx + badgeTopOffsetPx
		drawBadge(canvas, originalLabel, isLeft = true, topY = badgeTop)
		drawBadge(canvas, enhancedLabel, isLeft = false, topY = badgeTop)
	}

	private fun drawBadge(canvas: Canvas, text: String, isLeft: Boolean, topY: Float) {
		val textW = badgeTextPaint.measureText(text)
		val fm = badgeTextPaint.fontMetrics
		val textH = fm.descent - fm.ascent
		val boxW = textW + badgePadHPx * 2f
		val boxH = textH + badgePadVPx * 2f

		val left = if (isLeft) {
			badgeSideMarginPx
		} else {
			width - badgeSideMarginPx - boxW
		}
		badgeRect.set(left, topY, left + boxW, topY + boxH)
		canvas.drawRoundRect(badgeRect, badgeCornerPx, badgeCornerPx, badgeBgPaint)
		canvas.drawRoundRect(badgeRect, badgeCornerPx, badgeCornerPx, badgeBorderPaint)
		val baseline = topY + badgePadVPx - fm.ascent
		canvas.drawText(text, left + badgePadHPx, baseline, badgeTextPaint)
	}
}
