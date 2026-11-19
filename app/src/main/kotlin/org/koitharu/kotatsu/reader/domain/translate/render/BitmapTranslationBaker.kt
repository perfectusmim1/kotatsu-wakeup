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
import kotlin.math.min
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
				val resolvedLayouts = resolveNonOverlappingLayouts(refinedLayouts, paint, imgW, imgH)

				for (layout in resolvedLayouts) {
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
			val hasStroke = style.hasTextStroke || style.strokeWidthEm > 0f
			val outerStrokeEm = if (hasStroke) {
				if (style.strokeWidthEm > 0f) style.strokeWidthEm else 0.10f
			} else {
				0f
			}
			val hasRowContour = style.lineWidthsPct.size == lines.size && lines.isNotEmpty()

			var effectiveBaseFontPx = baseFontPx
			var totalHeightPx = 0f
			for (line in lines) {
				val lineFontPx = (effectiveBaseFontPx * line.scale).coerceAtLeast(6f)
				totalHeightPx += lineFontPx * layout.lineHeightRatio
			}
			if (!blockSaved && rect.height() >= 14f && totalHeightPx > rect.height() && totalHeightPx > 0f) {
				val fitScale = (rect.height() / totalHeightPx).coerceIn(0.42f, 1f)
				effectiveBaseFontPx = (effectiveBaseFontPx * fitScale).coerceAtLeast(6f)
			}

			// Compute a single uniform horizontal shrink factor across all lines so every line stays strictly inside
			// the bubble's physical/oval contour without creating inconsistent font sizes between lines.
			if (!blockSaved && rect.width() >= 20f) {
				var uniformHorizFit = 1f
				for (lineIdx in lines.indices) {
					val line = lines[lineIdx]
					val candidateFontPx = (effectiveBaseFontPx * line.scale).coerceAtLeast(6f)
					paint.shader = null
					paint.style = Paint.Style.FILL
					paint.strokeWidth = 0f
					paint.textSize = candidateFontPx
					val rawPadPx = if (hasStroke) {
						(outerStrokeEm * candidateFontPx).coerceIn(0.75f, candidateFontPx * 0.22f)
					} else {
						2f
					}
					val rowPct = if (hasRowContour) style.lineWidthsPct[lineIdx].coerceIn(0.32f, 1.0f) else 1f
					val rowBoundW = (rect.width() * rowPct) - rawPadPx * 2f
					val maxSpanW = (imgW - 8f - rawPadPx * 2f)
						.coerceAtMost(rowBoundW.coerceAtLeast(14f))
						.coerceAtLeast(10f)
					val measuredW = paint.measureText(line.text)
					if (measuredW > maxSpanW && measuredW > 0f) {
						uniformHorizFit = min(uniformHorizFit, maxSpanW / measuredW)
					}
				}
				if (uniformHorizFit < 0.999f) {
					effectiveBaseFontPx = (effectiveBaseFontPx * uniformHorizFit.coerceIn(0.42f, 1f)).coerceAtLeast(6f)
				}
			}

			totalHeightPx = 0f
			for (line in lines) {
				val lineFontPx = (effectiveBaseFontPx * line.scale).coerceAtLeast(6f)
				totalHeightPx += lineFontPx * layout.lineHeightRatio
			}

			var cursorY = rect.top + (rect.height() - totalHeightPx) * 0.5f
			if (!blockSaved) {
				val minTop = max(4f, rect.top)
				val boxMaxTop = if (rect.height() >= 14f) max(minTop, rect.bottom - totalHeightPx) else (imgH - 4f - totalHeightPx)
				val maxTop = min((imgH - 4f - totalHeightPx).coerceAtLeast(4f), boxMaxTop).coerceAtLeast(minTop)
				cursorY = cursorY.coerceIn(minTop, maxTop)
			}

			for (lineIdx in lines.indices) {
				val line = lines[lineIdx]
				val rawLineFontPx = (effectiveBaseFontPx * line.scale).coerceAtLeast(6f)
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
				val rowPct = if (hasRowContour) style.lineWidthsPct[lineIdx].coerceIn(0.32f, 1.0f) else 1f
				val boxBoundW = if (!blockSaved && rect.width() >= 20f) {
					(rect.width() * rowPct) - rawPadPx * 2f
				} else {
					imgW - 8f - rawPadPx * 2f
				}
				val maxSpanW = (imgW - 8f - rawPadPx * 2f)
					.coerceAtMost(boxBoundW.coerceAtLeast(14f))
					.coerceAtLeast(10f)
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
						val minLeftX = max(4f, rect.left) + finalPadPx
						val maxBoxRight = if (rect.width() >= 20f) min(imgW - 4f, rect.right) else imgW - 4f
						val maxLeftX = (maxBoxRight - finalLineW - finalPadPx).coerceAtLeast(minLeftX)
						drawX = drawX.coerceIn(minLeftX, maxLeftX)
					} else {
						val halfSpan = finalLineW * 0.5f + finalPadPx
						val rowHalfW = if (rect.width() >= 20f) rect.width() * rowPct * 0.5f else (imgW - 8f) * 0.5f
						val leftLim = if (rect.width() >= 20f) max(4f, rect.centerX() - rowHalfW) else 4f
						val rightLim = if (rect.width() >= 20f) min(imgW - 4f, rect.centerX() + rowHalfW) else imgW - 4f
						val minCenterX = (leftLim + halfSpan).coerceAtMost((leftLim + rightLim) * 0.5f)
						val maxCenterX = (rightLim - halfSpan).coerceAtLeast((leftLim + rightLim) * 0.5f)
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

	private fun resolveNonOverlappingLayouts(
		layouts: List<RefinedBlockLayout>,
		paint: TextPaint,
		imgW: Int,
		imgH: Int,
	): List<RefinedBlockLayout> {
		if (layouts.size <= 1 || imgW <= 0 || imgH <= 0) return layouts
		val drawableIndices = ArrayList<Int>(layouts.size)
		for (i in layouts.indices) {
			val l = layouts[i]
			if (!l.isSeamEraseOnly && l.wrappedLines.isNotEmpty()) {
				drawableIndices.add(i)
			}
		}
		if (drawableIndices.size <= 1) return layouts

		fun toBlockRect(box: NormalizedBox): BlockRect {
			val x0 = (box.xMin / 1000f) * imgW
			val y0 = (box.yMin / 1000f) * imgH
			val x1 = (box.xMax / 1000f) * imgW
			val y1 = (box.yMax / 1000f) * imgH
			return BlockRect(
				left = min(x0, x1),
				top = min(y0, y1),
				right = max(x0, x1),
				bottom = max(y0, y1),
			)
		}

		fun toDrawnContentRect(layout: RefinedBlockLayout): BlockRect {
			val r = toBlockRect(layout.refinedBox)
			if (layout.style.lineWidthsPct.isNotEmpty()) {
				return r
			}
			var totalH = 0f
			var maxW = 0f
			for (wl in layout.wrappedLines) {
				totalH += (layout.fontPx * wl.scale).coerceAtLeast(6f) * layout.lineHeightRatio
				if (wl.widthPx > maxW) maxW = wl.widthPx
			}
			val pad = max(4f, layout.fontPx * 0.25f)
			if (totalH + pad > r.height) {
				val cy = r.centerY
				r.top = min(r.top, cy - (totalH + pad) * 0.5f)
				r.bottom = max(r.bottom, cy + (totalH + pad) * 0.5f)
			}
			if (maxW + pad * 2f > r.width) {
				val cx = r.centerX
				r.left = min(r.left, cx - (maxW + pad * 2f) * 0.5f)
				r.right = max(r.right, cx + (maxW + pad * 2f) * 0.5f)
			}
			return r
		}

		val origRects = drawableIndices.map { idx -> toDrawnContentRect(layouts[idx]) }
		val resolvedRects = origRects.map { it.copy() }
		val anchorRects = drawableIndices.map { idx -> toBlockRect(layouts[idx].bubble.box) }

		resolveNonOverlappingRects(resolvedRects, anchorRects, imgW.toFloat(), imgH.toFloat())

		val result = layouts.toMutableList()
		val minFontPx = max(7.5f, imgW * 0.011f)
		for (dIdx in drawableIndices.indices) {
			val orig = origRects[dIdx]
			val res = resolvedRects[dIdx]
			val layoutIdx = drawableIndices[dIdx]
			val layout = layouts[layoutIdx]
			val shrunk = res.width < orig.width - 2f || res.height < orig.height - 2f
			val shifted = abs(res.left - orig.left) > 1f || abs(res.top - orig.top) > 1f
			if (!shrunk && !shifted) continue

			val newBox = NormalizedBox(
				yMin = (res.top / imgH * 1000f).coerceIn(0f, 1000f),
				xMin = (res.left / imgW * 1000f).coerceIn(0f, 1000f),
				yMax = (res.bottom / imgH * 1000f).coerceIn(0f, 1000f),
				xMax = (res.right / imgW * 1000f).coerceIn(0f, 1000f),
			)
			if (!shrunk) {
				result[layoutIdx] = layout.copy(refinedBox = newBox)
				continue
			}

			val style = layout.style
			smartTextLayout.configureTextPaint(paint, style, layout.fontPx.coerceAtLeast(minFontPx), layout.translatedText)
			fun measureAt(s: String, px: Float): Float {
				paint.textSize = px.coerceAtLeast(1f)
				return smartTextLayout.measureStyledWordWidth(s, paint, style.fontFamily)
			}

			var fontPx = layout.fontPx.coerceAtLeast(minFontPx)
			val availW = res.width.coerceAtLeast(20f)
			val availH = res.height.coerceAtLeast(12f)
			val refitLines = if (layout.isSingleLineStat) {
				for (step in 0 until 16) {
					val lineW = measureAt(layout.translatedText, fontPx)
					val lineH = fontPx * layout.lineHeightRatio
					if ((lineW <= availW * 0.96f && lineH <= availH) || fontPx <= minFontPx) break
					fontPx = max(minFontPx, fontPx * 0.92f)
				}
				val finalW = measureAt(layout.translatedText, fontPx)
				val effScale = if (finalW > availW * 0.96f && finalW > 0f) max(0.65f, (availW * 0.96f) / finalW) else 1f
				listOf(SmartWrappedLine(layout.translatedText, finalW, effScale))
			} else {
				var bestWrap = SmartWrapResult(layout.wrappedLines, 0f, 0f, 0f, false)
				for (step in 0 until 20) {
					val wrapped = SmartTextLayout.computeSmartWrappedLines(
						text = layout.translatedText,
						maxWidthPx = (availW * 0.94f).coerceAtLeast(14f),
						fontPx = fontPx,
						fontFamily = style.fontFamily,
						lineHeightRatio = layout.lineHeightRatio,
						measureScaledWidth = ::measureAt,
					)
					bestWrap = wrapped
					if ((wrapped.totalHeightPx <= availH * 0.96f &&
							wrapped.maxWordWidth <= availW * 0.96f &&
							!wrapped.hasOverflow) ||
						fontPx <= minFontPx
					) {
						break
					}
					fontPx = max(minFontPx, fontPx * 0.92f)
				}
				bestWrap.lines.ifEmpty { layout.wrappedLines }
			}

			val updatedStyle = style.copy(
				fontSizeCqw = ((fontPx / imgW.coerceAtLeast(1)) * 100f).coerceIn(1.2f, 13.5f),
				lineWidthsPct = emptyList(),
			)
			result[layoutIdx] = layout.copy(
				refinedBox = newBox,
				style = updatedStyle,
				wrappedLines = refitLines,
				fontPx = fontPx,
			)
		}
		return result
	}

	internal companion object {
		const val WEBP_QUALITY = 92
		const val WEBP_MAX_DIMENSION = 16383

		internal data class BlockRect(
			var left: Float,
			var top: Float,
			var right: Float,
			var bottom: Float,
		) {
			val width: Float get() = right - left
			val height: Float get() = bottom - top
			val centerX: Float get() = (left + right) * 0.5f
			val centerY: Float get() = (top + bottom) * 0.5f
		}

		internal fun resolveNonOverlappingRects(
			rects: List<BlockRect>,
			anchors: List<BlockRect>,
			imgW: Float,
			imgH: Float,
		) {
			if (rects.size <= 1 || rects.size != anchors.size) return
			val gap = max(8f, min(imgW, imgH) * 0.008f)
			val minBoxW = 20f
			val minBoxH = 12f
			val boundMaxX = (imgW - 4f).coerceAtLeast(4f + minBoxW)
			val boundMaxY = (imgH - 4f).coerceAtLeast(4f + minBoxH)

			for (pass in 0 until 6) {
				var anyAdjusted = false
				for (i in rects.indices) {
					val ri = rects[i]
					val ai = anchors[i]
					for (j in i + 1 until rects.size) {
						val rj = rects[j]
						val aj = anchors[j]
						val overlapX = min(ri.right, rj.right) + gap - max(ri.left, rj.left)
						val overlapY = min(ri.bottom, rj.bottom) + gap - max(ri.top, rj.top)
						if (overlapX <= 0.5f || overlapY <= 0.5f) continue

						val dxNorm = abs(ai.centerX - aj.centerX) / max(16f, (ai.width + aj.width) * 0.5f)
						val dyNorm = abs(ai.centerY - aj.centerY) / max(16f, (ai.height + aj.height) * 0.5f)
						val separateVertically = if (abs(dyNorm - dxNorm) >= 0.08f) {
							dyNorm > dxNorm
						} else {
							overlapY <= overlapX
						}

						if (separateVertically) {
							val iIsUpper = if (abs(ai.centerY - aj.centerY) > 1f) ai.centerY < aj.centerY else ri.centerY <= rj.centerY
							val upper = if (iIsUpper) ri else rj
							val lower = if (iIsUpper) rj else ri
							val upperA = if (iIsUpper) ai else aj
							val lowerA = if (iIsUpper) aj else ai

							val minSplitY = (upper.top + minBoxH).coerceAtMost(boundMaxY - minBoxH)
							val maxSplitY = (lower.bottom - minBoxH).coerceAtLeast(minSplitY)
							val anchorSplitY = if (upperA.bottom <= lowerA.top) {
								(upperA.bottom + lowerA.top) * 0.5f
							} else {
								(upperA.centerY + lowerA.centerY) * 0.5f
							}
							val splitY = anchorSplitY.coerceIn(minSplitY, maxSplitY)
							val newUpperBot = min(upper.bottom, (splitY - gap * 0.5f).coerceAtLeast(upper.top + minBoxH))
							val newLowerTop = max(lower.top, (splitY + gap * 0.5f).coerceAtMost(lower.bottom - minBoxH))
							if (newUpperBot < upper.bottom - 0.5f || newLowerTop > lower.top + 0.5f) {
								upper.bottom = newUpperBot
								lower.top = newLowerTop
								anyAdjusted = true
							}
						} else {
							val iIsLeft = if (abs(ai.centerX - aj.centerX) > 1f) ai.centerX < aj.centerX else ri.centerX <= rj.centerX
							val leftR = if (iIsLeft) ri else rj
							val rightR = if (iIsLeft) rj else ri
							val leftA = if (iIsLeft) ai else aj
							val rightA = if (iIsLeft) aj else ai

							val minSplitX = (leftR.left + minBoxW).coerceAtMost(boundMaxX - minBoxW)
							val maxSplitX = (rightR.right - minBoxW).coerceAtLeast(minSplitX)
							val anchorSplitX = if (leftA.right <= rightA.left) {
								(leftA.right + rightA.left) * 0.5f
							} else {
								(leftA.centerX + rightA.centerX) * 0.5f
							}
							val splitX = anchorSplitX.coerceIn(minSplitX, maxSplitX)
							val newLeftRight = min(leftR.right, (splitX - gap * 0.5f).coerceAtLeast(leftR.left + minBoxW))
							val newRightLeft = max(rightR.left, (splitX + gap * 0.5f).coerceAtMost(rightR.right - minBoxW))
							if (newLeftRight < leftR.right - 0.5f || newRightLeft > rightR.left + 0.5f) {
								leftR.right = newLeftRight
								rightR.left = newRightLeft
								anyAdjusted = true
							}
						}
					}
				}
				if (!anyAdjusted) break
			}

			for (r in rects) {
				r.left = r.left.coerceIn(4f, boundMaxX - minBoxW)
				r.right = r.right.coerceIn(r.left + minBoxW, boundMaxX)
				r.top = r.top.coerceIn(4f, boundMaxY - minBoxH)
				r.bottom = r.bottom.coerceIn(r.top + minBoxH, boundMaxY)
			}
		}
	}
}
