package org.koitharu.kotatsu.reader.domain.translate.render

import android.graphics.Bitmap
import android.graphics.RectF
import android.text.TextPaint
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.koitharu.kotatsu.reader.domain.translate.model.NormalizedBox
import org.koitharu.kotatsu.reader.domain.translate.model.OcrBubbleGroup
import org.koitharu.kotatsu.reader.domain.translate.model.ScanlationFontKey
import org.koitharu.kotatsu.reader.domain.translate.model.TextBlockKind
import org.koitharu.kotatsu.reader.domain.translate.model.TextStyleFingerprint

data class RefinedBlockLayout(
	val bubble: OcrBubbleGroup,
	val translatedText: String,
	val kind: TextBlockKind,
	val refinedBox: NormalizedBox,
	val style: TextStyleFingerprint,
	val wrappedLines: List<SmartWrappedLine>,
	val fontPx: Float,
	val lineHeightRatio: Float,
	val isSingleLineStat: Boolean,
)

@Singleton
class BubbleInpainter @Inject constructor(
	private val styleExtractor: StyleExtractor,
	private val smartTextLayout: SmartTextLayout,
) {

	private enum class MaskPassMode {
		DARK,
		GENTLE_DARK,
		LIGHT,
		ALT_LIGHT,
	}

	private class FgComponent(
		val minX: Int,
		val minY: Int,
		val maxX: Int,
		val maxY: Int,
		val count: Int,
		val touchEdge: Boolean,
		val pixelIndices: IntArray,
	)

	private class MaskSelectionResult(
		val rawMask: ByteArray,
		val borderMask: ByteArray,
		val rawCount: Int,
		val gMinX: Int,
		val gMinY: Int,
		val gMaxX: Int,
		val gMaxY: Int,
	)

	private data class BubbleWallBounds(
		val left: Int,
		val right: Int,
		val top: Int,
		val bottom: Int,
		val hasClosedWalls: Boolean,
	)

	private data class RingSampleStats(
		val medR: Int,
		val medG: Int,
		val medB: Int,
		val stdDev: Float,
		val count: Int,
	)

	fun findDominantBackgroundRgb(
		pixels: IntArray,
		ww: Int,
		wh: Int,
		lineRects: List<RectF>,
	): IntArray {
		val hist = IntArray(32 * 32 * 32)
		val step = if (ww * wh > 180_000) 2 else 1

		var lightCoreCount = 0
		var darkCoreCount = 0

		for (y in 0 until wh step step) {
			val row = y * ww
			val yf = y.toFloat()
			for (x in 0 until ww step step) {
				val p = pixels[row + x]
				val r = PixelColorMath.red(p)
				val g = PixelColorMath.green(p)
				val b = PixelColorMath.blue(p)
				val nearCore = isNearAnyRect(x.toFloat(), yf, lineRects, pad = 4f)
				val weight = if (nearCore) 3 else 1

				if (nearCore) {
					val lum = PixelColorMath.luminance(r, g, b)
					if (lum >= 180f) lightCoreCount++ else if (lum <= 75f) darkCoreCount++
				}

				val bin = ((r ushr 3) shl 10) or ((g ushr 3) shl 5) or (b ushr 3)
				hist[bin] += weight
			}
		}

		var topBin = 0
		var topWeight = -1
		var topLightBin = -1
		var topLightWeight = -1

		for (bin in hist.indices) {
			val w = hist[bin]
			if (w > topWeight) {
				topWeight = w
				topBin = bin
			}
			val r5 = (bin ushr 10) and 31
			val g5 = (bin ushr 5) and 31
			val b5 = bin and 31
			if (r5 >= 20 && g5 >= 20 && b5 >= 20 && w > topLightWeight) {
				topLightWeight = w
				topLightBin = bin
			}
		}

		// Guard against oversized bold black text fooling the core histogram when paper is white.
		val winLum = (((topBin ushr 10) and 31) * 8) * 0.299f +
			(((topBin ushr 5) and 31) * 8) * 0.587f +
			((topBin and 31) * 8) * 0.114f
		if (winLum < 85f &&
			topLightBin >= 0 &&
			topLightWeight >= (topWeight * 0.28f).toInt() &&
			lightCoreCount > (darkCoreCount * 0.35f).toInt()
		) {
			topBin = topLightBin
		}

		val bestR5 = (topBin ushr 10) and 31
		val bestG5 = (topBin ushr 5) and 31
		val bestB5 = topBin and 31

		val collected = IntArray((ww * wh / (step * step)) + 16)
		var count = 0
		for (y in 0 until wh step step) {
			val row = y * ww
			for (x in 0 until ww step step) {
				val p = pixels[row + x]
				val r5 = PixelColorMath.red(p) ushr 3
				val g5 = PixelColorMath.green(p) ushr 3
				val b5 = PixelColorMath.blue(p) ushr 3
				if (abs(r5 - bestR5) <= 1 && abs(g5 - bestG5) <= 1 && abs(b5 - bestB5) <= 1) {
					collected[count++] = row + x
				}
			}
		}

		return if (count > 0) {
			PixelColorMath.medianRgbFromIndices(pixels, collected, count)
		} else {
			intArrayOf(bestR5 * 8 + 4, bestG5 * 8 + 4, bestB5 * 8 + 4)
		}
	}

	fun dilate3x3(
		src: ByteArray,
		dst: ByteArray,
		ww: Int,
		wh: Int,
		x0: Int,
		y0: Int,
		x1: Int,
		y1: Int,
		guardZeroMask: ByteArray?,
	) {
		System.arraycopy(src, 0, dst, 0, src.size)
		val minX = x0.coerceAtLeast(0)
		val minY = y0.coerceAtLeast(0)
		val maxX = x1.coerceAtMost(ww - 1)
		val maxY = y1.coerceAtMost(wh - 1)

		for (y in minY..maxY) {
			val row = y * ww
			val ny0 = (y - 1).coerceAtLeast(0)
			val ny1 = (y + 1).coerceAtMost(wh - 1)
			for (x in minX..maxX) {
				val idx = row + x
				if (src[idx] == 1.toByte()) continue
				if (guardZeroMask != null && guardZeroMask[idx] == 1.toByte()) continue

				val nx0 = (x - 1).coerceAtLeast(0)
				val nx1 = (x + 1).coerceAtMost(ww - 1)
				var hit = false
				loop@ for (ny in ny0..ny1) {
					val nRow = ny * ww
					for (nx in nx0..nx1) {
						if (src[nRow + nx] == 1.toByte()) {
							hit = true
							break@loop
						}
					}
				}
				if (hit) {
					dst[idx] = 1
				}
			}
		}
	}

	fun inpaintAndRefineBlock(
		workBitmap: Bitmap,
		bubble: OcrBubbleGroup,
		translatedText: String,
		explicitKind: TextBlockKind?,
		targetLang: String,
	): RefinedBlockLayout {
		val imgW = workBitmap.width
		val imgH = workBitmap.height
		val blockRect = bubble.box.toRectF(imgW, imgH)
		val bW = blockRect.width().coerceAtLeast(1f)
		val bH = blockRect.height().coerceAtLeast(1f)

		val rawLineRectsImg = if (bubble.lines.isNotEmpty()) {
			bubble.lines.mapNotNull { line ->
				if (line.box.isValid()) line.box.toRectF(imgW, imgH) else null
			}.ifEmpty { listOf(blockRect) }
		} else {
			listOf(blockRect)
		}

		// Compute search window bounded cleanly inside bitmap dimensions.
		var uLeft = blockRect.left
		var uTop = blockRect.top
		var uRight = blockRect.right
		var uBottom = blockRect.bottom
		for (lr in rawLineRectsImg) {
			if (lr.left < uLeft) uLeft = lr.left
			if (lr.top < uTop) uTop = lr.top
			if (lr.right > uRight) uRight = lr.right
			if (lr.bottom > uBottom) uBottom = lr.bottom
		}

		val padX = max(18, (bW * 0.22f).toInt())
		val padY = max(18, (bH * 0.22f).toInt())
		val wx0 = (uLeft.toInt() - padX).coerceIn(0, max(0, imgW - 1))
		val wy0 = (uTop.toInt() - padY).coerceIn(0, max(0, imgH - 1))
		val wx1 = (uRight.toInt() + padX).coerceIn(wx0, max(wx0, imgW - 1))
		val wy1 = (uBottom.toInt() + padY).coerceIn(wy0, max(wy0, imgH - 1))
		val ww = wx1 - wx0 + 1
		val wh = wy1 - wy0 + 1

		val localLineRects = rawLineRectsImg.map { r ->
			RectF(
				(r.left - wx0).coerceIn(0f, (ww - 1).toFloat()),
				(r.top - wy0).coerceIn(0f, (wh - 1).toFloat()),
				(r.right - wx0).coerceIn(0f, ww.toFloat()),
				(r.bottom - wy0).coerceIn(0f, wh.toFloat()),
			)
		}

		if (ww < 6 || wh < 6) {
			val fallbackStyle = TextStyleFingerprint(fontFamily = ScanlationFontKey.SHANTELL)
			return computeRefinedLayout(
				bubble = bubble,
				translatedText = translatedText,
				explicitKind = explicitKind,
				targetLang = targetLang,
				extractedStyle = fallbackStyle,
				imgW = imgW,
				imgH = imgH,
				wx0 = wx0,
				wy0 = wy0,
				wallBounds = null,
				localLineRects = localLineRects,
			)
		}

		val pixels = IntArray(ww * wh)
		workBitmap.getPixels(pixels, 0, ww, wx0, wy0, ww, wh)

		val bgRgb = findDominantBackgroundRgb(pixels, ww, wh, localLineRects)
		val bgR = bgRgb[0]
		val bgG = bgRgb[1]
		val bgB = bgRgb[2]
		val bgLum = PixelColorMath.luminance(bgR, bgG, bgB)

		val integralLum = buildIntegralLum(pixels, ww, wh)
		val avgLineH = (localLineRects.map { it.height() }.average().toFloat()).coerceIn(10f, max(12f, bH))
		val localRadius = (min(bW, bH) * 0.14f).toInt().coerceIn(9, 28)

		val selection = extractGlyphMaskWithFallbacks(
			pixels = pixels,
			ww = ww,
			wh = wh,
			integralLum = integralLum,
			localRadius = localRadius,
			bgR = bgR,
			bgG = bgG,
			bgB = bgB,
			bgLum = bgLum,
			localLineRects = localLineRects,
			avgLineH = avgLineH,
		)

		val rawMask = selection.rawMask
		val borderMask = selection.borderMask
		var gMinX = selection.gMinX
		var gMinY = selection.gMinY
		var gMaxX = selection.gMaxX
		var gMaxY = selection.gMaxY

		// Recover clipped diacritics/tails via bounded ink growth.
		if (selection.rawCount > 0) {
			val grownBounds = growGlyphMaskBounded(
				pixels = pixels,
				ww = ww,
				wh = wh,
				rawMask = rawMask,
				borderMask = borderMask,
				lineRects = localLineRects,
				avgLineH = avgLineH,
				bgR = bgR,
				bgG = bgG,
				bgB = bgB,
			)
			if (grownBounds != null) {
				gMinX = grownBounds[0]
				gMinY = grownBounds[1]
				gMaxX = grownBounds[2]
				gMaxY = grownBounds[3]
			}
		}

		val wallBounds = detectBubbleWallBounds(
			pixels = pixels,
			ww = ww,
			wh = wh,
			borderMask = borderMask,
			localLineRects = localLineRects,
			bgR = bgR,
			bgG = bgG,
			bgB = bgB,
		)

		val extractedStyle = styleExtractor.extractStyleFingerprint(
			StyleExtractionInput(
				pixels = pixels,
				ww = ww,
				wh = wh,
				glyphMask = rawMask,
				bgR = bgR,
				bgG = bgG,
				bgB = bgB,
				imageWidth = imgW,
				imageHeight = imgH,
				gMinX = gMinX,
				gMinY = gMinY,
				gMaxX = gMaxX,
				gMaxY = gMaxY,
				blockBox = bubble.box,
				kindHint = explicitKind ?: bubble.kindHint,
				lineCount = bubble.lines.size.coerceAtLeast(1),
				text = bubble.text,
			),
		)

		if (selection.rawCount > 0 && gMaxX >= gMinX && gMaxY >= gMinY) {
			val enclosed = styleExtractor.fillEnclosedGlyphInteriors(
				pixels = pixels,
				ww = ww,
				wh = wh,
				rawMask = rawMask,
				borderMask = borderMask,
				gMinX = gMinX,
				gMinY = gMinY,
				gMaxX = gMaxX,
				gMaxY = gMaxY,
				bgR = bgR,
				bgG = bgG,
				bgB = bgB,
			)

			var eraseMask = ByteArray(ww * wh)
			for (i in eraseMask.indices) {
				if ((rawMask[i] == 1.toByte() || enclosed.enclosedMask[i] == 1.toByte()) && borderMask[i] == 0.toByte()) {
					eraseMask[i] = 1
				}
			}

			// Absorb bright outer sticker halo around text before adaptive dilation.
			if (bgLum < 220f || extractedStyle.hasTextStroke) {
				absorbWhiteHaloIntoEraseMask(
					pixels = pixels,
					ww = ww,
					wh = wh,
					rawMask = rawMask,
					eraseMask = eraseMask,
					borderMask = borderMask,
					bgR = bgR,
					bgG = bgG,
					bgB = bgB,
					x0 = (gMinX - 3).coerceAtLeast(0),
					y0 = (gMinY - 3).coerceAtLeast(0),
					x1 = (gMaxX + 3).coerceAtMost(ww - 1),
					y1 = (gMaxY + 3).coerceAtMost(wh - 1),
				)
			}

			// Adaptive 2..4 step 3x3 dilation (4..8px diameter) clipped away from borders.
			val dilateSteps = (avgLineH / 16f).roundToInt().coerceIn(2, 4)
			var scratch = ByteArray(ww * wh)
			val dPad = dilateSteps + 2
			val rx0 = (gMinX - dPad).coerceAtLeast(0)
			val ry0 = (gMinY - dPad).coerceAtLeast(0)
			val rx1 = (gMaxX + dPad).coerceAtMost(ww - 1)
			val ry1 = (gMaxY + dPad).coerceAtMost(wh - 1)
			for (step in 0 until dilateSteps) {
				dilate3x3(eraseMask, scratch, ww, wh, rx0, ry0, rx1, ry1, borderMask)
				val tmp = eraseMask
				eraseMask = scratch
				scratch = tmp
			}

			val ring = sampleOuterRingStats(
				pixels = pixels,
				ww = ww,
				wh = wh,
				eraseMask = eraseMask,
				borderMask = borderMask,
				bgR = bgR,
				bgG = bgG,
				bgB = bgB,
				x0 = (rx0 - 3).coerceAtLeast(0),
				y0 = (ry0 - 3).coerceAtLeast(0),
				x1 = (rx1 + 3).coerceAtMost(ww - 1),
				y1 = (ry1 + 3).coerceAtMost(wh - 1),
			)
			val ringMedLum = PixelColorMath.luminance(ring.medR, ring.medG, ring.medB)

			// 3-Tier Inpainting Pipeline:
			// Tier 1: Flat bubble median fill when ringStd < 4.5 (or clean bright enclosed bubble < 6.5).
			val isFlatBubble = ring.count >= 8 && (
				ring.stdDev < 4.5f ||
					(bgLum >= 200f && ringMedLum >= 210f && wallBounds.hasClosedWalls && ring.stdDev < 6.5f)
				)

			if (isFlatBubble) {
				expandAaEdgesInFlatBubble(
					pixels = pixels,
					ww = ww,
					wh = wh,
					eraseMask = eraseMask,
					borderMask = borderMask,
					ringMedR = ring.medR,
					ringMedG = ring.medG,
					ringMedB = ring.medB,
					x0 = (rx0 - 2).coerceAtLeast(0),
					y0 = (ry0 - 2).coerceAtLeast(0),
					x1 = (rx1 + 2).coerceAtMost(ww - 1),
					y1 = (ry1 + 2).coerceAtMost(wh - 1),
				)
				val fillRgb = PixelColorMath.packRgb(ring.medR, ring.medG, ring.medB)
				for (y in (ry0 - 2).coerceAtLeast(0)..(ry1 + 2).coerceAtMost(wh - 1)) {
					val row = y * ww
					for (x in (rx0 - 2).coerceAtLeast(0)..(rx1 + 2).coerceAtMost(ww - 1)) {
						val idx = row + x
						if (eraseMask[idx] == 1.toByte()) {
							pixels[idx] = fillRgb
						}
					}
				}
			} else {
				// Tier 2: Anisotropic FMM + harmonic texture reconstruction.
				styleExtractor.inpaintTexturedRegion(
					pixels = pixels,
					ww = ww,
					wh = wh,
					eraseMask = eraseMask,
					borderMask = borderMask,
					ringMedR = ring.medR,
					ringMedG = ring.medG,
					ringMedB = ring.medB,
					regionX0 = rx0,
					regionY0 = ry0,
					regionX1 = rx1,
					regionY1 = ry1,
				)

				// Tier 3: Post-inpaint residual verification pass catches orphan ink specks.
				runResidualVerificationPass(
					pixels = pixels,
					ww = ww,
					wh = wh,
					eraseMask = eraseMask,
					borderMask = borderMask,
					ring = ring,
					gMinX = gMinX,
					gMinY = gMinY,
					gMaxX = gMaxX,
					gMaxY = gMaxY,
				)
			}

			workBitmap.setPixels(pixels, 0, ww, wx0, wy0, ww, wh)
		}

		return computeRefinedLayout(
			bubble = bubble,
			translatedText = translatedText,
			explicitKind = explicitKind,
			targetLang = targetLang,
			extractedStyle = extractedStyle,
			imgW = imgW,
			imgH = imgH,
			wx0 = wx0,
			wy0 = wy0,
			wallBounds = wallBounds,
			localLineRects = localLineRects,
		)
	}

	private fun extractGlyphMaskWithFallbacks(
		pixels: IntArray,
		ww: Int,
		wh: Int,
		integralLum: DoubleArray,
		localRadius: Int,
		bgR: Int,
		bgG: Int,
		bgB: Int,
		bgLum: Float,
		localLineRects: List<RectF>,
		avgLineH: Float,
	): MaskSelectionResult {
		val primaryMode = if (bgLum < 120f) MaskPassMode.LIGHT else MaskPassMode.DARK
		val primary = runSingleMaskPass(
			mode = primaryMode,
			pixels = pixels,
			ww = ww,
			wh = wh,
			integralLum = integralLum,
			localRadius = localRadius,
			bgR = bgR,
			bgG = bgG,
			bgB = bgB,
			bgLum = bgLum,
			localLineRects = localLineRects,
			avgLineH = avgLineH,
		)
		if (primary.rawCount >= 18) return primary

		val gentleMode = if (primaryMode == MaskPassMode.DARK) MaskPassMode.GENTLE_DARK else MaskPassMode.ALT_LIGHT
		val gentle = runSingleMaskPass(
			mode = gentleMode,
			pixels = pixels,
			ww = ww,
			wh = wh,
			integralLum = integralLum,
			localRadius = localRadius,
			bgR = bgR,
			bgG = bgG,
			bgB = bgB,
			bgLum = bgLum,
			localLineRects = localLineRects,
			avgLineH = avgLineH,
		)

		val oppMode = if (primaryMode == MaskPassMode.DARK) MaskPassMode.ALT_LIGHT else MaskPassMode.DARK
		val opp = runSingleMaskPass(
			mode = oppMode,
			pixels = pixels,
			ww = ww,
			wh = wh,
			integralLum = integralLum,
			localRadius = localRadius,
			bgR = bgR,
			bgG = bgG,
			bgB = bgB,
			bgLum = bgLum,
			localLineRects = localLineRects,
			avgLineH = avgLineH,
		)

		return listOf(primary, gentle, opp).maxBy { it.rawCount }
	}

	private fun runSingleMaskPass(
		mode: MaskPassMode,
		pixels: IntArray,
		ww: Int,
		wh: Int,
		integralLum: DoubleArray,
		localRadius: Int,
		bgR: Int,
		bgG: Int,
		bgB: Int,
		bgLum: Float,
		localLineRects: List<RectF>,
		avgLineH: Float,
	): MaskSelectionResult {
		val candMask = buildForegroundCandidateMask(
			mode = mode,
			pixels = pixels,
			ww = ww,
			wh = wh,
			integralLum = integralLum,
			localRadius = localRadius,
			bgR = bgR,
			bgG = bgG,
			bgB = bgB,
			bgLum = bgLum,
		)

		val components = extractComponents(candMask, ww, wh)
		val borderMask = ByteArray(ww * wh)
		val rawMask = ByteArray(ww * wh)

		var rawCount = 0
		var gMinX = ww
		var gMinY = wh
		var gMaxX = -1
		var gMaxY = -1

		for (comp in components) {
			if (isStructuralFrameOrDivider(comp, ww, wh, localLineRects, avgLineH)) {
				for (idx in comp.pixelIndices) {
					borderMask[idx] = 1
				}
				continue
			}
			if (isValidGlyphComponent(comp, localLineRects, avgLineH)) {
				for (idx in comp.pixelIndices) {
					rawMask[idx] = 1
				}
				rawCount += comp.count
				if (comp.minX < gMinX) gMinX = comp.minX
				if (comp.minY < gMinY) gMinY = comp.minY
				if (comp.maxX > gMaxX) gMaxX = comp.maxX
				if (comp.maxY > gMaxY) gMaxY = comp.maxY
			}
		}

		return MaskSelectionResult(
			rawMask = rawMask,
			borderMask = borderMask,
			rawCount = rawCount,
			gMinX = gMinX,
			gMinY = gMinY,
			gMaxX = gMaxX,
			gMaxY = gMaxY,
		)
	}

	private fun buildForegroundCandidateMask(
		mode: MaskPassMode,
		pixels: IntArray,
		ww: Int,
		wh: Int,
		integralLum: DoubleArray,
		localRadius: Int,
		bgR: Int,
		bgG: Int,
		bgB: Int,
		bgLum: Float,
	): ByteArray {
		val mask = ByteArray(ww * wh)
		val deltaLocal = when (mode) {
			MaskPassMode.DARK -> 24f
			MaskPassMode.GENTLE_DARK -> 13f
			MaskPassMode.LIGHT, MaskPassMode.ALT_LIGHT -> 22f
		}
		val deltaBg = when (mode) {
			MaskPassMode.DARK -> 34f
			MaskPassMode.GENTLE_DARK -> 20f
			MaskPassMode.LIGHT, MaskPassMode.ALT_LIGHT -> 32f
		}
		val chromaCutoff = if (mode == MaskPassMode.GENTLE_DARK) 36f else 52f

		for (y in 0 until wh) {
			val row = y * ww
			val y0 = (y - localRadius).coerceAtLeast(0)
			val y1 = (y + localRadius).coerceAtMost(wh - 1)
			for (x in 0 until ww) {
				val idx = row + x
				val p = pixels[idx]
				val r = PixelColorMath.red(p)
				val g = PixelColorMath.green(p)
				val b = PixelColorMath.blue(p)
				val lum = PixelColorMath.luminance(r, g, b)

				val x0 = (x - localRadius).coerceAtLeast(0)
				val x1 = (x + localRadius).coerceAtMost(ww - 1)
				val localMean = queryIntegralMean(integralLum, ww, x0, y0, x1, y1)

				val distBg = PixelColorMath.colorDist(r, g, b, bgR, bgG, bgB)
				val maxDiffBg = PixelColorMath.maxChannelDiff(r, g, b, bgR, bgG, bgB)

				val isFg = when (mode) {
					MaskPassMode.DARK, MaskPassMode.GENTLE_DARK -> {
						(localMean - lum > deltaLocal && bgLum - lum > 14f) ||
							(bgLum - lum > deltaBg && maxDiffBg > 30) ||
							(distBg > chromaCutoff && PixelColorMath.saturation(r, g, b) >= 0.25f)
					}
					MaskPassMode.LIGHT, MaskPassMode.ALT_LIGHT -> {
						(lum - localMean > deltaLocal && lum >= 155f) ||
							(lum - bgLum > deltaBg && maxDiffBg > 32 && lum >= 155f) ||
							(distBg > 58f && lum > bgLum + 18f)
					}
				}
				if (isFg) {
					mask[idx] = 1
				}
			}
		}
		return mask
	}

	private fun extractComponents(mask: ByteArray, ww: Int, wh: Int): List<FgComponent> {
		val visited = BooleanArray(ww * wh)
		val queue = IntArray(ww * wh)
		val result = ArrayList<FgComponent>()

		for (y in 0 until wh) {
			val row = y * ww
			for (x in 0 until ww) {
				val start = row + x
				if (mask[start] != 1.toByte() || visited[start]) continue

				var qHead = 0
				var qTail = 0
				visited[start] = true
				queue[qTail++] = start

				var minX = x
				var maxX = x
				var minY = y
				var maxY = y
				var touchEdge = x == 0 || y == 0 || x == ww - 1 || y == wh - 1

				while (qHead < qTail) {
					val cur = queue[qHead++]
					val cx = cur % ww
					val cy = cur / ww
					if (cx < minX) minX = cx
					if (cx > maxX) maxX = cx
					if (cy < minY) minY = cy
					if (cy > maxY) maxY = cy
					if (cx == 0 || cy == 0 || cx == ww - 1 || cy == wh - 1) touchEdge = true

					for (dy in -1..1) {
						val ny = cy + dy
						if (ny < 0 || ny >= wh) continue
						val nRow = ny * ww
						for (dx in -1..1) {
							if (dx == 0 && dy == 0) continue
							val nx = cx + dx
							if (nx < 0 || nx >= ww) continue
							val ni = nRow + nx
							if (mask[ni] == 1.toByte() && !visited[ni]) {
								visited[ni] = true
								queue[qTail++] = ni
							}
						}
					}
				}

				result.add(
					FgComponent(
						minX = minX,
						minY = minY,
						maxX = maxX,
						maxY = maxY,
						count = qTail,
						touchEdge = touchEdge,
						pixelIndices = queue.copyOf(qTail),
					),
				)
			}
		}
		return result
	}

	private fun isStructuralFrameOrDivider(
		comp: FgComponent,
		ww: Int,
		wh: Int,
		lineRects: List<RectF>,
		avgLineH: Float,
	): Boolean {
		val cw = comp.maxX - comp.minX + 1
		val ch = comp.maxY - comp.minY + 1
		if (comp.count > (ww * wh * 0.42f).toInt()) return true
		if (comp.touchEdge && cw >= ww * 0.85f && ch >= wh * 0.85f) return true
		if (comp.touchEdge && ((cw >= ww * 0.82f && ch <= max(6f, avgLineH * 0.30f)) || (ch >= wh * 0.82f && cw <= max(6f, ww * 0.08f)))) {
			val hitsLine = lineRects.any { lr ->
				comp.maxX >= lr.left && comp.minX <= lr.right && comp.maxY >= lr.top && comp.minY <= lr.bottom
			}
			if (!hitsLine || cw * ch > ww * wh * 0.28f) return true
		}
		return false
	}

	private fun isValidGlyphComponent(
		comp: FgComponent,
		lineRects: List<RectF>,
		avgLineH: Float,
	): Boolean {
		if (comp.count < 2) return false
		val ch = (comp.maxY - comp.minY + 1).toFloat()
		val maxAllowedH = max(avgLineH * 3.4f, 44f)
		if (ch > maxAllowedH && comp.touchEdge) return false

		val margin = max(8f, avgLineH * 0.45f)
		return lineRects.any { lr ->
			comp.maxX >= lr.left - margin &&
				comp.minX <= lr.right + margin &&
				comp.maxY >= lr.top - margin &&
				comp.minY <= lr.bottom + margin
		}
	}

	private fun growGlyphMaskBounded(
		pixels: IntArray,
		ww: Int,
		wh: Int,
		rawMask: ByteArray,
		borderMask: ByteArray,
		lineRects: List<RectF>,
		avgLineH: Float,
		bgR: Int,
		bgG: Int,
		bgB: Int,
	): IntArray? {
		val seedBuf = IntArray(ww * wh)
		var seedCount = 0
		for (i in rawMask.indices) {
			if (rawMask[i] == 1.toByte()) {
				seedBuf[seedCount++] = i
			}
		}
		if (seedCount == 0) return null

		val seedRgb = PixelColorMath.medianRgbFromIndices(pixels, seedBuf, seedCount)
		val maxSteps = (avgLineH * 0.25f).toInt().coerceIn(3, 14)
		val expandPad = max(10f, avgLineH * 0.4f)

		val visited = BooleanArray(ww * wh)
		val queue = IntArray(ww * wh)
		val depth = IntArray(ww * wh)
		var qHead = 0
		var qTail = 0

		for (i in 0 until seedCount) {
			val idx = seedBuf[i]
			visited[idx] = true
			queue[qTail++] = idx
		}

		while (qHead < qTail) {
			val cur = queue[qHead++]
			val curDepth = depth[cur]
			if (curDepth >= maxSteps) continue

			val cx = cur % ww
			val cy = cur / ww
			for (dy in -1..1) {
				val ny = cy + dy
				if (ny < 0 || ny >= wh) continue
				val nRow = ny * ww
				for (dx in -1..1) {
					if (dx == 0 && dy == 0) continue
					val nx = cx + dx
					if (nx < 0 || nx >= ww) continue
					val ni = nRow + nx
					if (visited[ni] || borderMask[ni] == 1.toByte()) continue
					if (!isNearAnyRect(nx.toFloat(), ny.toFloat(), lineRects, expandPad)) continue

					val np = pixels[ni]
					val distToInk = PixelColorMath.colorDist(
						PixelColorMath.red(np), PixelColorMath.green(np), PixelColorMath.blue(np),
						seedRgb[0], seedRgb[1], seedRgb[2],
					)
					val maxDiffBg = PixelColorMath.maxChannelDiff(np, bgR, bgG, bgB)
					if (distToInk <= 46f && maxDiffBg >= 24) {
						visited[ni] = true
						rawMask[ni] = 1
						depth[ni] = curDepth + 1
						queue[qTail++] = ni
					}
				}
			}
		}

		var minX = ww
		var minY = wh
		var maxX = -1
		var maxY = -1
		for (y in 0 until wh) {
			val row = y * ww
			for (x in 0 until ww) {
				if (rawMask[row + x] == 1.toByte()) {
					if (x < minX) minX = x
					if (x > maxX) maxX = x
					if (y < minY) minY = y
					if (y > maxY) maxY = y
				}
			}
		}
		return if (maxX >= minX && maxY >= minY) intArrayOf(minX, minY, maxX, maxY) else null
	}

	private fun detectBubbleWallBounds(
		pixels: IntArray,
		ww: Int,
		wh: Int,
		borderMask: ByteArray,
		localLineRects: List<RectF>,
		bgR: Int,
		bgG: Int,
		bgB: Int,
	): BubbleWallBounds {
		var minLx = ww.toFloat()
		var minLy = wh.toFloat()
		var maxRx = 0f
		var maxBy = 0f
		for (r in localLineRects) {
			if (r.left < minLx) minLx = r.left
			if (r.top < minLy) minLy = r.top
			if (r.right > maxRx) maxRx = r.right
			if (r.bottom > maxBy) maxBy = r.bottom
		}
		val cx = ((minLx + maxRx) * 0.5f).toInt().coerceIn(0, ww - 1)
		val cy = ((minLy + maxBy) * 0.5f).toInt().coerceIn(0, wh - 1)

		fun isWallPixel(x: Int, y: Int): Boolean {
			val idx = y * ww + x
			if (borderMask[idx] == 1.toByte()) return true
			val p = pixels[idx]
			val lum = PixelColorMath.luminance(p)
			val distBg = PixelColorMath.colorDist(
				PixelColorMath.red(p), PixelColorMath.green(p), PixelColorMath.blue(p),
				bgR, bgG, bgB,
			)
			return lum < 95f && distBg > 75f
		}

		var wallL = 0
		var foundL = false
		for (x in minLx.toInt().coerceAtMost(cx) downTo 0) {
			if (isWallPixel(x, cy)) {
				wallL = x + 1
				foundL = true
				break
			}
		}

		var wallR = ww - 1
		var foundR = false
		for (x in maxRx.toInt().coerceAtLeast(cx) until ww) {
			if (isWallPixel(x, cy)) {
				wallR = x - 1
				foundR = true
				break
			}
		}

		var wallT = 0
		var foundT = false
		for (y in minLy.toInt().coerceAtMost(cy) downTo 0) {
			if (isWallPixel(cx, y)) {
				wallT = y + 1
				foundT = true
				break
			}
		}

		var wallB = wh - 1
		var foundB = false
		for (y in maxBy.toInt().coerceAtLeast(cy) until wh) {
			if (isWallPixel(cx, y)) {
				wallB = y - 1
				foundB = true
				break
			}
		}

		val closedCount = (if (foundL) 1 else 0) + (if (foundR) 1 else 0) + (if (foundT) 1 else 0) + (if (foundB) 1 else 0)
		return BubbleWallBounds(
			left = wallL,
			right = wallR.coerceAtLeast(wallL + 1),
			top = wallT,
			bottom = wallB.coerceAtLeast(wallT + 1),
			hasClosedWalls = closedCount >= 3,
		)
	}

	private fun absorbWhiteHaloIntoEraseMask(
		pixels: IntArray,
		ww: Int,
		wh: Int,
		rawMask: ByteArray,
		eraseMask: ByteArray,
		borderMask: ByteArray,
		bgR: Int,
		bgG: Int,
		bgB: Int,
		x0: Int,
		y0: Int,
		x1: Int,
		y1: Int,
	) {
		for (y in y0..y1) {
			val row = y * ww
			for (x in x0..x1) {
				val idx = row + x
				if (eraseMask[idx] == 1.toByte() || borderMask[idx] == 1.toByte()) continue
				val p = pixels[idx]
				val lum = PixelColorMath.luminance(p)
				if (lum < 205f) continue
				if (PixelColorMath.colorDist(PixelColorMath.red(p), PixelColorMath.green(p), PixelColorMath.blue(p), bgR, bgG, bgB) <= 22f) {
					continue
				}
				if (PixelColorMath.hasMaskNeighborWithin(rawMask, ww, wh, x, y, 2)) {
					eraseMask[idx] = 1
				}
			}
		}
	}

	private fun sampleOuterRingStats(
		pixels: IntArray,
		ww: Int,
		wh: Int,
		eraseMask: ByteArray,
		borderMask: ByteArray,
		bgR: Int,
		bgG: Int,
		bgB: Int,
		x0: Int,
		y0: Int,
		x1: Int,
		y1: Int,
	): RingSampleStats {
		val maxCap = ((x1 - x0 + 1) * (y1 - y0 + 1)).coerceAtLeast(1)
		val ringIndices = IntArray(maxCap)
		var ringCount = 0

		for (y in y0..y1) {
			val row = y * ww
			for (x in x0..x1) {
				val idx = row + x
				if (eraseMask[idx] == 1.toByte() || borderMask[idx] == 1.toByte()) continue
				val p = pixels[idx]
				if (PixelColorMath.maxChannelDiff(p, bgR, bgG, bgB) > 60) continue
				if (PixelColorMath.hasMaskNeighborWithin(eraseMask, ww, wh, x, y, 3)) {
					ringIndices[ringCount++] = idx
				}
			}
		}

		if (ringCount == 0) {
			return RingSampleStats(bgR, bgG, bgB, 15f, 0)
		}

		val med = PixelColorMath.medianRgbFromIndices(pixels, ringIndices, ringCount)
		var sumSqR = 0.0
		var sumSqG = 0.0
		var sumSqB = 0.0
		for (i in 0 until ringCount) {
			val p = pixels[ringIndices[i]]
			val dr = (PixelColorMath.red(p) - med[0]).toDouble()
			val dg = (PixelColorMath.green(p) - med[1]).toDouble()
			val db = (PixelColorMath.blue(p) - med[2]).toDouble()
			sumSqR += dr * dr
			sumSqG += dg * dg
			sumSqB += db * db
		}
		val std = sqrt((sumSqR + sumSqG + sumSqB) / (3.0 * ringCount)).toFloat()
		return RingSampleStats(med[0], med[1], med[2], std, ringCount)
	}

	private fun expandAaEdgesInFlatBubble(
		pixels: IntArray,
		ww: Int,
		wh: Int,
		eraseMask: ByteArray,
		borderMask: ByteArray,
		ringMedR: Int,
		ringMedG: Int,
		ringMedB: Int,
		x0: Int,
		y0: Int,
		x1: Int,
		y1: Int,
	) {
		val additions = IntArray((x1 - x0 + 1) * (y1 - y0 + 1))
		var addCount = 0
		for (y in y0..y1) {
			val row = y * ww
			for (x in x0..x1) {
				val idx = row + x
				if (eraseMask[idx] == 1.toByte() || borderMask[idx] == 1.toByte()) continue
				val p = pixels[idx]
				val dist = PixelColorMath.colorDist(
					PixelColorMath.red(p), PixelColorMath.green(p), PixelColorMath.blue(p),
					ringMedR, ringMedG, ringMedB,
				)
				if (dist <= 14f) continue
				if (PixelColorMath.hasMaskNeighborWithin(eraseMask, ww, wh, x, y, 2)) {
					additions[addCount++] = idx
				}
			}
		}
		for (i in 0 until addCount) {
			eraseMask[additions[i]] = 1
		}
	}

	private fun runResidualVerificationPass(
		pixels: IntArray,
		ww: Int,
		wh: Int,
		eraseMask: ByteArray,
		borderMask: ByteArray,
		ring: RingSampleStats,
		gMinX: Int,
		gMinY: Int,
		gMaxX: Int,
		gMaxY: Int,
	) {
		val x0 = (gMinX - 2).coerceIn(3, max(3, ww - 4))
		val y0 = (gMinY - 2).coerceIn(3, max(3, wh - 4))
		val x1 = (gMaxX + 2).coerceIn(x0, max(x0, ww - 4))
		val y1 = (gMaxY + 2).coerceIn(y0, max(y0, wh - 4))
		if (x1 <= x0 || y1 <= y0) return

		val numPixels = (x1 - x0 + 1) * (y1 - y0 + 1)
		val devThreshold = max(36f, ring.stdDev * 2.2f)
		var resMask = ByteArray(ww * wh)
		var residualFound = 0

		for (y in y0..y1) {
			val row = y * ww
			for (x in x0..x1) {
				val idx = row + x
				if (borderMask[idx] == 1.toByte()) continue
				val p = pixels[idx]
				val r = PixelColorMath.red(p)
				val g = PixelColorMath.green(p)
				val b = PixelColorMath.blue(p)

				val pL = pixels[row + x - 3]
				val pR = pixels[row + x + 3]
				val pT = pixels[(y - 3) * ww + x]
				val pB = pixels[(y + 3) * ww + x]
				val nR = (PixelColorMath.red(pL) + PixelColorMath.red(pR) + PixelColorMath.red(pT) + PixelColorMath.red(pB)) / 4
				val nG = (PixelColorMath.green(pL) + PixelColorMath.green(pR) + PixelColorMath.green(pT) + PixelColorMath.green(pB)) / 4
				val nB = (PixelColorMath.blue(pL) + PixelColorMath.blue(pR) + PixelColorMath.blue(pT) + PixelColorMath.blue(pB)) / 4

				val dev = PixelColorMath.colorDist(r, g, b, nR, nG, nB)
				val distRing = PixelColorMath.colorDist(r, g, b, ring.medR, ring.medG, ring.medB)
				if (dev > devThreshold && distRing > 28f && eraseMask[idx] == 0.toByte()) {
					resMask[idx] = 1
					residualFound++
				}
			}
		}

		if (residualFound in 5 until (numPixels * 0.25f).toInt()) {
			val scratch = ByteArray(ww * wh)
			val bx0 = (x0 - 3).coerceAtLeast(0)
			val by0 = (y0 - 3).coerceAtLeast(0)
			val bx1 = (x1 + 3).coerceAtMost(ww - 1)
			val by1 = (y1 + 3).coerceAtMost(wh - 1)
			dilate3x3(resMask, scratch, ww, wh, bx0, by0, bx1, by1, borderMask)
			dilate3x3(scratch, resMask, ww, wh, bx0, by0, bx1, by1, borderMask)

			styleExtractor.inpaintTexturedRegion(
				pixels = pixels,
				ww = ww,
				wh = wh,
				eraseMask = resMask,
				borderMask = borderMask,
				ringMedR = ring.medR,
				ringMedG = ring.medG,
				ringMedB = ring.medB,
				regionX0 = bx0,
				regionY0 = by0,
				regionX1 = bx1,
				regionY1 = by1,
			)
		}
	}

	private fun computeRefinedLayout(
		bubble: OcrBubbleGroup,
		translatedText: String,
		explicitKind: TextBlockKind?,
		targetLang: String,
		extractedStyle: TextStyleFingerprint,
		imgW: Int,
		imgH: Int,
		wx0: Int,
		wy0: Int,
		wallBounds: BubbleWallBounds?,
		localLineRects: List<RectF>,
	): RefinedBlockLayout {
		val origRect = bubble.box.toRectF(imgW, imgH)
		val kind = explicitKind ?: when {
			bubble.kindHint != TextBlockKind.DIALOGUE -> bubble.kindHint
			abs(extractedStyle.rotationDeg) >= 6f || extractedStyle.gradientTopColor != null -> TextBlockKind.SFX
			extractedStyle.hasTextStroke && !extractedStyle.isDark &&
				PixelColorMath.saturation(
					PixelColorMath.red(extractedStyle.textColor),
					PixelColorMath.green(extractedStyle.textColor),
					PixelColorMath.blue(extractedStyle.textColor),
				) >= 0.30f -> TextBlockKind.SFX
			extractedStyle.isDark && origRect.width() / origRect.height().coerceAtLeast(1f) > 1.8f && bubble.box.width > 250f ->
				TextBlockKind.NARRATION
			else -> TextBlockKind.DIALOGUE
		}

		val refinedRect = if (wallBounds != null && wallBounds.hasClosedWalls) {
			val safeMargin = max(6f, imgW * 0.008f)
			val safeLeft = (wx0 + wallBounds.left + safeMargin).coerceAtMost(origRect.centerX() - 8f)
			val safeRight = (wx0 + wallBounds.right - safeMargin).coerceAtLeast(origRect.centerX() + 8f)
			val safeTop = (wy0 + wallBounds.top + safeMargin).coerceAtMost(origRect.centerY() - 8f)
			val safeBottom = (wy0 + wallBounds.bottom - safeMargin).coerceAtLeast(origRect.centerY() + 8f)
			val expLeft = min(origRect.left, (origRect.left + safeLeft) * 0.5f).coerceAtLeast(safeLeft)
			val expRight = max(origRect.right, (origRect.right + safeRight) * 0.5f).coerceAtMost(safeRight)
			val expTop = min(origRect.top, (origRect.top + safeTop) * 0.5f).coerceAtLeast(safeTop)
			val expBottom = max(origRect.bottom, (origRect.bottom + safeBottom) * 0.5f).coerceAtMost(safeBottom)
			RectF(
				expLeft.coerceIn(0f, (imgW - 4).toFloat()),
				expTop.coerceIn(0f, (imgH - 4).toFloat()),
				expRight.coerceIn(expLeft + 4f, imgW.toFloat()),
				expBottom.coerceIn(expTop + 4f, imgH.toFloat()),
			)
		} else {
			origRect
		}

		val refinedBox = NormalizedBox(
			yMin = (refinedRect.top / imgH * 1000f).coerceIn(0f, 1000f),
			xMin = (refinedRect.left / imgW * 1000f).coerceIn(0f, 1000f),
			yMax = (refinedRect.bottom / imgH * 1000f).coerceIn(0f, 1000f),
			xMax = (refinedRect.right / imgW * 1000f).coerceIn(0f, 1000f),
		)

		val formattedText = smartTextLayout.formatScanlationText(translatedText, targetLang)
		val isSingleLineStat = localLineRects.size <= 1 &&
			!formattedText.contains('\n') &&
			formattedText.length <= 34 &&
			STAT_SINGLE_LINE_REGEX.containsMatchIn(formattedText)

		val rotRad = abs(extractedStyle.rotationDeg) * PI.toFloat() / 180f
		val effBoxW: Float
		val effBoxH: Float
		if (rotRad > 0.05f) {
			val c = cos(rotRad)
			val s = sin(rotRad)
			effBoxW = (refinedRect.width() * c + refinedRect.height() * s) * 0.88f
			effBoxH = (refinedRect.height() * c + refinedRect.width() * s).coerceAtMost(refinedRect.height() * 1.15f) * 0.90f
		} else {
			effBoxW = refinedRect.width() * 0.92f
			effBoxH = refinedRect.height() * 0.92f
		}

		val availW = effBoxW.coerceAtLeast(16f)
		val availH = effBoxH.coerceAtLeast(16f)
		val lineHeightRatio = smartTextLayout.getLineHeightRatioForBlock(kind, extractedStyle)

		val minPx = max(9f, imgW * 0.012f)
		val targetInitialPx = (extractedStyle.fontSizeCqw * imgW / 100f).coerceIn(minPx, imgW * 0.085f)
		val maxPx = when (kind) {
			TextBlockKind.SFX -> max(targetInitialPx * 1.35f, min(availH * 0.85f, imgW * 0.11f))
			else -> max(targetInitialPx * 1.25f, min(availH * 0.78f, imgW * 0.075f))
		}.coerceAtLeast(minPx)

		val paint = TextPaint()
		var bestFontPx = minPx
		var bestWrap = SmartWrapResult(emptyList(), 0f, false)

		if (isSingleLineStat) {
			var lo = minPx
			var hi = min(availH * 0.82f, maxPx).coerceAtLeast(minPx)
			repeat(12) {
				val mid = (lo + hi) * 0.5f
				smartTextLayout.configureTextPaint(paint, extractedStyle, mid, formattedText)
				val w = smartTextLayout.measureStyledWordWidth(formattedText, paint)
				if (w <= availW * 0.96f && mid <= availH * 0.88f) {
					bestFontPx = mid
					bestWrap = SmartWrapResult(listOf(SmartWrappedLine(formattedText, w, 1f)), w, false)
					lo = mid
				} else {
					hi = mid
				}
			}
			if (bestWrap.lines.isEmpty()) {
				smartTextLayout.configureTextPaint(paint, extractedStyle, bestFontPx, formattedText)
				val w = smartTextLayout.measureStyledWordWidth(formattedText, paint)
				val scale = if (w > availW) (availW / max(1f, w)).coerceAtLeast(0.45f) else 1f
				bestWrap = SmartWrapResult(listOf(SmartWrappedLine(formattedText, w, scale)), w * scale, false)
			}
		} else {
			var lo = minPx
			var hi = maxPx
			repeat(14) {
				val mid = (lo + hi) * 0.5f
				smartTextLayout.configureTextPaint(paint, extractedStyle, mid, formattedText)
				val wrap = smartTextLayout.computeSmartWrappedLines(
					text = formattedText,
					maxWidthPx = availW,
					paint = paint,
					lineWidthsPct = extractedStyle.lineWidthsPct,
					emphasisLastLineScale = extractedStyle.emphasisLastLineScale,
				)
				val totalH = if (wrap.lines.isEmpty()) {
					0f
				} else {
					(wrap.lines.size - 1) * mid * lineHeightRatio + mid
				}
				val fits = wrap.lines.isNotEmpty() &&
					!wrap.hasOverflow &&
					totalH <= availH * 0.94f &&
					wrap.maxLineWidthPx <= availW * 0.98f
				if (fits) {
					bestFontPx = mid
					bestWrap = wrap
					lo = mid
				} else {
					hi = mid
				}
			}

			if (bestWrap.lines.isEmpty()) {
				smartTextLayout.configureTextPaint(paint, extractedStyle, minPx, formattedText)
				bestWrap = smartTextLayout.computeSmartWrappedLines(
					text = formattedText,
					maxWidthPx = availW,
					paint = paint,
					lineWidthsPct = extractedStyle.lineWidthsPct,
					emphasisLastLineScale = extractedStyle.emphasisLastLineScale,
				)
				// Ensure tall dense translations shrink cleanly to avoid vertical overflow.
				while (bestWrap.lines.isNotEmpty() && bestFontPx > 6f) {
					val totalH = (bestWrap.lines.size - 1) * bestFontPx * lineHeightRatio + bestFontPx
					if (totalH <= availH * 0.96f) break
					bestFontPx *= 0.88f
					smartTextLayout.configureTextPaint(paint, extractedStyle, bestFontPx, formattedText)
					bestWrap = smartTextLayout.computeSmartWrappedLines(
						text = formattedText,
						maxWidthPx = availW,
						paint = paint,
						lineWidthsPct = extractedStyle.lineWidthsPct,
						emphasisLastLineScale = extractedStyle.emphasisLastLineScale,
					)
				}
			}
		}

		val finalStyle = extractedStyle.copy(
			fontSizeCqw = ((bestFontPx / imgW.coerceAtLeast(1)) * 100f).coerceIn(0.8f, 18f),
		)

		return RefinedBlockLayout(
			bubble = bubble,
			translatedText = formattedText,
			kind = kind,
			refinedBox = refinedBox,
			style = finalStyle,
			wrappedLines = bestWrap.lines,
			fontPx = bestFontPx,
			lineHeightRatio = lineHeightRatio,
			isSingleLineStat = isSingleLineStat,
		)
	}

	private fun buildIntegralLum(pixels: IntArray, ww: Int, wh: Int): DoubleArray {
		val iw = ww + 1
		val sum = DoubleArray(iw * (wh + 1))
		for (y in 0 until wh) {
			val pRow = y * ww
			val sRow = (y + 1) * iw
			val prevRow = y * iw
			var rowAcc = 0.0
			for (x in 0 until ww) {
				rowAcc += PixelColorMath.luminance(pixels[pRow + x])
				sum[sRow + x + 1] = sum[prevRow + x + 1] + rowAcc
			}
		}
		return sum
	}

	private fun queryIntegralMean(
		integral: DoubleArray,
		ww: Int,
		x0: Int,
		y0: Int,
		x1: Int,
		y1: Int,
	): Float {
		val iw = ww + 1
		val area = ((x1 - x0 + 1) * (y1 - y0 + 1)).coerceAtLeast(1)
		val s = integral[(y1 + 1) * iw + (x1 + 1)] -
			integral[y0 * iw + (x1 + 1)] -
			integral[(y1 + 1) * iw + x0] +
			integral[y0 * iw + x0]
		return (s / area).toFloat()
	}

	private fun isNearAnyRect(x: Float, y: Float, rects: List<RectF>, pad: Float): Boolean {
		for (r in rects) {
			if (x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad) {
				return true
			}
		}
		return false
	}

	private companion object {
		val STAT_SINGLE_LINE_REGEX = Regex("^[A-Za-z0-9ÇĞIİÖŞÜçğıöşü\\s/+%-]{1,18}:\\s*[-+]?\\d")
	}
}
