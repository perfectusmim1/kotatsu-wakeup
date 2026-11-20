package org.koitharu.kotatsu.reader.domain.upscale

import org.koitharu.kotatsu.reader.domain.upscale.model.StripPlan
import org.koitharu.kotatsu.reader.domain.upscale.model.StripSizeResult
import org.koitharu.kotatsu.reader.domain.upscale.model.TilePlan
import org.koitharu.kotatsu.reader.domain.upscale.model.UpscaleMode
import kotlin.math.min
import kotlin.math.roundToInt

object StripPlanner {

	const val STRIP_HEIGHT = 1024
	const val STRIP_OVERLAP = 16
	const val AI_STRIP_HEIGHT = 496
	const val AI_STRIP_OVERLAP = 8
	const val AI_TILE_SIZE = 128
	const val AI_TILE_OVERLAP = 8
	const val AI_BASE_INPUT_WIDTH = 768
	const val AI_MAX_OUTPUT_WIDTH = 2560
	private const val AI_HIGH_RES_INPUT_RATIO = 0.65f
	const val DEFAULT_MAX_TEX_SIZE = 4096

	fun planStripsForMode(
		height: Int,
		mode: UpscaleMode,
	): List<StripPlan> = if (mode == UpscaleMode.AI) {
		planStrips(height = height, stripHeight = AI_STRIP_HEIGHT, overlap = AI_STRIP_OVERLAP)
	} else {
		planStrips(height = height, stripHeight = STRIP_HEIGHT, overlap = STRIP_OVERLAP)
	}

	/**
	 * Uzun dikey webtoon ve manga sayfalarını OOM veya GPU doku sınırına takılmaması için
	 * örtüşmeli dikey şeritlere böler.
	 */
	fun planStrips(
		height: Int,
		stripHeight: Int = STRIP_HEIGHT,
		overlap: Int = STRIP_OVERLAP,
	): List<StripPlan> {
		val safeHeight = height.coerceAtLeast(0)
		if (safeHeight <= 0) return emptyList()
		val step = stripHeight.coerceAtLeast(1)
		val pad = overlap.coerceAtLeast(0)

		val strips = ArrayList<StripPlan>((safeHeight + step - 1) / step)
		var index = 0
		var validY0 = 0
		while (validY0 < safeHeight) {
			val validY1 = min(validY0 + step, safeHeight)
			val y0 = (validY0 - pad).coerceAtLeast(0)
			val y1 = min(safeHeight, validY1 + pad)
			strips.add(
				StripPlan(
					index = index,
					y0 = y0,
					y1 = y1,
					validY0 = validY0,
					validY1 = validY1,
					cropTop = validY0 - y0,
					cropBottom = y1 - validY1,
				),
			)
			index += 1
			validY0 += step
		}
		return strips
	}

	/**
	 * Bir şeridi ONNX Runtime bellek tüketimini sınırlamak için sabit örtüşmeli karolara böler.
	 */
	fun planTiles(
		width: Int,
		height: Int,
		tileSize: Int = AI_TILE_SIZE,
		overlap: Int = AI_TILE_OVERLAP,
	): List<TilePlan> {
		val safeW = width.coerceAtLeast(0)
		val safeH = height.coerceAtLeast(0)
		if (safeW <= 0 || safeH <= 0) return emptyList()
		val step = tileSize.coerceAtLeast(1)
		val pad = overlap.coerceAtLeast(0)

		val cols = (safeW + step - 1) / step
		val rows = (safeH + step - 1) / step
		val tiles = ArrayList<TilePlan>(cols * rows)

		var ty0 = 0
		while (ty0 < safeH) {
			val ty1 = min(ty0 + step, safeH)
			var tx0 = 0
			while (tx0 < safeW) {
				val tx1 = min(tx0 + step, safeW)
				tiles.add(TilePlan(tx0 = tx0, ty0 = ty0, tx1 = tx1, ty1 = ty1, pad = pad))
				tx0 += step
			}
			ty0 += step
		}
		return tiles
	}

	/**
	 * Akıllı atlama: Kaynak görsel zaten ekrandaki fiziksel piksel genişliğine eşit veya daha büyükse
	 * ve "yüksek çözünürlüklü sayfaları da işle" kapalıysa sayfa atlanır.
	 */
	fun shouldSkipHighResPage(
		sourceWidth: Int,
		screenWidthPx: Int,
		upscaleHighRes: Boolean,
	): Boolean {
		if (upscaleHighRes) return false
		if (sourceWidth <= 0 || screenWidthPx <= 0) return false
		return sourceWidth >= screenWidthPx
	}

	/**
	 * Şerit giriş ve 2x çıkış piksel boyutlarını hesaplar; GPU doku sınırını (maxTexSize) aşmaz.
	 * AI modunda yüksek genişlikli sayfalarda bellek ve hız dengesi için giriş genişliği akıllıca
	 * optimize edilir, ancak çıkış çözünürlüğü hiçbir zaman orijinal sayfa genişliğinin altına düşmez.
	 */
	fun computeStripOutputSize(
		srcW: Int,
		validH: Int,
		maxTexSize: Int = DEFAULT_MAX_TEX_SIZE,
		mode: UpscaleMode = UpscaleMode.FAST,
	): StripSizeResult {
		val safeSrcW = srcW.coerceAtLeast(1)
		val safeValidH = validH.coerceAtLeast(1)
		val safeMaxTex = maxTexSize.coerceAtLeast(256)
		val maxInputW = (safeMaxTex / 2).coerceAtLeast(1)

		val inputWidth = if (mode == UpscaleMode.AI) {
			val aiMaxInputW = min(maxInputW, (AI_MAX_OUTPUT_WIDTH / 2).coerceAtLeast(1))
			if (safeSrcW <= AI_BASE_INPUT_WIDTH) {
				min(safeSrcW, aiMaxInputW)
			} else {
				val scaledTarget = (safeSrcW * AI_HIGH_RES_INPUT_RATIO).roundToInt()
				val desired = maxOf(AI_BASE_INPUT_WIDTH, scaledTarget)
				min(safeSrcW, min(desired, aiMaxInputW))
			}
		} else {
			min(safeSrcW, maxInputW)
		}.coerceAtLeast(1)
		val inputScale = inputWidth.toFloat() / safeSrcW.toFloat()

		val effectiveMaxOut = if (mode == UpscaleMode.AI) min(safeMaxTex, AI_MAX_OUTPUT_WIDTH) else safeMaxTex
		val outWidth = min(inputWidth * 2, effectiveMaxOut).coerceAtLeast(1)
		val scaleFromOriginal = outWidth.toFloat() / safeSrcW.toFloat()
		val outHeight = min((safeValidH * scaleFromOriginal).roundToInt(), safeMaxTex).coerceAtLeast(1)

		return StripSizeResult(
			inputWidth = inputWidth,
			inputScale = inputScale,
			outWidth = outWidth,
			outHeight = outHeight,
		)
	}

	fun runSelfCheck() {
		for (h in intArrayOf(1, 500, 1024, 1025, 2048, 15000)) {
			val strips = planStrips(h)
			check(strips.isNotEmpty()) { "expected strips for h=$h" }
			check(strips.first().validY0 == 0) { "first strip validY0 != 0 for h=$h" }
			check(strips.last().validY1 == h) { "last strip validY1 != $h" }
			for (i in strips.indices) {
				val s = strips[i]
				check(s.index == i) { "strip index mismatch at $i" }
				check(s.y0 >= 0 && s.y1 <= h) { "strip bounds out of range at $i" }
				check(s.validY0 >= s.y0 && s.validY1 <= s.y1) { "valid bounds outside strip at $i" }
				check(s.validY1 > s.validY0) { "empty valid strip at $i" }
				check(s.cropTop == s.validY0 - s.y0) { "cropTop mismatch at $i" }
				check(s.cropBottom == s.y1 - s.validY1) { "cropBottom mismatch at $i" }
				check(s.y1 - s.y0 <= STRIP_HEIGHT + STRIP_OVERLAP * 2) { "strip too tall at $i" }
				if (i > 0) {
					check(s.validY0 == strips[i - 1].validY1) { "gap or overlap in valid strips at $i" }
				}
			}
		}

		val tileDims = arrayOf(17 to 33, 256 to 256, 800 to 1056, 512 to 1024)
		for ((w, h) in tileDims) {
			val tiles = planTiles(w, h)
			check(tiles.isNotEmpty()) { "expected tiles for ${w}x${h}" }
			var coveredArea = 0
			for (t in tiles) {
				check(t.tx0 >= 0 && t.ty0 >= 0 && t.tx1 <= w && t.ty1 <= h) { "tile bounds invalid" }
				check(t.tx1 > t.tx0 && t.ty1 > t.ty0) { "empty tile" }
				check(t.tx1 - t.tx0 <= AI_TILE_SIZE && t.ty1 - t.ty0 <= AI_TILE_SIZE) { "tile too large" }
				coveredArea += (t.tx1 - t.tx0) * (t.ty1 - t.ty0)
			}
			check(coveredArea == w * h) { "tile area $coveredArea != ${w * h}" }
		}

		check(shouldSkipHighResPage(1200, 1080, false)) { "1200 >= 1080 should skip" }
		check(!shouldSkipHighResPage(720, 1080, false)) { "720 < 1080 should process" }
		check(!shouldSkipHighResPage(1200, 1080, true)) { "upscaleHighRes override failed" }

		val wide = computeStripOutputSize(5000, 1024, 4096)
		check(wide.inputWidth <= 2048) { "wide input must cap at maxTexSize/2" }
		check(wide.outWidth <= 4096 && wide.outHeight <= 4096) { "output must stay within maxTexSize" }

		for (srcW in intArrayOf(600, 720, 800, 1080, 1440, 2000)) {
			val aiSize = computeStripOutputSize(srcW, AI_STRIP_HEIGHT, DEFAULT_MAX_TEX_SIZE, UpscaleMode.AI)
			check(aiSize.outWidth > srcW) { "AI output width ${aiSize.outWidth} must exceed source width $srcW" }
			check(aiSize.outWidth <= AI_MAX_OUTPUT_WIDTH) { "AI output width must cap at $AI_MAX_OUTPUT_WIDTH" }
		}
	}
}
