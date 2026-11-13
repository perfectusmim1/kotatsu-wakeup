package org.koitharu.kotatsu.reader.domain.translate.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleInpainterBackgroundTest {

	private fun argb(r: Int, g: Int, b: Int): Int =
		(0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

	@Test
	fun boldWhiteGlyphsOnDarkCrimsonGradientSelectCrimsonBackground() {
		val ww = 160
		val wh = 120
		val crimsonShades = intArrayOf(
			argb(0x48, 0x08, 0x08),
			argb(0x58, 0x08, 0x08),
			argb(0x68, 0x08, 0x08),
			argb(0x78, 0x08, 0x18),
			argb(0x88, 0x08, 0x18),
			argb(0x98, 0x08, 0x28),
		)
		val pixels = IntArray(ww * wh) { idx ->
			val x = idx % ww
			val y = idx / ww
			crimsonShades[((x / 6) + (y / 6)) % crimsonShades.size]
		}
		val line1 = BubbleInpainter.LineCoreRect(x0 = 24, x1 = 136, y0 = 24, y1 = 50)
		val line2 = BubbleInpainter.LineCoreRect(x0 = 24, x1 = 136, y0 = 62, y1 = 88)
		// Fill thick pure white glyph blocks inside both line cores so 0xFFF has huge raw bin density.
		for (lc in listOf(line1, line2)) {
			for (y in (lc.y0 + 3) until (lc.y1 - 3)) {
				for (x in (lc.x0 + 4) until (lc.x1 - 4)) {
					if ((x - lc.x0) % 12 < 8) {
						pixels[y * ww + x] = argb(255, 255, 255)
					}
				}
			}
		}

		val bg = BubbleInpainter.findDominantBackgroundRgb(
			pixels = pixels,
			ww = ww,
			wh = wh,
			cx0 = 24,
			cy0 = 24,
			cx1 = 136,
			cy1 = 88,
			lineCores = listOf(line1, line2),
		)
		val lum = PixelColorMath.luminance(bg[0], bg[1], bg[2])
		assertTrue(
			"Expected dark crimson background (< 90 lum), got rgb=(${bg[0]}, ${bg[1]}, ${bg[2]}) lum=$lum",
			lum < 90f && bg[0] > bg[1] + 30,
		)
	}

	@Test
	fun boldWhiteGlyphsOnDarkSceneDoNotInvertToWhite() {
		val ww = 140
		val wh = 90
		val darkPalette = intArrayOf(
			argb(18, 22, 34),
			argb(26, 30, 42),
			argb(34, 38, 52),
		)
		val pixels = IntArray(ww * wh) { idx ->
			val x = idx % ww
			val y = idx / ww
			darkPalette[((x / 5) + (y / 5)) % darkPalette.size]
		}
		val core = BubbleInpainter.LineCoreRect(x0 = 20, x1 = 120, y0 = 26, y1 = 64)
		for (y in 28 until 62) {
			for (x in 24 until 116) {
				if ((x - 24) % 9 < 6) {
					pixels[y * ww + x] = argb(255, 255, 255)
				}
			}
		}

		val bg = BubbleInpainter.findDominantBackgroundRgb(
			pixels = pixels,
			ww = ww,
			wh = wh,
			cx0 = 20,
			cy0 = 26,
			cx1 = 120,
			cy1 = 64,
			lineCores = listOf(core),
		)
		val lum = PixelColorMath.luminance(bg[0], bg[1], bg[2])
		assertTrue(
			"Expected dark background (< 70 lum), got rgb=(${bg[0]}, ${bg[1]}, ${bg[2]}) lum=$lum",
			lum < 70f,
		)
	}

	@Test
	fun blackTextInWhiteBubblePreservesWhiteBackground() {
		val ww = 140
		val wh = 90
		val pixels = IntArray(ww * wh) { argb(255, 255, 255) }
		val core = BubbleInpainter.LineCoreRect(x0 = 24, x1 = 116, y0 = 28, y1 = 62)
		for (y in 32 until 58) {
			for (x in 28 until 112) {
				if ((x - 28) % 8 < 5) {
					pixels[y * ww + x] = argb(12, 12, 12)
				}
			}
		}

		val bg = BubbleInpainter.findDominantBackgroundRgb(
			pixels = pixels,
			ww = ww,
			wh = wh,
			cx0 = 24,
			cy0 = 28,
			cx1 = 116,
			cy1 = 62,
			lineCores = listOf(core),
		)
		val lum = PixelColorMath.luminance(bg[0], bg[1], bg[2])
		assertTrue(
			"Expected white bubble background (>= 225 lum), got rgb=(${bg[0]}, ${bg[1]}, ${bg[2]}) lum=$lum",
			lum >= 225f,
		)
	}

	@Test
	fun whiteFillDarkOutlineOverLightOrMidSceneTriggersLightFirst() {
		assertTrue(
			"Expected white-fill outline text over mid jacket even when distant white sky skews bgLumInit=248",
			BubbleInpainter.isWhiteFillDarkOutlineMidScene(
				bgLumInit = 248f,
				flankMeanLum = 167.5f,
				flankWhiteRatio = 0.356f,
				coreWhiteRatio = 0.364f,
				whiteCoreCount = 5469,
				whiteTouchesDarkOutline = 3672,
				brighterCount = 0,
			),
		)
		assertTrue(
			"Expected white-fill outline text over brown hair/glow even when bgLumInit=248",
			BubbleInpainter.isWhiteFillDarkOutlineMidScene(
				bgLumInit = 248f,
				flankMeanLum = 129.8f,
				flankWhiteRatio = 0.124f,
				coreWhiteRatio = 0.374f,
				whiteCoreCount = 3833,
				whiteTouchesDarkOutline = 2590,
				brighterCount = 0,
			),
		)
		assertFalse(
			"Expected black text in white speech bubble NOT to trigger light-first",
			BubbleInpainter.isWhiteFillDarkOutlineMidScene(
				bgLumInit = 255f,
				flankMeanLum = 255f,
				flankWhiteRatio = 1.0f,
				coreWhiteRatio = 0.49f,
				whiteCoreCount = 5590,
				whiteTouchesDarkOutline = 3693,
				brighterCount = 0,
			),
		)
	}

	@Test
	fun ownCoreGlyphNotSuppressedByDistantDiagonalSiblingBox() {
		val diagonalSibling = BubbleInpainter.AbsSiblingBox(x0 = 0, y0 = 1887, x1 = 506, y1 = 2229)
		assertFalse(
			"Own line-core glyph partially grazed at top edge by tilted diagonal sibling must be erased",
			BubbleInpainter.shouldSuppressComponentForSibling(
				ax0 = 202,
				ay0 = 1873,
				ax1 = 222,
				ay1 = 1907,
				absSiblings = listOf(diagonalSibling),
				requireHeavyAreaOverlap = true,
			),
		)
		val overlappingHorizontalSibling = BubbleInpainter.AbsSiblingBox(x0 = 150, y0 = 1870, x1 = 460, y1 = 1912)
		assertTrue(
			"Component genuinely contained inside an overlapping horizontal sibling line should stay protected",
			BubbleInpainter.shouldSuppressComponentForSibling(
				ax0 = 202,
				ay0 = 1873,
				ax1 = 222,
				ay1 = 1907,
				absSiblings = listOf(overlappingHorizontalSibling),
				requireHeavyAreaOverlap = true,
			),
		)
	}
}
