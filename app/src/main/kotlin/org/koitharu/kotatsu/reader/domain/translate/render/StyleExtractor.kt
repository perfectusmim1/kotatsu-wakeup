package org.koitharu.kotatsu.reader.domain.translate.render

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.koitharu.kotatsu.reader.domain.translate.model.FontStyleMode
import org.koitharu.kotatsu.reader.domain.translate.model.NormalizedBox
import org.koitharu.kotatsu.reader.domain.translate.model.ScanlationFontKey
import org.koitharu.kotatsu.reader.domain.translate.model.TextBlockKind
import org.koitharu.kotatsu.reader.domain.translate.model.TextStyleFingerprint

internal object PixelColorMath {

	fun red(argb: Int): Int = (argb ushr 16) and 0xFF

	fun green(argb: Int): Int = (argb ushr 8) and 0xFF

	fun blue(argb: Int): Int = argb and 0xFF

	fun clampByte(v: Int): Int = v.coerceIn(0, 255)

	fun packRgb(r: Int, g: Int, b: Int): Int =
		(0xFF shl 24) or (clampByte(r) shl 16) or (clampByte(g) shl 8) or clampByte(b)

	fun luminance(r: Int, g: Int, b: Int): Float = 0.299f * r + 0.587f * g + 0.114f * b

	fun luminance(argb: Int): Float = luminance(red(argb), green(argb), blue(argb))

	fun colorDistSq(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Int {
		val dr = r1 - r2
		val dg = g1 - g2
		val db = b1 - b2
		return dr * dr + dg * dg + db * db
	}

	fun colorDist(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Float =
		sqrt(colorDistSq(r1, g1, b1, r2, g2, b2).toFloat())

	fun colorDist(argb1: Int, argb2: Int): Float = colorDist(
		red(argb1), green(argb1), blue(argb1),
		red(argb2), green(argb2), blue(argb2),
	)

	fun maxChannelDiff(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Int =
		max(abs(r1 - r2), max(abs(g1 - g2), abs(b1 - b2)))

	fun maxChannelDiff(argb: Int, r2: Int, g2: Int, b2: Int): Int =
		maxChannelDiff(red(argb), green(argb), blue(argb), r2, g2, b2)

	fun saturation(r: Int, g: Int, b: Int): Float {
		val maxC = max(r, max(g, b))
		if (maxC <= 0) return 0f
		val minC = min(r, min(g, b))
		return (maxC - minC).toFloat() / maxC.toFloat()
	}

	fun blendRgb(c1: Int, c2: Int, t: Float): Int {
		val f = t.coerceIn(0f, 1f)
		val inv = 1f - f
		val r = (red(c1) * inv + red(c2) * f).roundToInt()
		val g = (green(c1) * inv + green(c2) * f).roundToInt()
		val b = (blue(c1) * inv + blue(c2) * f).roundToInt()
		return packRgb(r, g, b)
	}

	/**
	 * O(N) counting-sort median across 0..255 channel buckets without per-pixel boxing.
	 */
	fun medianRgbFromIndices(
		pixels: IntArray,
		indices: IntArray,
		count: Int,
	): IntArray {
		if (count <= 0) return intArrayOf(255, 255, 255)
		val histR = IntArray(256)
		val histG = IntArray(256)
		val histB = IntArray(256)
		for (i in 0 until count) {
			val p = pixels[indices[i]]
			histR[red(p)]++
			histG[green(p)]++
			histB[blue(p)]++
		}
		val mid = count / 2
		return intArrayOf(
			findHistogramMedian(histR, mid),
			findHistogramMedian(histG, mid),
			findHistogramMedian(histB, mid),
		)
	}

	fun findHistogramMedian(hist: IntArray, targetIndex: Int): Int {
		var acc = 0
		for (v in 0..255) {
			acc += hist[v]
			if (acc > targetIndex) return v
		}
		return 128
	}

	fun hasMaskNeighborWithin(
		mask: ByteArray,
		ww: Int,
		wh: Int,
		x: Int,
		y: Int,
		radius: Int,
	): Boolean {
		val x0 = (x - radius).coerceAtLeast(0)
		val y0 = (y - radius).coerceAtLeast(0)
		val x1 = (x + radius).coerceAtMost(ww - 1)
		val y1 = (y + radius).coerceAtMost(wh - 1)
		for (ny in y0..y1) {
			val row = ny * ww
			for (nx in x0..x1) {
				if (mask[row + nx] == 1.toByte()) return true
			}
		}
		return false
	}
}

data class EnclosedFillResult(
	val enclosedMask: ByteArray,
	val enclosedCount: Int,
)

data class StyleExtractionInput(
	val pixels: IntArray,
	val ww: Int,
	val wh: Int,
	val glyphMask: ByteArray,
	val bgR: Int,
	val bgG: Int,
	val bgB: Int,
	val imageWidth: Int,
	val imageHeight: Int,
	val gMinX: Int,
	val gMinY: Int,
	val gMaxX: Int,
	val gMaxY: Int,
	val blockBox: NormalizedBox,
	val kindHint: TextBlockKind,
	val lineCount: Int,
	val text: String,
)

@Singleton
class StyleExtractor @Inject constructor() {

	fun fillEnclosedGlyphInteriors(
		pixels: IntArray,
		ww: Int,
		wh: Int,
		rawMask: ByteArray,
		borderMask: ByteArray,
		gMinX: Int,
		gMinY: Int,
		gMaxX: Int,
		gMaxY: Int,
		bgR: Int,
		bgG: Int,
		bgB: Int,
	): EnclosedFillResult {
		val enclosedMask = ByteArray(ww * wh)
		if (gMaxX < gMinX || gMaxY < gMinY || ww <= 0 || wh <= 0) {
			return EnclosedFillResult(enclosedMask, 0)
		}

		val x0 = (gMinX - 2).coerceAtLeast(0)
		val y0 = (gMinY - 2).coerceAtLeast(0)
		val x1 = (gMaxX + 2).coerceAtMost(ww - 1)
		val y1 = (gMaxY + 2).coerceAtMost(wh - 1)
		val bw = x1 - x0 + 1
		val bh = y1 - y0 + 1
		if (bw <= 2 || bh <= 2) {
			return EnclosedFillResult(enclosedMask, 0)
		}

		val closedWall = BooleanArray(ww * wh)
		var rawCount = 0
		var rawLumSum = 0.0
		for (y in y0..y1) {
			val rowOffset = y * ww
			for (x in x0..x1) {
				val idx = rowOffset + x
				val isRaw = rawMask[idx] == 1.toByte()
				if (isRaw) {
					rawCount++
					rawLumSum += PixelColorMath.luminance(pixels[idx])
					closedWall[idx] = true
				} else if (borderMask[idx] == 1.toByte()) {
					closedWall[idx] = true
				}
			}
		}
		if (rawCount == 0) {
			return EnclosedFillResult(enclosedMask, 0)
		}
		val rawLum = (rawLumSum / rawCount).toFloat()

		// 1px morphological closing bridges anti-aliased pinholes in closed letter loops.
		for (y in (y0 + 1)..(y1 - 1)) {
			val rowOffset = y * ww
			for (x in (x0 + 1)..(x1 - 1)) {
				val idx = rowOffset + x
				if (closedWall[idx]) continue
				var nCount = 0
				for (dy in -1..1) {
					val nr = (y + dy) * ww
					for (dx in -1..1) {
						if (dx == 0 && dy == 0) continue
						if (rawMask[nr + x + dx] == 1.toByte()) nCount++
					}
				}
				if (nCount >= 4) {
					closedWall[idx] = true
				}
			}
		}

		val visitedOuter = BooleanArray(ww * wh)
		val queue = IntArray(bw * bh)
		var qHead = 0
		var qTail = 0

		fun enqueueIfOpen(px: Int, py: Int) {
			val idx = py * ww + px
			if (!closedWall[idx] && !visitedOuter[idx]) {
				visitedOuter[idx] = true
				queue[qTail++] = idx
			}
		}

		for (x in x0..x1) {
			enqueueIfOpen(x, y0)
			enqueueIfOpen(x, y1)
		}
		for (y in (y0 + 1)..(y1 - 1)) {
			enqueueIfOpen(x0, y)
			enqueueIfOpen(x1, y)
		}

		while (qHead < qTail) {
			val curr = queue[qHead++]
			val cx = curr % ww
			val cy = curr / ww
			if (cx > x0) enqueueIfOpen(cx - 1, cy)
			if (cx < x1) enqueueIfOpen(cx + 1, cy)
			if (cy > y0) enqueueIfOpen(cx, cy - 1)
			if (cy < y1) enqueueIfOpen(cx, cy + 1)
		}

		val glyphW = (gMaxX - gMinX + 1).coerceAtLeast(1)
		val glyphH = (gMaxY - gMinY + 1).coerceAtLeast(1)
		val maxHoleArea = max(400, (glyphW * glyphH * 0.42f).toInt())
		val bgLum = PixelColorMath.luminance(bgR, bgG, bgB)

		val seenPocket = BooleanArray(ww * wh)
		val compBuf = IntArray(bw * bh)
		val acceptedIndices = IntArray(bw * bh)
		var totalEnclosed = 0
		val maxAllowedTotal = max(48, (rawCount * 0.36f).toInt())

		for (y in y0..y1) {
			val rowOffset = y * ww
			for (x in x0..x1) {
				val startIdx = rowOffset + x
				if (closedWall[startIdx] || visitedOuter[startIdx] || seenPocket[startIdx]) continue

				var cHead = 0
				var cTail = 0
				seenPocket[startIdx] = true
				compBuf[cTail++] = startIdx

				var sumR = 0L
				var sumG = 0L
				var sumB = 0L
				while (cHead < cTail) {
					val cur = compBuf[cHead++]
					val p = pixels[cur]
					sumR += PixelColorMath.red(p)
					sumG += PixelColorMath.green(p)
					sumB += PixelColorMath.blue(p)

					val px = cur % ww
					val py = cur / ww
					if (px > x0) {
						val ni = cur - 1
						if (!closedWall[ni] && !visitedOuter[ni] && !seenPocket[ni]) {
							seenPocket[ni] = true
							compBuf[cTail++] = ni
						}
					}
					if (px < x1) {
						val ni = cur + 1
						if (!closedWall[ni] && !visitedOuter[ni] && !seenPocket[ni]) {
							seenPocket[ni] = true
							compBuf[cTail++] = ni
						}
					}
					if (py > y0) {
						val ni = cur - ww
						if (!closedWall[ni] && !visitedOuter[ni] && !seenPocket[ni]) {
							seenPocket[ni] = true
							compBuf[cTail++] = ni
						}
					}
					if (py < y1) {
						val ni = cur + ww
						if (!closedWall[ni] && !visitedOuter[ni] && !seenPocket[ni]) {
							seenPocket[ni] = true
							compBuf[cTail++] = ni
						}
					}
				}

				if (cTail <= 0 || cTail > maxHoleArea) continue
				val holeR = (sumR / cTail).toInt()
				val holeG = (sumG / cTail).toInt()
				val holeB = (sumB / cTail).toInt()
				val holeLum = PixelColorMath.luminance(holeR, holeG, holeB)
				val dHoleToBg = PixelColorMath.colorDist(holeR, holeG, holeB, bgR, bgG, bgB)
				val maxDiffHoleBg = PixelColorMath.maxChannelDiff(holeR, holeG, holeB, bgR, bgG, bgB)

				val isOutlinedDarkHole = rawLum >= 185f && holeLum < rawLum - 65f && glyphH >= 28
				val isCounterHole = (dHoleToBg < 42f || maxDiffHoleBg < 48 || bgLum >= 42f) &&
					cTail <= min(maxHoleArea, 360)

				if (isOutlinedDarkHole || isCounterHole) {
					if (totalEnclosed + cTail <= maxAllowedTotal || isCounterHole && cTail <= 96) {
						for (k in 0 until cTail) {
							val idx = compBuf[k]
							if (borderMask[idx] == 0.toByte() && rawMask[idx] == 0.toByte()) {
								acceptedIndices[totalEnclosed++] = idx
							}
						}
					}
				}
			}
		}

		// Apply 36% enclosed-area guard over total glyph pixels before committing mask.
		val finalCount = if (totalEnclosed <= maxAllowedTotal) {
			totalEnclosed
		} else {
			0
		}
		for (i in 0 until finalCount) {
			enclosedMask[acceptedIndices[i]] = 1
		}
		return EnclosedFillResult(enclosedMask = enclosedMask, enclosedCount = finalCount)
	}

	fun extractStyleFingerprint(input: StyleExtractionInput): TextStyleFingerprint {
		val bgColor = PixelColorMath.packRgb(input.bgR, input.bgG, input.bgB)
		val bgLum = PixelColorMath.luminance(input.bgR, input.bgG, input.bgB)
		val isDark = bgLum < 128f
		val defaultText = if (isDark) 0xFFFFFFFF.toInt() else 0xFF111111.toInt()

		if (input.gMaxX < input.gMinX || input.gMaxY < input.gMinY) {
			return TextStyleFingerprint(
				bgColor = bgColor,
				textColor = defaultText,
				strokeColor = bgColor,
				isDark = isDark,
				fontFamily = defaultFontForKind(input.kindHint, isDark),
			)
		}

		val pMinX = input.gMinX.coerceIn(0, input.ww - 1)
		val pMinY = input.gMinY.coerceIn(0, input.wh - 1)
		val pMaxX = input.gMaxX.coerceIn(pMinX, input.ww - 1)
		val pMaxY = input.gMaxY.coerceIn(pMinY, input.wh - 1)
		val bw = pMaxX - pMinX + 1
		val bh = pMaxY - pMinY + 1

		// 2-pass Manhattan Chamfer Distance Transform with 1-cell outside padding.
		val dw = bw + 2
		val dh = bh + 2
		val dist = IntArray(dw * dh)
		for (y in 0 until bh) {
			val wy = pMinY + y
			val rowW = wy * input.ww
			val rowD = (y + 1) * dw
			for (x in 0 until bw) {
				if (input.glyphMask[rowW + pMinX + x] == 1.toByte()) {
					dist[rowD + x + 1] = 9999
				}
			}
		}
		for (y in 1..bh) {
			val rowD = y * dw
			for (x in 1..bw) {
				val idx = rowD + x
				if (dist[idx] > 0) {
					val cand = min(dist[idx - dw], dist[idx - 1]) + 1
					if (cand < dist[idx]) dist[idx] = cand
				}
			}
		}
		var maxDist = 0
		for (y in bh downTo 1) {
			val rowD = y * dw
			for (x in bw downTo 1) {
				val idx = rowD + x
				if (dist[idx] > 0) {
					val cand = min(dist[idx + dw], dist[idx + 1]) + 1
					if (cand < dist[idx]) dist[idx] = cand
					if (dist[idx] > maxDist) maxDist = dist[idx]
				}
			}
		}

		if (maxDist == 0) {
			return TextStyleFingerprint(
				bgColor = bgColor,
				textColor = defaultText,
				strokeColor = bgColor,
				isDark = isDark,
				fontFamily = defaultFontForKind(input.kindHint, isDark),
			)
		}

		val coreThreshold = if (maxDist >= 3) max(2, (maxDist * 0.55f).toInt()) else 1
		val maxFgCap = bw * bh
		val coreIdx = IntArray(maxFgCap)
		val edgeIdx = IntArray(maxFgCap)
		val topCoreIdx = IntArray(maxFgCap)
		val botCoreIdx = IntArray(maxFgCap)
		var coreCount = 0
		var edgeCount = 0
		var topCoreCount = 0
		var botCoreCount = 0

		val topCutoffY = pMinY + bh * 0.32f
		val botCutoffY = pMinY + bh * 0.68f

		for (y in 0 until bh) {
			val wy = pMinY + y
			val rowW = wy * input.ww
			val rowD = (y + 1) * dw
			for (x in 0 until bw) {
				val d = dist[rowD + x + 1]
				if (d <= 0) continue
				val wIdx = rowW + pMinX + x
				if (d >= coreThreshold) {
					coreIdx[coreCount++] = wIdx
					if (wy <= topCutoffY) {
						topCoreIdx[topCoreCount++] = wIdx
					} else if (wy >= botCutoffY) {
						botCoreIdx[botCoreCount++] = wIdx
					}
				}
				if (d == 1) {
					edgeIdx[edgeCount++] = wIdx
				}
			}
		}

		if (coreCount == 0 && edgeCount > 0) {
			System.arraycopy(edgeIdx, 0, coreIdx, 0, edgeCount)
			coreCount = edgeCount
		}

		val coreRgb = PixelColorMath.medianRgbFromIndices(input.pixels, coreIdx, coreCount)
		val coreLum = PixelColorMath.luminance(coreRgb[0], coreRgb[1], coreRgb[2])
		val snapTextRgb = when {
			!isDark && coreLum <= 34f && PixelColorMath.saturation(coreRgb[0], coreRgb[1], coreRgb[2]) < 0.2f ->
				intArrayOf(17, 17, 17)
			isDark && coreLum >= 232f && PixelColorMath.saturation(coreRgb[0], coreRgb[1], coreRgb[2]) < 0.16f ->
				intArrayOf(255, 255, 255)
			else -> coreRgb
		}
		val textColor = PixelColorMath.packRgb(snapTextRgb[0], snapTextRgb[1], snapTextRgb[2])

		val edgeRgb = if (edgeCount > 0) {
			PixelColorMath.medianRgbFromIndices(input.pixels, edgeIdx, edgeCount)
		} else {
			coreRgb
		}
		val edgeLum = PixelColorMath.luminance(edgeRgb[0], edgeRgb[1], edgeRgb[2])

		// White halo probe + dark-box colored glow probe in 1..3px rim outside glyphMask.
		val haloX0 = (pMinX - 3).coerceAtLeast(0)
		val haloY0 = (pMinY - 3).coerceAtLeast(0)
		val haloX1 = (pMaxX + 3).coerceAtMost(input.ww - 1)
		val haloY1 = (pMaxY + 3).coerceAtMost(input.wh - 1)
		var probeTotal = 0
		var whiteHaloHits = 0
		val glowIdx = IntArray((haloX1 - haloX0 + 1) * (haloY1 - haloY0 + 1))
		var glowCount = 0

		for (y in haloY0..haloY1) {
			val rowW = y * input.ww
			for (x in haloX0..haloX1) {
				val idx = rowW + x
				if (input.glyphMask[idx] != 0.toByte()) continue
				if (!PixelColorMath.hasMaskNeighborWithin(input.glyphMask, input.ww, input.wh, x, y, 2)) continue
				val p = input.pixels[idx]
				val pr = PixelColorMath.red(p)
				val pg = PixelColorMath.green(p)
				val pb = PixelColorMath.blue(p)
				val lum = PixelColorMath.luminance(pr, pg, pb)
				val sat = PixelColorMath.saturation(pr, pg, pb)
				probeTotal++
				if (lum >= 225f && sat < 0.16f) {
					whiteHaloHits++
				}
				if (isDark && sat >= 0.22f && lum >= 70f) {
					glowIdx[glowCount++] = idx
				}
			}
		}

		val whiteHaloRatio = if (probeTotal > 0) whiteHaloHits.toFloat() / probeTotal.toFloat() else 0f
		val hasOuterHaloStroke = bgLum < 220f && coreLum < 150f && whiteHaloRatio >= 0.65f

		var hasTextStroke = false
		var strokeColor = bgColor
		var strokeWidthEm = 0f
		var shadowColor: Int? = null
		var shadowBlurEm = 0f

		when {
			hasOuterHaloStroke -> {
				hasTextStroke = true
				strokeColor = 0xFFFFFFFF.toInt()
				strokeWidthEm = 0.13f
				shadowColor = 0x55FFFFFF
				shadowBlurEm = 0.06f
			}
			!isDark && coreLum >= 205f && edgeLum < coreLum - 45f -> {
				hasTextStroke = true
				strokeColor = PixelColorMath.packRgb(edgeRgb[0], edgeRgb[1], edgeRgb[2])
				strokeWidthEm = 0.12f
			}
			isDark && coreLum >= 190f && glowCount >= max(6, probeTotal / 8) -> {
				val glowRgb = PixelColorMath.medianRgbFromIndices(input.pixels, glowIdx, glowCount)
				val glowPacked = PixelColorMath.packRgb(glowRgb[0], glowRgb[1], glowRgb[2])
				shadowColor = glowPacked
				shadowBlurEm = 0.18f
				hasTextStroke = true
				strokeColor = if (edgeLum < coreLum - 36f) {
					PixelColorMath.packRgb(edgeRgb[0], edgeRgb[1], edgeRgb[2])
				} else {
					PixelColorMath.blendRgb(glowPacked, 0xFF0A1428.toInt(), 0.42f)
				}
				strokeWidthEm = 0.065f
			}
		}

		// Vertical linear gradient detection when top and bottom core hues diverge within 24..155.
		var gradientTopColor: Int? = null
		var gradientBottomColor: Int? = null
		if (bh >= 16 && topCoreCount >= 10 && botCoreCount >= 10) {
			val topRgb = PixelColorMath.medianRgbFromIndices(input.pixels, topCoreIdx, topCoreCount)
			val botRgb = PixelColorMath.medianRgbFromIndices(input.pixels, botCoreIdx, botCoreCount)
			val gradDiff = PixelColorMath.colorDist(
				topRgb[0], topRgb[1], topRgb[2],
				botRgb[0], botRgb[1], botRgb[2],
			)
			if (gradDiff in 24f..155f) {
				gradientTopColor = PixelColorMath.packRgb(topRgb[0], topRgb[1], topRgb[2])
				gradientBottomColor = PixelColorMath.packRgb(botRgb[0], botRgb[1], botRgb[2])
			}
		}

		val rotationDeg = estimateRotationDeg(
			glyphMask = input.glyphMask,
			ww = input.ww,
			x0 = pMinX,
			y0 = pMinY,
			x1 = pMaxX,
			y1 = pMaxY,
			lineCount = input.lineCount,
		)

		val textSat = PixelColorMath.saturation(snapTextRgb[0], snapTextRgb[1], snapTextRgb[2])
		val kind = classifyBlockKind(
			kindHint = input.kindHint,
			isDark = isDark,
			hasGradient = gradientTopColor != null,
			hasStroke = hasTextStroke && !hasOuterHaloStroke,
			textSat = textSat,
			rotationDeg = rotationDeg,
			blockBox = input.blockBox,
			text = input.text,
		)

		val fontFamily = classifyFontFamily(
			kind = kind,
			isDark = isDark,
			maxStrokeRadius = maxDist,
			hasGradient = gradientTopColor != null,
			hasGlow = shadowColor != null && isDark,
		)
		val fontWeight = if (maxDist >= 4 || kind == TextBlockKind.SFX || kind == TextBlockKind.EMPHASIS) 800 else 700
		val fontStyle = if (kind == TextBlockKind.EMPHASIS && abs(rotationDeg) < 2f) FontStyleMode.ITALIC else FontStyleMode.NORMAL

		val safeLines = input.lineCount.coerceAtLeast(1)
		val estLineH = (bh.toFloat() / safeLines) * 0.78f
		val fontSizeCqw = ((estLineH / input.imageWidth.coerceAtLeast(1)) * 100f).coerceIn(1.5f, 12.0f)

		return TextStyleFingerprint(
			fontFamily = fontFamily,
			fontWeight = fontWeight,
			fontStyle = fontStyle,
			bgColor = bgColor,
			textColor = textColor,
			gradientTopColor = gradientTopColor,
			gradientBottomColor = gradientBottomColor,
			strokeColor = strokeColor,
			strokeWidthEm = strokeWidthEm,
			shadowColor = shadowColor,
			shadowBlurEm = shadowBlurEm,
			isDark = isDark,
			hasTextStroke = hasTextStroke,
			fontSizeCqw = fontSizeCqw,
			rotationDeg = rotationDeg,
			skewDeg = 0f,
			letterSpacingEm = if (kind == TextBlockKind.SFX) 0.02f else 0f,
			lineWidthsPct = emptyList(),
			emphasisLastLineScale = 1f,
		)
	}

	/**
	 * Tier 2 & 3 textured background reconstruction: directional edge gradient anisotropy,
	 * orthogonal clean-boundary span interpolation, layer-by-layer 5x5 Telea FMM propagation,
	 * and 10 iterations of anisotropic harmonic smoothing.
	 */
	fun inpaintTexturedRegion(
		pixels: IntArray,
		ww: Int,
		wh: Int,
		eraseMask: ByteArray,
		borderMask: ByteArray,
		ringMedR: Int,
		ringMedG: Int,
		ringMedB: Int,
		regionX0: Int,
		regionY0: Int,
		regionX1: Int,
		regionY1: Int,
	) {
		val x0 = regionX0.coerceIn(0, ww - 1)
		val y0 = regionY0.coerceIn(0, wh - 1)
		val x1 = regionX1.coerceIn(x0, ww - 1)
		val y1 = regionY1.coerceIn(y0, wh - 1)
		val rw = x1 - x0 + 1
		val rh = y1 - y0 + 1
		if (rw <= 0 || rh <= 0) return

		val anisoH = measureBoundaryAnisotropy(
			pixels = pixels,
			ww = ww,
			wh = wh,
			eraseMask = eraseMask,
			borderMask = borderMask,
			ringMedR = ringMedR,
			ringMedG = ringMedG,
			ringMedB = ringMedB,
			x0 = x0,
			y0 = y0,
			x1 = x1,
			y1 = y1,
		)

		val spanR = FloatArray(ww * wh)
		val spanG = FloatArray(ww * wh)
		val spanB = FloatArray(ww * wh)

		computeOrthogonalSpanEstimate(
			pixels = pixels,
			ww = ww,
			wh = wh,
			eraseMask = eraseMask,
			borderMask = borderMask,
			ringMedR = ringMedR,
			ringMedG = ringMedG,
			ringMedB = ringMedB,
			x0 = x0,
			y0 = y0,
			x1 = x1,
			y1 = y1,
			anisoH = anisoH,
			outR = spanR,
			outG = spanG,
			outB = spanB,
		)

		val wr = FloatArray(ww * wh)
		val wg = FloatArray(ww * wh)
		val wb = FloatArray(ww * wh)
		val filled = BooleanArray(ww * wh)
		val inQueue = BooleanArray(ww * wh)

		for (y in y0..y1) {
			val row = y * ww
			for (x in x0..x1) {
				val idx = row + x
				if (eraseMask[idx] == 0.toByte()) {
					filled[idx] = true
					val p = pixels[idx]
					wr[idx] = PixelColorMath.red(p).toFloat()
					wg[idx] = PixelColorMath.green(p).toFloat()
					wb[idx] = PixelColorMath.blue(p).toFloat()
				}
			}
		}

		// Layer-by-layer Telea FMM frontier BFS.
		var frontier = IntArray(rw * rh)
		var nextFrontier = IntArray(rw * rh)
		var fCount = 0

		for (y in y0..y1) {
			val row = y * ww
			for (x in x0..x1) {
				val idx = row + x
				if (eraseMask[idx] != 1.toByte()) continue
				val hasKnownNeighbor =
					(x > x0 && filled[idx - 1]) ||
						(x < x1 && filled[idx + 1]) ||
						(y > y0 && filled[idx - ww]) ||
						(y < y1 && filled[idx + ww])
				if (hasKnownNeighbor) {
					inQueue[idx] = true
					frontier[fCount++] = idx
				}
			}
		}

		while (fCount > 0) {
			var nextCount = 0
			for (i in 0 until fCount) {
				val idx = frontier[i]
				val px = idx % ww
				val py = idx / ww

				// Local gradient estimate on already-filled neighbors guides directional weighting.
				val gx = if (px > 0 && px < ww - 1 && filled[idx - 1] && filled[idx + 1]) {
					(wr[idx + 1] - wr[idx - 1]) * 0.299f +
						(wg[idx + 1] - wg[idx - 1]) * 0.587f +
						(wb[idx + 1] - wb[idx - 1]) * 0.114f
				} else 0f
				val gy = if (py > 0 && py < wh - 1 && filled[idx - ww] && filled[idx + ww]) {
					(wr[idx + ww] - wr[idx - ww]) * 0.299f +
						(wg[idx + ww] - wg[idx - ww]) * 0.587f +
						(wb[idx + ww] - wb[idx - ww]) * 0.114f
				} else 0f
				val gMag = sqrt(gx * gx + gy * gy)

				var wSum = 0f
				var accR = 0f
				var accG = 0f
				var accB = 0f

				for (dy in -2..2) {
					val ny = py + dy
					if (ny < 0 || ny >= wh) continue
					val nRow = ny * ww
					for (dx in -2..2) {
						if (dx == 0 && dy == 0) continue
						val nx = px + dx
						if (nx < 0 || nx >= ww) continue
						val nIdx = nRow + nx
						val nFilled = if (nx in x0..x1 && ny in y0..y1) {
							filled[nIdx]
						} else {
							eraseMask[nIdx] == 0.toByte()
						}
						if (!nFilled) continue

						val distSq = (dx * dx + dy * dy).toFloat()
						val dirFactor = if (gMag > 1f) {
							// Isophote tangent is perpendicular to intensity gradient (gx, gy).
							val tx = -gy / gMag
							val ty = gx / gMag
							val dLen = sqrt(distSq)
							val align = abs((dx * tx + dy * ty) / dLen)
							0.35f + 0.65f * align
						} else {
							1f
						}

						val isOrigBoundary = eraseMask[nIdx] == 0.toByte()
						val nR: Float
						val nG: Float
						val nB: Float
						val outlierPenalty: Float
						if (isOrigBoundary) {
							val p = pixels[nIdx]
							val r = PixelColorMath.red(p)
							val g = PixelColorMath.green(p)
							val b = PixelColorMath.blue(p)
							val isOutlier = borderMask[nIdx] == 1.toByte() ||
								PixelColorMath.maxChannelDiff(r, g, b, ringMedR, ringMedG, ringMedB) > 58
							if (isOutlier) {
								nR = ringMedR.toFloat()
								nG = ringMedG.toFloat()
								nB = ringMedB.toFloat()
								outlierPenalty = 0.25f
							} else {
								nR = r.toFloat()
								nG = g.toFloat()
								nB = b.toFloat()
								outlierPenalty = 1f
							}
						} else {
							nR = wr[nIdx]
							nG = wg[nIdx]
							nB = wb[nIdx]
							outlierPenalty = 1f
						}

						val w = (dirFactor * outlierPenalty) / distSq
						wSum += w
						accR += w * nR
						accG += w * nG
						accB += w * nB
					}
				}

				val fmmR = if (wSum > 0f) accR / wSum else ringMedR.toFloat()
				val fmmG = if (wSum > 0f) accG / wSum else ringMedG.toFloat()
				val fmmB = if (wSum > 0f) accB / wSum else ringMedB.toFloat()

				wr[idx] = 0.55f * spanR[idx] + 0.45f * fmmR
				wg[idx] = 0.55f * spanG[idx] + 0.45f * fmmG
				wb[idx] = 0.55f * spanB[idx] + 0.45f * fmmB
				filled[idx] = true

				if (px > x0) {
					val ni = idx - 1
					if (eraseMask[ni] == 1.toByte() && !filled[ni] && !inQueue[ni]) {
						inQueue[ni] = true
						nextFrontier[nextCount++] = ni
					}
				}
				if (px < x1) {
					val ni = idx + 1
					if (eraseMask[ni] == 1.toByte() && !filled[ni] && !inQueue[ni]) {
						inQueue[ni] = true
						nextFrontier[nextCount++] = ni
					}
				}
				if (py > y0) {
					val ni = idx - ww
					if (eraseMask[ni] == 1.toByte() && !filled[ni] && !inQueue[ni]) {
						inQueue[ni] = true
						nextFrontier[nextCount++] = ni
					}
				}
				if (py < y1) {
					val ni = idx + ww
					if (eraseMask[ni] == 1.toByte() && !filled[ni] && !inQueue[ni]) {
						inQueue[ni] = true
						nextFrontier[nextCount++] = ni
					}
				}
			}

			val tmp = frontier
			frontier = nextFrontier
			nextFrontier = tmp
			fCount = nextCount
		}

		// 10 iterations of anisotropic harmonic smoothing (preserves stripes/gradients along dominant axis).
		val wH = (0.25f * anisoH).coerceIn(0.12f, 0.38f)
		val wV = 0.5f - wH
		for (iter in 0 until 10) {
			for (y in (y0 + 1).coerceAtLeast(1) until y1.coerceAtMost(wh - 1)) {
				val row = y * ww
				for (x in (x0 + 1).coerceAtLeast(1) until x1.coerceAtMost(ww - 1)) {
					val idx = row + x
					if (eraseMask[idx] != 1.toByte()) continue
					val lIdx = idx - 1
					val rIdx = idx + 1
					val tIdx = idx - ww
					val bIdx = idx + ww

					val lSafe = if (eraseMask[lIdx] == 0.toByte() && (borderMask[lIdx] == 1.toByte() || !isCleanKnown(pixels[lIdx], ringMedR, ringMedG, ringMedB))) idx else lIdx
					val rSafe = if (eraseMask[rIdx] == 0.toByte() && (borderMask[rIdx] == 1.toByte() || !isCleanKnown(pixels[rIdx], ringMedR, ringMedG, ringMedB))) idx else rIdx
					val tSafe = if (eraseMask[tIdx] == 0.toByte() && (borderMask[tIdx] == 1.toByte() || !isCleanKnown(pixels[tIdx], ringMedR, ringMedG, ringMedB))) idx else tIdx
					val bSafe = if (eraseMask[bIdx] == 0.toByte() && (borderMask[bIdx] == 1.toByte() || !isCleanKnown(pixels[bIdx], ringMedR, ringMedG, ringMedB))) idx else bIdx

					wr[idx] = wH * (wr[lSafe] + wr[rSafe]) + wV * (wr[tSafe] + wr[bSafe])
					wg[idx] = wH * (wg[lSafe] + wg[rSafe]) + wV * (wg[tSafe] + wg[bSafe])
					wb[idx] = wH * (wb[lSafe] + wb[rSafe]) + wV * (wb[tSafe] + wb[bSafe])
				}
			}
		}

		for (y in y0..y1) {
			val row = y * ww
			for (x in x0..x1) {
				val idx = row + x
				if (eraseMask[idx] == 1.toByte()) {
					pixels[idx] = PixelColorMath.packRgb(
						wr[idx].roundToInt(),
						wg[idx].roundToInt(),
						wb[idx].roundToInt(),
					)
				}
			}
		}
	}

	private fun isCleanKnown(argb: Int, ringMedR: Int, ringMedG: Int, ringMedB: Int): Boolean =
		PixelColorMath.maxChannelDiff(argb, ringMedR, ringMedG, ringMedB) <= 58

	private fun measureBoundaryAnisotropy(
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
	): Float {
		var gradXSum = 0.0
		var gradYSum = 0.0
		val minX = max(1, x0)
		val maxX = min(ww - 2, x1)
		val minY = max(1, y0)
		val maxY = min(wh - 2, y1)

		for (y in minY..maxY) {
			val row = y * ww
			for (x in minX..maxX) {
				val idx = row + x
				if (eraseMask[idx] != 0.toByte() || borderMask[idx] != 0.toByte()) continue
				if (!isCleanKnown(pixels[idx], ringMedR, ringMedG, ringMedB)) continue
				if (eraseMask[idx - 1] == 0.toByte() && eraseMask[idx + 1] == 0.toByte()) {
					gradXSum += abs(PixelColorMath.luminance(pixels[idx + 1]) - PixelColorMath.luminance(pixels[idx - 1]))
				}
				if (eraseMask[idx - ww] == 0.toByte() && eraseMask[idx + ww] == 0.toByte()) {
					gradYSum += abs(PixelColorMath.luminance(pixels[idx + ww]) - PixelColorMath.luminance(pixels[idx - ww]))
				}
			}
		}
		return ((gradYSum + 10.0) / (gradXSum + 10.0)).toFloat().coerceIn(0.35f, 2.8f)
	}

	private fun computeOrthogonalSpanEstimate(
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
		anisoH: Float,
		outR: FloatArray,
		outG: FloatArray,
		outB: FloatArray,
	) {
		val maxProbe = 64
		for (y in y0..y1) {
			val row = y * ww
			for (x in x0..x1) {
				val idx = row + x
				if (eraseMask[idx] != 1.toByte()) continue

				var leftP = -1
				var leftD = 0
				for (step in 1..maxProbe) {
					val lx = x - step
					if (lx < 0) break
					val li = row + lx
					if (borderMask[li] == 1.toByte()) break
					if (eraseMask[li] == 0.toByte() && isCleanKnown(pixels[li], ringMedR, ringMedG, ringMedB)) {
						leftP = pixels[li]
						leftD = step
						break
					}
				}

				var rightP = -1
				var rightD = 0
				for (step in 1..maxProbe) {
					val rx = x + step
					if (rx >= ww) break
					val ri = row + rx
					if (borderMask[ri] == 1.toByte()) break
					if (eraseMask[ri] == 0.toByte() && isCleanKnown(pixels[ri], ringMedR, ringMedG, ringMedB)) {
						rightP = pixels[ri]
						rightD = step
						break
					}
				}

				var topP = -1
				var topD = 0
				for (step in 1..maxProbe) {
					val ty = y - step
					if (ty < 0) break
					val ti = ty * ww + x
					if (borderMask[ti] == 1.toByte()) break
					if (eraseMask[ti] == 0.toByte() && isCleanKnown(pixels[ti], ringMedR, ringMedG, ringMedB)) {
						topP = pixels[ti]
						topD = step
						break
					}
				}

				var botP = -1
				var botD = 0
				for (step in 1..maxProbe) {
					val by = y + step
					if (by >= wh) break
					val bi = by * ww + x
					if (borderMask[bi] == 1.toByte()) break
					if (eraseMask[bi] == 0.toByte() && isCleanKnown(pixels[bi], ringMedR, ringMedG, ringMedB)) {
						botP = pixels[bi]
						botD = step
						break
					}
				}

				var hasH = false
				var hR = 0f
				var hG = 0f
				var hB = 0f
				var hSpan = 999f
				if (leftP != -1 && rightP != -1) {
					hasH = true
					hSpan = (leftD + rightD).toFloat()
					val t = rightD / hSpan
					hR = PixelColorMath.red(leftP) * t + PixelColorMath.red(rightP) * (1f - t)
					hG = PixelColorMath.green(leftP) * t + PixelColorMath.green(rightP) * (1f - t)
					hB = PixelColorMath.blue(leftP) * t + PixelColorMath.blue(rightP) * (1f - t)
				} else if (leftP != -1 || rightP != -1) {
					hasH = true
					val sp = if (leftP != -1) leftP else rightP
					hSpan = (if (leftP != -1) leftD else rightD).toFloat() * 1.4f
					hR = PixelColorMath.red(sp).toFloat()
					hG = PixelColorMath.green(sp).toFloat()
					hB = PixelColorMath.blue(sp).toFloat()
				}

				var hasV = false
				var vR = 0f
				var vG = 0f
				var vB = 0f
				var vSpan = 999f
				if (topP != -1 && botP != -1) {
					hasV = true
					vSpan = (topD + botD).toFloat()
					val t = botD / vSpan
					vR = PixelColorMath.red(topP) * t + PixelColorMath.red(botP) * (1f - t)
					vG = PixelColorMath.green(topP) * t + PixelColorMath.green(botP) * (1f - t)
					vB = PixelColorMath.blue(topP) * t + PixelColorMath.blue(botP) * (1f - t)
				} else if (topP != -1 || botP != -1) {
					hasV = true
					val sp = if (topP != -1) topP else botP
					vSpan = (if (topP != -1) topD else botD).toFloat() * 1.4f
					vR = PixelColorMath.red(sp).toFloat()
					vG = PixelColorMath.green(sp).toFloat()
					vB = PixelColorMath.blue(sp).toFloat()
				}

				when {
					hasH && hasV -> {
						val effH = hSpan / anisoH
						val effV = vSpan * anisoH
						val wH = effV / (effH + effV)
						val wV = 1f - wH
						outR[idx] = wH * hR + wV * vR
						outG[idx] = wH * hG + wV * vG
						outB[idx] = wH * hB + wV * vB
					}
					hasH -> {
						outR[idx] = hR
						outG[idx] = hG
						outB[idx] = hB
					}
					hasV -> {
						outR[idx] = vR
						outG[idx] = vG
						outB[idx] = vB
					}
					else -> {
						outR[idx] = ringMedR.toFloat()
						outG[idx] = ringMedG.toFloat()
						outB[idx] = ringMedB.toFloat()
					}
				}
			}
		}
	}

	private fun estimateRotationDeg(
		glyphMask: ByteArray,
		ww: Int,
		x0: Int,
		y0: Int,
		x1: Int,
		y1: Int,
		lineCount: Int,
	): Float {
		val w = x1 - x0 + 1
		val h = y1 - y0 + 1
		if (lineCount > 2 || w < 24 || w < h * 0.9f) return 0f

		var n = 0
		var sumX = 0.0
		var sumY = 0.0
		var sumXX = 0.0
		var sumXY = 0.0
		var sumYY = 0.0

		for (x in x0..x1) {
			var cySum = 0.0
			var cyCnt = 0
			for (y in y0..y1) {
				if (glyphMask[y * ww + x] == 1.toByte()) {
					cySum += y
					cyCnt++
				}
			}
			if (cyCnt == 0) continue
			val mx = x.toDouble()
			val my = cySum / cyCnt
			n++
			sumX += mx
			sumY += my
			sumXX += mx * mx
			sumXY += mx * my
			sumYY += my * my
		}
		if (n < 12) return 0f

		val varX = sumXX - (sumX * sumX) / n
		val varY = sumYY - (sumY * sumY) / n
		if (varX < 1e-3 || varY < 1e-3) return 0f
		val covXY = sumXY - (sumX * sumY) / n
		val rSquared = (covXY * covXY) / (varX * varY)
		if (rSquared < 0.82) return 0f

		val slope = covXY / varX
		val angleDeg = (atan(slope) * 180.0 / PI).toFloat()
		return if (abs(angleDeg) in 5f..24f) angleDeg else 0f
	}

	private fun classifyBlockKind(
		kindHint: TextBlockKind,
		isDark: Boolean,
		hasGradient: Boolean,
		hasStroke: Boolean,
		textSat: Float,
		rotationDeg: Float,
		blockBox: NormalizedBox,
		text: String,
	): TextBlockKind {
		if (kindHint == TextBlockKind.SFX || kindHint == TextBlockKind.NARRATION || kindHint == TextBlockKind.EMPHASIS) {
			return kindHint
		}
		if (abs(rotationDeg) >= 6f || hasGradient || (hasStroke && textSat >= 0.30f)) {
			return TextBlockKind.SFX
		}
		val exclamations = text.count { it == '!' || it == '?' }
		if (exclamations >= 2 && text.length <= 28 && (isDark || hasStroke)) {
			return TextBlockKind.EMPHASIS
		}
		val aspect = if (blockBox.height > 0f) blockBox.width / blockBox.height else 1f
		if (isDark && aspect > 1.8f && blockBox.width > 0.25f) {
			return TextBlockKind.NARRATION
		}
		return TextBlockKind.DIALOGUE
	}

	private fun classifyFontFamily(
		kind: TextBlockKind,
		isDark: Boolean,
		maxStrokeRadius: Int,
		hasGradient: Boolean,
		hasGlow: Boolean,
	): ScanlationFontKey = when (kind) {
		TextBlockKind.SFX -> when {
			hasGradient -> ScanlationFontKey.BANGERS
			maxStrokeRadius >= 4 -> ScanlationFontKey.BANGERS
			else -> ScanlationFontKey.MARKER
		}
		TextBlockKind.EMPHASIS -> ScanlationFontKey.ANTON
		TextBlockKind.NARRATION -> if (isDark || hasGlow) ScanlationFontKey.CINZEL else ScanlationFontKey.SHANTELL
		TextBlockKind.DIALOGUE -> ScanlationFontKey.SHANTELL
	}

	private fun defaultFontForKind(kind: TextBlockKind, isDark: Boolean): ScanlationFontKey = when (kind) {
		TextBlockKind.SFX -> ScanlationFontKey.BANGERS
		TextBlockKind.EMPHASIS -> ScanlationFontKey.ANTON
		TextBlockKind.NARRATION -> if (isDark) ScanlationFontKey.CINZEL else ScanlationFontKey.SHANTELL
		TextBlockKind.DIALOGUE -> ScanlationFontKey.SHANTELL
	}
}
