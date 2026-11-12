package org.koitharu.kotatsu.reader.domain.translate.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.reader.domain.translate.model.OcrBubbleGroup
import org.koitharu.kotatsu.reader.domain.translate.model.TextStyleFingerprint
import org.koitharu.kotatsu.reader.domain.translate.model.TranslatedPageRenderer
import org.koitharu.kotatsu.reader.domain.translate.model.TranslatedTextItem

@Singleton
class BitmapTranslationBaker @Inject constructor(
	private val bubbleInpainter: BubbleInpainter,
	private val smartTextLayout: SmartTextLayout,
) : TranslatedPageRenderer {

	override suspend fun renderTranslatedPage(
		sourceBitmap: Bitmap,
		bubbles: List<OcrBubbleGroup>,
		translations: List<TranslatedTextItem>,
		outputFile: File,
		targetLang: String,
	): File = withContext(Dispatchers.Default) {
		require(!sourceBitmap.isRecycled && sourceBitmap.width > 0 && sourceBitmap.height > 0) {
			"Cannot render translated page from invalid or recycled bitmap."
		}

		val workBitmap = createMutableArgbCopy(sourceBitmap)
		try {
			val translationById = translations.associateBy { it.id }
			val activeBlocks = bubbles
				.mapNotNull { bubble ->
					if (!bubble.box.isValid()) return@mapNotNull null
					val item = translationById[bubble.id] ?: return@mapNotNull null
					if (item.isTrash || item.text.isBlank()) return@mapNotNull null
					bubble to item
				}
				.sortedWith(compareBy({ it.first.box.yMin }, { it.first.box.xMin }))

			val refinedLayouts = ArrayList<RefinedBlockLayout>(activeBlocks.size)
			for ((bubble, item) in activeBlocks) {
				coroutineContext.ensureActive()
				val layout = bubbleInpainter.inpaintAndRefineBlock(
					workBitmap = workBitmap,
					bubble = bubble,
					translatedText = item.text,
					explicitKind = item.kind,
					targetLang = targetLang,
				)
				refinedLayouts.add(layout)
			}

			if (refinedLayouts.isNotEmpty()) {
				val canvas = Canvas(workBitmap)
				val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
				val imgW = workBitmap.width
				val imgH = workBitmap.height

				for (layout in refinedLayouts) {
					coroutineContext.ensureActive()
					drawLayoutBlock(canvas, paint, layout, imgW, imgH)
				}
			}

			coroutineContext.ensureActive()
			writeBitmapAtomically(workBitmap, outputFile)
		} finally {
			if (workBitmap !== sourceBitmap && !workBitmap.isRecycled) {
				workBitmap.recycle()
			}
		}
	}

	private fun createMutableArgbCopy(source: Bitmap): Bitmap {
		val copied = runCatching { source.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull()
		if (copied != null) return copied
		val fresh = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
		Canvas(fresh).drawBitmap(source, 0f, 0f, null)
		return fresh
	}

	private fun drawLayoutBlock(
		canvas: Canvas,
		paint: TextPaint,
		layout: RefinedBlockLayout,
		imgW: Int,
		imgH: Int,
	) {
		val lines = layout.wrappedLines
		if (lines.isEmpty()) return

		val style = layout.style
		val rect = layout.refinedBox.toRectF(imgW, imgH)
		val fontPx = layout.fontPx.coerceAtLeast(6f)
		smartTextLayout.configureTextPaint(paint, style, fontPx, layout.translatedText)

		val lineHeightPx = fontPx * layout.lineHeightRatio
		val fm = paint.fontMetrics
		val midLineOffset = -(fm.ascent + fm.descent) * 0.5f

		val anchorX: Float
		if (layout.isSingleLineStat && rect.width() > fontPx * 3f) {
			paint.textAlign = Paint.Align.LEFT
			anchorX = rect.left + max(4f, rect.width() * 0.04f)
		} else {
			paint.textAlign = Paint.Align.CENTER
			anchorX = rect.centerX()
		}

		val blockSaved = abs(style.rotationDeg) >= 0.5f
		if (blockSaved) {
			canvas.save()
			canvas.rotate(style.rotationDeg, rect.centerX(), rect.centerY())
		}

		try {
			val midIndex = (lines.size - 1) * 0.5f
			for (idx in lines.indices) {
				val line = lines[idx]
				val extraY = if (line.scale > 1.05f) {
					(line.scale - 1f) * lineHeightPx * 0.35f
				} else {
					0f
				}
				val lineCenterY = rect.centerY() + (idx - midIndex) * lineHeightPx + extraY
				val baselineY = lineCenterY + midLineOffset

				val lineScaled = abs(line.scale - 1f) > 0.01f
				if (lineScaled) {
					canvas.save()
					val scaleY = if (line.scale < 1f) line.scale.coerceAtLeast(0.84f) else line.scale
					canvas.scale(line.scale, scaleY, anchorX, lineCenterY)
				}

				try {
					drawStyledLine(
						canvas = canvas,
						paint = paint,
						lineText = line.text,
						anchorX = anchorX,
						baselineY = baselineY,
						fontPx = fontPx,
						ascent = fm.ascent,
						descent = fm.descent,
						style = style,
					)
				} finally {
					if (lineScaled) {
						canvas.restore()
					}
				}
			}
		} finally {
			if (blockSaved) {
				canvas.restore()
			}
		}
	}

	private fun drawStyledLine(
		canvas: Canvas,
		paint: TextPaint,
		lineText: String,
		anchorX: Float,
		baselineY: Float,
		fontPx: Float,
		ascent: Float,
		descent: Float,
		style: TextStyleFingerprint,
	) {
		val shadowColor = style.shadowColor
		if (shadowColor != null && style.shadowBlurEm > 0f) {
			paint.shader = null
			paint.style = Paint.Style.FILL
			paint.color = style.textColor
			paint.setShadowLayer((style.shadowBlurEm * fontPx).coerceIn(1.5f, 24f), 0f, 0f, shadowColor)
			canvas.drawText(lineText, anchorX, baselineY, paint)
			paint.clearShadowLayer()
		}

		if (style.hasTextStroke || style.strokeWidthEm > 0f) {
			val outerStrokeEm = if (style.strokeWidthEm > 0f) style.strokeWidthEm else 0.10f
			val strokeWidthPx = (outerStrokeEm * fontPx * 2f).coerceIn(1.5f, fontPx * 0.44f)
			paint.shader = null
			paint.style = Paint.Style.STROKE
			paint.strokeJoin = Paint.Join.ROUND
			paint.strokeCap = Paint.Cap.ROUND
			paint.strokeMiter = 2f
			paint.strokeWidth = strokeWidthPx
			paint.color = style.strokeColor
			canvas.drawText(lineText, anchorX, baselineY, paint)
		}

		paint.style = Paint.Style.FILL
		paint.strokeWidth = 0f
		val topGrad = style.gradientTopColor
		val botGrad = style.gradientBottomColor
		if (topGrad != null && botGrad != null) {
			paint.shader = LinearGradient(
				0f,
				baselineY + ascent,
				0f,
				baselineY + descent,
				topGrad,
				botGrad,
				Shader.TileMode.CLAMP,
			)
		} else {
			paint.shader = null
			paint.color = style.textColor
		}
		canvas.drawText(lineText, anchorX, baselineY, paint)
		paint.shader = null
	}

	private suspend fun writeBitmapAtomically(bitmap: Bitmap, outputFile: File): File = withContext(Dispatchers.IO) {
		outputFile.parentFile?.mkdirs()
		val tmpFile = File("${outputFile.path}.tmp.${System.nanoTime()}")
		try {
			val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				Bitmap.CompressFormat.WEBP_LOSSY
			} else {
				@Suppress("DEPRECATION")
				Bitmap.CompressFormat.WEBP
			}
			FileOutputStream(tmpFile).buffered().use { out ->
				check(bitmap.compress(format, WEBP_QUALITY, out)) {
					"Failed to encode translated WebP bitmap."
				}
				out.flush()
			}
			if (outputFile.exists()) {
				outputFile.delete()
			}
			if (!tmpFile.renameTo(outputFile)) {
				tmpFile.copyTo(outputFile, overwrite = true)
				tmpFile.delete()
			}
			outputFile
		} catch (t: Throwable) {
			tmpFile.delete()
			throw t
		}
	}

	private companion object {
		const val WEBP_QUALITY = 92
	}
}
