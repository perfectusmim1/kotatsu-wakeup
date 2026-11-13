package org.koitharu.kotatsu.reader.domain.translate.render

import android.graphics.Bitmap
import android.text.TextPaint
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.koitharu.kotatsu.reader.domain.translate.model.NormalizedBox
import org.koitharu.kotatsu.reader.domain.translate.model.OcrBubbleGroup
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
	val isSeamEraseOnly: Boolean = false,
)

@Singleton
class BubbleInpainter @Inject constructor(
	private val styleExtractor: StyleExtractor,
	private val smartTextLayout: SmartTextLayout,
) {

	internal data class LineCoreRect(
		val x0: Int,
		val x1: Int,
		val y0: Int,
		val y1: Int,
	)

	internal data class AbsSiblingBox(
		val x0: Int,
		val y0: Int,
		val x1: Int,
		val y1: Int,
	)

	private data class ComponentInfo(
		val id: Int,
		val minx: Int,
		val maxx: Int,
		val miny: Int,
		val maxy: Int,
		val w: Int,
		val h: Int,
		val area: Int,
		val corePixels: Int,
		val touchesBorder: Boolean,
		val touchesSideOrWinEdge: Boolean,
		val touchesImageSeamEdge: Boolean,
		val overlapsCore: Boolean,
	)

	private class MaskPassResult(
		val labels: IntArray,
		val comps: List<ComponentInfo>,
		val valid: List<ComponentInfo>,
		val accepted: MutableSet<Int>,
		val spanW: Int,
	)

	private class WindowAnalysisResult(
		val pixels: IntArray,
		val ww: Int,
		val wh: Int,
		val numPixels: Int,
		val cx0: Int,
		val cy0: Int,
		val cx1: Int,
		val cy1: Int,
		val bgR: Int,
		val bgG: Int,
		val bgB: Int,
		val labels: IntArray,
		val comps: List<ComponentInfo>,
		val valid: List<ComponentInfo>,
		val accepted: MutableSet<Int>,
		val medLineH: Float,
		val preferLightFirst: Boolean,
		val isBlueOrMidPanel: Boolean,
		val isSingleOrDarkSfx: Boolean,
		val whiteCoreCount: Int,
		val expandLeft: Int,
		val expandRight: Int,
		val expandTop: Int,
		val expandBottom: Int,
		val isStructuralFrameOrDivider: (ComponentInfo) -> Boolean,
	)

	/**
	 * ToonParrot `bubbleInpainter.ts` (v15) full parity:
	 * Samples flat-gradient dominant background, separates dark/light ink via dual R+RTight integral
	 * windows and flank scene restoration, preserves connected text rows, rescues closed-cavity orphan
	 * glyphs, absorbs white & row-aware dark halos, executes 3-tier inpainting (gradient halo + flat
	 * light bubble + textured FMM + residual verification + shadow sweep), and sizes text against full
	 * bubble contour geometry.
	 */
	fun inpaintAndRefineBlock(
		workBitmap: Bitmap,
		bubble: OcrBubbleGroup,
		translatedText: String,
		explicitKind: TextBlockKind?,
		targetLang: String,
		siblingBoxes: List<NormalizedBox> = emptyList(),
		seamEraseOnly: Boolean = false,
	): RefinedBlockLayout? {
		val fullWidth = workBitmap.width
		val fullHeight = workBitmap.height
		if (fullWidth <= 4 || fullHeight <= 4 || !bubble.box.isValid()) return null

		val sanitizedTranslated = if (seamEraseOnly) {
			""
		} else {
			translatedText
				.replace(HTML_TAG_REGEX, " ")
				.replace(ANGLE_BRACKET_REGEX, " ")
				.replace(MULTI_SPACE_REGEX, " ")
				.trim()
		}

		if (!seamEraseOnly) {
			if (sanitizedTranslated.isEmpty() || sanitizedTranslated.equals("[SFX]", ignoreCase = true)) {
				return null
			}
			if (bubble.text.isNotBlank() &&
				RenderTextHelpers.normalizeForCompare(sanitizedTranslated) ==
				RenderTextHelpers.normalizeForCompare(bubble.text)
			) {
				return null
			}
		}

		var ymin = bubble.box.yMin
		var xmin = bubble.box.xMin
		var ymax = bubble.box.yMax
		var xmax = bubble.box.xMax
		for (l in bubble.lines) {
			if (!l.box.isValid()) continue
			if (l.box.yMin < ymin) ymin = l.box.yMin
			if (l.box.xMin < xmin) xmin = l.box.xMin
			if (l.box.yMax > ymax) ymax = l.box.yMax
			if (l.box.xMax > xmax) xmax = l.box.xMax
		}

		val boxX0 = floor((xmin / 1000f) * fullWidth).toInt().coerceIn(0, fullWidth - 1)
		val boxX1 = ceil((xmax / 1000f) * fullWidth).toInt().coerceIn(boxX0 + 1, fullWidth)
		val boxY0 = floor((ymin / 1000f) * fullHeight).toInt().coerceIn(0, fullHeight - 1)
		val boxY1 = ceil((ymax / 1000f) * fullHeight).toInt().coerceIn(boxY0 + 1, fullHeight)

		val bw = boxX1 - boxX0
		val bh = boxY1 - boxY0
		val displayText = if (seamEraseOnly) {
			""
		} else {
			smartTextLayout.formatScanlationText(sanitizedTranslated, targetLang)
		}
		if (!seamEraseOnly && displayText.isEmpty()) return null

		val origLineCount = bubble.lines.size.coerceAtLeast(1)
		val isSingleLineStat =
			!seamEraseOnly &&
				origLineCount == 1 &&
				!displayText.contains('\n') &&
				displayText.length <= 48 &&
				RenderTextHelpers.isRpgStatLine(displayText)

		val absSiblings = siblingBoxes.mapNotNull { sb ->
			if (!sb.isValid()) return@mapNotNull null
			AbsSiblingBox(
				x0 = floor((sb.xMin / 1000f) * fullWidth).toInt(),
				y0 = floor((sb.yMin / 1000f) * fullHeight).toInt(),
				x1 = ceil((sb.xMax / 1000f) * fullWidth).toInt(),
				y1 = ceil((sb.yMax / 1000f) * fullHeight).toInt(),
			)
		}

		fun overlapsAnySiblingAbs(
			ax0: Int,
			ay0: Int,
			ax1: Int,
			ay1: Int,
			requireHeavyAreaOverlap: Boolean = false,
		): Boolean = shouldSuppressComponentForSibling(
			ax0 = ax0,
			ay0 = ay0,
			ax1 = ax1,
			ay1 = ay1,
			absSiblings = absSiblings,
			requireHeavyAreaOverlap = requireHeavyAreaOverlap,
		)

		val nearTopSeam = boxY0 <= 90
		val nearBottomSeam = fullHeight - boxY1 <= 90
		val basePadX = if (nearTopSeam || nearBottomSeam) 130f else if (bh < 55) 64f else 48f
		val padWinX = max(basePadX, max(bw * 0.56f, bh * 1.15f))
		val basePadY = if (bh < 55) 56f else 48f
		val padWinY = max(basePadY, max(bh * 1.15f, bw * 0.42f))

		var wx0 = max(0, floor(boxX0 - padWinX).toInt())
		var wx1 = min(fullWidth, ceil(boxX1 + padWinX).toInt())
		var wy0 = if (nearTopSeam) 0 else max(0, floor(boxY0 - padWinY).toInt())
		var wy1 = if (nearBottomSeam) fullHeight else min(fullHeight, ceil(boxY1 + padWinY).toInt())

		fun analyzeWindow(curWx0: Int, curWy0: Int, curWx1: Int, curWy1: Int): WindowAnalysisResult? {
			val ww = curWx1 - curWx0
			val wh = curWy1 - curWy0
			if (ww <= 4 || wh <= 4) return null

			val numPixels = ww * wh
			val pixels = IntArray(numPixels)
			workBitmap.getPixels(pixels, 0, ww, curWx0, curWy0, ww, wh)

			val cx0 = (boxX0 - curWx0).coerceIn(0, ww - 1)
			val cx1 = (boxX1 - curWx0).coerceIn(cx0 + 1, ww)
			val cy0 = (boxY0 - curWy0).coerceIn(0, wh - 1)
			val cy1 = (boxY1 - curWy0).coerceIn(cy0 + 1, wh)

			val validLines = bubble.lines.filter { it.box.isValid() }
			val lineCores = if (validLines.isNotEmpty()) {
				validLines.map { l ->
					LineCoreRect(
						x0 = (floor((l.box.xMin / 1000f) * fullWidth).toInt() - curWx0).coerceIn(0, ww - 1),
						x1 = (ceil((l.box.xMax / 1000f) * fullWidth).toInt() - curWx0).coerceIn(1, ww),
						y0 = (floor((l.box.yMin / 1000f) * fullHeight).toInt() - curWy0).coerceIn(0, wh - 1),
						y1 = (ceil((l.box.yMax / 1000f) * fullHeight).toInt() - curWy0).coerceIn(1, wh),
					)
				}
			} else {
				listOf(LineCoreRect(cx0, cx1, cy0, cy1))
			}

			val bgRgb = findDominantBackgroundRgb(pixels, ww, wh, cx0, cy0, cx1, cy1, lineCores)
			var bgR = bgRgb[0]
			var bgG = bgRgb[1]
			var bgB = bgRgb[2]
			var bgLumInit = PixelColorMath.luminance(bgR, bgG, bgB)

			// 2D Integral RGB images.
			val iw = ww + 1
			val ih = wh + 1
			val iiR = DoubleArray(iw * ih)
			val iiG = DoubleArray(iw * ih)
			val iiB = DoubleArray(iw * ih)
			for (y in 1..wh) {
				var rowSumR = 0.0
				var rowSumG = 0.0
				var rowSumB = 0.0
				val prevRowOffset = (y - 1) * iw
				val currRowOffset = y * iw
				val srcRowOff = (y - 1) * ww
				for (x in 1..ww) {
					val p = pixels[srcRowOff + x - 1]
					rowSumR += PixelColorMath.red(p)
					rowSumG += PixelColorMath.green(p)
					rowSumB += PixelColorMath.blue(p)
					iiR[currRowOffset + x] = iiR[prevRowOffset + x] + rowSumR
					iiG[currRowOffset + x] = iiG[prevRowOffset + x] + rowSumG
					iiB[currRowOffset + x] = iiB[prevRowOffset + x] + rowSumB
				}
			}

			val medLineH = if (lineCores.isNotEmpty()) {
				val hs = FloatArray(lineCores.size) { i -> max(8, lineCores[i].y1 - lineCores[i].y0).toFloat() }
				PixelColorMath.medianFloat(hs, hs.size)
			} else {
				max(16f, bh.toFloat())
			}
			val rBig = min(bw, bh).times(0.42f).roundToInt().coerceIn(24, 54)
			val rTight = (medLineH * 0.18f).roundToInt().coerceIn(8, 16)

			fun isInsideAnyCore(x: Int, y: Int): Boolean {
				for (i in lineCores.indices) {
					val lc = lineCores[i]
					if (x in lc.x0..lc.x1 && y in lc.y0..lc.y1) return true
				}
				return false
			}

			fun isNearAnyCore(x: Int, y: Int, pad: Int): Boolean {
				for (i in lineCores.indices) {
					val lc = lineCores[i]
					if (x >= lc.x0 - pad && x <= lc.x1 + pad && y >= lc.y0 - pad && y <= lc.y1 + pad) {
						return true
					}
				}
				return false
			}

			var coreSamples = 0
			var jetBlackCoreCount = 0
			var whiteCoreCount = 0
			var blackTouchesMid = 0
			var whiteTouchesMid = 0
			var whiteTouchesDarkOutline = 0

			fun probeMidScene(px: Int, py: Int): Boolean {
				val cx = px.coerceIn(0, ww - 1)
				val cy = py.coerceIn(0, wh - 1)
				val l = PixelColorMath.luminance(pixels[cy * ww + cx])
				return l in 55f..205f
			}

			fun probeDarkOutline(px: Int, py: Int): Boolean {
				val cx = px.coerceIn(0, ww - 1)
				val cy = py.coerceIn(0, wh - 1)
				val l = PixelColorMath.luminance(pixels[cy * ww + cx])
				return l <= min(135f, max(75f, bgLumInit - 18f))
			}

			for (i in lineCores.indices) {
				val lc = lineCores[i]
				var y = lc.y0
				while (y < lc.y1) {
					val rowOff = y * ww
					var x = lc.x0
					while (x < lc.x1) {
						coreSamples++
						val lum = PixelColorMath.luminance(pixels[rowOff + x])
						if (lum <= 42f) {
							jetBlackCoreCount++
							if (probeMidScene(x - 3, y) ||
								probeMidScene(x + 3, y) ||
								probeMidScene(x, y - 3) ||
								probeMidScene(x, y + 3)
							) {
								blackTouchesMid++
							}
						}
						if (lum >= 225f) {
							if (lum >= 232f) {
								whiteCoreCount++
								if (probeMidScene(x - 3, y) ||
									probeMidScene(x + 3, y) ||
									probeMidScene(x, y - 3) ||
									probeMidScene(x, y + 3)
								) {
									whiteTouchesMid++
								}
							}
							if (probeDarkOutline(x - 3, y) ||
								probeDarkOutline(x + 3, y) ||
								probeDarkOutline(x, y - 3) ||
								probeDarkOutline(x, y + 3)
							) {
								whiteTouchesDarkOutline++
							}
						}
						x += 2
					}
					y += 2
				}
			}

			val hasJetBlackCoreInk =
				bgLumInit >= 145f &&
					bgLumInit < 226f &&
					whiteCoreCount >= 18 &&
					whiteCoreCount.toFloat() / max(1, coreSamples) <= 0.72f &&
					jetBlackCoreCount >= 14 &&
					jetBlackCoreCount.toFloat() / max(1, coreSamples) >= 0.015f

			fun buildDarkForegroundMask(localThresh: Float): ByteArray {
				val fg = ByteArray(numPixels)
				for (y in 0 until wh) {
					val y0s = max(0, y - rBig)
					val y1s = min(wh, y + rBig + 1)
					val r0Off = y0s * iw
					val r1Off = y1s * iw
					val spanY = y1s - y0s

					val ty0s = max(0, y - rTight)
					val ty1s = min(wh, y + rTight + 1)
					val tr0Off = ty0s * iw
					val tr1Off = ty1s * iw
					val tSpanY = ty1s - ty0s
					val rowOff = y * ww

					for (x in 0 until ww) {
						val pIdx = rowOff + x
						val p = pixels[pIdx]
						val pr = PixelColorMath.red(p)
						val pg = PixelColorMath.green(p)
						val pb = PixelColorMath.blue(p)
						val pixLum = PixelColorMath.luminance(pr, pg, pb)
						if (pixLum >= 225f) continue

						val inCore = isInsideAnyCore(x, y)
						val nearCore3 = isNearAnyCore(x, y, 3)
						if (hasJetBlackCoreInk && pixLum > (if (nearCore3) 58f else 120f)) {
							continue
						}

						val dBg = PixelColorMath.maxChannelDiff(pr, pg, pb, bgR, bgG, bgB)
						if (dBg < 26 && abs(pixLum - bgLumInit) < 20f) continue

						val x0s = max(0, x - rBig)
						val x1s = min(ww, x + rBig + 1)
						val invArea = 1.0 / (spanY * (x1s - x0s))
						val meanR = ((iiR[r1Off + x1s] - iiR[r0Off + x1s] - iiR[r1Off + x0s] + iiR[r0Off + x0s]) * invArea).toFloat()
						val meanG = ((iiG[r1Off + x1s] - iiG[r0Off + x1s] - iiG[r1Off + x0s] + iiG[r0Off + x0s]) * invArea).toFloat()
						val meanB = ((iiB[r1Off + x1s] - iiB[r0Off + x1s] - iiB[r1Off + x0s] + iiB[r0Off + x0s]) * invArea).toFloat()

						val tx0s = max(0, x - rTight)
						val tx1s = min(ww, x + rTight + 1)
						val tInvArea = 1.0 / (tSpanY * (tx1s - tx0s))
						val tMeanR = ((iiR[tr1Off + tx1s] - iiR[tr0Off + tx1s] - iiR[tr1Off + tx0s] + iiR[tr0Off + tx0s]) * tInvArea).toFloat()
						val tMeanG = ((iiG[tr1Off + tx1s] - iiG[tr0Off + tx1s] - iiG[tr1Off + tx0s] + iiG[tr0Off + tx0s]) * tInvArea).toFloat()
						val tMeanB = ((iiB[tr1Off + tx1s] - iiB[tr0Off + tx1s] - iiB[tr1Off + tx0s] + iiB[tr0Off + tx0s]) * tInvArea).toFloat()

						val effLocal = if (inCore) max(9f, localThresh * 0.65f) else localThresh
						val meanLum = PixelColorMath.luminance(meanR, meanG, meanB)
						val tMeanLum = PixelColorMath.luminance(tMeanR, tMeanG, tMeanB)

						val dLumMedDark = meanLum - pixLum
						val dLumTightDark = tMeanLum - pixLum
						val dMedDark = max(meanR - pr, max(meanG - pg, meanB - pb))
						val dTightDark = max(tMeanR - pr, max(tMeanG - pg, tMeanB - pb))

						val nearDarkRingWhite =
							bgLumInit >= 170f &&
								pixLum < min(135f, bgLumInit - 65f) &&
								dLumTightDark > effLocal * 0.72f &&
								(inCore || tMeanLum >= 145f)
						val midToneDarkInk =
							bgLumInit >= 75f &&
								pixLum <= 110f &&
								dLumMedDark > effLocal * 0.78f &&
								dMedDark > effLocal * 0.85f &&
								(meanLum >= 85f || tMeanLum >= 120f)

						if ((meanLum >= 160f && dLumMedDark > effLocal * 0.82f && dMedDark > effLocal) ||
							nearDarkRingWhite ||
							midToneDarkInk ||
							(pixLum < 115f &&
								tMeanLum >= 135f &&
								tMeanLum > bgLumInit + 32f &&
								(pixLum < bgLumInit - 26f || dBg >= 38) &&
								dLumTightDark > effLocal * 0.75f &&
								dTightDark > effLocal * 0.9f)
						) {
							fg[pIdx] = 1
						}
					}
				}
				return fg
			}

			fun buildLightForegroundMask(localThresh: Float, globalThresh: Float): ByteArray {
				val fg = ByteArray(numPixels)
				for (y in 0 until wh) {
					val y0s = max(0, y - rBig)
					val y1s = min(wh, y + rBig + 1)
					val r0Off = y0s * iw
					val r1Off = y1s * iw
					val spanY = y1s - y0s
					val rowOff = y * ww

					for (x in 0 until ww) {
						val x0s = max(0, x - rBig)
						val x1s = min(ww, x + rBig + 1)
						val invArea = 1.0 / (spanY * (x1s - x0s))
						val meanR = ((iiR[r1Off + x1s] - iiR[r0Off + x1s] - iiR[r1Off + x0s] + iiR[r0Off + x0s]) * invArea).toFloat()
						val meanG = ((iiG[r1Off + x1s] - iiG[r0Off + x1s] - iiG[r1Off + x0s] + iiG[r0Off + x0s]) * invArea).toFloat()
						val meanB = ((iiB[r1Off + x1s] - iiB[r0Off + x1s] - iiB[r1Off + x0s] + iiB[r0Off + x0s]) * invArea).toFloat()

						val pIdx = rowOff + x
						val p = pixels[pIdx]
						val pr = PixelColorMath.red(p)
						val pg = PixelColorMath.green(p)
						val pb = PixelColorMath.blue(p)

						val inCore = isInsideAnyCore(x, y)
						val effLocal = if (inCore) max(9f, localThresh * 0.65f) else localThresh
						val dLocalLight = max(pr - meanR, max(pg - meanG, pb - meanB))
						val dGlobalLight = max(pr - bgR, max(pg - bgG, pb - bgB)).toFloat()

						if (dLocalLight > effLocal || (dLocalLight > effLocal * 0.5f && dGlobalLight > globalThresh * 1.25f)) {
							fg[pIdx] = 1
							continue
						}

						val chromaDiffLocal =
							abs((pr - pg) - (meanR - meanG)) +
								abs((pr - pb) - (meanR - meanB)) +
								abs((pg - pb) - (meanG - meanB))
						if (chromaDiffLocal > effLocal * 1.55f && dLocalLight > 6f) {
							fg[pIdx] = 1
						}
					}
				}
				return fg
			}

			fun extractComponents(fg: ByteArray): Pair<IntArray, List<ComponentInfo>> {
				val labels = IntArray(numPixels)
				val comps = ArrayList<ComponentInfo>()
				val queue = IntArray(numPixels)
				var curId = 0

				for (y in 0 until wh) {
					val rOff = y * ww
					for (x in 0 until ww) {
						val startIdx = rOff + x
						if (fg[startIdx] == 0.toByte() || labels[startIdx] != 0) continue

						curId++
						labels[startIdx] = curId
						var qHead = 0
						var qTail = 0
						queue[qTail++] = startIdx

						var minx = x
						var maxx = x
						var miny = y
						var maxy = y
						var area = 0
						var corePixels = 0
						var touchesBorder = false
						var touchesSideOrWinEdge = false
						var touchesImageSeamEdge = false
						var overlapsCore = false

						while (qHead < qTail) {
							val curr = queue[qHead++]
							val cy = curr / ww
							val cx = curr - cy * ww
							area++

							if (cx < minx) minx = cx
							if (cx > maxx) maxx = cx
							if (cy < miny) miny = cy
							if (cy > maxy) maxy = cy

							val hitSideOrInner =
								cx <= 1 ||
									cx >= ww - 2 ||
									(cy <= 1 && curWy0 > 0) ||
									(cy >= wh - 2 && curWy1 < fullHeight)
							val hitSeamEdge =
								(curWy0 == 0 && cy <= 1) || (curWy1 == fullHeight && cy >= wh - 2)
							if (hitSideOrInner) {
								touchesSideOrWinEdge = true
								touchesBorder = true
							}
							if (hitSeamEdge) {
								touchesImageSeamEdge = true
								touchesBorder = true
							}
							if (isInsideAnyCore(cx, cy)) {
								overlapsCore = true
								corePixels++
							}

							val nyMin = if (cy > 0) cy - 1 else 0
							val nyMax = if (cy < wh - 1) cy + 1 else wh - 1
							val nxMin = if (cx > 0) cx - 1 else 0
							val nxMax = if (cx < ww - 1) cx + 1 else ww - 1
							for (ny in nyMin..nyMax) {
								val nr = ny * ww
								for (nx in nxMin..nxMax) {
									val nIdx = nr + nx
									if (fg[nIdx] != 0.toByte() && labels[nIdx] == 0) {
										labels[nIdx] = curId
										queue[qTail++] = nIdx
									}
								}
							}
						}

						comps.add(
							ComponentInfo(
								id = curId,
								minx = minx,
								maxx = maxx,
								miny = miny,
								maxy = maxy,
								w = maxx - minx + 1,
								h = maxy - miny + 1,
								area = area,
								corePixels = corePixels,
								touchesBorder = touchesBorder,
								touchesSideOrWinEdge = touchesSideOrWinEdge,
								touchesImageSeamEdge = touchesImageSeamEdge,
								overlapsCore = overlapsCore,
							),
						)
					}
				}
				return labels to comps
			}

			var brighterCount = 0
			var darkerCount = 0
			for (i in lineCores.indices) {
				val lc = lineCores[i]
				for (cy in lc.y0..min(wh - 1, lc.y1)) {
					val rOff = cy * ww
					for (cx in lc.x0..min(ww - 1, lc.x1)) {
						val p = pixels[rOff + cx]
						val pr = PixelColorMath.red(p)
						val pg = PixelColorMath.green(p)
						val pb = PixelColorMath.blue(p)
						val lum = PixelColorMath.luminance(pr, pg, pb)
						if (lum > bgLumInit + 28f && max(pr - bgR, max(pg - bgG, pb - bgB)) > 30) {
							brighterCount++
						}
						if (lum < bgLumInit - 26f && max(bgR - pr, max(bgG - pg, bgB - pb)) > 28) {
							darkerCount++
						}
					}
				}
			}

			// Flank scene sampling (`isWhiteFillDarkOutlineOnScene`).
			var flankSamples = 0
			var flankWhite = 0
			var flankLumSum = 0.0
			val flankRs = IntArray(max(64, (cy1 - cy0 + 1) * 16))
			val flankGs = IntArray(flankRs.size)
			val flankBs = IntArray(flankRs.size)
			var flankArrLen = 0
			val fxL0 = max(0, cx0 - 24)
			val fxL1 = max(0, cx0 - 6)
			val fxR0 = min(ww - 1, cx1 + 6)
			val fxR1 = min(ww - 1, cx1 + 24)

			var fy = max(0, cy0)
			val fyEnd = min(wh - 1, cy1)
			while (fy <= fyEnd) {
				val fOff = fy * ww
				var fx = fxL0
				while (fx <= fxL1) {
					val p = pixels[fOff + fx]
					val r = PixelColorMath.red(p)
					val g = PixelColorMath.green(p)
					val b = PixelColorMath.blue(p)
					val lum = PixelColorMath.luminance(r, g, b)
					flankSamples++
					flankLumSum += lum
					if (flankArrLen < flankRs.size) {
						flankRs[flankArrLen] = r
						flankGs[flankArrLen] = g
						flankBs[flankArrLen] = b
						flankArrLen++
					}
					if (lum >= 232f) flankWhite++
					fx += 3
				}
				fx = fxR0
				while (fx <= fxR1) {
					val p = pixels[fOff + fx]
					val r = PixelColorMath.red(p)
					val g = PixelColorMath.green(p)
					val b = PixelColorMath.blue(p)
					val lum = PixelColorMath.luminance(r, g, b)
					flankSamples++
					flankLumSum += lum
					if (flankArrLen < flankRs.size) {
						flankRs[flankArrLen] = r
						flankGs[flankArrLen] = g
						flankBs[flankArrLen] = b
						flankArrLen++
					}
					if (lum >= 232f) flankWhite++
					fx += 3
				}
				fy += 3
			}

			val coreWhiteRatio = whiteCoreCount.toFloat() / max(1, coreSamples)
			val flankWhiteRatio = flankWhite.toFloat() / max(1, flankSamples)
			val flankMeanLum = if (flankSamples > 0) (flankLumSum / flankSamples).toFloat() else bgLumInit
			val isWhiteFillDarkOutlineOnScene =
				flankSamples >= 12 &&
					whiteCoreCount >= 18 &&
					jetBlackCoreCount >= 18 &&
					blackTouchesMid >= 20 &&
					blackTouchesMid > whiteTouchesMid * 1.6f &&
					coreWhiteRatio < 0.45f &&
					flankWhiteRatio <= 0.25f &&
					flankMeanLum <= 205f
			val hasWhiteFillDarkOutlineOnMidScene = isWhiteFillDarkOutlineMidScene(
				bgLumInit = bgLumInit,
				flankMeanLum = flankMeanLum,
				flankWhiteRatio = flankWhiteRatio,
				coreWhiteRatio = coreWhiteRatio,
				whiteCoreCount = whiteCoreCount,
				whiteTouchesDarkOutline = whiteTouchesDarkOutline,
				brighterCount = brighterCount,
			)

			if ((isWhiteFillDarkOutlineOnScene || (hasWhiteFillDarkOutlineOnMidScene && flankMeanLum < bgLumInit - 12f)) &&
				flankArrLen >= 12
			) {
				bgR = PixelColorMath.medianInt(flankRs, flankArrLen)
				bgG = PixelColorMath.medianInt(flankGs, flankArrLen)
				bgB = PixelColorMath.medianInt(flankBs, flankArrLen)
				bgLumInit = PixelColorMath.luminance(bgR, bgG, bgB)
			}

			var preferLightFirst =
				bgLumInit < 55f ||
					isWhiteFillDarkOutlineOnScene ||
					hasWhiteFillDarkOutlineOnMidScene ||
					(bgLumInit < 160f && brighterCount > darkerCount * 2.2f && darkerCount < brighterCount * 0.35f)
			val isBlueOrMidPanel = bgB > bgR + 20 || bgLumInit >= 50f
			val isSingleOrDarkSfx =
				preferLightFirst &&
					!isBlueOrMidPanel &&
					!isSingleLineStat &&
					origLineCount == 1 &&
					displayText.length <= 16

			fun isSeamCutGlyphCandidate(c: ComponentInfo): Boolean =
				c.touchesImageSeamEdge &&
					!c.touchesSideOrWinEdge &&
					c.w <= max(medLineH * 3.8f, 96f) &&
					c.h <= max(medLineH * 2.0f, 48f) &&
					c.area <= max(medLineH * medLineH * 2.4f, 950f) &&
					c.w.toFloat() / max(1, c.h) < 6.0f

			val isStructuralFrameOrDivider: (ComponentInfo) -> Boolean = divider@{ c ->
				if (c.touchesSideOrWinEdge) return@divider true
				val seamGlyph = isSeamCutGlyphCandidate(c)
				if (c.touchesBorder && !seamGlyph) return@divider true
				val coreRatio = c.corePixels.toFloat() / max(1, c.area)
				val extendsOutsideCore =
					c.minx < cx0 - 3 || c.maxx > cx1 + 3 || c.miny < cy0 - 3 || c.maxy > cy1 + 3
				val looksLikeConnectedTextRow =
					!c.touchesSideOrWinEdge &&
						c.h >= 6 &&
						c.h <= max(medLineH * 2.15f, 58f) &&
						c.w <= max(bw * 1.18f, ww * 0.92f) &&
						c.miny >= cy0 - max(8f, medLineH * 0.55f) &&
						c.maxy <= cy1 + max(8f, medLineH * 0.55f) &&
						coreRatio >= 0.14f

				// 1. Horizontal divider lines (RPG stat frames).
				if (c.w >= max(medLineH * 2.4f, min(bw * 0.38f, ww * 0.28f)) &&
					c.h <= max(12, (medLineH * 0.45f).roundToInt()) &&
					c.w.toFloat() / max(1, c.h) >= 5.5f &&
					coreRatio < 0.45f
				) {
					return@divider true
				}
				// 2. Vertical divider / frame bar fragments.
				if (c.h >= max(bh * 1.15f, medLineH * 1.75f) &&
					c.w <= max(18, (medLineH * 0.65f).roundToInt()) &&
					c.h.toFloat() / max(1, c.w) >= 3.5f &&
					coreRatio < 0.30f
				) {
					return@divider true
				}
				// 3. Low-core-ratio frame extending outside text core.
				if (!seamGlyph &&
					!looksLikeConnectedTextRow &&
					extendsOutsideCore &&
					coreRatio < 0.28f &&
					(c.w > medLineH * 2.2f || c.h > medLineH * 1.6f || c.area > medLineH * medLineH * 1.4f)
				) {
					return@divider true
				}
				// 4. Surrounding outer bubble frame / spike ring / RPG box frame.
				if ((!looksLikeConnectedTextRow && c.w >= ww * 0.88f) ||
					c.h >= wh * 0.75f ||
					(!looksLikeConnectedTextRow &&
						(c.w > max(bw * 0.82f, medLineH * 3.6f) || c.h > max(bh * 1.22f, medLineH * 1.85f)) &&
						coreRatio < 0.28f) ||
					(c.minx < cx0 - 4 && c.maxx > cx1 + 4 && c.miny < cy0 - 4 && c.maxy > cy1 + 4 && coreRatio < 0.35f)
				) {
					return@divider true
				}
				false
			}

			fun isValidGlyphComponent(c: ComponentInfo): Boolean {
				if (c.area < 2) return false
				if (isStructuralFrameOrDivider(c)) return false
				val isStrongOwnCoreGlyph = c.overlapsCore && c.corePixels * 2 >= c.area
				if (overlapsAnySiblingAbs(
						curWx0 + c.minx,
						curWy0 + c.miny,
						curWx0 + c.maxx + 1,
						curWy0 + c.maxy + 1,
						requireHeavyAreaOverlap = isStrongOwnCoreGlyph,
					)
				) {
					return false
				}
				val coreRatio = c.corePixels.toFloat() / max(1, c.area)
				val coreBoxArea = max(1, bw * bh)
				if (isSingleOrDarkSfx) {
					if (coreRatio >= 0.22f && c.area <= coreBoxArea * 1.45f) return true
					return c.w <= max(bw * 1.35f, ww * 0.90f) && c.h <= max(bh * 2.8f, wh * 0.88f)
				}
				val isContainedInParagraphBand =
					!c.touchesBorder &&
						c.miny >= cy0 - max(8f, medLineH * 0.65f) &&
						c.maxy <= cy1 + max(8f, medLineH * 0.65f) &&
						c.minx <= cx1 + max(72f, medLineH * 4.5f) &&
						c.maxx >= cx0 - max(72f, medLineH * 4.5f) &&
						c.h >= 4 &&
						c.h <= max(medLineH * 2.4f, 58f) &&
						c.w <= max(bw * 1.25f, max(ww * 0.95f, 450f))
				val farOutsideBand =
					!c.overlapsCore &&
						!isContainedInParagraphBand &&
						(c.miny < cy0 - 6 || c.maxy > cy1 + 6)
				if (farOutsideBand && c.area > 18 && !preferLightFirst) return false

				val isStrongCoreOrGlowingWord =
					!c.touchesBorder &&
						(coreRatio >= 0.32f ||
							(preferLightFirst && isContainedInParagraphBand) ||
							(coreRatio >= 0.14f && c.overlapsCore && c.h <= max(medLineH * 2.15f, 58f)))
				val maxAllowedCompH = if (isStrongCoreOrGlowingWord) {
					max(medLineH * 2.5f, 58f)
				} else {
					max(medLineH * 1.65f, 32f)
				}
				val maxAllowedCompW = when {
					isStrongCoreOrGlowingWord -> max(bw * 1.25f, max(ww * 0.95f, 450f))
					coreRatio >= 0.45f -> max(medLineH * 5.2f, 96f)
					else -> max(medLineH * 3.2f, 64f)
				}
				if (c.h > maxAllowedCompH || c.w > maxAllowedCompW) return false
				val maxH = max(medLineH, 18f)
				if (!isSingleOrDarkSfx &&
					c.overlapsCore &&
					coreRatio < 0.38f &&
					(c.minx < cx0 - max(26f, maxH * 1.1f) ||
						c.maxx > cx1 + max(26f, maxH * 1.1f) ||
						c.miny < cy0 - max(16f, maxH * 0.65f) ||
						c.maxy > cy1 + max(16f, maxH * 0.65f))
				) {
					return false
				}
				if (c.area > medLineH * medLineH * 0.75f &&
					coreRatio < 0.16f &&
					!(preferLightFirst && isContainedInParagraphBand)
				) {
					return false
				}
				return true
			}

			fun getAcceptedSpanW(acc: Set<Int>, vList: List<ComponentInfo>): Int {
				var minX = Int.MAX_VALUE
				var maxX = Int.MIN_VALUE
				for (c in vList) {
					if (c.id !in acc) continue
					if (c.minx < minX) minX = c.minx
					if (c.maxx > maxX) maxX = c.maxx
				}
				return if (maxX >= minX) maxX - minX + 1 else 0
			}

			fun runMaskPass(fgMask: ByteArray): MaskPassResult {
				var res = extractComponents(fgMask)
				val shouldSplitDark = !preferLightFirst && jetBlackCoreCount >= 14 && whiteCoreCount >= 14
				val shouldSplitLight = preferLightFirst && whiteCoreCount >= 14
				if (shouldSplitDark || shouldSplitLight) {
					val mergedBorderIds = HashSet<Int>()
					val blobAreaThresh = max(1400f, min(medLineH * medLineH * 2.2f, bw * bh * 0.25f))
					val maxGlyphBlobH = max(medLineH * 2.25f, 64f)
					val maxGlyphBlobW = max(medLineH * 3.8f, bw * 0.50f)
					for (c in res.second) {
						val isOversizedMerge =
							c.touchesBorder ||
								(!isSingleOrDarkSfx && (c.h > maxGlyphBlobH || c.w > maxGlyphBlobW))
						if (isOversizedMerge && c.overlapsCore && c.area >= blobAreaThresh) {
							mergedBorderIds.add(c.id)
						}
					}
					if (mergedBorderIds.isNotEmpty()) {
						val splitMask = fgMask.copyOf()
						var stripped = 0
						val labelsArr = res.first
						val lightStripThresh = if (bgLumInit >= 80f && whiteCoreCount >= 40) {
							min(204f, max(168f, bgLumInit + 58f))
						} else {
							min(200f, max(155f, bgLumInit + 50f))
						}
						for (i in 0 until numPixels) {
							if (labelsArr[i] !in mergedBorderIds) continue
							val lum = PixelColorMath.luminance(pixels[i])
							val shouldStrip = if (shouldSplitDark) lum > 58f else lum < lightStripThresh
							if (shouldStrip) {
								splitMask[i] = 0
								stripped++
							}
						}
						if (stripped > 0) {
							res = extractComponents(splitMask)
						}
						if (shouldSplitLight) {
							val stillHugeIds = HashSet<Int>()
							for (c in res.second) {
								val stillOversized =
									c.touchesBorder ||
										(!isSingleOrDarkSfx && (c.h > maxGlyphBlobH || c.w > maxGlyphBlobW))
								if (stillOversized && c.overlapsCore && c.area >= blobAreaThresh) {
									stillHugeIds.add(c.id)
								}
							}
							if (stillHugeIds.isNotEmpty()) {
								val clippedMask = splitMask.copyOf()
								val splitLabels = res.first
								var clipped = 0
								for (i in 0 until numPixels) {
									if (splitLabels[i] !in stillHugeIds) continue
									val py = i / ww
									val px = i - py * ww
									if (!isNearAnyCore(px, py, 4)) {
										clippedMask[i] = 0
										clipped++
									}
								}
								if (clipped > 0) {
									res = extractComponents(clippedMask)
								}
							}
						}
					}
				}
				val v = res.second.filter(::isValidGlyphComponent)
				val acc = HashSet<Int>()
				for (c in v) {
					val match = c.overlapsCore ||
						(preferLightFirst &&
							!c.touchesBorder &&
							c.minx >= cx0 - max(medLineH * 1.25f, 42f) &&
							c.maxx <= cx1 + max(medLineH * 1.25f, 42f) &&
							c.miny >= cy0 - 4 &&
							c.maxy <= cy1 + 4 &&
							c.h >= 10 &&
							c.h <= max(medLineH * 2.2f, 54f) &&
							c.w <= max(medLineH * 3.5f, min(bw * 1.08f, ww * 0.90f)))
					if (match) acc.add(c.id)
				}
				return MaskPassResult(res.first, res.second, v, acc, getAcceptedSpanW(acc, v))
			}

			var pass = runMaskPass(
				if (preferLightFirst) buildLightForegroundMask(16f, 26f) else buildDarkForegroundMask(20f),
			)
			val minAcceptSpan = bw * if (isSingleLineStat) 0.62f else 0.32f
			if (pass.accepted.isEmpty() || pass.spanW < minAcceptSpan) {
				val gentlePass = runMaskPass(
					if (preferLightFirst) buildLightForegroundMask(10f, 16f) else buildDarkForegroundMask(11f),
				)
				if (gentlePass.spanW > pass.spanW) pass = gentlePass
			}
			if (pass.accepted.isEmpty() || pass.spanW < bw * 0.68f) {
				val canTryAlt = if (preferLightFirst) {
					darkerCount >= brighterCount * 0.25f
				} else {
					bgLumInit < 210f && whiteCoreCount >= 24
				}
				if (canTryAlt) {
					val altPass = runMaskPass(
						if (preferLightFirst) buildDarkForegroundMask(16f) else buildLightForegroundMask(18f, 28f),
					)
					if (altPass.spanW > pass.spanW * 1.18f || (pass.accepted.isEmpty() && altPass.accepted.isNotEmpty())) {
						pass = altPass
						preferLightFirst = !preferLightFirst
					}
				}
			}

			val labels = pass.labels
			val comps = pass.comps
			val valid = pass.valid
			val accepted = pass.accepted

			val maxGrowX = if (isSingleOrDarkSfx) {
				max(64f, max(bh * 2.1f, bw * 0.58f)).roundToInt()
			} else {
				min(54, max(18, (medLineH * 1.55f).roundToInt()))
			}
			val maxRowGrowX = if (isSingleOrDarkSfx) {
				maxGrowX
			} else {
				min(240, max(96, (medLineH * 4.5f).roundToInt()))
			}
			val maxGrowY = if (isSingleOrDarkSfx) {
				max(46f, bh * 1.45f).roundToInt()
			} else {
				min(16, max(8, (medLineH * 0.42f).roundToInt()))
			}
			val allowedMinX = max(1, cx0 - maxGrowX)
			val allowedMaxX = min(ww - 2, cx1 + maxGrowX)
			val allowedRowMinX = max(1, cx0 - maxRowGrowX)
			val allowedRowMaxX = min(ww - 2, cx1 + maxRowGrowX)
			val allowedMinY = max(1, cy0 - maxGrowY)
			val allowedMaxY = min(wh - 2, cy1 + maxGrowY)

			fun hasDarkBubbleWallBetween(c: ComponentInfo, a: ComponentInfo): Boolean {
				val isDarkCardGap = preferLightFirst && bgLumInit <= 75f
				if (!isDarkCardGap && (preferLightFirst || bgLumInit < 140f)) return false
				val gx0 = min(c.maxx, a.maxx) + 1
				val gx1 = max(c.minx, a.minx) - 1
				if (gx1 - gx0 < 2) return false
				val midY = ((max(c.miny, a.miny) + min(c.maxy, a.maxy)) * 0.5f).roundToInt().coerceIn(0, wh - 1)
				val rOff = midY * ww
				var wallRun = 0
				for (x in gx0..gx1) {
					val lum = PixelColorMath.luminance(pixels[rOff + x])
					val isWallPx = if (isDarkCardGap) lum >= 185f else lum <= 95f
					if (isWallPx) {
						wallRun++
						if (wallRun >= 2) return true
					} else {
						wallRun = 0
					}
				}
				return false
			}

			fun isHalftoneScreentoneDot(cand: ComponentInfo): Boolean {
				if (cand.overlapsCore || cand.area > 360 || cand.w > 22 || cand.h > 22) return false
				val isTinyDot = cand.area <= 18 && cand.w <= 6 && cand.h <= 6
				val aspect = max(cand.w, cand.h).toFloat() / max(1, min(cand.w, cand.h))
				val fillRatio = cand.area.toFloat() / max(1, cand.w * cand.h)
				if (!isTinyDot && (aspect > 1.8f || fillRatio < 0.48f)) return false
				val cx = (cand.minx + cand.maxx) * 0.5f
				val cy = (cand.miny + cand.maxy) * 0.5f
				val searchR = if (isTinyDot) 22f else max(28f, max(cand.w, cand.h) * 3.8f)
				var similarNeighbors = 0
				var verticalOrDiagNeighbors = 0
				for (other in valid) {
					if (other.id == cand.id) continue
					if (other.area > 360 || other.w > 22 || other.h > 22) continue
					if (!isTinyDot) {
						if (abs(other.w - cand.w) > 6 || abs(other.h - cand.h) > 6) continue
					} else if (other.area > 18 || other.w > 6 || other.h > 6) {
						continue
					}
					val ox = (other.minx + other.maxx) * 0.5f
					val oy = (other.miny + other.maxy) * 0.5f
					if (abs(ox - cx) <= searchR && abs(oy - cy) <= searchR) {
						similarNeighbors++
						if (abs(oy - cy) >= max(4f, cand.h * 0.45f)) {
							verticalOrDiagNeighbors++
						}
						if (similarNeighbors >= 5 && (isTinyDot || verticalOrDiagNeighbors >= 2)) return true
					}
				}
				return false
			}

			var changed = true
			var growIter = 0
			val maxGrowIter = if (isSingleOrDarkSfx) 6 else 8
			while (changed && growIter < maxGrowIter) {
				changed = false
				growIter++
				val accComps = valid.filter { it.id in accepted }
				if (accComps.isEmpty()) break

				val heightInts = accComps.filter { it.h >= 6 }.map { it.h }
				val maxH = (heightInts.maxOrNull()?.toFloat()) ?: max(20f, bh * 0.5f)
				val medH = if (heightInts.isNotEmpty()) {
					val arr = heightInts.toIntArray()
					PixelColorMath.medianInt(arr, arr.size).toFloat()
				} else {
					20f
				}
				val padX = min(
					maxGrowX.toFloat(),
					if (isSingleOrDarkSfx) max(18f, medH * 1.35f) else max(10f, (medH * 0.68f).roundToInt().toFloat()),
				)
				val padY = min(
					maxGrowY.toFloat(),
					if (isSingleOrDarkSfx) max(12f, medH * 0.85f) else max(6f, (medH * 0.35f).roundToInt().toFloat()),
				)
				val maxCompH = if (isSingleOrDarkSfx) {
					max(medH * 3.8f, max(maxH * 3.2f, bh * 2.8f))
				} else {
					max(medH * 2.4f, max(medLineH * 2.4f, 56f))
				}
				val maxCompW = when {
					isSingleOrDarkSfx -> max(medH * 6.0f, bw * 1.2f)
					preferLightFirst -> max(bw * 1.2f, max(ww * 0.95f, 450f))
					else -> max(medH * 3.2f, max(medLineH * 3.2f, 72f))
				}

				for (c in valid) {
					if (c.id in accepted) continue
					if (c.minx < allowedRowMinX || c.maxx > allowedRowMaxX || c.miny < allowedMinY || c.maxy > allowedMaxY) {
						continue
					}
					if ((c.area < 6 && !c.overlapsCore) || isHalftoneScreentoneDot(c)) continue
					if (c.h > maxCompH || c.w > maxCompW) continue
					val withinStdX = c.minx >= allowedMinX && c.maxx <= allowedMaxX

					var nearExistingGlyph = false
					val rowReachX = min(52f, max(padX, (medLineH * 1.25f).roundToInt().toFloat()))
					for (k in accComps.indices) {
						val a = accComps[k]
						val gapX = max(0, max(c.minx - a.maxx, a.minx - c.maxx))
						val vOv = min(c.maxy, a.maxy) - max(c.miny, a.miny)
						val gapY = max(0, max(c.miny - a.maxy, a.miny - c.maxy))
						val hOv = min(c.maxx, a.maxx) - max(c.minx, a.minx)
						val sameHeightScale = max(c.h, a.h).toFloat() / max(1, min(c.h, a.h)) <= 1.6f
						val rowEdgePunct =
							!isSingleOrDarkSfx &&
								vOv > 0 &&
								gapX <= min(18f, padX) &&
								c.h <= medH * 0.65f &&
								c.w <= medH * 0.65f &&
								!hasDarkBubbleWallBetween(c, a)
						val strongRowMatch =
							!isSingleOrDarkSfx &&
								gapX <= rowReachX &&
								vOv >= min(c.h, a.h) * 0.42f &&
								sameHeightScale &&
								c.h <= medLineH * 1.65f &&
								!hasDarkBubbleWallBetween(c, a)
						if (!withinStdX && !strongRowMatch && !rowEdgePunct) continue
						val outsideCoreX = c.maxx < cx0 - 4 || c.minx > cx1 + 4
						val exteriorSizeOk =
							c.overlapsCore ||
								isSingleOrDarkSfx ||
								if (outsideCoreX) {
									c.h <= medH * 1.42f && c.w <= medH * 1.55f
								} else {
									c.h <= medH * 1.48f && c.w <= medH * 2.1f
								}
						if (exteriorSizeOk &&
							(strongRowMatch ||
								rowEdgePunct ||
								(gapX <= padX && (vOv >= min(c.h, a.h) * 0.2f || gapY <= padY * 0.65f)) ||
								(gapY <= padY && hOv >= 0))
						) {
							nearExistingGlyph = true
							break
						}
					}
					if (nearExistingGlyph) {
						accepted.add(c.id)
						changed = true
					}
				}
			}

			var expandLeft = 0
			var expandRight = 0
			var expandTop = 0
			var expandBottom = 0
			val lineSpanLimit = max(medLineH, 22f)
			for (c in comps) {
				if (!c.touchesBorder) continue
				val vOverlapCore = min(c.maxy, cy1 + 8) - max(c.miny, cy0 - 8)
				if (c.h <= lineSpanLimit * 2.4f &&
					c.w <= min(bw * 1.15f, lineSpanLimit * 5f) &&
					vOverlapCore >= min(c.h.toFloat(), lineSpanLimit) * 0.45f
				) {
					if (c.minx <= 2 && curWx0 > 0 && cx0 - c.maxx <= lineSpanLimit * 1.25f) {
						expandLeft = max(expandLeft, (lineSpanLimit * 1.4f + 24f).roundToInt())
					}
					if (c.maxx >= ww - 3 && curWx1 < fullWidth && c.minx - cx1 <= lineSpanLimit * 1.25f) {
						expandRight = max(expandRight, (lineSpanLimit * 1.4f + 24f).roundToInt())
					}
				}
				val hOverlapCore = min(c.maxx, cx1 + 12) - max(c.minx, cx0 - 12)
				if (c.w <= min(bw * 1.2f, lineSpanLimit * 6f) &&
					c.h <= lineSpanLimit * 2.2f &&
					hOverlapCore >= min(c.w, bw) * 0.45f
				) {
					if (c.miny <= 2 && curWy0 > 0 && cy0 - c.maxy <= lineSpanLimit * 1.1f) {
						expandTop = max(expandTop, (lineSpanLimit * 1.2f + 24f).roundToInt())
					}
					if (c.maxy >= wh - 3 && curWy1 < fullHeight && c.miny - cy1 <= lineSpanLimit * 1.1f) {
						expandBottom = max(expandBottom, (lineSpanLimit * 1.2f + 24f).roundToInt())
					}
				}
			}

			return WindowAnalysisResult(
				pixels = pixels,
				ww = ww,
				wh = wh,
				numPixels = numPixels,
				cx0 = cx0,
				cy0 = cy0,
				cx1 = cx1,
				cy1 = cy1,
				bgR = bgR,
				bgG = bgG,
				bgB = bgB,
				labels = labels,
				comps = comps,
				valid = valid,
				accepted = accepted,
				medLineH = medLineH,
				preferLightFirst = preferLightFirst,
				isBlueOrMidPanel = isBlueOrMidPanel,
				isSingleOrDarkSfx = isSingleOrDarkSfx,
				whiteCoreCount = whiteCoreCount,
				expandLeft = expandLeft,
				expandRight = expandRight,
				expandTop = expandTop,
				expandBottom = expandBottom,
				isStructuralFrameOrDivider = isStructuralFrameOrDivider,
			)
		}

		var winResult = analyzeWindow(wx0, wy0, wx1, wy1) ?: return null
		if (winResult.expandLeft > 0 ||
			winResult.expandRight > 0 ||
			winResult.expandTop > 0 ||
			winResult.expandBottom > 0
		) {
			val newWx0 = max(0, wx0 - winResult.expandLeft)
			val newWx1 = min(fullWidth, wx1 + winResult.expandRight)
			val newWy0 = max(0, wy0 - winResult.expandTop)
			val newWy1 = min(fullHeight, wy1 + winResult.expandBottom)
			val expanded = analyzeWindow(newWx0, newWy0, newWx1, newWy1)
			if (expanded != null && expanded.accepted.isNotEmpty()) {
				wx0 = newWx0
				wx1 = newWx1
				wy0 = newWy0
				wy1 = newWy1
				winResult = expanded
			}
		}

		if (winResult.accepted.isEmpty()) return null

		val pixels = winResult.pixels
		val ww = winResult.ww
		val wh = winResult.wh
		val numPixels = winResult.numPixels
		val cx0 = winResult.cx0
		val cy0 = winResult.cy0
		val cx1 = winResult.cx1
		val cy1 = winResult.cy1
		val bgR = winResult.bgR
		val bgG = winResult.bgG
		val bgB = winResult.bgB
		val labels = winResult.labels
		val comps = winResult.comps
		val valid = winResult.valid
		val accepted = winResult.accepted
		val medLineH = winResult.medLineH
		val preferLightFirst = winResult.preferLightFirst
		val isSingleOrDarkSfx = winResult.isSingleOrDarkSfx
		val whiteCoreCount = winResult.whiteCoreCount
		val isStructuralFrameOrDivider = winResult.isStructuralFrameOrDivider
		val bgLumInit = PixelColorMath.luminance(bgR, bgG, bgB)

		val borderIds = HashSet<Int>()
		for (c in comps) {
			if (c.id !in accepted && isStructuralFrameOrDivider(c)) {
				val keep = if (!preferLightFirst) {
					true
				} else {
					val fillRatio = c.area.toFloat() / max(1, c.w * c.h)
					c.w <= 16 || c.h <= 16 || fillRatio < 0.22f ||
						(bgLumInit <= 75f && (c.touchesSideOrWinEdge || !c.overlapsCore))
				}
				if (keep) borderIds.add(c.id)
			}
		}

		val borderMask = ByteArray(numPixels)
		val rawGlyphMask = ByteArray(numPixels)
		for (i in 0 until numPixels) {
			val lbl = labels[i]
			if (lbl != 0) {
				if (lbl in accepted) {
					rawGlyphMask[i] = 1
					continue
				}
				if (lbl in borderIds) {
					if (!preferLightFirst || bgLumInit > 75f) {
						borderMask[i] = 1
						continue
					}
					val p = pixels[i]
					val pr = PixelColorMath.red(p)
					val pg = PixelColorMath.green(p)
					val pb = PixelColorMath.blue(p)
					val lum = PixelColorMath.luminance(pr, pg, pb)
					val diff = PixelColorMath.maxChannelDiff(pr, pg, pb, bgR, bgG, bgB)
					val ly = i / ww
					val lx = i - ly * ww
					val outsideCore = lx < cx0 - 6 || lx > cx1 + 6 || ly < cy0 - 6 || ly > cy1 + 6
					if (lum >= 185f && diff >= 110 && outsideCore) {
						borderMask[i] = 1
					}
					continue
				}
			}
			if (bgLumInit >= 195f || (preferLightFirst && bgLumInit <= 75f)) {
				val ly = i / ww
				val lx = i - ly * ww
				if (lx < cx0 - 6 || lx > cx1 + 6 || ly < cy0 - 6 || ly > cy1 + 6) {
					val p = pixels[i]
					val pr = PixelColorMath.red(p)
					val pg = PixelColorMath.green(p)
					val pb = PixelColorMath.blue(p)
					val lum = PixelColorMath.luminance(pr, pg, pb)
					val diff = PixelColorMath.maxChannelDiff(pr, pg, pb, bgR, bgG, bgB)
					val isWall = if (bgLumInit >= 195f) {
						lum < 105f && diff >= 85
					} else {
						lum >= 185f && diff >= 110
					}
					if (isWall) {
						borderMask[i] = 1
					}
				}
			}
		}

		var accComps = valid.filter { it.id in accepted }
		var gMinX = ww
		var gMaxX = 0
		var gMinY = wh
		var gMaxY = 0
		for (c in accComps) {
			if (c.minx < gMinX) gMinX = c.minx
			if (c.maxx > gMaxX) gMaxX = c.maxx
			if (c.miny < gMinY) gMinY = c.miny
			if (c.maxy > gMaxY) gMaxY = c.maxy
		}

		val glyphHInts = accComps.filter { it.h >= 7 }.map { it.h }.toIntArray()
		val glyphWInts = accComps.filter { it.h >= 7 }.map { it.w }.toIntArray()
		val rawMedGlyphH = if (glyphHInts.isNotEmpty()) {
			PixelColorMath.medianInt(glyphHInts, glyphHInts.size).toFloat()
		} else {
			max(16f, fullWidth * 0.025f)
		}
		val medGlyphH = if (isSingleLineStat) max(rawMedGlyphH, bh * 0.82f) else rawMedGlyphH
		val medGlyphW = if (glyphWInts.isNotEmpty()) {
			PixelColorMath.medianInt(glyphWInts, glyphWInts.size).toFloat()
		} else {
			max(12f, medGlyphH * 0.65f)
		}

		val midLocalYInit = ((gMinY + gMaxY) * 0.5f).roundToInt()
		val midLocalXInit = ((gMinX + gMaxX) * 0.5f).roundToInt()
		val bandHalfY = max(10, (max((gMaxY - gMinY).toFloat(), medGlyphH * 1.8f) * 0.24f).roundToInt())
		val bandY0 = (midLocalYInit - bandHalfY).coerceIn(0, wh - 1)
		val bandY1 = (midLocalYInit + bandHalfY).coerceIn(0, wh - 1)

		fun isStructuralWallAt(lx: Int, ly: Int): Boolean = borderMask[ly * ww + lx] == 1.toByte()

		val isDarkCard = preferLightFirst && bgLumInit <= 75f
		val wallPadX = if (isDarkCard) {
			(medGlyphH * 0.34f).roundToInt().coerceIn(8, 16)
		} else {
			(medGlyphH * 0.16f).roundToInt().coerceIn(3, 6)
		}
		val wallPadY = if (isDarkCard) {
			(medGlyphH * 0.44f).roundToInt().coerceIn(12, 18)
		} else {
			(medGlyphH * 0.20f).roundToInt().coerceIn(4, 8)
		}

		var safeLocalLeft = 0
		var hitLeftWall = false
		for (x in gMinX.coerceIn(0, ww - 1) downTo 0) {
			var hit = false
			var y = bandY0
			while (y <= bandY1) {
				if (isStructuralWallAt(x, y)) {
					hit = true
					break
				}
				y += 2
			}
			if (hit) {
				safeLocalLeft = x + wallPadX
				hitLeftWall = true
				break
			}
		}

		var safeLocalRight = ww
		var hitRightWall = false
		for (x in gMaxX.coerceIn(0, ww - 1) until ww) {
			var hit = false
			var y = bandY0
			while (y <= bandY1) {
				if (isStructuralWallAt(x, y)) {
					hit = true
					break
				}
				y += 2
			}
			if (hit) {
				safeLocalRight = x - wallPadX
				hitRightWall = true
				break
			}
		}

		val bandHalfX = max(8, ((gMaxX - gMinX) * 0.26f).roundToInt())
		val bandX0 = (midLocalXInit - bandHalfX).coerceIn(0, ww - 1)
		val bandX1 = (midLocalXInit + bandHalfX).coerceIn(0, ww - 1)

		var safeLocalTop = 0
		var hitTopWall = false
		for (y in gMinY.coerceIn(0, wh - 1) downTo 0) {
			var hit = false
			var x = bandX0
			while (x <= bandX1) {
				if (isStructuralWallAt(x, y)) {
					hit = true
					break
				}
				x += 3
			}
			if (hit) {
				safeLocalTop = y + wallPadY
				hitTopWall = true
				break
			}
		}

		var safeLocalBottom = wh
		var hitBottomWall = false
		for (y in gMaxY.coerceIn(0, wh - 1) until wh) {
			var hit = false
			var x = bandX0
			while (x <= bandX1) {
				if (isStructuralWallAt(x, y)) {
					hit = true
					break
				}
				x += 3
			}
			if (hit) {
				safeLocalBottom = y - wallPadY
				hitBottomWall = true
				break
			}
		}

		// Clamp walls away from neighboring sibling bubbles.
		val gAbsY0 = wy0 + gMinY
		val gAbsY1 = wy0 + gMaxY
		val gAbsMidX = wx0 + (gMinX + gMaxX) * 0.5f
		for (sib in absSiblings) {
			val vOv = min(gAbsY1, sib.y1) - max(gAbsY0, sib.y0)
			if (vOv < min(gMaxY - gMinY + 1, sib.y1 - sib.y0) * 0.25f) continue
			val sibMidX = (sib.x0 + sib.x1) * 0.5f
			if (sibMidX < gAbsMidX && sib.x1 <= wx0 + gMinX + 8) {
				safeLocalLeft = max(safeLocalLeft, sib.x1 - wx0 + 8)
			} else if (sibMidX > gAbsMidX && sib.x0 >= wx0 + gMaxX - 8) {
				safeLocalRight = min(safeLocalRight, sib.x0 - wx0 - 8)
			}
		}
		safeLocalLeft = max(safeLocalLeft, 4 - wx0)
		safeLocalRight = min(safeLocalRight, fullWidth - 4 - wx0)

		// Capture orphan glyph fragments trapped inside closed bright speech bubble cavity.
		val cavWallHits =
			(if (hitLeftWall) 1 else 0) +
				(if (hitRightWall) 1 else 0) +
				(if (hitTopWall) 1 else 0) +
				(if (hitBottomWall) 1 else 0)
		if (bgLumInit >= 195f && hitLeftWall && hitRightWall && cavWallHits >= 3) {
			val cavLeft = max(2, max(safeLocalLeft, cx0 - max(28, (medLineH * 1.25f).roundToInt())))
			val cavRight = min(ww - 3, min(safeLocalRight, cx1 + max(28, (medLineH * 1.25f).roundToInt())))
			val cavTop = max(
				2,
				max(
					cy0 - max(20, (medLineH * 0.85f).roundToInt()),
					when {
						hitTopWall -> safeLocalTop - wallPadY + 2
						wy0 == 0 -> 0
						else -> gMinY - (medGlyphH * 2.2f).roundToInt()
					},
				),
			)
			val cavBottom = min(
				wh - 3,
				min(
					cy1 + max(24, (medLineH * 1.05f).roundToInt()),
					when {
						hitBottomWall -> max(safeLocalBottom + wallPadY - 2, gMaxY + (medGlyphH * 1.4f).roundToInt())
						wy1 == fullHeight -> wh - 1
						else -> gMaxY + (medGlyphH * 1.4f).roundToInt()
					},
				),
			)
			var cavityAdded = false
			for (c in comps) {
				if (c.id in accepted || c.id in borderIds) continue
				if (c.area < 5 || c.touchesSideOrWinEdge || isStructuralFrameOrDivider(c)) continue
				if (!c.overlapsCore && c.area <= 360 && c.w <= 22 && c.h <= 22) continue
				if (overlapsAnySiblingAbs(wx0 + c.minx, wy0 + c.miny, wx0 + c.maxx + 1, wy0 + c.maxy + 1)) {
					continue
				}
				if (c.miny < cavTop || c.maxy > cavBottom) continue
				if (c.minx > gMaxX + medGlyphH * 1.6f || c.maxx < gMinX - medGlyphH * 1.6f) continue
				if (c.maxy > safeLocalBottom + wallPadY - 2) {
					val cMidX = ((c.minx + c.maxx) * 0.5f).roundToInt().coerceIn(0, ww - 1)
					var crossedWallVert = false
					for (y in min(gMaxY, c.miny)..min(wh - 1, c.maxy + 2)) {
						if (isStructuralWallAt(cMidX, y)) {
							crossedWallVert = true
							break
						}
					}
					if (crossedWallVert) continue
				}
				if (c.h > max(medGlyphH * 1.75f, medLineH * 1.75f) ||
					c.w > max(medGlyphH * 3.8f, max(medLineH * 3.8f, 96f))
				) {
					continue
				}

				val cMidY = ((c.miny + c.maxy) * 0.5f).roundToInt().coerceIn(0, wh - 1)
				val scanOriginX = midLocalXInit.coerceIn(0, ww - 1)
				var rowLeftWall = 0
				var rowHitLeft = false
				for (x in scanOriginX downTo 0) {
					if (isStructuralWallAt(x, cMidY)) {
						rowLeftWall = x
						rowHitLeft = true
						break
					}
				}
				var rowRightWall = ww - 1
				var rowHitRight = false
				for (x in scanOriginX until ww) {
					if (isStructuralWallAt(x, cMidY)) {
						rowRightWall = x
						rowHitRight = true
						break
					}
				}
				val fitsRowWalls = if (rowHitLeft && rowHitRight && rowRightWall - rowLeftWall < ww - 6) {
					c.minx >= rowLeftWall + 2 && c.maxx <= rowRightWall - 2
				} else {
					c.minx >= cavLeft && c.maxx <= cavRight
				}
				if (fitsRowWalls) {
					accepted.add(c.id)
					cavityAdded = true
					if (c.minx < gMinX) gMinX = c.minx
					if (c.maxx > gMaxX) gMaxX = c.maxx
					if (c.miny < gMinY) gMinY = c.miny
					if (c.maxy > gMaxY) gMaxY = c.maxy
				}
			}
			if (cavityAdded) {
				accComps = comps.filter { it.id in accepted }
				for (i in 0 until numPixels) {
					if (labels[i] in accepted) rawGlyphMask[i] = 1
				}
			}
		}

		val midLocalY = ((gMinY + gMaxY) * 0.5f).roundToInt()
		val midLocalX = ((gMinX + gMaxX) * 0.5f).roundToInt()

		// Fill enclosed glyph interiors via Jordan outer flood fill.
		val fillRes = styleExtractor.fillEnclosedGlyphInteriors(
			pixels = pixels,
			ww = ww,
			wh = wh,
			rawMask = rawGlyphMask,
			borderMask = borderMask,
			gMinX = gMinX,
			gMinY = gMinY,
			gMaxX = gMaxX,
			gMaxY = gMaxY,
			bgR = bgR,
			bgG = bgG,
			bgB = bgB,
		)

		// White & dark halo absorption (`preDilateMask`).
		val wallHitsEarly =
			(if (hitLeftWall) 1 else 0) +
				(if (hitRightWall) 1 else 0) +
				(if (hitTopWall) 1 else 0) +
				(if (hitBottomWall) 1 else 0)

		var preDilateMask = fillRes.combinedMask
		if (!preferLightFirst &&
			(bgLumInit < 236f || (wallHitsEarly < 3 && bgLumInit < 248f && whiteCoreCount >= 12))
		) {
			val haloSteps = if (isSingleOrDarkSfx || bgLumInit < 190f) 3 else 2
			val y0H = max(1, gMinY - 5)
			val y1H = min(wh - 2, gMaxY + 5)
			val x0H = max(1, gMinX - 6)
			val x1H = min(ww - 2, gMaxX + 6)
			for (step in 0 until haloSteps) {
				var haloAdded = 0
				val nextMask = preDilateMask.copyOf()
				for (y in y0H..y1H) {
					val rOff = y * ww
					for (x in x0H..x1H) {
						val idx = rOff + x
						if (preDilateMask[idx] != 0.toByte() || borderMask[idx] != 0.toByte()) continue
						if (preDilateMask[idx - 1] != 0.toByte() ||
							preDilateMask[idx + 1] != 0.toByte() ||
							preDilateMask[idx - ww] != 0.toByte() ||
							preDilateMask[idx + ww] != 0.toByte()
						) {
							val p = pixels[idx]
							val pr = PixelColorMath.red(p)
							val pg = PixelColorMath.green(p)
							val pb = PixelColorMath.blue(p)
							val pLum = PixelColorMath.luminance(pr, pg, pb)
							val dGlowBg = max(pr - bgR, max(pg - bgG, pb - bgB)).toFloat()
							val lumPass = if (bgLumInit >= 205f) {
								pLum >= 232f && pLum > bgLumInit + 16f
							} else {
								pLum >= 216f && pLum > bgLumInit + 22f
							}
							if (lumPass || (bgLumInit < 140f && (pLum > bgLumInit + 22f || dGlowBg > 28f))) {
								nextMask[idx] = 1
								haloAdded++
							}
						}
					}
				}
				preDilateMask = nextMask
				if (haloAdded == 0) break
			}
		} else if (preferLightFirst) {
			val outerBgIndices = IntArray(numPixels)
			var outerBgCount = 0
			var oy = max(0, gMinY - 16)
			val oyEnd = min(wh - 1, gMaxY + 16)
			while (oy <= oyEnd) {
				val inVertBand = oy < gMinY - 5 || oy > gMaxY + 5
				val rOff = oy * ww
				var ox = max(0, gMinX - 22)
				val oxEnd = min(ww - 1, gMaxX + 22)
				while (ox <= oxEnd) {
					if (inVertBand || ox < gMinX - 5 || ox > gMaxX + 5) {
						val idx = rOff + ox
						if (preDilateMask[idx] == 0.toByte() && borderMask[idx] == 0.toByte()) {
							outerBgIndices[outerBgCount++] = idx
						}
					}
					ox += 2
				}
				oy += 2
			}

			val outerBgLum: Float
			val outerBgR: Float
			val outerBgB: Float
			if (outerBgCount >= 10) {
				val lums = FloatArray(outerBgCount) { PixelColorMath.luminance(pixels[outerBgIndices[it]]) }
				val rs = IntArray(outerBgCount) { PixelColorMath.red(pixels[outerBgIndices[it]]) }
				val bs = IntArray(outerBgCount) { PixelColorMath.blue(pixels[outerBgIndices[it]]) }
				outerBgLum = PixelColorMath.medianFloat(lums, outerBgCount)
				outerBgR = PixelColorMath.medianInt(rs, outerBgCount).toFloat()
				outerBgB = PixelColorMath.medianInt(bs, outerBgCount).toFloat()
			} else {
				outerBgLum = bgLumInit
				outerBgR = bgR.toFloat()
				outerBgB = bgB.toFloat()
			}
			val effOuterBg = max(bgLumInit, outerBgLum)

			if (effOuterBg >= 24f) {
				val darkHaloMaxSteps = if (effOuterBg >= 100f) 9 else 7
				val isBlueBg = bgB > bgR + 14 || outerBgB > outerBgR + 10f
				val haloY0 = max(1, gMinY - 10)
				val haloY1 = min(wh - 2, gMaxY + 10)
				val rowMinXs = IntArray(wh)
				val rowMaxXs = IntArray(wh)
				for (y in haloY0..haloY1) {
					val rOff = y * ww
					var rMin = gMinX
					var rMax = gMaxX
					var foundRowGlyph = false
					for (x in max(0, gMinX - 4)..min(ww - 1, gMaxX + 4)) {
						if (preDilateMask[rOff + x] != 0.toByte()) {
							if (!foundRowGlyph) {
								rMin = x
								foundRowGlyph = true
							}
							rMax = x
						}
					}
					rowMinXs[y] = rMin
					rowMaxXs[y] = rMax
				}

				val rowLumScratch = FloatArray(128)
				for (step in 0 until darkHaloMaxSteps) {
					var darkHaloAdded = 0
					val nextMask = preDilateMask.copyOf()
					for (y in haloY0..haloY1) {
						var rowLumCount = 0
						val ry0 = max(0, y - 2)
						val ry1 = min(wh - 1, y + 2)
						val ancL = rowMinXs[y]
						val ancR = rowMaxXs[y]
						for (ry in ry0..ry1) {
							val rrOff = ry * ww
							var rx = max(0, ancL - 22)
							val rxLEnd = max(0, ancL - 5)
							while (rx <= rxLEnd && rowLumCount < rowLumScratch.size) {
								val rIdx = rrOff + rx
								if (preDilateMask[rIdx] == 0.toByte() && borderMask[rIdx] == 0.toByte()) {
									rowLumScratch[rowLumCount++] = PixelColorMath.luminance(pixels[rIdx])
								}
								rx += 2
							}
							rx = min(ww - 1, ancR + 5)
							val rxREnd = min(ww - 1, ancR + 22)
							while (rx <= rxREnd && rowLumCount < rowLumScratch.size) {
								val rIdx = rrOff + rx
								if (preDilateMask[rIdx] == 0.toByte() && borderMask[rIdx] == 0.toByte()) {
									rowLumScratch[rowLumCount++] = PixelColorMath.luminance(pixels[rIdx])
								}
								rx += 2
							}
						}
						val rowBgLum = if (rowLumCount >= 6) {
							max(effOuterBg * 0.75f, PixelColorMath.medianFloat(rowLumScratch, rowLumCount))
						} else {
							effOuterBg
						}
						val rOff = y * ww
						val xStart = max(1, gMinX - 14)
						val xEnd = min(ww - 2, gMaxX + 14)
						for (x in xStart..xEnd) {
							val idx = rOff + x
							if (preDilateMask[idx] != 0.toByte() || borderMask[idx] != 0.toByte()) continue
							if (preDilateMask[idx - 1] != 0.toByte() ||
								preDilateMask[idx + 1] != 0.toByte() ||
								preDilateMask[idx - ww] != 0.toByte() ||
								preDilateMask[idx + ww] != 0.toByte()
							) {
								val p = pixels[idx]
								val pr = PixelColorMath.red(p)
								val pg = PixelColorMath.green(p)
								val pb = PixelColorMath.blue(p)
								val pLum = PixelColorMath.luminance(pr, pg, pb)
								val maxC = max(pr, max(pg, pb))
								val minC = min(pr, min(pg, pb))
								val sat = (maxC - minC).toFloat() / max(42, maxC)
								val blueDarkHalo = isBlueBg && (pb - pr < 12) && pLum <= rowBgLum + 8f && sat < 0.34f
								val coloredOutlineHalo = step <= 3 &&
									rowBgLum >= 95f &&
									pLum <= min(118f, rowBgLum - 22f) &&
									sat < 0.70f
								val darkSceneHalo = (coloredOutlineHalo || sat < 0.38f) &&
									((pLum <= min(max(122f, rowBgLum - 22f), rowBgLum - 4f) && rowBgLum >= 26f) ||
										(pLum <= 26f && sat < 0.28f && rowBgLum >= 22f && pLum <= rowBgLum - 3f))
								if (blueDarkHalo || darkSceneHalo) {
									nextMask[idx] = 1
									darkHaloAdded++
								}
							}
						}
					}
					preDilateMask = nextMask
					if (darkHaloAdded == 0) break
				}
			}
		}

		// Adaptive 3x3 dilation clipped by borderMask.
		val isOpenCleanDarkInk =
			!preferLightFirst && (wallHitsEarly < 3 || bgLumInit >= 140f || whiteCoreCount >= 12)
		val isOpenLightOutlineScene = preferLightFirst && wallHitsEarly < 3 && bgLumInit < 232f
		val enclosedCount = fillRes.enclosedCount
		val dilateSteps = when {
			isOpenCleanDarkInk -> if (medGlyphH >= 36f) 2 else 1
			isOpenLightOutlineScene ->
				((medGlyphH * 0.09f).roundToInt() + if (enclosedCount > 20) 2 else 1).coerceIn(3, 5)
			else ->
				((medGlyphH * 0.08f).roundToInt() + if (enclosedCount > 20) 2 else 1).coerceIn(2, 4)
		}

		var mask = preDilateMask
		var scratchMask = ByteArray(numPixels)
		for (d in 0 until dilateSteps) {
			dilate3x3(mask, scratchMask, ww, wh)
			val tmp = mask
			mask = scratchMask
			scratchMask = tmp
		}

		val eraseMask = ByteArray(numPixels)
		var egMinX = ww
		var egMaxX = 0
		var egMinY = wh
		var egMaxY = 0
		var eraseCount = 0
		for (y in 0 until wh) {
			val rOff = y * ww
			for (x in 0 until ww) {
				val idx = rOff + x
				if (mask[idx] != 0.toByte() && borderMask[idx] == 0.toByte()) {
					eraseMask[idx] = 1
					eraseCount++
					if (x < egMinX) egMinX = x
					if (x > egMaxX) egMaxX = x
					if (y < egMinY) egMinY = y
					if (y > egMaxY) egMaxY = y
				}
			}
		}
		if (eraseCount == 0) return null

		// Sample clean 2px outer ring excluding structural border/background outliers (>60).
		val ringIndices = IntArray(numPixels)
		var ringCount = 0
		for (y in max(2, egMinY - 3)..min(wh - 3, egMaxY + 3)) {
			val rOff = y * ww
			for (x in max(2, egMinX - 3)..min(ww - 3, egMaxX + 3)) {
				val idx = rOff + x
				if (eraseMask[idx] != 0.toByte() || borderMask[idx] != 0.toByte()) continue
				if (eraseMask[idx - 2] != 0.toByte() ||
					eraseMask[idx + 2] != 0.toByte() ||
					eraseMask[idx - 2 * ww] != 0.toByte() ||
					eraseMask[idx + 2 * ww] != 0.toByte()
				) {
					val p = pixels[idx]
					if (PixelColorMath.maxChannelDiff(p, bgR, bgG, bgB) <= 60) {
						ringIndices[ringCount++] = idx
					}
				}
			}
		}

		val ringMedR: Int
		val ringMedG: Int
		val ringMedB: Int
		var ringStd = 0f
		if (ringCount > 10) {
			val medRgb = PixelColorMath.medianRgbFromIndices(pixels, ringIndices, ringCount)
			ringMedR = medRgb[0]
			ringMedG = medRgb[1]
			ringMedB = medRgb[2]
			var varSum = 0.0
			for (i in 0 until ringCount) {
				val p = pixels[ringIndices[i]]
				val dr = PixelColorMath.red(p) - ringMedR
				val dg = PixelColorMath.green(p) - ringMedG
				val db = PixelColorMath.blue(p) - ringMedB
				varSum += (dr * dr + dg * dg + db * db) / 3.0
			}
			ringStd = sqrt(varSum / ringCount).toFloat()
		} else {
			ringMedR = bgR
			ringMedG = bgG
			ringMedB = bgB
		}
		val ringMedLum = PixelColorMath.luminance(ringMedR, ringMedG, ringMedB)

		// Verify whether non-erased interior pixels contain scene texture (>= 14%).
		var interiorNonErase = 0
		var interiorSceneDiff = 0
		val scY0 = max(0, egMinY)
		val scY1 = min(wh - 1, egMaxY)
		val scX0 = max(0, egMinX)
		val scX1 = min(ww - 1, egMaxX)
		var scY = scY0
		while (scY <= scY1) {
			val rOff = scY * ww
			var scX = scX0
			while (scX <= scX1) {
				val idx = rOff + scX
				if (eraseMask[idx] == 0.toByte() && borderMask[idx] == 0.toByte()) {
					interiorNonErase++
					if (PixelColorMath.maxChannelDiff(pixels[idx], ringMedR, ringMedG, ringMedB) > 36) {
						interiorSceneDiff++
					}
				}
				scX += 2
			}
			scY += 2
		}
		val hasTexturedSceneInterior =
			!preferLightFirst && interiorNonErase >= 16 && interiorSceneDiff.toFloat() / interiorNonErase >= 0.14f
		if (hasTexturedSceneInterior && ringStd < 16f) {
			ringStd = 16f
		}

		val wallHits =
			(if (hitLeftWall) 1 else 0) +
				(if (hitRightWall) 1 else 0) +
				(if (hitTopWall) 1 else 0) +
				(if (hitBottomWall) 1 else 0)
		val hasActualPhysicalWalls =
			!hasTexturedSceneInterior && (wallHits >= 3 || (hitLeftWall && hitRightWall && ringStd < 11f))
		val hasClosedBubbleWalls =
			!hasTexturedSceneInterior &&
				(hasActualPhysicalWalls || (bgLumInit >= 235f && ringMedLum >= 238f && ringStd < 4.5f))

		val compCenters = accComps
			.filter { it.h >= medGlyphH * 0.55f }
			.map { c -> CompCenter((c.minx + c.maxx) * 0.5f, (c.miny + c.maxy) * 0.5f, c.w, c.h) }

		val styleRes = styleExtractor.extractStyleFingerprint(
			StyleExtractionInput(
				pixels = pixels,
				ww = ww,
				wh = wh,
				rawGlyphMask = rawGlyphMask,
				enclosedInteriorMask = fillRes.enclosedInteriorMask,
				enclosedCount = enclosedCount,
				ringMedR = ringMedR,
				ringMedG = ringMedG,
				ringMedB = ringMedB,
				ringStd = ringStd,
				medGlyphH = medGlyphH,
				medGlyphW = medGlyphW,
				fullWidth = fullWidth,
				hasClosedBubbleWalls = hasClosedBubbleWalls,
				compCenters = compCenters,
				origLineCount = origLineCount,
				kindHint = explicitKind ?: bubble.kindHint,
			),
		)
		val style = styleRes.fingerprint
		val resolvedKind = explicitKind ?: styleRes.kind

		// Skip open-scene orphan fragments & standalone repeated-only SFX BEFORE touching workBitmap.
		if (!seamEraseOnly) {
			val origOrTransText = bubble.text.ifBlank { sanitizedTranslated }
			val trimmedOrig = origOrTransText.trim()
			val cleanedAlphaOrig = origOrTransText.replace(TR_EN_NON_ALPHA_REGEX, "").lowercase(Locale.ROOT)
			val cleanedAlphaTrans = sanitizedTranslated.replace(TR_EN_NON_ALPHA_REGEX, "").lowercase(Locale.ROOT)
			val isSingleUppercaseStandaloneSfx =
				origLineCount == 1 &&
					trimmedOrig == trimmedOrig.uppercase(Locale.ROOT) &&
					!HAS_PUNCT_SFX_EXC_REGEX.containsMatchIn(trimmedOrig) &&
					wallHits < 4
			val isTinySingleGlyphArtifact =
				!hasClosedBubbleWalls &&
					cleanedAlphaOrig.length <= 1 &&
					!DIGITS_ONLY_REGEX.matches(trimmedOrig) &&
					bgLumInit < 235f
			val isRepeatedStandaloneSfx =
				(cleanedAlphaOrig in RenderTextHelpers.REPEATED_ONLY_SFX_STEMS ||
					cleanedAlphaTrans in RenderTextHelpers.REPEATED_ONLY_SFX_STEMS) &&
					!isSingleLineStat &&
					wallHits < 4 &&
					(!hasActualPhysicalWalls || resolvedKind == TextBlockKind.SFX || isSingleUppercaseStandaloneSfx) &&
					!origOrTransText.contains('?')
			if (isTinySingleGlyphArtifact || isRepeatedStandaloneSfx) {
				return null
			}
		}

		// 3-Tier Inpainting (`bubbleInpainter.ts:1987-2335`).
		val ringMedChroma = max(ringMedR, max(ringMedG, ringMedB)) - min(ringMedR, min(ringMedG, ringMedB))
		val isGradientLightBubble =
			!preferLightFirst &&
				max(bgLumInit, ringMedLum) >= 185f &&
				(ringMedChroma >= 8 || ringMedLum < 244f || (ringStd >= 3.5f && ringMedLum < 250f)) &&
				(ringStd >= 3.0f || (ringMedChroma >= 8 && bgLumInit >= ringMedLum + 6f))

		if (isGradientLightBubble) {
			val rowOuterLum = FloatArray(wh) { ringMedLum }
			val rowOuterChroma = FloatArray(wh) { ringMedChroma.toFloat() }
			val oLums = FloatArray(48)
			val oChrs = IntArray(48)
			for (y in max(0, egMinY - 4)..min(wh - 1, egMaxY + 4)) {
				val rOff = y * ww
				var oCount = 0
				for (x in max(0, egMinX - 18) until min(ww, egMaxX + 18)) {
					if (x in (egMinX - 4)..(egMaxX + 4)) continue
					val idx = rOff + x
					if (eraseMask[idx] != 0.toByte() || borderMask[idx] != 0.toByte()) continue
					if (oCount < oLums.size) {
						val p = pixels[idx]
						val pr = PixelColorMath.red(p)
						val pg = PixelColorMath.green(p)
						val pb = PixelColorMath.blue(p)
						oLums[oCount] = PixelColorMath.luminance(pr, pg, pb)
						oChrs[oCount] = max(pr, max(pg, pb)) - min(pr, min(pg, pb))
						oCount++
					}
				}
				if (oCount >= 6) {
					rowOuterLum[y] = PixelColorMath.medianFloat(oLums, oCount)
					rowOuterChroma[y] = PixelColorMath.medianInt(oChrs, oCount).toFloat()
				}
			}

			for (step in 0 until 5) {
				var gHaloAdded = 0
				val prevErase = eraseMask.copyOf()
				for (y in max(1, egMinY - 4)..min(wh - 2, egMaxY + 4)) {
					val rOff = y * ww
					val refLum = min(ringMedLum, rowOuterLum[y])
					val refChr = max(ringMedChroma.toFloat(), rowOuterChroma[y])
					for (x in max(1, egMinX - 4)..min(ww - 2, egMaxX + 4)) {
						val idx = rOff + x
						if (prevErase[idx] != 0.toByte() || borderMask[idx] != 0.toByte()) continue
						if (prevErase[idx - 1] != 0.toByte() ||
							prevErase[idx + 1] != 0.toByte() ||
							prevErase[idx - ww] != 0.toByte() ||
							prevErase[idx + ww] != 0.toByte()
						) {
							val p = pixels[idx]
							val pr = PixelColorMath.red(p)
							val pg = PixelColorMath.green(p)
							val pb = PixelColorMath.blue(p)
							val pLum = PixelColorMath.luminance(pr, pg, pb)
							val pChroma = max(pr, max(pg, pb)) - min(pr, min(pg, pb))
							val chromaPass = if (refChr >= 10f) {
								pChroma <= refChr * 0.68f || pLum >= min(248f, refLum + 10f)
							} else {
								pLum >= refLum + 10f
							}
							if (pLum >= max(226f, refLum + 10f) &&
								pChroma <= max(12f, refChr * 0.65f) &&
								chromaPass
							) {
								eraseMask[idx] = 1
								gHaloAdded++
								if (x < egMinX) egMinX = x
								if (x > egMaxX) egMaxX = x
								if (y < egMinY) egMinY = y
								if (y > egMaxY) egMaxY = y
							}
						}
					}
				}
				if (gHaloAdded == 0) break
			}
		}

		val cleaned = FloatArray(numPixels * 3)
		for (i in 0 until numPixels) {
			val p = pixels[i]
			val c = i * 3
			cleaned[c] = PixelColorMath.red(p).toFloat()
			cleaned[c + 1] = PixelColorMath.green(p).toFloat()
			cleaned[c + 2] = PixelColorMath.blue(p).toFloat()
		}

		val isFlatLightBubble =
			!hasTexturedSceneInterior &&
				!isGradientLightBubble &&
				((ringStd < 4.5f && ringMedLum >= 232f) ||
					(wallHits >= 3 && ringStd < 5.5f && ringMedLum >= 220f))

		if (isFlatLightBubble) {
			val baseErase = eraseMask.copyOf()
			for (y in max(0, egMinY - 2)..min(wh - 1, egMaxY + 2)) {
				val rOff = y * ww
				for (x in max(0, egMinX - 2)..min(ww - 1, egMaxX + 2)) {
					val idx = rOff + x
					if (borderMask[idx] != 0.toByte() || eraseMask[idx] != 0.toByte()) continue
					if ((x > 0 && baseErase[idx - 1] != 0.toByte()) ||
						(x < ww - 1 && baseErase[idx + 1] != 0.toByte()) ||
						(y > 0 && baseErase[idx - ww] != 0.toByte()) ||
						(y < wh - 1 && baseErase[idx + ww] != 0.toByte())
					) {
						if (PixelColorMath.maxChannelDiff(pixels[idx], ringMedR, ringMedG, ringMedB) > 14) {
							eraseMask[idx] = 1
							if (x < egMinX) egMinX = x
							if (x > egMaxX) egMaxX = x
							if (y < egMinY) egMinY = y
							if (y > egMaxY) egMaxY = y
						}
					}
				}
			}
			val medRf = ringMedR.toFloat()
			val medGf = ringMedG.toFloat()
			val medBf = ringMedB.toFloat()
			for (i in 0 until numPixels) {
				if (eraseMask[i] == 0.toByte()) continue
				val c = i * 3
				cleaned[c] = medRf
				cleaned[c + 1] = medGf
				cleaned[c + 2] = medBf
			}
		} else {
			styleExtractor.inpaintTexturedRegion(
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

			// Post-Inpaint Residual Verification.
			val residualThresh = max(42f, ringStd * 2.5f)
			var residualFound = 0
			var resMask = ByteArray(numPixels)
			for (y in max(3, gMinY - 2)..min(wh - 4, gMaxY + 2)) {
				val rOff = y * ww
				for (x in max(3, gMinX - 2)..min(ww - 4, gMaxX + 2)) {
					val idx = rOff + x
					if (eraseMask[idx] != 0.toByte() || borderMask[idx] != 0.toByte()) continue
					val nearErase =
						eraseMask[idx - 1] != 0.toByte() ||
							eraseMask[idx + 1] != 0.toByte() ||
							eraseMask[idx - ww] != 0.toByte() ||
							eraseMask[idx + ww] != 0.toByte() ||
							eraseMask[idx - 2] != 0.toByte() ||
							eraseMask[idx + 2] != 0.toByte() ||
							eraseMask[idx - 2 * ww] != 0.toByte() ||
							eraseMask[idx + 2 * ww] != 0.toByte() ||
							eraseMask[idx - 3] != 0.toByte() ||
							eraseMask[idx + 3] != 0.toByte()
					if (!nearErase) continue

					val cIdx = idx * 3
					val lIdx = (idx - 3) * 3
					val rIdx = (idx + 3) * 3
					val tIdx = (idx - 3 * ww) * 3
					val bIdx = (idx + 3 * ww) * 3
					val locR = (cleaned[lIdx] + cleaned[rIdx] + cleaned[tIdx] + cleaned[bIdx]) * 0.25f
					val locG = (cleaned[lIdx + 1] + cleaned[rIdx + 1] + cleaned[tIdx + 1] + cleaned[bIdx + 1]) * 0.25f
					val locB = (cleaned[lIdx + 2] + cleaned[rIdx + 2] + cleaned[tIdx + 2] + cleaned[bIdx + 2]) * 0.25f
					val dev = max(abs(cleaned[cIdx] - locR), max(abs(cleaned[cIdx + 1] - locG), abs(cleaned[cIdx + 2] - locB)))
					val pLumCur = PixelColorMath.luminance(cleaned[cIdx], cleaned[cIdx + 1], cleaned[cIdx + 2])
					val hLocLum = PixelColorMath.luminance(
						(cleaned[lIdx] + cleaned[rIdx]) * 0.5f,
						(cleaned[lIdx + 1] + cleaned[rIdx + 1]) * 0.5f,
						(cleaned[lIdx + 2] + cleaned[rIdx + 2]) * 0.5f,
					)
					val hUniformDrop =
						preferLightFirst &&
							PixelColorMath.maxChannelDiff(
								cleaned[lIdx], cleaned[lIdx + 1], cleaned[lIdx + 2],
								cleaned[rIdx], cleaned[rIdx + 1], cleaned[rIdx + 2],
							) <= 20f &&
							pLumCur < hLocLum - 18f
					val isLightSceneResidual = if (!preferLightFirst && ringMedLum >= 120f) {
						pLumCur <= min(62f, (ringMedLum * 0.42f).roundToInt().toFloat()) && pLumCur < hLocLum - 35f
					} else {
						dev > residualThresh || hUniformDrop
					}
					if (isLightSceneResidual) {
						resMask[idx] = 1
						residualFound++
					}
				}
			}

			if (residualFound > 4 && residualFound < numPixels * 0.25f) {
				val d1 = ByteArray(numPixels)
				dilate3x3(resMask, d1, ww, wh)
				val dilatedRes = if (!preferLightFirst && ringMedLum >= 120f) {
					d1
				} else {
					val d2 = ByteArray(numPixels)
					dilate3x3(d1, d2, ww, wh)
					d2
				}
				for (y in 0 until wh) {
					val rOff = y * ww
					for (x in 0 until ww) {
						val idx = rOff + x
						if (dilatedRes[idx] != 0.toByte() && borderMask[idx] == 0.toByte() && eraseMask[idx] == 0.toByte()) {
							eraseMask[idx] = 1
							if (x < egMinX) egMinX = x
							if (x > egMaxX) egMaxX = x
							if (y < egMinY) egMinY = y
							if (y > egMaxY) egMaxY = y
						}
					}
				}
				styleExtractor.inpaintTexturedRegion(
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
			}

			// Black-outlined white scene lettering soft grey shadow row sweep (`bubbleInpainter.ts:2226-2335`).
			if (preferLightFirst &&
				!hasClosedBubbleWalls &&
				style.hasTextStroke &&
				style.strokeColor == 0xFF0A0A0C.toInt()
			) {
				val swY0 = max(0, egMinY - 4)
				val swY1 = min(wh - 1, egMaxY + 5)
				val lRs = FloatArray(16)
				val lGs = FloatArray(16)
				val lBs = FloatArray(16)
				val rRs = FloatArray(16)
				val rGs = FloatArray(16)
				val rBs = FloatArray(16)
				for (y in swY0..swY1) {
					val rOff = y * ww
					var rFirstX = -1
					var rLastX = -1
					for (x in egMinX..egMaxX) {
						if (eraseMask[rOff + x] != 0.toByte()) {
							if (rFirstX < 0) rFirstX = x
							rLastX = x
						}
					}
					val isEdgeShadowRow = rFirstX < 0
					if (isEdgeShadowRow) {
						rFirstX = egMinX
						rLastX = egMaxX
					}
					var lCount = 0
					var lx = max(0, rFirstX - 30)
					val lxEnd = max(0, rFirstX - 13)
					while (lx <= lxEnd && lCount < lRs.size) {
						val lIdx = rOff + lx
						if (eraseMask[lIdx] == 0.toByte() && borderMask[lIdx] == 0.toByte()) {
							val lc = lIdx * 3
							lRs[lCount] = cleaned[lc]
							lGs[lCount] = cleaned[lc + 1]
							lBs[lCount] = cleaned[lc + 2]
							lCount++
						}
						lx += 2
					}
					var rCount = 0
					var rx = min(ww - 1, rLastX + 13)
					val rxEnd = min(ww - 1, rLastX + 30)
					while (rx <= rxEnd && rCount < rRs.size) {
						val rIdx = rOff + rx
						if (eraseMask[rIdx] == 0.toByte() && borderMask[rIdx] == 0.toByte()) {
							val rc = rIdx * 3
							rRs[rCount] = cleaned[rc]
							rGs[rCount] = cleaned[rc + 1]
							rBs[rCount] = cleaned[rc + 2]
							rCount++
						}
						rx += 2
					}
					if (lCount < 3 || rCount < 3) continue
					val fLR = PixelColorMath.medianFloat(lRs, lCount)
					val fLG = PixelColorMath.medianFloat(lGs, lCount)
					val fLB = PixelColorMath.medianFloat(lBs, lCount)
					val fRR = PixelColorMath.medianFloat(rRs, rCount)
					val fRG = PixelColorMath.medianFloat(rGs, rCount)
					val fRB = PixelColorMath.medianFloat(rBs, rCount)
					val pairDiff = PixelColorMath.maxChannelDiff(fLR, fLG, fLB, fRR, fRG, fRB)
					val minRowLum = min(
						PixelColorMath.luminance(fLR, fLG, fLB),
						PixelColorMath.luminance(fRR, fRG, fRB),
					)
					if (pairDiff > (if (minRowLum < 90f) 34f else 22f)) continue
					if (minRowLum < 16f) continue
					if (isEdgeShadowRow && (minRowLum < 90f || pairDiff > 14f)) continue

					val sx0 = max(0, rFirstX - 12)
					val sx1 = min(ww - 1, rLastX + 12)
					val spanLen = max(1, sx1 - sx0).toFloat()
					val texAvail = rFirstX >= 32 && minRowLum >= 26f && pairDiff <= (if (minRowLum < 90f) 22f else 14f)
					val texPeriod = 18
					val texShift = ((y * 7) xor (y ushr 2)) % texPeriod
					val darkCut = if (minRowLum >= 90f) minRowLum - 5f else minRowLum - 8f
					for (x in sx0..sx1) {
						val idx = rOff + x
						if (borderMask[idx] != 0.toByte()) continue
						val c3 = idx * 3
						val pLum = PixelColorMath.luminance(cleaned[c3], cleaned[c3 + 1], cleaned[c3 + 2])
						if ((!isEdgeShadowRow && eraseMask[idx] != 0.toByte()) || pLum < darkCut) {
							val t = (x - sx0) / spanLen
							var baseR = (1f - t) * fLR + t * fRR
							var baseG = (1f - t) * fLG + t * fRG
							var baseB = (1f - t) * fLB + t * fRB
							if (texAvail) {
								val phase = (x - sx0 + texShift) % (texPeriod * 2)
								val off = if (phase < texPeriod) phase else texPeriod * 2 - 1 - phase
								val sX = rFirstX - 14 - off
								if (sX in 0 until ww) {
									val sIdx = rOff + sX
									if (eraseMask[sIdx] == 0.toByte() && borderMask[sIdx] == 0.toByte()) {
										val sc = sIdx * 3
										val dR = cleaned[sc] - fLR
										val dG = cleaned[sc + 1] - fLG
										val dB = cleaned[sc + 2] - fLB
										if (abs(dR) <= 32f && abs(dG) <= 32f && abs(dB) <= 32f) {
											baseR += dR
											baseG += dG
											baseB += dB
										}
									}
								}
							}
							cleaned[c3] = baseR.coerceIn(0f, 255f)
							cleaned[c3 + 1] = baseG.coerceIn(0f, 255f)
							cleaned[c3 + 2] = baseB.coerceIn(0f, 255f)
							if (eraseMask[idx] == 0.toByte()) {
								eraseMask[idx] = 1
								if (x < egMinX) egMinX = x
								if (x > egMaxX) egMaxX = x
								if (y < egMinY) egMinY = y
								if (y > egMaxY) egMaxY = y
							}
						}
					}
				}
			}
		}

		// Commit only erased pixels back onto workBitmap so untouched pixels stay razor sharp.
		for (i in 0 until numPixels) {
			if (eraseMask[i] == 0.toByte()) continue
			val cIdx = i * 3
			pixels[i] = PixelColorMath.packRgb(
				cleaned[cIdx].roundToInt(),
				cleaned[cIdx + 1].roundToInt(),
				cleaned[cIdx + 2].roundToInt(),
			)
		}
		workBitmap.setPixels(pixels, 0, ww, wx0, wy0, ww, wh)

		if (seamEraseOnly) {
			return RefinedBlockLayout(
				bubble = bubble,
				translatedText = "",
				kind = resolvedKind,
				refinedBox = bubble.box,
				style = style,
				wrappedLines = emptyList(),
				fontPx = 0f,
				lineHeightRatio = 1.13f,
				isSingleLineStat = false,
				isSeamEraseOnly = true,
			)
		}

		// Symmetric centered safe interior box + Cap-Height font sizing + contour width measurement.
		val glyphW = max(24f, (gMaxX - gMinX + 1).toFloat())
		val glyphH = max(18f, (gMaxY - gMinY + 1).toFloat())
		val coreCenterY = ((cy0 + cy1) * 0.5f).roundToInt()
		val baseMidY = if (abs(midLocalY - coreCenterY) <= max(18f, medGlyphH * 0.85f)) midLocalY else coreCenterY
		val wallCenterY = ((safeLocalTop + safeLocalBottom) * 0.5f).roundToInt()
		val layoutCenterX = if (hitLeftWall &&
			hitRightWall &&
			safeLocalRight > safeLocalLeft + 24 &&
			abs(midLocalX - (safeLocalLeft + safeLocalRight) * 0.5f) <=
			(safeLocalRight - safeLocalLeft) * (if (isFlatLightBubble) 0.34f else 0.18f)
		) {
			((safeLocalLeft + safeLocalRight) * 0.5f).roundToInt()
		} else {
			midLocalX
		}
		val layoutCenterY = if (hitTopWall &&
			hitBottomWall &&
			safeLocalBottom > safeLocalTop + 20 &&
			abs(wallCenterY - baseMidY) <= max(14f, medGlyphH * 0.60f)
		) {
			wallCenterY
		} else {
			baseMidY
		}

		val spanLocalW = if (safeLocalRight > safeLocalLeft + 24) (safeLocalRight - safeLocalLeft).toFloat() else glyphW
		val maxHalfW = max(
			glyphW * 0.5f,
			if (hitLeftWall && hitRightWall && isFlatLightBubble) {
				spanLocalW * 0.5f
			} else {
				min((layoutCenterX - safeLocalLeft).toFloat(), (safeLocalRight - layoutCenterX).toFloat())
			},
		)
		val maxHalfH = max(
			glyphH * 0.55f,
			min((layoutCenterY - safeLocalTop).toFloat(), (safeLocalBottom - layoutCenterY).toFloat()),
		)
		var safeW = max(glyphW, maxHalfW * 2f)
		if (!(hitLeftWall && hitRightWall)) {
			safeW = min(safeW, max(glyphW * 1.32f, medGlyphH * 10f))
		}
		val safeH = max(glyphH * 1.1f, maxHalfH * 2f)

		val capHeights = accComps
			.filter { it.h >= max(8f, medGlyphH * 0.55f) }
			.map { it.h }
			.sorted()
		var origCapH = if (capHeights.isNotEmpty()) {
			capHeights[min(capHeights.lastIndex, (capHeights.size * 0.65f).toInt())].toFloat()
		} else {
			medGlyphH
		}
		if (!hasClosedBubbleWalls &&
			origLineCount == 1 &&
			bh >= 28 &&
			style.hasTextStroke &&
			origCapH < bh * 0.48f
		) {
			origCapH = max(origCapH, (bh * 0.72f).roundToInt().toFloat())
		}
		val targetEmPx = origCapH / 0.69f

		val paint = TextPaint()
		smartTextLayout.configureTextPaint(paint, style, targetEmPx.coerceAtLeast(12f), displayText)
		val lineHeightRatio = smartTextLayout.getLineHeightRatioForBlock(resolvedKind, style)

		fun measureAtFontPx(s: String, px: Float): Float {
			paint.textSize = px.coerceAtLeast(1f)
			return smartTextLayout.measureStyledWordWidth(s, paint, style.fontFamily)
		}

		var fontPx: Float
		var finalBoxW: Float
		var finalBoxH: Float
		var finalLineWidthsPct: List<Float> = emptyList()
		val emphasisLastLineScale = if (resolvedKind == TextBlockKind.SFX) 1f else bubble.emphasisLastLineScale
		var finalWrap = SmartWrapResult(emptyList(), 0f, 0f, 0f, false)

		if (isSingleLineStat) {
			val statLeftLocalX = gMinX.toFloat()
			val wallRBound = if (hitRightWall) safeLocalRight + (wallPadX * 0.5f).roundToInt() else ww - 4
			val maxStatW = max(
				bw * 1.08f,
				min(fullWidth - (wx0 + statLeftLocalX) - 8f, wallRBound - statLeftLocalX),
			).coerceAtLeast(24f)
			val minStatFontPx = max(11f, fullWidth * 0.015f)
			val maxStatFontPx = max(minStatFontPx, fullWidth * 0.048f)
			fontPx = targetEmPx.coerceIn(minStatFontPx, maxStatFontPx)
			finalBoxW = max(bw.toFloat(), glyphW)
			finalBoxH = max(bh.toFloat(), ceil(fontPx * 1.14f))
			for (step in 0 until 16) {
				val lineW = measureAtFontPx(displayText, fontPx)
				if (lineW <= maxStatW * 0.96f || fontPx <= minStatFontPx) {
					finalBoxW = ceil(lineW + 10f).coerceIn(24f, max(24f, maxStatW))
					finalBoxH = max(bh.toFloat(), ceil(fontPx * 1.14f))
					val effScale = if (lineW > finalBoxW && lineW > 0f) max(0.88f, finalBoxW / lineW) else 1f
					finalWrap = SmartWrapResult(
						lines = listOf(SmartWrappedLine(displayText, lineW, effScale)),
						maxWordWidth = lineW,
						totalHeightPx = fontPx * lineHeightRatio,
						maxLineWidthPx = lineW * effScale,
						hasOverflow = false,
					)
					break
				}
				fontPx = max(minStatFontPx, fontPx * 0.95f)
			}
			if (finalWrap.lines.isEmpty()) {
				val lineW = measureAtFontPx(displayText, fontPx)
				finalWrap = SmartWrapResult(
					lines = listOf(SmartWrappedLine(displayText, lineW, 1f)),
					maxWordWidth = lineW,
					totalHeightPx = fontPx * lineHeightRatio,
					maxLineWidthPx = lineW,
					hasOverflow = false,
				)
			}
		} else if (resolvedKind == TextBlockKind.SFX) {
			val minSfxFontPx = max(14f, fullWidth * 0.022f)
			val maxSfxFontPx = max(
				minSfxFontPx,
				min(fullWidth * 0.135f, max(glyphH * 1.35f, targetEmPx * 1.15f)),
			)
			fontPx = targetEmPx.coerceIn(minSfxFontPx, maxSfxFontPx)
			finalBoxW = if (origLineCount == 1) {
				max(glyphW * 1.38f, max(safeW, fontPx * displayText.length * 0.58f))
			} else {
				max(glyphW * 1.25f, safeW)
			}
			finalBoxW = min(finalBoxW, fullWidth * 0.92f).coerceAtLeast(24f)
			finalBoxH = max(glyphH * 1.25f, safeH)

			for (step in 0 until 16) {
				val wrapped = SmartTextLayout.computeSmartWrappedLines(
					text = displayText,
					maxWidthPx = finalBoxW * 0.95f,
					fontPx = fontPx,
					fontFamily = style.fontFamily,
					lineHeightRatio = lineHeightRatio,
				) { s, px -> measureAtFontPx(s, px) }
				finalWrap = wrapped
				if (wrapped.totalHeightPx <= finalBoxH * 0.98f &&
					wrapped.maxWordWidth <= finalBoxW * 0.96f &&
					(origLineCount > 1 || wrapped.lines.size <= 2)
				) {
					finalBoxW = max(glyphW, min(finalBoxW, wrapped.maxWordWidth * 1.22f + 24f))
					finalBoxH = max(glyphH, wrapped.totalHeightPx + fontPx * 0.35f)
					break
				}
				if (fontPx <= minSfxFontPx) break
				fontPx = max(minSfxFontPx, fontPx * 0.94f)
			}
		} else {
			val minInitDialogPx = fullWidth * 0.019f
			val maxDialogFontPx = max(
				minInitDialogPx,
				min(
					fullWidth * 0.135f,
					max(
						fullWidth * if (emphasisLastLineScale >= 1.2f) 0.068f else 0.072f,
						targetEmPx * 1.02f,
					),
				),
			)
			fontPx = targetEmPx.coerceIn(minInitDialogPx, maxDialogFontPx)
			val minFontPx = max(11f, fullWidth * 0.014f)

			val charLen = displayText.length
			val desiredCharsPerLine = if (charLen <= 10) {
				charLen + 2
			} else {
				(ceil(sqrt(charLen * 2.1)).toInt() * 2).coerceIn(10, 26)
			}
			val singleCharW = measureAtFontPx("A", fontPx).let { if (it > 0f) it else fontPx * 0.61f }
			val desiredW = desiredCharsPerLine * singleCharW + 18f
			val rawLeftWall = if (hitLeftWall) (safeLocalLeft - wallPadX).toFloat() else safeLocalLeft.toFloat()
			val rawRightWall = if (hitRightWall) (safeLocalRight + wallPadX).toFloat() else safeLocalRight.toFloat()
			val rawWallSpan = max(glyphW, rawRightWall - rawLeftWall)
			val targetWallClear = if (isDarkCard) {
				(medGlyphH * 0.34f).roundToInt().coerceIn(8, 16)
			} else {
				(medGlyphH * 0.12f).roundToInt().coerceIn(2, 4)
			}
			val maxDrawableW = max(glyphW, rawWallSpan - 2f * targetWallClear)
			val interiorSpan = if (safeLocalRight > safeLocalLeft + 24) (safeLocalRight - safeLocalLeft).toFloat() else safeW
			val isOpenMultilineScene =
				!isFlatLightBubble && !hasClosedBubbleWalls && origLineCount >= 3
			val closedHorizW = min((ww - 4).toFloat(), max(safeW, maxDrawableW / 0.88f))
			val rawUsableSafeW = if (hitLeftWall && hitRightWall && !isOpenMultilineScene) {
				closedHorizW
			} else {
				min(safeW, max(28f, interiorSpan))
			}
			val usableSafeW = if (isOpenMultilineScene) {
				min(rawUsableSafeW, max(glyphW * 1.18f, medGlyphH * 10.5f))
			} else {
				rawUsableSafeW
			}.coerceAtLeast(24f)
			val horizSlack = 0.89f

			finalBoxW = if (isOpenMultilineScene) {
				max(min(glyphW * 1.15f, usableSafeW), min(glyphW * 0.96f, usableSafeW))
			} else {
				val minBoxW = min(glyphW * 0.85f, usableSafeW)
				max(glyphW * 0.94f, desiredW).coerceIn(minBoxW, max(minBoxW, usableSafeW))
			}

			val topWallDist = if (hitTopWall) (max(gMinY, layoutCenterY) - min(gMinY, safeLocalTop)).toFloat() else Float.POSITIVE_INFINITY
			val botWallDist = if (hitBottomWall) (max(gMaxY + 1, safeLocalBottom) - min(gMaxY + 1, layoutCenterY)).toFloat() else Float.POSITIVE_INFINITY
			val vTopCandidate = if (hitTopWall) min(gMinY.toFloat(), safeLocalTop.toFloat()) else min(gMinY.toFloat(), layoutCenterY - safeH * 0.5f)
			val vBotCandidate = if (hitBottomWall) max((gMaxY + 1).toFloat(), safeLocalBottom.toFloat()) else max((gMaxY + 1).toFloat(), layoutCenterY + safeH * 0.5f)
			val wallSpanV = if (vTopCandidate < vBotCandidate) {
				val b = if (hitBottomWall) max((gMaxY + 1).toFloat(), safeLocalBottom.toFloat()) else max((gMaxY + 1).toFloat(), layoutCenterY + safeH * 0.65f)
				val t = if (hitTopWall) min(gMinY.toFloat(), safeLocalTop.toFloat()) else min(gMinY.toFloat(), layoutCenterY - safeH * 0.65f)
				b - t
			} else {
				safeH
			}
			val wallVertCapH = when {
				hitTopWall && hitBottomWall && isDarkCard ->
					min(
						(safeLocalBottom - safeLocalTop).toFloat().coerceAtLeast(glyphH),
						max(glyphH * 1.05f, min(topWallDist + botWallDist, wallSpanV) * 0.90f),
					)
				hitTopWall && hitBottomWall -> max(glyphH * 1.05f, min(topWallDist + botWallDist, wallSpanV) * 0.90f)
				hitTopWall xor hitBottomWall -> max(glyphH * 1.15f, (topWallDist + botWallDist) * 0.90f)
				else -> min(topWallDist * 2f, botWallDist * 2f)
			}
			val maxAvailBoxH = when {
				hitTopWall && hitBottomWall && isDarkCard ->
					(safeLocalBottom - safeLocalTop).toFloat().coerceAtLeast(glyphH)
				hitTopWall && hitBottomWall -> max(safeH, wallVertCapH)
				(hitTopWall xor hitBottomWall) && !isSingleOrDarkSfx -> {
					max(safeH, min(safeLocalBottom - safeLocalTop, wh) * 0.55f).coerceAtMost(max(safeH, glyphH * 1.95f))
				}
				else -> max(safeH, glyphH * 1.4f)
			}
			finalBoxH = min(max(glyphH * 1.15f, safeH), wallVertCapH)

			val canMeasureContour =
				hasActualPhysicalWalls &&
					hitLeftWall &&
					hitRightWall &&
					(hitTopWall || hitBottomWall) &&
					!isOpenMultilineScene
			val contourScanX = layoutCenterX.coerceIn(0, ww - 1)
			val contourPadX = (medGlyphH * 0.22f).roundToInt().coerceIn(4, 9)

			fun measureRowClearSpanPx(yTopLocal: Float, yBotLocal: Float): Float {
				val yMid = ((yTopLocal + yBotLocal) * 0.5f).roundToInt()
				val sampleYs = intArrayOf(
					yTopLocal.roundToInt().coerceIn(0, wh - 1),
					yMid.coerceIn(0, wh - 1),
					yBotLocal.roundToInt().coerceIn(0, wh - 1),
				)
				var minHalf = Float.MAX_VALUE
				var spanCount = 0
				for (sy in sampleYs) {
					if (isStructuralWallAt(contourScanX, sy)) {
						minHalf = 0f
						spanCount++
						break
					}
					var halfL = (contourScanX - rawLeftWall - contourPadX).toFloat()
					var hitL = false
					for (x in contourScanX downTo 0) {
						if (isStructuralWallAt(x, sy)) {
							halfL = (contourScanX - x - contourPadX).toFloat()
							hitL = true
							break
						}
					}
					var halfR = (rawRightWall - contourScanX - contourPadX).toFloat()
					var hitR = false
					for (x in contourScanX until ww) {
						if (isStructuralWallAt(x, sy)) {
							halfR = (x - contourScanX - contourPadX).toFloat()
							hitR = true
							break
						}
					}
					if (hitL && hitR) {
						minHalf = min(minHalf, max(0f, min(halfL, halfR)))
						spanCount++
					}
				}
				if (spanCount == 0) return finalBoxW * 0.88f
				return (2f * minHalf).coerceAtLeast(8f)
			}

			fun computeRowPctsForWrapped(wrappedRes: SmartWrapResult): Pair<List<Float>, List<Float>> {
				if (!canMeasureContour || wrappedRes.lines.isEmpty()) {
					return emptyList<Float>() to emptyList()
				}
				val boxWrapW = max(1f, finalBoxW * 0.88f)
				val rowSpans = ArrayList<Float>(wrappedRes.lines.size)
				val rowPcts = ArrayList<Float>(wrappedRes.lines.size)
				var curY = layoutCenterY - wrappedRes.totalHeightPx * 0.5f
				for (line in wrappedRes.lines) {
					val lh = fontPx * line.scale * lineHeightRatio
					val spanPx = measureRowClearSpanPx(curY + lh * 0.10f, curY + lh * 0.90f)
					rowSpans.add(spanPx)
					rowPcts.add(((spanPx / boxWrapW).coerceIn(0.56f, 1.0f) * 1000f).roundToInt() / 1000f)
					curY += lh
				}
				return if (wrappedRes.lines.size >= 2) {
					rowSpans to rowPcts
				} else {
					rowSpans to emptyList()
				}
			}

			for (step in 0 until 26) {
				var wrapped = SmartTextLayout.computeSmartWrappedLines(
					text = displayText,
					maxWidthPx = finalBoxW * 0.88f,
					fontPx = fontPx,
					emphasisLastLineScale = emphasisLastLineScale,
					fontFamily = style.fontFamily,
					lineHeightRatio = lineHeightRatio,
				) { s, px -> measureAtFontPx(s, px) }

				var (rowSpans, rowPcts) = computeRowPctsForWrapped(wrapped)
				if (canMeasureContour && rowPcts.any { it < 0.96f }) {
					val contoured = SmartTextLayout.computeSmartWrappedLines(
						text = displayText,
						maxWidthPx = finalBoxW * 0.88f,
						fontPx = fontPx,
						lineWidthsPct = rowPcts,
						emphasisLastLineScale = emphasisLastLineScale,
						fontFamily = style.fontFamily,
						lineHeightRatio = lineHeightRatio,
					) { s, px -> measureAtFontPx(s, px) }
					if (contoured.lines.size == rowPcts.size) {
						wrapped = contoured
						val updated = computeRowPctsForWrapped(wrapped)
						rowSpans = updated.first
						rowPcts = updated.second
					}
				}
				finalWrap = wrapped

				var linesClearWalls = true
				val boxWrapWCheck = max(1f, finalBoxW * 0.88f)
				if (canMeasureContour && rowSpans.size == wrapped.lines.size && step < 22) {
					for (li in wrapped.lines.indices) {
						if (rowSpans[li] >= boxWrapWCheck * 0.98f) continue
						val line = wrapped.lines[li]
						val drawnW = line.widthPx * line.scale
						if (drawnW > rowSpans[li] * 1.01f) {
							linesClearWalls = false
							break
						}
					}
				}

				val vertFillRatio = when {
					hitTopWall && hitBottomWall && isDarkCard -> 0.78f
					hitTopWall && hitBottomWall -> 0.88f
					else -> 1.0f
				}
				val maxSafeLineW = if (hitLeftWall && hitRightWall) {
					val cardMargin = if (isDarkCard) 6f else 0f
					min(finalBoxW * horizSlack, (interiorSpan - cardMargin).coerceAtLeast(24f))
				} else {
					finalBoxW * horizSlack
				}
				val fitsContainerH = if (hitTopWall && hitBottomWall && isDarkCard) {
					wrapped.totalHeightPx + fontPx * 0.42f <= (safeLocalBottom - safeLocalTop).toFloat().coerceAtLeast(glyphH) &&
						wrapped.totalHeightPx <= finalBoxH * vertFillRatio
				} else {
					wrapped.totalHeightPx <= finalBoxH * vertFillRatio
				}
				if (fitsContainerH &&
					!wrapped.hasOverflow &&
					wrapped.maxWordWidth <= maxSafeLineW &&
					wrapped.maxLineWidthPx <= maxSafeLineW * 1.01f &&
					linesClearWalls
				) {
					if (rowPcts.size == wrapped.lines.size && rowPcts.any { it < 0.98f }) {
						finalLineWidthsPct = rowPcts
					}
					finalBoxH = max(glyphH, wrapped.totalHeightPx + fontPx * 0.35f)
					break
				}

				if (finalBoxW < usableSafeW - 1f) {
					finalBoxW = usableSafeW
					continue
				}
				if (finalBoxH < maxAvailBoxH - 1f &&
					wrapped.maxWordWidth <= finalBoxW * horizSlack &&
					linesClearWalls
				) {
					finalBoxH = maxAvailBoxH
					continue
				}
				if (fontPx <= minFontPx) break
				fontPx = max(minFontPx, fontPx * 0.95f)
			}
		}

		val refinedX0: Float
		val refinedX1: Float
		val refinedY0: Float
		val refinedY1: Float
		if (isSingleLineStat) {
			val statLeftLocalX = gMinX.toFloat()
			val statCenterLocalY = ((gMinY + gMaxY) * 0.5f).roundToInt().toFloat()
			refinedX0 = (wx0 + statLeftLocalX).coerceIn(0f, max(0f, fullWidth - finalBoxW))
			refinedX1 = (refinedX0 + finalBoxW).coerceIn(refinedX0 + 4f, fullWidth.toFloat())
			val absStatCenterY = wy0 + statCenterLocalY
			refinedY0 = (absStatCenterY - finalBoxH * 0.5f).coerceIn(0f, max(0f, fullHeight - 4f))
			refinedY1 = (absStatCenterY + finalBoxH * 0.5f).coerceIn(refinedY0 + 4f, fullHeight.toFloat())
		} else {
			val absCenterX = wx0 + layoutCenterX.toFloat()
			val rawBoundPad = if (hitLeftWall && hitRightWall && !isDarkCard) wallPadX + 12 else 0
			val safeAbsLeft = (wx0 + safeLocalLeft - rawBoundPad).toFloat()
			val safeAbsRight = (wx0 + safeLocalRight + rawBoundPad).toFloat()
			val minBoundX = max(4f, if (isDarkCard && hitLeftWall) safeAbsLeft else min((wx0 + gMinX).toFloat(), safeAbsLeft))
			val maxBoundX = min((fullWidth - 4).toFloat(), if (isDarkCard && hitRightWall) safeAbsRight else max((wx0 + gMaxX).toFloat(), safeAbsRight))
			if (finalBoxW > maxBoundX - minBoundX && maxBoundX - minBoundX >= 28f) {
				finalBoxW = maxBoundX - minBoundX
			}
			refinedX0 = (absCenterX - finalBoxW * 0.5f).coerceIn(minBoundX, max(minBoundX, maxBoundX - finalBoxW))
			refinedX1 = (refinedX0 + finalBoxW).coerceIn(min(refinedX0 + 4f, maxBoundX), maxBoundX)

			val absCenterY = wy0 + layoutCenterY.toFloat()
			val safeAbsTop = (wy0 + safeLocalTop).toFloat()
			val safeAbsBot = (wy0 + safeLocalBottom).toFloat()
			val minBoundY = if (hitTopWall) {
				if (isDarkCard && gMinY >= safeLocalTop) safeAbsTop else min((wy0 + gMinY).toFloat(), safeAbsTop)
			} else {
				0f
			}
			val maxBoundY = if (hitBottomWall) {
				if (isDarkCard && gMaxY <= safeLocalBottom) safeAbsBot else max((wy0 + gMaxY).toFloat(), safeAbsBot)
			} else {
				fullHeight.toFloat()
			}
			if (finalBoxH > maxBoundY - minBoundY && maxBoundY - minBoundY >= glyphH) {
				finalBoxH = maxBoundY - minBoundY
			}
			refinedY0 = (absCenterY - finalBoxH * 0.5f).coerceIn(minBoundY, max(minBoundY, maxBoundY - finalBoxH))
			refinedY1 = (refinedY0 + finalBoxH).coerceIn(min(refinedY0 + 4f, maxBoundY), maxBoundY)
		}

		val refinedBox = NormalizedBox(
			yMin = (refinedY0 / fullHeight * 1000f).coerceIn(0f, 1000f),
			xMin = (refinedX0 / fullWidth * 1000f).coerceIn(0f, 1000f),
			yMax = (refinedY1 / fullHeight * 1000f).coerceIn(0f, 1000f),
			xMax = (refinedX1 / fullWidth * 1000f).coerceIn(0f, 1000f),
		)

		val fontSizeCqw = ((fontPx / fullWidth.coerceAtLeast(1)) * 100f).coerceIn(1.5f, 13.5f)
		val finalStyle = style.copy(
			fontSizeCqw = fontSizeCqw,
			lineWidthsPct = finalLineWidthsPct,
			emphasisLastLineScale = emphasisLastLineScale,
		)

		return RefinedBlockLayout(
			bubble = bubble,
			translatedText = displayText,
			kind = resolvedKind,
			refinedBox = refinedBox,
			style = finalStyle,
			wrappedLines = finalWrap.lines,
			fontPx = fontPx,
			lineHeightRatio = lineHeightRatio,
			isSingleLineStat = isSingleLineStat,
			isSeamEraseOnly = false,
		)
	}

	private fun dilate3x3(src: ByteArray, dst: ByteArray, ww: Int, wh: Int) {
		for (y in 0 until wh) {
			val yMin = if (y > 0) y - 1 else 0
			val yMax = if (y < wh - 1) y + 1 else wh - 1
			val dstRow = y * ww
			for (x in 0 until ww) {
				if (src[dstRow + x] != 0.toByte()) {
					dst[dstRow + x] = 1
					continue
				}
				val xMin = if (x > 0) x - 1 else 0
				val xMax = if (x < ww - 1) x + 1 else ww - 1
				var hit: Byte = 0
				for (ny in yMin..yMax) {
					val rOff = ny * ww
					for (nx in xMin..xMax) {
						if (src[rOff + nx] != 0.toByte()) {
							hit = 1
							break
						}
					}
					if (hit != 0.toByte()) break
				}
				dst[dstRow + x] = hit
			}
		}
	}

	internal companion object {
		val HTML_TAG_REGEX = Regex("<[^>]{1,32}>")
		val ANGLE_BRACKET_REGEX = Regex("[<>]+")
		val MULTI_SPACE_REGEX = Regex("\\s+")
		val TR_EN_NON_ALPHA_REGEX = Regex("[^a-zA-ZçğıöşüÇĞİÖŞÜ]")
		val HAS_PUNCT_SFX_EXC_REGEX = Regex("[?!/\\\\]")
		val DIGITS_ONLY_REGEX = Regex("^\\d+$")

		internal fun findDominantBackgroundRgb(
			pixels: IntArray,
			ww: Int,
			wh: Int,
			cx0: Int,
			cy0: Int,
			cx1: Int,
			cy1: Int,
			lineCores: List<LineCoreRect>,
		): IntArray {
			val flatBinCounts = IntArray(4096)
			val allBinCounts = IntArray(4096)
			val sx0 = max(0, cx0 - 6)
			val sx1 = min(ww, cx1 + 6)
			val sy0 = max(0, cy0 - 6)
			val sy1 = min(wh, cy1 + 6)

			var brightFlatCount = 0
			var nonCoreFlatTotal = 0
			var nonCoreBrightFlat = 0
			var nonCoreDarkFlat = 0
			var midToneInBoxCount = 0
			var whiteInBoxCount = 0
			var totalFlatCount = 0

			for (y in sy0 until sy1) {
				val yNext = if (y + 1 < wh) y + 1 else y
				val rowOff = y * ww
				val nextRowOff = yNext * ww
				for (x in sx0 until sx1) {
					val p = pixels[rowOff + x]
					val r = PixelColorMath.red(p)
					val g = PixelColorMath.green(p)
					val b = PixelColorMath.blue(p)
					val lum = PixelColorMath.luminance(r, g, b)

					val xNext = if (x + 1 < ww) x + 1 else x
					val prx = pixels[rowOff + xNext]
					val pby = pixels[nextRowOff + x]
					val gx = abs(PixelColorMath.red(prx) - r) +
						abs(PixelColorMath.green(prx) - g) +
						abs(PixelColorMath.blue(prx) - b)
					val gy = abs(PixelColorMath.red(pby) - r) +
						abs(PixelColorMath.green(pby) - g) +
						abs(PixelColorMath.blue(pby) - b)
					val isFlat = gx + gy <= 66

					var inLineCore = false
					for (li in lineCores.indices) {
						val lc = lineCores[li]
						if (x >= lc.x0 && x < lc.x1 && y >= lc.y0 && y < lc.y1) {
							inLineCore = true
							break
						}
					}
					val insideBlockBox = x in cx0 until cx1 && y in cy0 until cy1
					// Collar & inter-line pixels describe the true background without letting bold white/black glyph bodies outvote it.
					val weight = if (inLineCore) 1 else if (insideBlockBox) 2 else 3
					if (insideBlockBox) {
						if (lum >= 105f && lum < 228f) midToneInBoxCount++
						else if (lum >= 232f) whiteInBoxCount++
					}

					val bin = ((r ushr 4) shl 8) or ((g ushr 4) shl 4) or (b ushr 4)
					allBinCounts[bin] += weight
					if (isFlat) {
						flatBinCounts[bin] += weight
						totalFlatCount += weight
						if (lum >= 195f) brightFlatCount += weight
						if (!inLineCore) {
							nonCoreFlatTotal++
							if (lum >= 195f) nonCoreBrightFlat++
							if (lum < 105f) nonCoreDarkFlat++
						}
					}
				}
			}

			val brightFlatRatio = if (totalFlatCount > 0) brightFlatCount.toFloat() / totalFlatCount else 0f
			val nonCoreBrightRatio = if (nonCoreFlatTotal > 0) nonCoreBrightFlat.toFloat() / nonCoreFlatTotal else 0f
			val nonCoreDarkRatio = if (nonCoreFlatTotal > 0) nonCoreDarkFlat.toFloat() / nonCoreFlatTotal else 0f
			val suppressDarkInk = if (nonCoreFlatTotal >= 16) {
				nonCoreBrightRatio >= 0.50f && nonCoreDarkFlat <= nonCoreBrightFlat * 0.45f
			} else {
				totalFlatCount > 24 && brightFlatRatio >= 0.55f
			}
			val suppressWhiteInk = nonCoreFlatTotal >= 16 &&
				nonCoreDarkRatio >= 0.45f &&
				nonCoreBrightFlat <= nonCoreDarkFlat * 0.65f
			val suppressWhiteHalo = midToneInBoxCount >= 120 && midToneInBoxCount > whiteInBoxCount * 1.35f

			fun selectBestBin(bins: IntArray, applySuppression: Boolean): Pair<Int, Int> {
				var best = -1
				var bestCnt = -1
				for (bin in 0 until 4096) {
					val cnt = bins[bin]
					if (cnt <= bestCnt) continue
					if (applySuppression) {
						val br = (((bin ushr 8) and 15) shl 4) + 8
						val bg = (((bin ushr 4) and 15) shl 4) + 8
						val bb = ((bin and 15) shl 4) + 8
						val binLum = PixelColorMath.luminance(br, bg, bb)
						if (suppressDarkInk && binLum < 105f) continue
						if (suppressWhiteInk && binLum >= 200f) continue
						if (suppressWhiteHalo && binLum >= 228f) continue
					}
					bestCnt = cnt
					best = bin
				}
				return best to bestCnt
			}

			val targetBins = if (totalFlatCount >= 24) flatBinCounts else allBinCounts
			var (bestBin, bestCount) = selectBestBin(targetBins, applySuppression = true)
			if (bestBin < 0 && totalFlatCount >= 24) {
				val (altBin, altCnt) = selectBestBin(allBinCounts, applySuppression = true)
				bestBin = altBin
				bestCount = altCnt
			}
			if (bestBin < 0) {
				val (rawBin, rawCnt) = selectBestBin(allBinCounts, applySuppression = false)
				bestBin = rawBin
				bestCount = rawCnt
			}

			if (bestCount <= 0 || bestBin < 0) return intArrayOf(255, 255, 255)

			val collected = IntArray(max(16, (sx1 - sx0) * (sy1 - sy0)))
			var collectedCount = 0
			for (y in sy0 until sy1) {
				val rowOff = y * ww
				for (x in sx0 until sx1) {
					val p = pixels[rowOff + x]
					val r = PixelColorMath.red(p)
					val g = PixelColorMath.green(p)
					val b = PixelColorMath.blue(p)
					val bin = ((r ushr 4) shl 8) or ((g ushr 4) shl 4) or (b ushr 4)
					if (bin == bestBin) {
						collected[collectedCount++] = rowOff + x
					}
				}
			}
			return if (collectedCount > 0) {
				PixelColorMath.medianRgbFromIndices(pixels, collected, collectedCount)
			} else {
				intArrayOf(255, 255, 255)
			}
		}

		internal fun isWhiteFillDarkOutlineMidScene(
			bgLumInit: Float,
			flankMeanLum: Float,
			flankWhiteRatio: Float,
			coreWhiteRatio: Float,
			whiteCoreCount: Int,
			whiteTouchesDarkOutline: Int,
			brighterCount: Int,
		): Boolean =
			(bgLumInit < 215f || flankMeanLum <= 198f) &&
				flankWhiteRatio <= 0.48f &&
				flankMeanLum <= 212f &&
				coreWhiteRatio in 0.05f..0.46f &&
				whiteCoreCount >= 24 &&
				whiteTouchesDarkOutline >= 24 &&
				(bgLumInit < 178f || brighterCount >= 24 || flankMeanLum <= 198f)

		internal fun shouldSuppressComponentForSibling(
			ax0: Int,
			ay0: Int,
			ax1: Int,
			ay1: Int,
			absSiblings: List<AbsSiblingBox>,
			requireHeavyAreaOverlap: Boolean,
		): Boolean {
			if (absSiblings.isEmpty()) return false
			val cx = (ax0 + ax1) * 0.5f
			val cy = (ay0 + ay1) * 0.5f
			val cH = (ay1 - ay0).coerceAtLeast(1)
			val compArea = max(1, (ax1 - ax0) * cH)
			for (i in absSiblings.indices) {
				val s = absSiblings[i]
				if (!requireHeavyAreaOverlap && cx >= s.x0 - 2f && cx <= s.x1 + 2f && cy >= s.y0 - 2f && cy <= s.y1 + 2f) {
					return true
				}
				val ox = min(ax1, s.x1) - max(ax0, s.x0)
				val oy = min(ay1, s.y1) - max(ay0, s.y0)
				if (ox > 0 && oy > 0) {
					val ratio = (ox * oy).toFloat() / compArea
					if (!requireHeavyAreaOverlap) {
						if (ratio >= 0.32f) return true
					} else {
						val sH = (s.y1 - s.y0).coerceAtLeast(1)
						val sCy = (s.y0 + s.y1) * 0.5f
						// Ignore oversized/tilted diagonal sibling bounds whose center sits far away from this glyph.
						val isDistantOversizedSibling = sH > max(cH * 2.5f, 96f) &&
							abs(sCy - cy) > max(cH * 1.5f, sH * 0.25f)
						if (isDistantOversizedSibling && ratio < 0.90f) continue
						val verticallyContained = ay0 >= s.y0 - 2 && ay1 <= s.y1 + 2
						if (ratio >= 0.85f || (verticallyContained && ratio >= 0.55f)) return true
					}
				}
			}
			return false
		}
	}
}
