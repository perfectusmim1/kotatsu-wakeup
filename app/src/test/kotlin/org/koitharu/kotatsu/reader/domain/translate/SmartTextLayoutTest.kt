package org.koitharu.kotatsu.reader.domain.translate

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.reader.domain.translate.render.RenderTextHelpers
import org.koitharu.kotatsu.reader.domain.translate.render.SmartTextLayout

class SmartTextLayoutTest {

	private val proportionalMeasure: (String, Float) -> Float = { text, fontPx ->
		text.length * fontPx * 0.5f
	}

	@Test
	fun `formatScanlationText uses Turkish locale casing and collapses dialogue breaks`() {
		val formatted = SmartTextLayout.formatScanlationText(
			text = "iyileşme   iksiri\nhazır   mı?",
			targetLang = "tr",
		)
		assertEquals("İYİLEŞME İKSİRİ HAZIR MI?", formatted)
	}

	@Test
	fun `formatScanlationText preserves explicit newlines for multi-line RPG stats`() {
		val raw = "  Ad: Sung Jinwoo \r\n Seviye: 99\nCan: +2500 "
		val formatted = SmartTextLayout.formatScanlationText(raw, targetLang = "tr")
		assertEquals("AD: SUNG JİNWOO\nSEVİYE: 99\nCAN: +2500", formatted)
	}

	@Test
	fun `isRpgStatLine separates genuine stat bars from dialogue with colons`() {
		assertTrue(RenderTextHelpers.isRpgStatLine("HEALTH: 100"))
		assertTrue(RenderTextHelpers.isRpgStatLine("NAME: JIANG CHENG"))
		assertTrue(RenderTextHelpers.isRpgStatLine("ATK: +45"))
		assertTrue(RenderTextHelpers.isRpgStatLine("GÜÇ PUANI: 1200"))

		assertFalse(RenderTextHelpers.isRpgStatLine("CHICKITO: OH DAMN,"))
		assertFalse(RenderTextHelpers.isRpgStatLine("AHMET: YES SIR"))
		assertFalse(RenderTextHelpers.isRpgStatLine("AYŞE: NE DEDİN?"))
		assertFalse(RenderTextHelpers.isRpgStatLine("MEHMET: BUNU YAPMALI MIYIZ!"))
	}

	@Test
	fun `splitOversizedWordBySyllable leaves short words intact and hyphenates long Turkish words`() {
		val measure: (String) -> Float = { it.length * 10f }

		assertEquals(
			listOf("MERHABA"),
			SmartTextLayout.splitOversizedWordBySyllable("MERHABA", maxWidthPx = 50f, measure),
		)

		assertEquals(
			listOf("KAHRAMAN-", "LIK"),
			SmartTextLayout.splitOversizedWordBySyllable("KAHRAMAN-LIK", maxWidthPx = 100f, measure),
		)

		val parts = SmartTextLayout.splitOversizedWordBySyllable(
			word = "KAHRAMANLARIMIZ",
			maxWidthPx = 100f,
			measureWidth = measure,
		)
		assertEquals(listOf("KAHRAMAN-", "LARIMIZ"), parts)
		assertEquals("KAHRAMANLARIMIZ", parts.joinToString("").replace("-", ""))
	}

	@Test
	fun `computeSmartWrappedLines DP avoids orphan short last line`() {
		val result = SmartTextLayout.computeSmartWrappedLines(
			text = "AAAAA BBBBB CCCCC DD",
			maxWidthPx = 180f,
			fontPx = 20f,
			measureScaledWidth = proportionalMeasure,
		)

		assertEquals(listOf("AAAAA BBBBB", "CCCCC DD"), result.lines.map { it.text })
		assertFalse(result.hasOverflow)
	}

	@Test
	fun `computeSmartWrappedLines respects explicit oval contour lineWidthsPct`() {
		val result = SmartTextLayout.computeSmartWrappedLines(
			text = "AAAA BBBB CCCC DDDD EEEE FFFF GGGG",
			maxWidthPx = 150f,
			fontPx = 20f,
			lineWidthsPct = listOf(0.65f, 1.0f, 0.65f),
			measureScaledWidth = proportionalMeasure,
		)

		assertEquals(
			listOf("AAAA BBBB", "CCCC DDDD EEEE", "FFFF GGGG"),
			result.lines.map { it.text },
		)
	}

	@Test
	fun `computeSmartWrappedLines applies emphasisLastLineScale and computes accurate totalHeightPx`() {
		val fontPx = 20f
		val ratio = 1.13f
		val scale = 1.35f

		val emphasized = SmartTextLayout.computeSmartWrappedLines(
			text = "HEMEN BURADAN UZAKLAŞ YOK OL",
			maxWidthPx = 400f,
			fontPx = fontPx,
			emphasisLastLineScale = scale,
			lineHeightRatio = ratio,
			measureScaledWidth = proportionalMeasure,
		)

		assertEquals(listOf("HEMEN BURADAN UZAKLAŞ", "YOK OL"), emphasized.lines.map { it.text })
		assertEquals(1f, emphasized.lines[0].scale, 0.001f)
		assertEquals(scale, emphasized.lines[1].scale, 0.001f)
		val expectedHeight = fontPx * 1f * ratio + fontPx * scale * ratio
		assertTrue(abs(emphasized.totalHeightPx - expectedHeight) < 0.01f)
		assertEquals(210f, emphasized.maxLineWidthPx, 0.01f)

		val singleWordLast = SmartTextLayout.computeSmartWrappedLines(
			text = "ARTIK DURMANIN TAM ZAMANI",
			maxWidthPx = 400f,
			fontPx = fontPx,
			emphasisLastLineScale = scale,
			lineHeightRatio = ratio,
			measureScaledWidth = proportionalMeasure,
		)
		assertEquals(listOf("ARTIK DURMANIN TAM", "ZAMANI"), singleWordLast.lines.map { it.text })

		val unemphasized = SmartTextLayout.computeSmartWrappedLines(
			text = "HEMEN BURADAN UZAKLAŞ YOK OL",
			maxWidthPx = 400f,
			fontPx = fontPx,
			emphasisLastLineScale = 1f,
			lineHeightRatio = ratio,
			measureScaledWidth = proportionalMeasure,
		)
		assertEquals(listOf("HEMEN BURADAN UZAKLAŞ YOK OL"), unemphasized.lines.map { it.text })
		assertTrue(abs(unemphasized.totalHeightPx - fontPx * ratio) < 0.01f)
	}
}
