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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.reader.domain.translate.model.NormalizedBox
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
				.mapIndexedNotNull { idx, bubble ->
					if (!bubble.box.isValid()) return@mapIndexedNotNull null
					val item = translationById[bubble.id] ?: return@mapIndexedNotNull null
					val trimmed = item.text.trim()
					val isSeamErase = trimmed.equals("[SEAM_ERASE]", ignoreCase = true)
					if (!isSeamErase && (item.isTrash || trimmed.isBlank() || trimmed.equals("[SFX]", ignoreCase = true))) {
						return@mapIndexedNotNull null
					}
					Triple(idx, bubble, item to isSeamErase)
				}
				.sortedWith(compareBy({ it.second.box.yMin }, { it.second.box.xMin }))

			val activeSiblingBoxesByIdx = HashMap<Int, List<NormalizedBox>>(activeBlocks.size)
			for ((idx, bubble, _) in activeBlocks) {
				val lineBoxes = bubble.lines.mapNotNull { line -> line.box.takeIf { it.isValid() } }
				activeSiblingBoxesByIdx[idx] = lineBoxes.ifEmpty { listOf(bubble.box) }
			}

			val refinedLayouts = ArrayList<RefinedBlockLayout>(activeBlocks.size)
			for ((bubbleIdx, bubble, itemPair) in activeBlocks) {
				coroutineContext.ensureActive()
				val (item, isSeamErase) = itemPair
				val siblingBoxes = ArrayList<NormalizedBox>(activeBlocks.size * 2)
				for ((otherIdx, otherBoxes) in activeSiblingBoxesByIdx) {
					if (otherIdx != bubbleIdx) {
						siblingBoxes.addAll(otherBoxes)
					}
				}
				val layout = bubbleInpainter.inpaintAndRefineBlock(
					workBitmap = workBitmap,
					bubble = bubble,
					translatedText = item.text,
					explicitKind = item.kind,
					targetLang = targetLang,
					siblingBoxes = siblingBoxes,
					seamEraseOnly = isSeamErase,
				)
				if (layout != null) {
					refinedLayouts.add(layout)
				}
			}

			if (refinedLayouts.isNotEmpty()) {
				val canvas = Canvas(workBitmap)
				val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
				val imgW = workBitmap.width
				val imgH = workBitmap.height

				for (layout in refinedLayouts) {
					coroutineContext.ensureActive()
					if (layout.isSeamEraseOnly || layout.wrappedLines.isEmpty()) continue
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
		val baseFontPx = layout.fontPx.coerceAtLeast(6f)
		smartTextLayout.configureTextPaint(paint, style, baseFontPx, layout.translatedText)

		val anchorX: Float
		if (layout.isSingleLineStat) {
			paint.textAlign = Paint.Align.LEFT
			anchorX = rect.left.coerceAtLeast(4f)
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
			var totalHeightPx = 0f
			for (line in lines) {
				val lineFontPx = (baseFontPx * line.scale).coerceAtLeast(6f)
				totalHeightPx += lineFontPx * layout.lineHeightRatio
			}
			var cursorY = rect.top + (rect.height() - totalHeightPx) * 0.5f
			if (!blockSaved) {
				val maxTop = (imgH - 4f - totalHeightPx).coerceAtLeast(4f)
				cursorY = cursorY.coerceIn(4f, maxTop)
			}

			val hasStroke = style.hasTextStroke || style.strokeWidthEm > 0f
			val outerStrokeEm = if (hasStroke) {
				if (style.strokeWidthEm > 0f) style.strokeWidthEm else 0.10f
			} else {
				0f
			}

			for (line in lines) {
				val rawLineFontPx = (baseFontPx * line.scale).coerceAtLeast(6f)
				val lineH = rawLineFontPx * layout.lineHeightRatio
				val lineCenterY = cursorY + lineH * 0.5f
				cursorY += lineH

				paint.shader = null
				paint.style = Paint.Style.FILL
				paint.strokeWidth = 0f
				paint.textSize = rawLineFontPx

				val rawPadPx = if (hasStroke) {
					(outerStrokeEm * rawLineFontPx).coerceIn(0.75f, rawLineFontPx * 0.22f)
				} else {
					2f
				}
				val boxBoundW = if (!layout.isSingleLineStat && abs(style.rotationDeg) < 0.5f && rect.width() >= 64f) {
					rect.width() - rawPadPx * 2f
				} else {
					imgW - 8f - rawPadPx * 2f
				}
				val maxSpanW = (imgW - 8f - rawPadPx * 2f)
					.coerceAtMost(boxBoundW.coerceAtLeast(24f))
					.coerceAtLeast(12f)
				val rawLineW = paint.measureText(line.text)
				val drawFontPx = if (rawLineW > maxSpanW && rawLineW > 0f) {
					(rawLineFontPx * (maxSpanW / rawLineW)).coerceAtLeast(6f)
				} else {
					rawLineFontPx
				}
				if (drawFontPx != rawLineFontPx) {
					paint.textSize = drawFontPx
				}

				val finalLineW = if (drawFontPx != rawLineFontPx) paint.measureText(line.text) else rawLineW
				val finalPadPx = if (hasStroke) {
					(outerStrokeEm * drawFontPx).coerceIn(0.75f, drawFontPx * 0.22f)
				} else {
					2f
				}

				var drawX = anchorX
				if (!blockSaved) {
					if (paint.textAlign == Paint.Align.LEFT) {
						val minLeftX = 4f + finalPadPx
						val maxLeftX = (imgW - 4f - finalLineW - finalPadPx).coerceAtLeast(minLeftX)
						drawX = drawX.coerceIn(minLeftX, maxLeftX)
					} else {
						val halfSpan = finalLineW * 0.5f + finalPadPx
						val minCenterX = (4f + halfSpan).coerceAtMost(imgW * 0.5f)
						val maxCenterX = (imgW - 4f - halfSpan).coerceAtLeast(imgW * 0.5f)
						drawX = drawX.coerceIn(minCenterX, maxCenterX)
					}
				}

				val fm = paint.fontMetrics
				var baselineY = lineCenterY - (fm.ascent + fm.descent) * 0.5f
				if (!blockSaved) {
					val topBound = 2f + finalPadPx - fm.ascent
					val botBound = imgH - 2f - finalPadPx - fm.descent
					if (topBound <= botBound) {
						baselineY = baselineY.coerceIn(topBound, botBound)
					}
				}

				drawStyledLine(
					canvas = canvas,
					paint = paint,
					lineText = line.text,
					anchorX = drawX,
					baselineY = baselineY,
					fontPx = drawFontPx,
					ascent = fm.ascent,
					descent = fm.descent,
					style = style,
				)
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
		val hasStroke = style.hasTextStroke || style.strokeWidthEm > 0f
		val outerStrokeEm = if (style.strokeWidthEm > 0f) style.strokeWidthEm else 0.10f
		val strokeWidthPx = if (hasStroke) {
			(outerStrokeEm * fontPx * 2f).coerceIn(1.5f, fontPx * 0.44f)
		} else {
			0f
		}

		val shadowColor = style.shadowColor
		if (shadowColor != null && style.shadowBlurEm > 0f) {
			paint.shader = null
			paint.setShadowLayer((style.shadowBlurEm * fontPx).coerceIn(1.5f, 24f), 0f, 0f, shadowColor)
			if (hasStroke) {
				paint.style = Paint.Style.FILL_AND_STROKE
				paint.strokeJoin = Paint.Join.ROUND
				paint.strokeCap = Paint.Cap.ROUND
				paint.strokeMiter = 2f
				paint.strokeWidth = strokeWidthPx
				paint.color = style.strokeColor
			} else {
				paint.style = Paint.Style.FILL
				paint.strokeWidth = 0f
				paint.color = style.textColor
			}
			canvas.drawText(lineText, anchorX, baselineY, paint)
			paint.clearShadowLayer()
		}

		if (hasStroke) {
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
			val preferJpeg = bitmap.width > WEBP_MAX_DIMENSION || bitmap.height > WEBP_MAX_DIMENSION
			val primaryFormat = if (preferJpeg) {
				Bitmap.CompressFormat.JPEG
			} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				Bitmap.CompressFormat.WEBP_LOSSY
			} else {
				@Suppress("DEPRECATION")
				Bitmap.CompressFormat.WEBP
			}
			val primaryEncoded = FileOutputStream(tmpFile).buffered().use { out ->
				val ok = bitmap.compress(primaryFormat, WEBP_QUALITY, out)
				out.flush()
				ok
			}
			if (!primaryEncoded && primaryFormat != Bitmap.CompressFormat.JPEG) {
				FileOutputStream(tmpFile, false).buffered().use { out ->
					check(bitmap.compress(Bitmap.CompressFormat.JPEG, WEBP_QUALITY, out)) {
						"Failed to encode translated bitmap (${bitmap.width}x${bitmap.height})."
					}
					out.flush()
				}
			} else {
				check(primaryEncoded) {
					"Failed to encode translated bitmap (${bitmap.width}x${bitmap.height})."
				}
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
		const val WEBP_MAX_DIMENSION = 16383
	}
}
