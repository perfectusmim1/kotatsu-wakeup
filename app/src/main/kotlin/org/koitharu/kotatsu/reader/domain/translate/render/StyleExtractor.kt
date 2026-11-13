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

	fun luminance(r: Float, g: Float, b: Float): Float = 0.299f * r + 0.587f * g + 0.114f * b

	fun luminance(argb: Int): Float = luminance(red(argb), green(argb), blue(argb))

	fun colorDist(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Float {
		val dr = (r1 - r2).toFloat()
		val dg = (g1 - g2).toFloat()
		val db = (b1 - b2).toFloat()
		return sqrt(dr * dr * 0.30f + dg * dg * 0.59f + db * db * 0.11f)
	}

	fun colorDist(argb1: Int, argb2: Int): Float = colorDist(
		red(argb1), green(argb1), blue(argb1),
		red(argb2), green(argb2), blue(argb2),
	)

	fun maxChannelDiff(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Int =
		max(abs(r1 - r2), max(abs(g1 - g2), abs(b1 - b2)))

	fun maxChannelDiff(r1: Float, g1: Float, b1: Float, r2: Float, g2: Float, b2: Float): Float =
		max(abs(r1 - r2), max(abs(g1 - g2), abs(b1 - b2)))

	fun maxChannelDiff(argb: Int, r2: Int, g2: Int, b2: Int): Int =
		maxChannelDiff(red(argb), green(argb), blue(argb), r2, g2, b2)

	fun saturation(r: Int, g: Int, b: Int): Float {
		val maxC = max(r, max(g, b))
		val minC = min(r, min(g, b))
		val chroma = maxC - minC
		if (chroma < 12) return 0f
		return chroma.toFloat() / max(maxC, 48).toFloat()
	}

	fun medianInt(values: IntArray, count: Int): Int {
		if (count <= 0) return 0
		val slice = values.copyOf(count)
		slice.sort()
		val mid = count ushr 1
		return if (count and 1 != 0) {
			slice[mid]
		} else {
			((slice[mid - 1] + slice[mid]) * 0.5f).roundToInt()
		}
	}

	fun medianFloat(values: FloatArray, count: Int): Float {
		if (count <= 0) return 0f
		val slice = values.copyOf(count)
		slice.sort()
		val mid = count ushr 1
		return if (count and 1 != 0) {
			slice[mid]
		} else {
			(slice[mid - 1] + slice[mid]) * 0.5f
		}
	}

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
		return intArrayOf(
			findHistogramMedian(histR, count),
			findHistogramMedian(histG, count),
			findHistogramMedian(histB, count),
		)
	}

	fun medianRgbFromWindow(
		pixels: IntArray,
		count: Int,
		indices: IntArray,
	): FloatArray {
		if (count <= 0) return floatArrayOf(255f, 255f, 255f)
		val histR = IntArray(256)
		val histG = IntArray(256)
		val histB = IntArray(256)
		for (i in 0 until count) {
			val p = pixels[indices[i]]
			histR[red(p)]++
			histG[green(p)]++
			histB[blue(p)]++
		}
		return floatArrayOf(
			findHistogramMedianFloat(histR, count),
			findHistogramMedianFloat(histG, count),
			findHistogramMedianFloat(histB, count),
		)
	}

	private fun findHistogramMedian(hist: IntArray, totalCount: Int): Int =
		findHistogramMedianFloat(hist, totalCount).roundToInt()

	private fun findHistogramMedianFloat(hist: IntArray, totalCount: Int): Float {
		val mid = totalCount ushr 1
		if (totalCount and 1 != 0) {
			var acc = 0
			for (v in 0..255) {
				acc += hist[v]
				if (acc > mid) return v.toFloat()
			}
			return 128f
		}
		var vLow = -1
		var vHigh = -1
		var acc = 0
		for (v in 0..255) {
			acc += hist[v]
			if (vLow < 0 && acc >= mid) vLow = v
			if (acc >= mid + 1) {
				vHigh = v
				break
			}
		}
		return if (vLow >= 0 && vHigh >= 0) (vLow + vHigh) * 0.5f else 128f
	}
}

data class CompCenter(
	val x: Float,
	val y: Float,
	val w: Int,
	val h: Int,
)

data class EnclosedFillResult(
	val combinedMask: ByteArray,
	val enclosedInteriorMask: ByteArray,
	val enclosedCount: Int,
)

data class StyleExtractionInput(
	val pixels: IntArray,
	val ww: Int,
	val wh: Int,
	val rawGlyphMask: ByteArray,
	val enclosedInteriorMask: ByteArray,
	val enclosedCount: Int,
	val ringMedR: Int,
	val ringMedG: Int,
	val ringMedB: Int,
	val ringStd: Float,
	val medGlyphH: Float,
	val medGlyphW: Float,
	val fullWidth: Int,
	val hasClosedBubbleWalls: Boolean,
	val compCenters: List<CompCenter>,
	val origLineCount: Int = 1,
	val kindHint: TextBlockKind = TextBlockKind.DIALOGUE,
)

data class ExtractedStyleResult(
	val fingerprint: TextStyleFingerprint,
	val kind: TextBlockKind,
)

@Singleton
class StyleExtractor @Inject constructor() {

	/**
	 * ToonParrot `styleExtractor.ts:85-340` parity:
	 * Uses Jordan-curve outer flood-fill with 1px morphological closing to separate true
	 * glyph interiors (e.g. hollow outlined SFX or black-outlined white titles) from
	 * ordinary counter-holes inside bubble letters ("O, 0, D, P, A, a, e").
	 */
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
		val numPixels = ww * wh
		val combinedMask = rawMask.copyOf()
		val enclosedInteriorMask = ByteArray(numPixels)
		if (numPixels <= 0 || gMaxX < gMinX || gMaxY < gMinY) {
			return EnclosedFillResult(combinedMask, enclosedInteriorMask, 0)
		}

		val clampedMinX = gMinX.coerceIn(0, ww - 1)
		val clampedMaxX = gMaxX.coerceIn(clampedMinX, ww - 1)
		val clampedMinY = gMinY.coerceIn(0, wh - 1)
		val clampedMaxY = gMaxY.coerceIn(clampedMinY, wh - 1)

		var rawLum = 20f
		var rawMaskCount = 0
		val bgLum = PixelColorMath.luminance(bgR, bgG, bgB)
		val rawStep = max(1, ((clampedMaxY - clampedMinY + 1) * (clampedMaxX - clampedMinX + 1)) / 600)
		val rawLums = FloatArray(700)
		var rawLumSamples = 0
		for (y in clampedMinY..clampedMaxY) {
			val rOff = y * ww
			for (x in clampedMinX..clampedMaxX) {
				val idx = rOff + x
				if (rawMask[idx] == 0.toByte()) continue
				rawMaskCount++
				if (rawMaskCount % rawStep == 0 && rawLumSamples < rawLums.size) {
					rawLums[rawLumSamples++] = PixelColorMath.luminance(pixels[idx])
				}
			}
		}
		if (rawLumSamples > 0) {
			rawLum = PixelColorMath.medianFloat(rawLums, rawLumSamples)
		}

		// Pre-measure whether rawMask itself contains saturated glow around white glyphs.
		val stepR = max(1, numPixels / 4000)
		var glowSatHits = 0
		var rawSampleCount = 0
		var kProbe = 0
		while (kProbe < numPixels) {
			if (rawMask[kProbe] != 0.toByte()) {
				rawSampleCount++
				val p = pixels[kProbe]
				if (PixelColorMath.saturation(
						PixelColorMath.red(p),
						PixelColorMath.green(p),
						PixelColorMath.blue(p),
					) >= 0.35f
				) {
					glowSatHits++
				}
			}
			kProbe += stepR
		}
		val hasColoredGlowRaw = rawSampleCount >= 8 && glowSatHits >= rawSampleCount * 0.35f

		// 1px morphological closing wall across the window.
		val wall = ByteArray(numPixels)
		for (y in 0 until wh) {
			val rOff = y * ww
			val yMin = if (y > 0) y - 1 else 0
			val yMax = if (y < wh - 1) y + 1 else wh - 1
			for (x in 0 until ww) {
				val idx = rOff + x
				if (rawMask[idx] != 0.toByte()) {
					wall[idx] = 1
					continue
				}
				val xMin = if (x > 0) x - 1 else 0
				val xMax = if (x < ww - 1) x + 1 else ww - 1
				var nCount = 0
				for (ny in yMin..yMax) {
					val nr = ny * ww
					for (nx in xMin..xMax) {
						if (rawMask[nr + nx] != 0.toByte()) nCount++
					}
				}
				if (nCount >= 4) wall[idx] = 1
			}
		}

		// Outer flood-fill starting from all 4 window edges.
		val visitedOuter = ByteArray(numPixels)
		val queue = IntArray(numPixels)
		var qHead = 0
		var qTail = 0

		fun pushIfOpen(idx: Int) {
			if (visitedOuter[idx] == 0.toByte() && wall[idx] == 0.toByte()) {
				visitedOuter[idx] = 1
				queue[qTail++] = idx
			}
		}

		for (x in 0 until ww) {
			pushIfOpen(x)
			pushIfOpen((wh - 1) * ww + x)
		}
		for (y in 0 until wh) {
			pushIfOpen(y * ww)
			pushIfOpen(y * ww + (ww - 1))
		}

		while (qHead < qTail) {
			val curr = queue[qHead++]
			val cy = curr / ww
			val cx = curr - cy * ww
			if (cx > 0) pushIfOpen(curr - 1)
			if (cx < ww - 1) pushIfOpen(curr + 1)
			if (cy > 0) pushIfOpen(curr - ww)
			if (cy < wh - 1) pushIfOpen(curr + ww)
		}

		val glyphBoxArea = max(1, (clampedMaxX - clampedMinX + 1) * (clampedMaxY - clampedMinY + 1))
		val maxHoleArea = max(400, (glyphBoxArea * 0.42f).toInt())
		val compVisited = ByteArray(numPixels)
		val compPixels = IntArray(numPixels)
		var enclosedCount = 0

		val x0 = max(1, clampedMinX)
		val x1 = min(ww - 2, clampedMaxX)
		val y0 = max(1, clampedMinY)
		val y1 = min(wh - 2, clampedMaxY)
		val rawSatApprox = PixelColorMath.saturation(bgR, bgG, bgB)
		val glyphSpanY = clampedMaxY - clampedMinY

		val hRs = IntArray(128)
		val hGs = IntArray(128)
		val hBs = IntArray(128)

		for (y in y0..y1) {
			val rOff = y * ww
			for (x in x0..x1) {
				val startIdx = rOff + x
				if (rawMask[startIdx] != 0.toByte() ||
					visitedOuter[startIdx] != 0.toByte() ||
					compVisited[startIdx] != 0.toByte() ||
					borderMask[startIdx] != 0.toByte()
				) {
					continue
				}

				var cHead = 0
				var cTail = 0
				compVisited[startIdx] = 1
				compPixels[cTail++] = startIdx
				var touchesBorderMask = false

				while (cHead < cTail) {
					val curr = compPixels[cHead++]
					val cy = curr / ww
					val cx = curr - cy * ww

					if (cx > 0) {
						val ni = curr - 1
						if (borderMask[ni] != 0.toByte()) {
							touchesBorderMask = true
						} else if (rawMask[ni] == 0.toByte() && visitedOuter[ni] == 0.toByte() && compVisited[ni] == 0.toByte()) {
							compVisited[ni] = 1
							compPixels[cTail++] = ni
						}
					}
					if (cx < ww - 1) {
						val ni = curr + 1
						if (borderMask[ni] != 0.toByte()) {
							touchesBorderMask = true
						} else if (rawMask[ni] == 0.toByte() && visitedOuter[ni] == 0.toByte() && compVisited[ni] == 0.toByte()) {
							compVisited[ni] = 1
							compPixels[cTail++] = ni
						}
					}
					if (cy > 0) {
						val ni = curr - ww
						if (borderMask[ni] != 0.toByte()) {
							touchesBorderMask = true
						} else if (rawMask[ni] == 0.toByte() && visitedOuter[ni] == 0.toByte() && compVisited[ni] == 0.toByte()) {
							compVisited[ni] = 1
							compPixels[cTail++] = ni
						}
					}
					if (cy < wh - 1) {
						val ni = curr + ww
						if (borderMask[ni] != 0.toByte()) {
							touchesBorderMask = true
						} else if (rawMask[ni] == 0.toByte() && visitedOuter[ni] == 0.toByte() && compVisited[ni] == 0.toByte()) {
							compVisited[ni] = 1
							compPixels[cTail++] = ni
						}
					}
				}

				if (!touchesBorderMask && cTail <= maxHoleArea) {
					var sampleCount = 0
					val step = max(1, cTail / 64)
					var k = 0
					while (k < cTail && sampleCount < hRs.size) {
						val p = pixels[compPixels[k]]
						hRs[sampleCount] = PixelColorMath.red(p)
						hGs[sampleCount] = PixelColorMath.green(p)
						hBs[sampleCount] = PixelColorMath.blue(p)
						sampleCount++
						k += step
					}

					val holeR = PixelColorMath.medianInt(hRs, sampleCount)
					val holeG = PixelColorMath.medianInt(hGs, sampleCount)
					val holeB = PixelColorMath.medianInt(hBs, sampleCount)
					val holeLum = PixelColorMath.luminance(holeR, holeG, holeB)
					val dHoleToBg = PixelColorMath.colorDist(holeR, holeG, holeB, bgR, bgG, bgB)
					val maxDiffHoleBg = PixelColorMath.maxChannelDiff(holeR, holeG, holeB, bgR, bgG, bgB)

					val isDarkCounterHole =
						rawLum >= 185f &&
							holeLum < rawLum - 65f &&
							dHoleToBg >= 42f &&
							maxDiffHoleBg >= 48 &&
							bgLum < 52f &&
							glyphSpanY >= 26

					val isColoredGlowAroundWhite =
						rawLum <= 190f &&
							rawSatApprox < 0.25f &&
							holeLum >= 238f &&
							holeLum >= rawLum + 48f &&
							glyphSpanY >= 22 &&
							cTail >= 24 &&
							hasColoredGlowRaw

					val isBrightInsideDarkOutline =
						isColoredGlowAroundWhite ||
							(rawLum <= 105f &&
								holeLum >= max(195f, rawLum + 80f) &&
								glyphSpanY >= 18 &&
								(dHoleToBg >= 32f ||
									maxDiffHoleBg >= 36 ||
									(holeLum >= bgLum + 12f && bgLum < 225f) ||
									bgLum <= 185f))

					if (isDarkCounterHole || isBrightInsideDarkOutline) {
						for (i in 0 until cTail) {
							val pIdx = compPixels[i]
							combinedMask[pIdx] = 1
							enclosedInteriorMask[pIdx] = 1
							enclosedCount++
						}
					} else if (cTail <= min(maxHoleArea, 360)) {
						for (i in 0 until cTail) {
							combinedMask[compPixels[i]] = 1
						}
					}
				}
			}
		}

		// Protect against ordinary counter-holes ("O, 0, D") leaking into style extraction.
		if (enclosedCount > 0 && enclosedCount < max(20, (rawMaskCount * 0.28f).toInt())) {
			enclosedInteriorMask.fill(0)
			enclosedCount = 0
		}

		return EnclosedFillResult(
			combinedMask = combinedMask,
			enclosedInteriorMask = enclosedInteriorMask,
			enclosedCount = enclosedCount,
		)
	}

	/**
	 * ToonParrot `styleExtractor.ts:366-895` exact parity:
	 * Samples core/edge/halo colors, outlines, RPG system blue/cyan glows, warm gold titles,
	 * vertical gradients, SFX rotation, and enforces consistent `SHANTELL` typography.
	 */
	fun extractStyleFingerprint(input: StyleExtractionInput): ExtractedStyleResult {
		val ww = input.ww
		val wh = input.wh
		val numPixels = ww * wh
		val pixels = input.pixels
		val rawGlyphMask = input.rawGlyphMask
		val enclosedInteriorMask = input.enclosedInteriorMask
		val ringMedR = input.ringMedR
		val ringMedG = input.ringMedG
		val ringMedB = input.ringMedB
		val ringStd = input.ringStd

		val bgColor = PixelColorMath.packRgb(ringMedR, ringMedG, ringMedB)
		val bgLum = PixelColorMath.luminance(ringMedR, ringMedG, ringMedB)
		val isDarkBg = bgLum < 145f

		// 1. Manhattan Chamfer Distance Transform across full window.
		val fullGlyphMask = ByteArray(numPixels)
		var totalGlyphPixels = 0
		for (i in 0 until numPixels) {
			if (rawGlyphMask[i] != 0.toByte() || enclosedInteriorMask[i] != 0.toByte()) {
				fullGlyphMask[i] = 1
				totalGlyphPixels++
			}
		}

		val dist = IntArray(numPixels)
		for (i in 0 until numPixels) {
			dist[i] = if (fullGlyphMask[i] != 0.toByte()) 9999 else 0
		}
		for (y in 0 until wh) {
			val rOff = y * ww
			for (x in 0 until ww) {
				val idx = rOff + x
				var d = dist[idx]
				if (d == 0) continue
				if (x > 0 && dist[idx - 1] + 1 < d) d = dist[idx - 1] + 1
				if (y > 0 && dist[idx - ww] + 1 < d) d = dist[idx - ww] + 1
				dist[idx] = d
			}
		}
		var maxDist = 0
		for (y in wh - 1 downTo 0) {
			val rOff = y * ww
			for (x in ww - 1 downTo 0) {
				val idx = rOff + x
				var d = dist[idx]
				if (d == 0) continue
				if (x < ww - 1 && dist[idx + 1] + 1 < d) d = dist[idx + 1] + 1
				if (y < wh - 1 && dist[idx + ww] + 1 < d) d = dist[idx + ww] + 1
				dist[idx] = d
				if (d in 1..8999 && d > maxDist) maxDist = d
			}
		}

		// 2. Sample outline edge vs core fill pixels.
		val edgeIndices = IntArray(max(16, totalGlyphPixels))
		val coreIndices = IntArray(max(16, totalGlyphPixels))
		val coreYs = IntArray(max(16, totalGlyphPixels))
		var edgeCount = 0
		var coreCount = 0

		val hasSignificantEnclosed =
			!input.hasClosedBubbleWalls &&
				input.enclosedCount >= 24 &&
				input.enclosedCount >= totalGlyphPixels * 0.22f

		if (hasSignificantEnclosed) {
			for (y in 0 until wh) {
				val rOff = y * ww
				for (x in 0 until ww) {
					val idx = rOff + x
					if (rawGlyphMask[idx] != 0.toByte() && dist[idx] <= 3) {
						edgeIndices[edgeCount++] = idx
					} else if (enclosedInteriorMask[idx] != 0.toByte()) {
						val nearStroke =
							(x > 1 && rawGlyphMask[idx - 2] != 0.toByte()) ||
								(x < ww - 2 && rawGlyphMask[idx + 2] != 0.toByte()) ||
								(y > 1 && rawGlyphMask[idx - 2 * ww] != 0.toByte()) ||
								(y < wh - 2 && rawGlyphMask[idx + 2 * ww] != 0.toByte()) ||
								dist[idx] <= 6
						if (nearStroke) {
							coreIndices[coreCount] = idx
							coreYs[coreCount] = y
							coreCount++
						}
					}
				}
			}
		}

		if (coreCount < 12) {
			edgeCount = 0
			coreCount = 0
			val edgeMaxD = if (maxDist >= 6) 2 else 1
			val coreMinD = when {
				maxDist >= 7 -> 4
				maxDist >= 5 -> 3
				else -> 2
			}
			for (y in 0 until wh) {
				val rOff = y * ww
				for (x in 0 until ww) {
					val idx = rOff + x
					if (rawGlyphMask[idx] == 0.toByte()) continue
					val d = dist[idx]
					if (d <= 0) continue
					if (d <= edgeMaxD) {
						edgeIndices[edgeCount++] = idx
					}
					if (d >= coreMinD) {
						coreIndices[coreCount] = idx
						coreYs[coreCount] = y
						coreCount++
					}
				}
			}
		}

		if (coreCount < 8) {
			coreCount = 0
			for (y in 0 until wh) {
				val rOff = y * ww
				for (x in 0 until ww) {
					val idx = rOff + x
					if (rawGlyphMask[idx] == 0.toByte()) continue
					if (coreCount >= coreIndices.size) break
					coreIndices[coreCount] = idx
					coreYs[coreCount] = y
					coreCount++
				}
			}
		}

		val fillRgb = if (coreCount > 0) {
			PixelColorMath.medianRgbFromWindow(pixels, coreCount, coreIndices)
		} else {
			val fallback = if (isDarkBg) 255f else 12f
			floatArrayOf(fallback, fallback, fallback)
		}
		val fillR = fillRgb[0].roundToInt()
		val fillG = fillRgb[1].roundToInt()
		val fillB = fillRgb[2].roundToInt()

		val edgeRgb = if (edgeCount > 0) {
			PixelColorMath.medianRgbFromWindow(pixels, edgeCount, edgeIndices)
		} else {
			fillRgb
		}
		val edgeR = edgeRgb[0].roundToInt()
		val edgeG = edgeRgb[1].roundToInt()
		val edgeB = edgeRgb[2].roundToInt()

		val fillToEdgeDist = PixelColorMath.colorDist(fillR, fillG, fillB, edgeR, edgeG, edgeB)
		val edgeToBgDist = PixelColorMath.colorDist(edgeR, edgeG, edgeB, ringMedR, ringMedG, ringMedB)
		val fillToBgDist = PixelColorMath.colorDist(fillR, fillG, fillB, ringMedR, ringMedG, ringMedB)
		val fillLum = PixelColorMath.luminance(fillR, fillG, fillB)
		val edgeLum = PixelColorMath.luminance(edgeR, edgeG, edgeB)

		// 2-px offset outer halo ring sampling.
		val haloIndices = IntArray(numPixels)
		var haloCount = 0
		var whiteHaloCount = 0
		var darkHaloCount = 0
		for (y in 2 until wh - 2) {
			val rOff = y * ww
			for (x in 2 until ww - 2) {
				val idx = rOff + x
				if (fullGlyphMask[idx] != 0.toByte()) continue
				if (fullGlyphMask[idx - 2] != 0.toByte() ||
					fullGlyphMask[idx + 2] != 0.toByte() ||
					fullGlyphMask[idx - 2 * ww] != 0.toByte() ||
					fullGlyphMask[idx + 2 * ww] != 0.toByte()
				) {
					haloIndices[haloCount++] = idx
					val p = pixels[idx]
					val hr = PixelColorMath.red(p)
					val hg = PixelColorMath.green(p)
					val hb = PixelColorMath.blue(p)
					val hLum = PixelColorMath.luminance(hr, hg, hb)
					val hSat = PixelColorMath.saturation(hr, hg, hb)
					if (hLum >= 210f && hSat < 0.20f) {
						whiteHaloCount++
					} else if (hLum <= 90f && hSat < 0.32f) {
						darkHaloCount++
					}
				}
			}
		}

		val haloRgb = if (haloCount > 0) {
			PixelColorMath.medianRgbFromWindow(pixels, haloCount, haloIndices)
		} else {
			floatArrayOf(ringMedR.toFloat(), ringMedG.toFloat(), ringMedB.toFloat())
		}
		val haloR = haloRgb[0].roundToInt()
		val haloG = haloRgb[1].roundToInt()
		val haloB = haloRgb[2].roundToInt()
		val haloLum = PixelColorMath.luminance(haloR, haloG, haloB)
		val haloToFillDist = PixelColorMath.colorDist(haloR, haloG, haloB, fillR, fillG, fillB)
		val haloToBgDist = PixelColorMath.colorDist(haloR, haloG, haloB, ringMedR, ringMedG, ringMedB)
		val whiteHaloRatio = if (haloCount > 0) whiteHaloCount.toFloat() / haloCount else 0f
		val darkHaloRatio = if (haloCount > 0) darkHaloCount.toFloat() / haloCount else 0f

		val hasThinWhiteFill =
			edgeLum >= 225f && fillLum >= 130f && PixelColorMath.saturation(edgeR, edgeG, edgeB) < 0.22f
		val effectiveWhiteFillLum = if (hasThinWhiteFill) edgeLum else fillLum

		val hasOuterHaloStroke =
			fillLum <= 125f &&
				haloLum >= 224f &&
				whiteHaloRatio >= 0.48f &&
				haloToFillDist >= 85f &&
				((!input.hasClosedBubbleWalls &&
					((bgLum <= 242f &&
						(haloLum >= bgLum + 8f || haloToBgDist >= 18f || ringStd >= 14f)) ||
						(haloLum >= 232f && ringStd >= 10f && bgLum <= 253f))) ||
					(haloLum >= 234f && ringStd >= 10f && haloToFillDist >= 110f) ||
					(bgLum <= 228f && haloLum >= bgLum + 16f && haloToBgDist >= 18f) ||
					(bgLum <= 240f && haloLum >= 240f && whiteHaloRatio >= 0.62f && haloToBgDist >= 10f))

		val haloSat = PixelColorMath.saturation(haloR, haloG, haloB)
		val haloChroma = max(haloR, max(haloG, haloB)) - min(haloR, min(haloG, haloB))
		val ringMedLumForHalo = PixelColorMath.luminance(ringMedR, ringMedG, ringMedB)
		val hasNeutralJetBlackHalo =
			haloLum <= 40f &&
				haloChroma <= 18 &&
				haloSat < 0.30f &&
				(darkHaloRatio >= 0.26f || (haloLum <= ringMedLumForHalo - 5f && haloToFillDist >= 110f)) &&
				max(bgLum, ringMedLumForHalo) >= 20f &&
				(fillToBgDist >= 85f ||
					(effectiveWhiteFillLum >= 205f && haloToFillDist >= 135f && bgLum <= 228f))

		val hasDarkOuterHaloStroke =
			!input.hasClosedBubbleWalls &&
				effectiveWhiteFillLum >= 175f &&
				(hasNeutralJetBlackHalo ||
					(haloLum <= 105f &&
						haloToFillDist >= 90f &&
						(if (bgLum >= 65f) haloToBgDist >= 30f || bgLum >= 115f else haloLum <= bgLum - 12f)) ||
					(darkHaloRatio >= 0.34f && bgLum >= 36f && fillToBgDist >= 85f))

		var hasOutline = false
		var strokeR = 255
		var strokeG = 255
		var strokeB = 255

		if (hasSignificantEnclosed &&
			edgeLum >= 195f &&
			fillLum <= 125f &&
			fillToEdgeDist >= 55f &&
			edgeToBgDist >= 50f
		) {
			hasOutline = true
			strokeR = edgeR
			strokeG = edgeG
			strokeB = edgeB
		} else if (hasSignificantEnclosed &&
			fillLum >= 185f &&
			edgeLum <= 105f &&
			fillToEdgeDist >= 55f &&
			(edgeToBgDist >= 40f || bgLum >= 115f)
		) {
			hasOutline = true
			strokeR = edgeR
			strokeG = edgeG
			strokeB = edgeB
		} else if (hasOuterHaloStroke) {
			hasOutline = true
			strokeR = haloR
			strokeG = haloG
			strokeB = haloB
		} else if (hasDarkOuterHaloStroke) {
			hasOutline = true
			if (haloLum <= 105f) {
				strokeR = haloR
				strokeG = haloG
				strokeB = haloB
			} else {
				strokeR = 10
				strokeG = 10
				strokeB = 12
			}
		}

		// 3. Classify region kind matching styleExtractor.ts:664-676.
		val relGlyphH = input.medGlyphH / max(1, input.fullWidth)
		val fillSat = PixelColorMath.saturation(fillR, fillG, fillB)
		val fillChroma = max(fillR, max(fillG, fillB)) - min(fillR, min(fillG, fillB))
		val isColoredFill = fillSat >= 0.28f && fillChroma >= 36

		val kind = when {
			hasOutline || (relGlyphH >= 0.056f && !input.hasClosedBubbleWalls && bgLum < 200f) -> TextBlockKind.SFX
			isDarkBg -> TextBlockKind.NARRATION
			input.kindHint == TextBlockKind.EMPHASIS -> TextBlockKind.EMPHASIS
			else -> TextBlockKind.DIALOGUE
		}

		// 4. Colors, strokes, glows, and vertical gradients.
		var finalTextColor = if (isDarkBg) 0xFFFFFFFF.toInt() else 0xFF0C0C0C.toInt()
		var finalStrokeColor = bgColor
		var strokeWidthEm = 0f
		var shadowColor: Int? = null
		var shadowBlurEm = 0f
		var gradientTopColor: Int? = null
		var gradientBottomColor: Int? = null

		if (hasOutline) {
			val strokeLum = PixelColorMath.luminance(strokeR, strokeG, strokeB)
			val strokeSat = PixelColorMath.saturation(strokeR, strokeG, strokeB)
			finalStrokeColor = when {
				strokeLum > 185f && strokeSat < 0.22f -> 0xFFFFFFFF.toInt()
				strokeLum < 65f && strokeSat < 0.22f -> 0xFF0A0A0C.toInt()
				else -> PixelColorMath.packRgb(strokeR, strokeG, strokeB)
			}
			strokeWidthEm = 0.14f
			val fillForCheckSat = if (hasThinWhiteFill) {
				PixelColorMath.saturation(edgeR, edgeG, edgeB)
			} else {
				fillSat
			}
			finalTextColor = if (effectiveWhiteFillLum >= 205f && fillForCheckSat < 0.22f) {
				0xFFFFFFFF.toInt()
			} else {
				PixelColorMath.packRgb(fillR, fillG, fillB)
			}

			// Vertical linear gradient on outlined title/SFX lettering.
			if (!hasThinWhiteFill && coreCount >= 24) {
				var minY = Int.MAX_VALUE
				var maxY = Int.MIN_VALUE
				for (i in 0 until coreCount) {
					val cy = coreYs[i]
					if (cy < minY) minY = cy
					if (cy > maxY) maxY = cy
				}
				val spanY = maxY - minY
				if (spanY >= 16) {
					val topCut = minY + spanY * 0.38f
					val botCut = maxY - spanY * 0.38f
					val topIndices = IntArray(coreCount)
					val botIndices = IntArray(coreCount)
					var topCount = 0
					var botCount = 0
					for (i in 0 until coreCount) {
						val cy = coreYs[i].toFloat()
						if (cy <= topCut) {
							topIndices[topCount++] = coreIndices[i]
						} else if (cy >= botCut) {
							botIndices[botCount++] = coreIndices[i]
						}
					}
					if (topCount >= 8 && botCount >= 8) {
						val topRgb = PixelColorMath.medianRgbFromWindow(pixels, topCount, topIndices)
						val botRgb = PixelColorMath.medianRgbFromWindow(pixels, botCount, botIndices)
						val tR = topRgb[0].roundToInt()
						val tG = topRgb[1].roundToInt()
						val tB = topRgb[2].roundToInt()
						val bR = botRgb[0].roundToInt()
						val bG = botRgb[1].roundToInt()
						val bB = botRgb[2].roundToInt()
						val gradDiff = PixelColorMath.colorDist(tR, tG, tB, bR, bG, bB)
						if (gradDiff in 24f..155f) {
							gradientTopColor = PixelColorMath.packRgb(tR, tG, tB)
							gradientBottomColor = PixelColorMath.packRgb(bR, bG, bB)
						}
					}
				}
			}
		} else if (isDarkBg) {
			val isWarmGoldText =
				fillLum >= 165f &&
					fillR > fillB + 45 &&
					fillG > fillB + 25 &&
					fillSat >= 0.30f &&
					fillToBgDist >= 70f

			finalTextColor = if (isWarmGoldText) {
				val boost = if (fillLum < 225f) (225f - fillLum) * 0.5f else 0f
				PixelColorMath.packRgb(
					(fillR + boost).roundToInt(),
					(fillG + boost).roundToInt(),
					(fillB + boost).roundToInt(),
				)
			} else {
				0xFFFFFFFF.toInt()
			}

			val bgChroma = max(ringMedR, max(ringMedG, ringMedB)) - min(ringMedR, min(ringMedG, ringMedB))
			val edgeChroma = max(edgeR, max(edgeG, edgeB)) - min(edgeR, min(edgeG, edgeB))

			if (finalTextColor == 0xFFFFFFFF.toInt() &&
				(darkHaloRatio >= 0.28f || haloLum <= 34f) &&
				haloSat < 0.26f &&
				max(bgLum, ringMedLumForHalo) >= 22f
			) {
				hasOutline = true
				finalStrokeColor = 0xFF0A0A0C.toInt()
				strokeWidthEm = 0.14f
			} else if (finalTextColor == 0xFFFFFFFF.toInt() && (bgChroma >= 18 || edgeChroma >= 22)) {
				if (ringMedB > ringMedR + 14 || edgeB > edgeR + 18) {
					hasOutline = true
					finalStrokeColor = 0xFF5CA8FF.toInt()
					strokeWidthEm = 0.06f
					shadowColor = 0xFF5CA8FF.toInt()
					shadowBlurEm = 0.16f
				} else {
					val mixR = ringMedR * 0.5f + edgeR * 0.5f
					val mixG = ringMedG * 0.5f + edgeG * 0.5f
					val mixB = ringMedB * 0.5f + edgeB * 0.5f
					val mixLum = PixelColorMath.luminance(mixR, mixG, mixB)
					val lift = max(35f, 175f - mixLum)
					val glowPacked = PixelColorMath.packRgb(
						(mixR + lift * 0.85f).roundToInt(),
						(mixG + lift * 0.85f).roundToInt(),
						(mixB + lift * 0.85f).roundToInt(),
					)
					hasOutline = true
					finalStrokeColor = glowPacked
					strokeWidthEm = 0.055f
					shadowColor = glowPacked
					shadowBlurEm = 0.15f
				}
			}
		} else {
			if (fillLum >= 185f &&
				(edgeLum <= fillLum - 25f ||
					edgeB > edgeR + 20 ||
					(fillLum >= 205f && haloLum <= 55f && haloToFillDist >= 135f))
			) {
				finalTextColor = 0xFFFFFFFF.toInt()
				hasOutline = true
				val isBlueOutline = edgeB > edgeR + 14
				val isDarkHalo = !isBlueOutline && haloLum <= 55f && haloToFillDist >= 135f
				finalStrokeColor = when {
					isBlueOutline -> 0xFF3B78D4.toInt()
					isDarkHalo -> 0xFF0A0A0C.toInt()
					else -> PixelColorMath.packRgb(edgeR, edgeG, edgeB)
				}
				strokeWidthEm = if (isDarkHalo) 0.14f else 0.11f
				if (isBlueOutline) {
					shadowColor = 0xFF5CA8FF.toInt()
					shadowBlurEm = 0.14f
				}
			} else if (isColoredFill && fillToBgDist >= 95f && fillLum < bgLum - 45f && fillSat >= 0.38f) {
				finalTextColor = PixelColorMath.packRgb(fillR, fillG, fillB)
			} else {
				finalTextColor = 0xFF0C0C0C.toInt()
			}
		}

		// 5. Slant rotation on standalone single-line SFX lettering.
		var rotationDeg = 0f
		if (kind == TextBlockKind.SFX &&
			!input.hasClosedBubbleWalls &&
			relGlyphH >= 0.048f &&
			input.origLineCount == 1 &&
			input.compCenters.size in 3..12
		) {
			val sortedC = input.compCenters.sortedBy { it.x }
			val totalSpanX = sortedC.last().x - sortedC.first().x
			if (totalSpanX >= input.medGlyphH * 1.4f) {
				var sumX = 0.0
				var sumY = 0.0
				for (c in sortedC) {
					sumX += c.x
					sumY += c.y
				}
				val meanX = sumX / sortedC.size
				val meanY = sumY / sortedC.size
				var num = 0.0
				var denX = 0.0
				var denY = 0.0
				for (c in sortedC) {
					val dx = c.x - meanX
					val dy = c.y - meanY
					num += dx * dy
					denX += dx * dx
					denY += dy * dy
				}
				val rSquared = if (denX > 10.0 && denY > 4.0) (num * num) / (denX * denY) else 0.0
				if (denX > 10.0 && rSquared >= 0.82) {
					val slope = num / denX
					val deg = (atan(slope) * 180.0 / PI).toFloat()
					if (abs(deg) in 5f..24f) {
						rotationDeg = (deg * 10f).roundToInt() / 10f
					}
				}
			}
		}

		// 6. Consistent legible scanlation font across every block (`SHANTELL`), styleExtractor.ts:872-876.
		val fontWeight = if (kind == TextBlockKind.SFX) 700 else 600
		val fingerprint = TextStyleFingerprint(
			fontFamily = ScanlationFontKey.SHANTELL,
			fontWeight = fontWeight,
			fontStyle = FontStyleMode.NORMAL,
			bgColor = bgColor,
			textColor = finalTextColor,
			gradientTopColor = gradientTopColor,
			gradientBottomColor = gradientBottomColor,
			strokeColor = finalStrokeColor,
			strokeWidthEm = strokeWidthEm,
			shadowColor = shadowColor,
			shadowBlurEm = shadowBlurEm,
			isDark = isDarkBg,
			hasTextStroke = hasOutline,
			fontSizeCqw = 2.6f,
			rotationDeg = rotationDeg,
			skewDeg = 0f,
			letterSpacingEm = 0.01f,
			lineWidthsPct = emptyList(),
			emphasisLastLineScale = 1f,
		)

		return ExtractedStyleResult(fingerprint = fingerprint, kind = kind)
	}

	/**
	 * Convenience overload accepting packed ARGB `IntArray` and committing masked regions back in place.
	 */
	fun inpaintTexturedRegion(
		pixels: IntArray,
		eraseMask: ByteArray,
		borderMask: ByteArray,
		ww: Int,
		wh: Int,
		egMinX: Int,
		egMaxX: Int,
		egMinY: Int,
		egMaxY: Int,
		ringMedR: Int,
		ringMedG: Int,
		ringMedB: Int,
	) {
		val numPixels = ww * wh
		val cleaned = FloatArray(numPixels * 3)
		for (i in 0 until numPixels) {
			val p = pixels[i]
			val c = i * 3
			cleaned[c] = PixelColorMath.red(p).toFloat()
			cleaned[c + 1] = PixelColorMath.green(p).toFloat()
			cleaned[c + 2] = PixelColorMath.blue(p).toFloat()
		}
		inpaintTexturedRegion(
			cleaned = cleaned,
			eraseMask = eraseMask,
			borderMask = borderMask,
			ww = ww,
			wh = wh,
			egMinX = egMinX,
			egMaxX = egMaxX,
			egMinY = egMinY,
			egMaxY = egMaxY,
			ringMedR = ringMedR,
			ringMedG = ringMedG,
			ringMedB = ringMedB,
		)
		for (i in 0 until numPixels) {
			if (eraseMask[i] != 0.toByte()) {
				val c = i * 3
				pixels[i] = PixelColorMath.packRgb(
					cleaned[c].roundToInt(),
					cleaned[c + 1].roundToInt(),
					cleaned[c + 2].roundToInt(),
				)
			}
		}
	}

	/**
	 * ToonParrot `styleExtractor.ts:902-1429` exact parity:
	 * Isophote-guided Telea Fast Marching with anisotropic span weighting, clean boundary escape,
	 * lockDir-preserving harmonic diffusion (10 iterations), and deterministic monochrome grain.
	 * Operates on interleaved RGB FloatArray `cleaned` of size `ww * wh * 3`.
	 */
	fun inpaintTexturedRegion(
		cleaned: FloatArray,
		eraseMask: ByteArray,
		borderMask: ByteArray,
		ww: Int,
		wh: Int,
		egMinX: Int,
		egMaxX: Int,
		egMinY: Int,
		egMaxY: Int,
		ringMedR: Int,
		ringMedG: Int,
		ringMedB: Int,
	) {
		val numPixels = ww * wh
		if (numPixels <= 0 || egMaxX < egMinX || egMaxY < egMinY) return

		val regX0 = egMinX.coerceIn(0, ww - 1)
		val regX1 = egMaxX.coerceIn(regX0, ww - 1)
		val regY0 = egMinY.coerceIn(0, wh - 1)
		val regY1 = egMaxY.coerceIn(regY0, wh - 1)

		// 1. Measure boundary gradient anisotropy on clean ring immediately bordering eraseMask.
		var gradXSum = 0.0
		var gradYSum = 0.0
		var gradCount = 0

		for (y in max(2, regY0 - 4)..min(wh - 3, regY1 + 4)) {
			val rOff = y * ww
			for (x in max(2, regX0 - 4)..min(ww - 3, regX1 + 4)) {
				val idx = rOff + x
				if (eraseMask[idx] != 0.toByte() || borderMask[idx] != 0.toByte()) continue
				if (eraseMask[idx - 1] != 0.toByte() ||
					eraseMask[idx + 1] != 0.toByte() ||
					eraseMask[idx - ww] != 0.toByte() ||
					eraseMask[idx + ww] != 0.toByte() ||
					eraseMask[idx - 2] != 0.toByte() ||
					eraseMask[idx + 2] != 0.toByte()
				) {
					val l = (idx - 1) * 3
					val r = (idx + 1) * 3
					val t = (idx - ww) * 3
					val b = (idx + ww) * 3
					val gx =
						abs(cleaned[r] - cleaned[l]) +
							abs(cleaned[r + 1] - cleaned[l + 1]) +
							abs(cleaned[r + 2] - cleaned[l + 2])
					val gy =
						abs(cleaned[b] - cleaned[t]) +
							abs(cleaned[b + 1] - cleaned[t + 1]) +
							abs(cleaned[b + 2] - cleaned[t + 2])
					gradXSum += gx
					gradYSum += gy
					gradCount++
				}
			}
		}

		val anisoH = if (gradCount > 8 && gradXSum + gradYSum > 1.0) {
			((gradYSum + 10.0) / (gradXSum + 10.0)).toFloat().coerceIn(0.58f, 1.75f)
		} else {
			1.0f
		}

		val ringMedRf = ringMedR.toFloat()
		val ringMedGf = ringMedG.toFloat()
		val ringMedBf = ringMedB.toFloat()
		val ringMedLum = PixelColorMath.luminance(ringMedRf, ringMedGf, ringMedBf)

		fun hasSmoothCleanNeighbor(pIdx: Int, cr: Float, cg: Float, cb: Float): Boolean {
			val py = pIdx / ww
			val px = pIdx - py * ww
			val n0 = if (px >= 2) pIdx - 2 else -1
			val n1 = if (px + 2 < ww) pIdx + 2 else -1
			val n2 = if (py >= 2) pIdx - 2 * ww else -1
			val n3 = if (py + 2 < wh) pIdx + 2 * ww else -1
			for (k in 0..3) {
				val nIdx = when (k) {
					0 -> n0
					1 -> n1
					2 -> n2
					else -> n3
				}
				if (nIdx < 0 || borderMask[nIdx] != 0.toByte() || eraseMask[nIdx] != 0.toByte()) continue
				val nc = nIdx * 3
				if (PixelColorMath.maxChannelDiff(cr, cg, cb, cleaned[nc], cleaned[nc + 1], cleaned[nc + 2]) <= 34f) {
					return true
				}
			}
			return false
		}

		fun isCleanBoundary(pIdx: Int): Boolean {
			if (pIdx < 0 || pIdx >= numPixels || borderMask[pIdx] != 0.toByte() || eraseMask[pIdx] != 0.toByte()) {
				return false
			}
			val c3 = pIdx * 3
			val cr = cleaned[c3]
			val cg = cleaned[c3 + 1]
			val cb = cleaned[c3 + 2]
			val pLum = PixelColorMath.luminance(cr, cg, cb)
			val dRing = PixelColorMath.maxChannelDiff(cr, cg, cb, ringMedRf, ringMedGf, ringMedBf)
			val darkAAFloor = when {
				ringMedLum >= 165f -> 44f
				ringMedLum >= 140f -> min(64f, (ringMedLum * 0.4f).roundToInt().toFloat())
				else -> 42f
			}
			if ((dRing > 48f &&
					pLum < darkAAFloor &&
					ringMedLum >= 52f &&
					pLum < ringMedLum - 20f &&
					!(ringMedLum >= 150f && hasSmoothCleanNeighbor(pIdx, cr, cg, cb))) ||
				(pLum <= min(34f, (ringMedLum * 0.72f).roundToInt().toFloat()) &&
					ringMedLum >= 26f &&
					dRing >= 14f) ||
				(ringMedB > ringMedR + 14 && cb - cr <= 16f && pLum <= ringMedLum + 8f && dRing >= 18f)
			) {
				return false
			}
			if (dRing <= 78f) return true
			return hasSmoothCleanNeighbor(pIdx, cr, cg, cb)
		}

		fun resolveCleanEdge(
			edgeIdx: Int,
			stride: Int,
			coordDelta: Int,
			coord: Int,
			limit: Int,
		): Int {
			if (edgeIdx < 0 || edgeIdx >= numPixels) return -1
			var bestIdx = if (isCleanBoundary(edgeIdx)) edgeIdx else -1
			val baseC = edgeIdx * 3
			val baseLum = PixelColorMath.luminance(cleaned[baseC], cleaned[baseC + 1], cleaned[baseC + 2])
			var bestLum = if (bestIdx >= 0) baseLum else -1f
			val suspiciousDarkCut = if (ringMedLum >= 160f) {
				min(58f, max(32f, (ringMedLum * 0.34f).roundToInt().toFloat()))
			} else {
				min(95f, max(40f, ringMedLum - 35f))
			}
			val maxLook = if (bestIdx < 0 || baseLum < max(70f, ringMedLum - 15f)) 14 else 8
			val maxTotalScan = 24
			var cleanSteps = 0
			for (s in 1..maxTotalScan) {
				val nextCoord = coord + coordDelta * s
				if (nextCoord < 0 || nextCoord >= limit) break
				val candIdx = edgeIdx + stride * s
				if (borderMask[candIdx] != 0.toByte()) break
				if (eraseMask[candIdx] != 0.toByte()) continue
				cleanSteps++
				if (cleanSteps > maxLook && bestIdx >= 0) break
				if (!isCleanBoundary(candIdx)) continue
				val cc = candIdx * 3
				val candLum = PixelColorMath.luminance(cleaned[cc], cleaned[cc + 1], cleaned[cc + 2])
				val bestC = max(0, bestIdx) * 3
				val refBlueDiff = if (bestIdx >= 0) {
					cleaned[bestC + 2] - cleaned[bestC]
				} else {
					cleaned[baseC + 2] - cleaned[baseC]
				}
				val candBlueMatch =
					ringMedB > ringMedR + 12 &&
						(cleaned[cc + 2] - cleaned[cc]) > refBlueDiff + 10f
				val allowLumEscape = bestIdx < 0 || bestLum < suspiciousDarkCut
				if ((allowLumEscape && candLum > bestLum + 10f) || bestIdx < 0 || candBlueMatch) {
					bestIdx = candIdx
					bestLum = candLum
					if (candLum >= baseLum + 26f && cleanSteps >= 5) break
				} else if (bestIdx >= 0 && bestLum >= 165f && cleanSteps >= 2 && abs(candLum - bestLum) <= 8f) {
					break
				}
			}
			return bestIdx
		}

		// 2. Horizontal and vertical clean-boundary span interpolation.
		val hInterp = cleaned.copyOf()
		val hSpan = FloatArray(numPixels) { 9999f }
		val hEdgeDiff = FloatArray(numPixels) { 999f }

		for (y in regY0..regY1) {
			val rOff = y * ww
			var rowFirstX = -1
			var rowLastX = -1
			for (rx in regX0..regX1) {
				if (eraseMask[rOff + rx] != 0.toByte()) {
					if (rowFirstX < 0) rowFirstX = rx
					rowLastX = rx
				}
			}
			val outerLIdx = if (rowFirstX > 0) resolveCleanEdge(rOff + rowFirstX - 1, -1, -1, rowFirstX - 1, ww) else -1
			val outerRIdx = if (rowLastX >= 0 && rowLastX + 1 < ww) {
				resolveCleanEdge(rOff + rowLastX + 1, 1, 1, rowLastX + 1, ww)
			} else {
				-1
			}
			val hasSmoothOuterPair = outerLIdx >= 0 && outerRIdx >= 0 &&
				PixelColorMath.maxChannelDiff(
					cleaned[outerLIdx * 3], cleaned[outerLIdx * 3 + 1], cleaned[outerLIdx * 3 + 2],
					cleaned[outerRIdx * 3], cleaned[outerRIdx * 3 + 1], cleaned[outerRIdx * 3 + 2],
				) <= 28f
			val outerMinLum = if (hasSmoothOuterPair) {
				min(
					PixelColorMath.luminance(cleaned[outerLIdx * 3], cleaned[outerLIdx * 3 + 1], cleaned[outerLIdx * 3 + 2]),
					PixelColorMath.luminance(cleaned[outerRIdx * 3], cleaned[outerRIdx * 3 + 1], cleaned[outerRIdx * 3 + 2]),
				)
			} else {
				0f
			}

			var x = regX0
			while (x <= regX1) {
				if (eraseMask[rOff + x] != 0.toByte()) {
					val xs0 = x
					while (x < ww && eraseMask[rOff + x] != 0.toByte()) x++
					val xs1 = x

					val leftEdge = if (xs0 > 0) resolveCleanEdge(rOff + xs0 - 1, -1, -1, xs0 - 1, ww) else -1
					val rightEdge = if (xs1 < ww) resolveCleanEdge(rOff + xs1, 1, 1, xs1, ww) else -1
					val useLeft = leftEdge >= 0
					val useRight = rightEdge >= 0
					val lIdx = max(0, leftEdge) * 3
					val rIdx = max(0, rightEdge) * 3

					var lr = if (useLeft) cleaned[lIdx] else if (useRight) cleaned[rIdx] else ringMedRf
					var lg = if (useLeft) cleaned[lIdx + 1] else if (useRight) cleaned[rIdx + 1] else ringMedGf
					var lb = if (useLeft) cleaned[lIdx + 2] else if (useRight) cleaned[rIdx + 2] else ringMedBf

					var rr = if (useRight) cleaned[rIdx] else if (useLeft) cleaned[lIdx] else ringMedRf
					var rg = if (useRight) cleaned[rIdx + 1] else if (useLeft) cleaned[lIdx + 1] else ringMedGf
					var rb = if (useRight) cleaned[rIdx + 2] else if (useLeft) cleaned[lIdx + 2] else ringMedBf

					if (hasSmoothOuterPair) {
						val lLum = PixelColorMath.luminance(lr, lg, lb)
						val rLum = PixelColorMath.luminance(rr, rg, rb)
						if (!useLeft || (lLum < outerMinLum - 16f && lLum < 105f)) {
							val o3 = outerLIdx * 3
							lr = cleaned[o3]
							lg = cleaned[o3 + 1]
							lb = cleaned[o3 + 2]
						}
						if (!useRight || (rLum < outerMinLum - 16f && rLum < 105f)) {
							val o3 = outerRIdx * 3
							rr = cleaned[o3]
							rg = cleaned[o3 + 1]
							rb = cleaned[o3 + 2]
						}
					}

					val rowEdgeDiff = when {
						useLeft && useRight -> PixelColorMath.maxChannelDiff(lr, lg, lb, rr, rg, rb)
						hasSmoothOuterPair -> 12f
						else -> 999f
					}

					val sp = max(1, xs1 - xs0)
					for (i in 0 until sp) {
						val t = (i + 0.5f) / sp
						val pIdx = rOff + xs0 + i
						val cIdx = pIdx * 3
						hInterp[cIdx] = (1f - t) * lr + t * rr
						hInterp[cIdx + 1] = (1f - t) * lg + t * rg
						hInterp[cIdx + 2] = (1f - t) * lb + t * rb
						hSpan[pIdx] = sp / anisoH
						hEdgeDiff[pIdx] = rowEdgeDiff
					}
				} else {
					x++
				}
			}
		}

		val vInterp = cleaned.copyOf()
		val vSpan = FloatArray(numPixels) { 9999f }
		val vEdgeDiff = FloatArray(numPixels) { 999f }

		for (x in regX0..regX1) {
			var y = regY0
			while (y <= regY1) {
				if (eraseMask[y * ww + x] != 0.toByte()) {
					val ys0 = y
					while (y < wh && eraseMask[y * ww + x] != 0.toByte()) y++
					val ys1 = y

					val topEdge = if (ys0 > 0) resolveCleanEdge((ys0 - 1) * ww + x, -ww, -1, ys0 - 1, wh) else -1
					val botEdge = if (ys1 < wh) resolveCleanEdge(ys1 * ww + x, ww, 1, ys1, wh) else -1
					val useTop = topEdge >= 0
					val useBot = botEdge >= 0
					val t3 = max(0, topEdge) * 3
					val b3 = max(0, botEdge) * 3

					val tr = if (useTop) cleaned[t3] else if (useBot) cleaned[b3] else ringMedRf
					val tg = if (useTop) cleaned[t3 + 1] else if (useBot) cleaned[b3 + 1] else ringMedGf
					val tb = if (useTop) cleaned[t3 + 2] else if (useBot) cleaned[b3 + 2] else ringMedBf

					val br = if (useBot) cleaned[b3] else if (useTop) cleaned[t3] else ringMedRf
					val bg = if (useBot) cleaned[b3 + 1] else if (useTop) cleaned[t3 + 1] else ringMedGf
					val bb = if (useBot) cleaned[b3 + 2] else if (useTop) cleaned[t3 + 2] else ringMedBf

					val colEdgeDiff = if (useTop && useBot) {
						PixelColorMath.maxChannelDiff(tr, tg, tb, br, bg, bb)
					} else {
						999f
					}

					val sp = max(1, ys1 - ys0)
					for (i in 0 until sp) {
						val t = (i + 0.5f) / sp
						val pIdx = (ys0 + i) * ww + x
						val cIdx = pIdx * 3
						vInterp[cIdx] = (1f - t) * tr + t * br
						vInterp[cIdx + 1] = (1f - t) * tg + t * bg
						vInterp[cIdx + 2] = (1f - t) * tb + t * bb
						vSpan[pIdx] = sp * anisoH
						vEdgeDiff[pIdx] = colEdgeDiff
					}
				} else {
					y++
				}
			}
		}

		// 3. Telea Fast Marching BFS with isophote directional locking (`lockDir`).
		val known = ByteArray(numPixels)
		val order = IntArray(numPixels)
		var qHead = 0
		var qTail = 0

		for (i in 0 until numPixels) {
			if (eraseMask[i] == 0.toByte()) {
				known[i] = 1
			}
		}

		for (y in regY0..regY1) {
			val rOff = y * ww
			for (x in regX0..regX1) {
				val idx = rOff + x
				if (eraseMask[idx] == 0.toByte()) continue
				if ((x > 0 && known[idx - 1] != 0.toByte()) ||
					(x < ww - 1 && known[idx + 1] != 0.toByte()) ||
					(y > 0 && known[idx - ww] != 0.toByte()) ||
					(y < wh - 1 && known[idx + ww] != 0.toByte())
				) {
					known[idx] = 2
					order[qTail++] = idx
				}
			}
		}

		val lockDir = ByteArray(numPixels)
		while (qHead < qTail) {
			val idx = order[qHead++]
			val y = idx / ww
			val x = idx - y * ww

			var wSum = 0f
			var rSum = 0f
			var gSum = 0f
			var bSum = 0f

			val rMinY = max(0, y - 2)
			val rMaxY = min(wh - 1, y + 2)
			val rMinX = max(0, x - 2)
			val rMaxX = min(ww - 1, x + 2)

			val hDiff = hEdgeDiff[idx]
			val vDiff = vEdgeDiff[idx]
			val cIdx = idx * 3
			val hvMismatch = PixelColorMath.maxChannelDiff(
				hInterp[cIdx], hInterp[cIdx + 1], hInterp[cIdx + 2],
				vInterp[cIdx], vInterp[cIdx + 1], vInterp[cIdx + 2],
			)
			val strongVertLine =
				ringMedLum >= 120f && vDiff <= 26f && hvMismatch >= 22f && vSpan[idx] <= hSpan[idx] * 0.9f
			val strongHorizLine =
				hDiff <= 26f && hvMismatch >= 22f && hSpan[idx] < vSpan[idx]
			val strongHorizGrad =
				!strongVertLine &&
					(strongHorizLine ||
						(hDiff <= 38f && vDiff >= hDiff + 24f) ||
						(hDiff <= 22f && vDiff > hDiff + 8f))
			val strongVertGrad =
				!strongHorizGrad &&
					(strongVertLine ||
						(vDiff <= 38f && hDiff >= vDiff + 24f) ||
						(vDiff <= 22f && hDiff > vDiff + 8f))

			if (strongHorizGrad) lockDir[idx] = 1
			else if (strongVertGrad) lockDir[idx] = 2

			val whW = when {
				strongHorizGrad -> 0.94f
				strongVertGrad -> 0.06f
				else -> vSpan[idx] / max(1e-4f, hSpan[idx] + vSpan[idx])
			}
			val wvW = 1f - whW
			val spanR = whW * hInterp[cIdx] + wvW * vInterp[cIdx]
			val spanG = whW * hInterp[cIdx + 1] + wvW * vInterp[cIdx + 1]
			val spanB = whW * hInterp[cIdx + 2] + wvW * vInterp[cIdx + 2]
			val spanLum = PixelColorMath.luminance(spanR, spanG, spanB)

			for (ny in rMinY..rMaxY) {
				val nrOff = ny * ww
				val dy = ny - y
				for (nx in rMinX..rMaxX) {
					val nIdx = nrOff + nx
					if (known[nIdx] != 1.toByte() || borderMask[nIdx] != 0.toByte()) continue
					val dx = nx - x
					val distSq = dx * dx + dy * dy
					if (distSq == 0) continue

					val dirFactor = when {
						dy == 0 -> anisoH
						dx == 0 -> 1f / anisoH
						else -> 1.0f
					}
					val w = dirFactor / distSq.toFloat()
					val c3 = nIdx * 3
					val nr = cleaned[c3]
					val ng = cleaned[c3 + 1]
					val nb = cleaned[c3 + 2]
					val nLum = PixelColorMath.luminance(nr, ng, nb)

					val isUnmaskedOrig = eraseMask[nIdx] == 0.toByte()
					val isDarkHaloNeighbor =
						isUnmaskedOrig &&
							((nLum < min(130f, spanLum - 22f) && !isCleanBoundary(nIdx)) ||
								(nLum < 120f && spanLum - nLum >= 26f) ||
								(ringMedB > ringMedR + 12 && nb - nr < 10f && nLum < 45f))
					val isOutlier =
						isDarkHaloNeighbor ||
							(isUnmaskedOrig &&
								PixelColorMath.maxChannelDiff(nr, ng, nb, ringMedRf, ringMedGf, ringMedBf) > 85f &&
								!hasSmoothCleanNeighbor(nIdx, nr, ng, nb))

					rSum += (if (isOutlier) spanR else nr) * w
					gSum += (if (isOutlier) spanG else ng) * w
					bSum += (if (isOutlier) spanB else nb) * w
					wSum += w
				}
			}

			if (wSum > 0f) {
				val fmmR = rSum / wSum
				val fmmG = gSum / wSum
				val fmmB = bSum / wSum
				val fmmWeight = if (strongHorizGrad || strongVertGrad) 0.05f else 0.42f
				val spanWeight = 1f - fmmWeight
				cleaned[cIdx] = spanWeight * spanR + fmmWeight * fmmR
				cleaned[cIdx + 1] = spanWeight * spanG + fmmWeight * fmmG
				cleaned[cIdx + 2] = spanWeight * spanB + fmmWeight * fmmB
			} else {
				cleaned[cIdx] = spanR
				cleaned[cIdx + 1] = spanG
				cleaned[cIdx + 2] = spanB
			}

			known[idx] = 1

			if (x > 0) {
				val nIdx = idx - 1
				if (known[nIdx] == 0.toByte() && eraseMask[nIdx] != 0.toByte()) {
					known[nIdx] = 2
					order[qTail++] = nIdx
				}
			}
			if (x < ww - 1) {
				val nIdx = idx + 1
				if (known[nIdx] == 0.toByte() && eraseMask[nIdx] != 0.toByte()) {
					known[nIdx] = 2
					order[qTail++] = nIdx
				}
			}
			if (y > 0) {
				val nIdx = idx - ww
				if (known[nIdx] == 0.toByte() && eraseMask[nIdx] != 0.toByte()) {
					known[nIdx] = 2
					order[qTail++] = nIdx
				}
			}
			if (y < wh - 1) {
				val nIdx = idx + ww
				if (known[nIdx] == 0.toByte() && eraseMask[nIdx] != 0.toByte()) {
					known[nIdx] = 2
					order[qTail++] = nIdx
				}
			}
		}

		// 4. Anisotropic direction-locked harmonic smoothing (10 iterations).
		val wH = (0.25f * anisoH).coerceIn(0.12f, 0.38f)
		for (iter in 0 until 10) {
			for (k in 0 until qTail) {
				val idx = order[k]
				val y = idx / ww
				val x = idx - y * ww
				if (x <= 0 || x >= ww - 1 || y <= 0 || y >= wh - 1) continue
				val ld = lockDir[idx]
				val pwH = when (ld) {
					1.toByte() -> 0.47f
					2.toByte() -> 0.03f
					else -> wH
				}
				val pwV = 0.5f - pwH
				val c = idx * 3
				val n = (idx - ww) * 3
				val s = (idx + ww) * 3
				val w = (idx - 1) * 3
				val e = (idx + 1) * 3
				cleaned[c] = pwH * (cleaned[w] + cleaned[e]) + pwV * (cleaned[n] + cleaned[s])
				cleaned[c + 1] = pwH * (cleaned[w + 1] + cleaned[e + 1]) + pwV * (cleaned[n + 1] + cleaned[s + 1])
				cleaned[c + 2] = pwH * (cleaned[w + 2] + cleaned[e + 2]) + pwV * (cleaned[n + 2] + cleaned[s + 2])
			}
		}

		// 5. Synthesize matching deterministic monochrome micro-grain if surrounding texture exhibits paper grain.
		var hpSum = 0.0
		var hpCount = 0
		var y = max(2, regY0 - 4)
		val yEnd = min(wh - 3, regY1 + 4)
		while (y <= yEnd) {
			val rOff = y * ww
			var x = max(2, regX0 - 6)
			val xEnd = min(ww - 3, regX1 + 6)
			while (x <= xEnd) {
				val idx = rOff + x
				if (eraseMask[idx] == 0.toByte() &&
					borderMask[idx] == 0.toByte() &&
					x > 0 && x + 1 < ww && y > 0 && y + 1 < wh &&
					eraseMask[idx - 1] == 0.toByte() &&
					eraseMask[idx + 1] == 0.toByte() &&
					eraseMask[idx - ww] == 0.toByte() &&
					eraseMask[idx + ww] == 0.toByte()
				) {
					val c = idx * 3
					val cl = (idx - 1) * 3
					val cr = (idx + 1) * 3
					val ct = (idx - ww) * 3
					val cb = (idx + ww) * 3
					val lC = PixelColorMath.luminance(cleaned[c], cleaned[c + 1], cleaned[c + 2])
					val lL = PixelColorMath.luminance(cleaned[cl], cleaned[cl + 1], cleaned[cl + 2])
					val lR = PixelColorMath.luminance(cleaned[cr], cleaned[cr + 1], cleaned[cr + 2])
					val lT = PixelColorMath.luminance(cleaned[ct], cleaned[ct + 1], cleaned[ct + 2])
					val lB = PixelColorMath.luminance(cleaned[cb], cleaned[cb + 1], cleaned[cb + 2])
					if (abs(lL - lR) <= 24f && abs(lT - lB) <= 24f) {
						hpSum += abs(lC - 0.25f * (lL + lR + lT + lB))
						hpCount++
					}
				}
				x += 2
			}
			y += 2
		}

		val avgGrain = if (hpCount >= 24) (hpSum / hpCount).toFloat() else 0f
		if (avgGrain >= 2.2f) {
			val grainAmp = (avgGrain * 1.45f).coerceIn(3.0f, 12.0f)
			for (k in 0 until qTail) {
				val idx = order[k]
				val py = idx / ww
				val px = idx - py * ww
				var h = (px * 374761393 + py * 668265263) xor 0x5bd1e995
				h = (h xor (h ushr 13)) * 1274126177
				h = h xor (h ushr 16)
				val u1 = ((h and 0xffff) / 65535f) - 0.5f
				val u2 = (((h ushr 16) and 0xffff) / 65535f) - 0.5f
				val delta = (u1 + u2) * grainAmp
				val c = idx * 3
				cleaned[c] = (cleaned[c] + delta).coerceIn(0f, 255f)
				cleaned[c + 1] = (cleaned[c + 1] + delta).coerceIn(0f, 255f)
				cleaned[c + 2] = (cleaned[c + 2] + delta).coerceIn(0f, 255f)
			}
		}
	}
}
