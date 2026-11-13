package org.koitharu.kotatsu.reader.domain.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.reader.domain.translate.model.NormalizedBox
import org.koitharu.kotatsu.reader.domain.translate.model.OcrBubbleGroup
import org.koitharu.kotatsu.reader.domain.translate.model.OcrLine
import org.koitharu.kotatsu.reader.domain.translate.model.TextBlockKind
import org.koitharu.kotatsu.reader.domain.translate.ocr.PaddleOcrEngine
import org.koitharu.kotatsu.reader.domain.translate.ocr.RawOcrLine
import org.koitharu.kotatsu.reader.domain.translate.ocr.TextBlockFilters

class OcrBubbleGroupingAndFiltersTest {

	@Test
	fun `cleanOcrText repairs misread apostrophes and strips edge noise`() {
		assertEquals("don't", TextBlockFilters.cleanOcrText("don\"t"))
		assertEquals("I'm", TextBlockFilters.cleanOcrText("I\"m"))
		assertEquals("i'm", TextBlockFilters.cleanOcrText("i\"m"))
		assertEquals("we'll you're they've he'd it's", TextBlockFilters.cleanOcrText("we\"ll you\"re they\"ve he\"d it\"s"))
		assertEquals("o'clock", TextBlockFilters.cleanOcrText("o\"clock"))
		assertEquals("\"Hello,\" he said", TextBlockFilters.cleanOcrText("\"Hello,\" he said"))
		assertEquals("Oh nO!", TextBlockFilters.cleanOcrText("| <unk> 0h n0! /"))
		assertEquals("MURD-", TextBlockFilters.cleanOcrText("MURD-\""))
	}

	@Test
	fun `isUsableOcrLine applies ToonParrot confidence tiers`() {
		assertTrue(TextBlockFilters.isUsableOcrLine(line("NO!", conf = 42f)))
		assertFalse(TextBlockFilters.isUsableOcrLine(line("NO!", conf = 38f)))

		assertTrue(TextBlockFilters.isUsableOcrLine(line("ALONE?", conf = 50f)))
		assertFalse(TextBlockFilters.isUsableOcrLine(line("BCDF", conf = 50f)))
		assertTrue(TextBlockFilters.isUsableOcrLine(line("BCDF", conf = 58f)))

		assertFalse(TextBlockFilters.isUsableOcrLine(line("AAA", conf = 60f)))
		assertFalse(TextBlockFilters.isUsableOcrLine(line("A | J 7", conf = 95f)))
		assertFalse(TextBlockFilters.isUsableOcrLine(line("X Q Z", conf = 95f)))
	}

	@Test
	fun `isPureSoundEffect preserves short exclamations and non-Latin dialogue while filtering SFX`() {
		// Meaningful exclamations MUST NEVER be skipped
		assertFalse(TextBlockFilters.isPureSoundEffect("NO!"))
		assertFalse(TextBlockFilters.isPureSoundEffect("YES"))
		assertFalse(TextBlockFilters.isPureSoundEffect("STOP"))
		assertFalse(TextBlockFilters.isPureSoundEffect("HELP"))
		assertFalse(TextBlockFilters.isPureSoundEffect("ALONE?"))
		assertFalse(TextBlockFilters.isPureSoundEffect("WHY?"))
		assertFalse(TextBlockFilters.isPureSoundEffect("WAIT!"))

		// Non-ASCII dialogue MUST NEVER be mistaken for empty ASCII SFX
		assertFalse(TextBlockFilters.isPureSoundEffect("大丈夫"))
		assertFalse(TextBlockFilters.isPureSoundEffect("뭐해?"))
		assertFalse(TextBlockFilters.isPureSoundEffect("Что?!"))
		assertFalse(TextBlockFilters.isPureSoundEffect("نعم"))

		// Single repeated-only word alone is preserved by isPureSoundEffect
		assertFalse(TextBlockFilters.isPureSoundEffect("TAP"))

		// Pure sound effects & repeated SFX are eliminated
		assertTrue(TextBlockFilters.isPureSoundEffect("BOOM!"))
		assertTrue(TextBlockFilters.isPureSoundEffect("BAM"))
		assertTrue(TextBlockFilters.isPureSoundEffect("CRACK"))
		assertTrue(TextBlockFilters.isPureSoundEffect("WHOOSH"))
		assertTrue(TextBlockFilters.isPureSoundEffect("SWOOOOSH"))
		assertTrue(TextBlockFilters.isPureSoundEffect("SLAM"))
		assertTrue(TextBlockFilters.isPureSoundEffect("CLANK"))
		assertTrue(TextBlockFilters.isPureSoundEffect("THUD"))
		assertTrue(TextBlockFilters.isPureSoundEffect("GASP"))
		assertTrue(TextBlockFilters.isPureSoundEffect("GRIN"))
		assertTrue(TextBlockFilters.isPureSoundEffect("SMIRK"))
		assertTrue(TextBlockFilters.isPureSoundEffect("SHIVER"))
		assertTrue(TextBlockFilters.isPureSoundEffect("STARE"))
		assertTrue(TextBlockFilters.isPureSoundEffect("RUSTLE"))
		assertTrue(TextBlockFilters.isPureSoundEffect("CREAK"))
		assertTrue(TextBlockFilters.isPureSoundEffect("SQUEAK"))
		assertTrue(TextBlockFilters.isPureSoundEffect("KWAANG!"))
		assertTrue(TextBlockFilters.isPureSoundEffect("BA-DUMP"))
		assertTrue(TextBlockFilters.isPureSoundEffect("CLANG CRASH"))
		assertTrue(TextBlockFilters.isPureSoundEffect("TAP TAP"))
		assertTrue(TextBlockFilters.isPureSoundEffect("STEP STEP STEP"))
		assertTrue(TextBlockFilters.isPureSoundEffect("TICK TOCK"))
		assertTrue(TextBlockFilters.isPureSoundEffect("GRRRRR"))
		assertTrue(TextBlockFilters.isPureSoundEffect("ÇAT!"))
	}

	@Test
	fun `isRpgStatLine distinguishes RPG stat rows from dialogue colons`() {
		assertTrue(TextBlockFilters.isRpgStatLine("HEALTH: 100"))
		assertTrue(TextBlockFilters.isRpgStatLine("NAME: JIANG CHENG"))
		assertTrue(TextBlockFilters.isRpgStatLine("LVL: 1"))
		assertTrue(TextBlockFilters.isRpgStatLine("MAGIC: 0"))
		assertTrue(TextBlockFilters.isRpgStatLine("RANK: S-CLASS"))

		assertFalse(TextBlockFilters.isRpgStatLine("CHICKITO: OH DAMN,"))
		assertFalse(TextBlockFilters.isRpgStatLine("CHICKITO: OH DAMN"))
		assertFalse(TextBlockFilters.isRpgStatLine("JOHN: NO WAY"))
		assertFalse(TextBlockFilters.isRpgStatLine("BOB: What are you doing?"))
		assertFalse(TextBlockFilters.isRpgStatLine("NPC: YES SIR"))
	}

	@Test
	fun `isCreditOrPromoText and isCreditsOrPromoPage detect scanlators watermarks and promo pages`() {
		assertTrue(TextBlockFilters.isCreditOrPromoText("Disaster Scans"))
		assertTrue(TextBlockFilters.isCreditOrPromoText("Surya Scans"))
		assertTrue(TextBlockFilters.isCreditOrPromoText("Flame-chan"))
		assertTrue(TextBlockFilters.isCreditOrPromoText("dsns"))
		assertTrue(TextBlockFilters.isCreditOrPromoText("Dssns"))
		assertTrue(TextBlockFilters.isCreditOrPromoText("Join our Discord: https://discord.gg/abc"))
		assertFalse(TextBlockFilters.isCreditOrPromoText("Why did you come up alone?"))

		val creditsPage = listOf(
			bubble("b0", "AsuraScans.com"),
			bubble("b1", "Translator: Alice"),
			bubble("b2", "Proofreader: Bob"),
			bubble("b3", "Typesetter: Carol"),
		)
		assertTrue(TextBlockFilters.isCreditsOrPromoPage(creditsPage))
		assertTrue(TextBlockFilters.filterBubbles(creditsPage, skipPureSfx = true, filterPromoAndCredits = true).isEmpty())

		val mixedNormalPage = listOf(
			bubble("b0", "I am an editor at a magazine."),
			bubble("b1", "STOP RIGHT THERE!"),
			bubble("b2", "Dssns"),
			bubble("b3", "BOOM!"),
		)
		assertFalse(TextBlockFilters.isCreditsOrPromoPage(mixedNormalPage))
		val filteredNormal = TextBlockFilters.filterBubbles(mixedNormalPage, skipPureSfx = true, filterPromoAndCredits = true)
		assertEquals(listOf("I am an editor at a magazine.", "STOP RIGHT THERE!"), filteredNormal.map { it.text })
	}

	@Test
	fun `extractDetBoxes uses 0_50 threshold and ToonParrot unclip margins`() {
		val w = 30
		val h = 30
		val prob = FloatArray(w * h)
		// Component A: 4x3 with probability 0.52 (passes 0.50, would fail 0.58)
		for (y in 5..7) {
			for (x in 5..8) {
				prob[y * w + x] = 0.52f
			}
		}
		// Component B: 4x3 with probability 0.45 (fails 0.50)
		for (y in 18..20) {
			for (x in 5..8) {
				prob[y * w + x] = 0.45f
			}
		}

		val boxes = PaddleOcrEngine.extractDetBoxes(prob, w, h)
		assertEquals(1, boxes.size)
		val box = boxes[0]
		// bw = 4, bh = 3 -> d = (12 * 1.78) / 14 = 1.5257 -> dx = max(1.95, 1.08), dy = max(1.60, 0.66)
		assertTrue(box.x0 < 3.5f)
		assertTrue(box.x1 > 10.5f)
		assertTrue(box.y0 < 4.0f)
		assertTrue(box.y1 > 9.0f)
	}

	@Test
	fun `groupLinesIntoBubbles separates system box headers but keeps normal dialogue bubbles together`() {
		val lines = listOf(
			// System header (taller, <=3 words) above long body -> MUST stay separate
			RawOcrLine("SYSTEM PROMPT", 95f, x0 = 100f, y0 = 40f, x1 = 300f, y1 = 80f), // h=40, w=200
			RawOcrLine("YOU REQUIRE 90 MORE EVOLUTION POINTS BEFORE LEVEL UP", 95f, x0 = 60f, y0 = 92f, x1 = 380f, y1 = 118f), // h=26, w=320

			// Normal speech bubble with larger short top line above wider body -> MUST stay merged!
			RawOcrLine("WAIT!", 95f, x0 = 120f, y0 = 300f, x1 = 220f, y1 = 336f), // h=36, w=100
			RawOcrLine("DON'T GO IN THERE ALONE RIGHT NOW!", 95f, x0 = 80f, y0 = 344f, x1 = 360f, y1 = 370f), // h=26, w=280
		)

		val bubbles = PaddleOcrEngine.groupLinesIntoBubbles(lines, imgWidth = 500, imgHeight = 500)
		assertEquals(3, bubbles.size)
		assertEquals("SYSTEM PROMPT", bubbles[0].text)
		assertEquals("YOU REQUIRE 90 MORE EVOLUTION POINTS BEFORE LEVEL UP", bubbles[1].text)
		assertEquals("WAIT! DON'T GO IN THERE ALONE RIGHT NOW!", bubbles[2].text)
	}

	@Test
	fun `groupLinesIntoBubbles separates twin bubbles with pixel barrier and filters faint gray watermark`() {
		val width = 400
		val height = 300
		val white = argb(255, 255, 255)
		val black = argb(15, 15, 15)
		val faintGray = argb(200, 200, 200)
		val pixels = IntArray(width * height) { white }

		// Left bubble text dark strokes inside [30..140, 40..64], right bubble [149..280, 40..64] (hGap=9 <= 24*0.45)
		fillRect(pixels, width, height, 35, 45, 130, 58, black)
		fillRect(pixels, width, height, 155, 45, 270, 58, black)
		// Dark bubble border wall between x=144..145 across y=20..85
		fillRect(pixels, width, height, 144, 20, 145, 85, black)

		// Vertical twin bubble barrier: top [60..200, 120..142], bottom [60..200, 156..178], dark wall at y=148..150
		fillRect(pixels, width, height, 65, 124, 190, 138, black)
		fillRect(pixels, width, height, 65, 160, 190, 174, black)
		fillRect(pixels, width, height, 50, 148, 210, 150, black)

		// Faint gray watermark on white background at [60..220, 220..250] (lum ~200 >= 175)
		fillRect(pixels, width, height, 65, 224, 215, 246, faintGray)

		val lines = listOf(
			RawOcrLine("LOOK,", 94f, x0 = 30f, y0 = 40f, x1 = 140f, y1 = 64f),
			RawOcrLine("IF YOU CAN", 94f, x0 = 149f, y0 = 40f, x1 = 280f, y1 = 64f),
			RawOcrLine("TOP BUBBLE", 94f, x0 = 60f, y0 = 120f, x1 = 200f, y1 = 142f),
			RawOcrLine("BOTTOM BUBBLE", 94f, x0 = 60f, y0 = 156f, x1 = 200f, y1 = 178f),
			RawOcrLine("A inane", 90f, x0 = 60f, y0 = 220f, x1 = 220f, y1 = 250f),
		)

		// Without pixel data, tight twin lines merge
		val noPixelsBubbles = PaddleOcrEngine.groupLinesIntoBubbles(lines.dropLast(1), width, height, null)
		assertEquals(2, noPixelsBubbles.size)

		// With pixel barriers, twin bubbles stay separated and faint gray watermark is dropped
		val bubbles = PaddleOcrEngine.groupLinesIntoBubbles(lines, width, height, pixels)
		assertEquals(4, bubbles.size)
		assertEquals("LOOK,", bubbles[0].text)
		assertEquals("IF YOU CAN", bubbles[1].text)
		assertEquals("TOP BUBBLE", bubbles[2].text)
		assertEquals("BOTTOM BUBBLE", bubbles[3].text)
	}

	@Test
	fun `groupLinesIntoBubbles keeps adjacent twin bubbles separate via independent vertical partners`() {
		val lines = listOf(
			// Left column bubble (x=40..140)
			RawOcrLine("FIRST", 92f, x0 = 40f, y0 = 40f, x1 = 140f, y1 = 64f),
			RawOcrLine("BUBBLE", 92f, x0 = 40f, y0 = 70f, x1 = 140f, y1 = 94f),
			// Right column bubble closely adjacent horizontally (x=148..250, hGap=8 <= 24*0.45)
			RawOcrLine("SECOND", 92f, x0 = 148f, y0 = 40f, x1 = 250f, y1 = 64f),
			RawOcrLine("COLUMN", 92f, x0 = 148f, y0 = 70f, x1 = 250f, y1 = 94f),
		)

		val bubbles = PaddleOcrEngine.groupLinesIntoBubbles(lines, imgWidth = 300, imgHeight = 200)
		assertEquals(2, bubbles.size)
		assertEquals("FIRST BUBBLE", bubbles[0].text)
		assertEquals("SECOND COLUMN", bubbles[1].text)
	}

	@Test
	fun `groupLinesIntoBubbles computes emphasisLastLineScale rescues low-conf fragments and keeps dialogue colons`() {
		val lines = listOf(
			// Split word rescue + emphasis last line ("ALONE?" = 36px vs 24px -> 1.50f)
			RawOcrLine("WHY DID YOU", 92f, x0 = 80f, y0 = 40f, x1 = 240f, y1 = 64f),
			RawOcrLine("COME UP", 92f, x0 = 100f, y0 = 70f, x1 = 220f, y1 = 94f),
			RawOcrLine("ALO", 90f, x0 = 95f, y0 = 102f, x1 = 165f, y1 = 138f),
			RawOcrLine("NE?", 38f, x0 = 167f, y0 = 102f, x1 = 230f, y1 = 138f),

			// Dialogue colon should NOT be treated as RPG stat split
			RawOcrLine("CHICKITO: OH DAMN,", 92f, x0 = 80f, y0 = 220f, x1 = 260f, y1 = 244f),
			RawOcrLine("WE ARE DOOMED!", 92f, x0 = 90f, y0 = 250f, x1 = 250f, y1 = 274f),

			// RPG stat lines MUST stay separate
			RawOcrLine("HEALTH: 100", 95f, x0 = 80f, y0 = 340f, x1 = 220f, y1 = 364f),
			RawOcrLine("NAME: JIANG CHENG", 95f, x0 = 80f, y0 = 370f, x1 = 260f, y1 = 394f),
		)

		val bubbles = PaddleOcrEngine.groupLinesIntoBubbles(lines, imgWidth = 400, imgHeight = 450)
		assertEquals(4, bubbles.size)
		assertEquals("WHY DID YOU COME UP ALONE?", bubbles[0].text)
		assertEquals(1.50f, bubbles[0].emphasisLastLineScale, 0.01f)
		assertEquals("CHICKITO: OH DAMN, WE ARE DOOMED!", bubbles[1].text)
		assertEquals(1f, bubbles[1].emphasisLastLineScale, 0.01f)
		assertEquals("HEALTH: 100", bubbles[2].text)
		assertEquals("NAME: JIANG CHENG", bubbles[3].text)
	}

	@Test
	fun `groupLinesIntoBubbles rescues edge-truncated top line and keeps side-by-side columns separate`() {
		val lines = listOf(
			// Edge truncated at y0=1 (conf 35 >= 34)
			RawOcrLine("Esta", 35f, x0 = 120f, y0 = 1f, x1 = 190f, y1 = 21f),
			RawOcrLine("BEBIDA SABE", 92f, x0 = 100f, y0 = 26f, x1 = 230f, y1 = 50f),

			// Side-by-side columns under a wide header ("KIM JEONGHYUK" over "REPARTIDOR" and "31 ANOS")
			RawOcrLine("KIM JEONGHYUK", 95f, x0 = 60f, y0 = 180f, x1 = 340f, y1 = 204f),
			RawOcrLine("REPARTIDOR", 95f, x0 = 60f, y0 = 214f, x1 = 175f, y1 = 236f),
			RawOcrLine("31 ANOS", 95f, x0 = 220f, y0 = 214f, x1 = 340f, y1 = 236f),
		)

		val bubbles = PaddleOcrEngine.groupLinesIntoBubbles(lines, imgWidth = 400, imgHeight = 400)
		assertEquals(4, bubbles.size)
		assertEquals("Esta BEBIDA SABE", bubbles[0].text)
		assertEquals("KIM JEONGHYUK", bubbles[1].text)
		assertEquals("REPARTIDOR", bubbles[2].text)
		assertEquals("31 ANOS", bubbles[3].text)
	}

	@Test
	fun `isAcceptableAutoMatch accepts high-purity English probes down to 38 confidence`() {
		assertTrue(
			PaddleOcrEngine.isAcceptableAutoMatch(
				usableLines = 4,
				avgConfidence = 52.1f,
				validCharRatio = 1.0f,
				scriptPurity = 1.0f,
				totalScriptLetters = 20,
			),
		)
		assertTrue(
			PaddleOcrEngine.isAcceptableAutoMatch(
				usableLines = 2,
				avgConfidence = 39.0f,
				validCharRatio = 0.90f,
				scriptPurity = 0.92f,
				totalScriptLetters = 12,
			),
		)
		assertFalse(
			PaddleOcrEngine.isAcceptableAutoMatch(
				usableLines = 2,
				avgConfidence = 35.0f,
				validCharRatio = 0.90f,
				scriptPurity = 0.92f,
				totalScriptLetters = 12,
			),
		)
	}

	@Test
	fun `det normalization uses BGR ordering and extractDetBoxes detects tilted strip angle`() {
		assertEquals(0.406f, PaddleOcrEngine.DET_MEAN[0], 0.001f)
		assertEquals(0.456f, PaddleOcrEngine.DET_MEAN[1], 0.001f)
		assertEquals(0.485f, PaddleOcrEngine.DET_MEAN[2], 0.001f)
		assertEquals(0.225f, PaddleOcrEngine.DET_STD[0], 0.001f)

		val w = 80
		val h = 60
		val prob = FloatArray(w * h)
		// Draw a slanted strip going up-right (~ -22 degrees in image coordinates)
		for (x in 12..64) {
			val cy = (42f - (x - 12) * 0.40f).toInt()
			for (dy in -2..2) {
				val y = cy + dy
				if (y in 0 until h) {
					prob[y * w + x] = 0.92f
				}
			}
		}
		val boxes = PaddleOcrEngine.extractDetBoxes(prob, w, h)
		assertEquals(1, boxes.size)
		val box = boxes.single()
		assertTrue("Expected negative tilt around -22 deg, was ${box.angleDeg}", box.angleDeg in -32f..-12f)
		assertTrue("Expected OBB width > height, got ${box.obbW}x${box.obbH}", box.obbW > box.obbH * 2f)
	}

	@Test
	fun `selectAutoProbeRegionsStatic prefers dialogue lines over giant SFX and maps source hints`() {
		assertEquals("en", PaddleOcrEngine.resolveHintGroup("en"))
		assertEquals("korean", PaddleOcrEngine.resolveHintGroup("ko"))
		assertEquals("cjk", PaddleOcrEngine.resolveHintGroup("ja"))
		assertEquals("eslav", PaddleOcrEngine.resolveHintGroup("ru"))
		assertEquals("arabic", PaddleOcrEngine.resolveHintGroup("ar"))
		assertEquals("latin", PaddleOcrEngine.resolveHintGroup("tr"))

		val giantSfx = PaddleOcrEngine.DetRegion(0f, 0f, 300f, 240f)
		val regions = listOf(giantSfx) + (1..14).map { idx ->
			PaddleOcrEngine.DetRegion(40f, idx * 40f, 260f, idx * 40f + 22f)
		}
		val picked = PaddleOcrEngine.selectAutoProbeRegionsStatic(regions)
		assertEquals(12, picked.size)
		assertFalse("Giant artwork SFX should not outrank 14 clean speech lines", giantSfx in picked)
	}

	@Test
	fun `groupLinesIntoBubbles separates midtone UI pills preserves reading order and joins spaced words properly`() {
		val w = 320
		val h = 260
		val pixels = IntArray(w * h) { argb(255, 255, 255) }
		// Pill 1 (mid-tone slate 70) at y=20..70, bright border 165 at y=71..84, Pill 2 (mid-tone 70) at y=85..135
		fillRect(pixels, w, h, 30, 20, 260, 70, argb(70, 70, 70))
		fillRect(pixels, w, h, 30, 71, 260, 84, argb(165, 165, 165))
		fillRect(pixels, w, h, 30, 85, 260, 135, argb(70, 70, 70))

		val pillLines = listOf(
			RawOcrLine("SKILL BLINK", 95f, 45f, 32f, 220f, 56f),
			RawOcrLine("TRAIT MANA LEAKAGE", 95f, 45f, 97f, 240f, 121f),
		)
		val pillBubbles = PaddleOcrEngine.groupLinesIntoBubbles(pillLines, w, h, pixels)
		assertEquals(2, pillBubbles.size)

		// Uniform wide-spaced narration lines should stay together
		val wideNarration = listOf(
			RawOcrLine("BECAUSE OF THIS,", 96f, 40f, 150f, 250f, 170f),
			RawOcrLine("EVEN THE MOST", 95f, 45f, 204f, 245f, 224f),
		)
		val narrationBubbles = PaddleOcrEngine.groupLinesIntoBubbles(wideNarration, w, h, null)
		assertEquals(1, narrationBubbles.size)
		assertEquals("BECAUSE OF THIS, EVEN THE MOST", narrationBubbles.single().text)

		// Spaced words on same line must keep space instead of gluing ("I DO" not "IDO")
		val spacedWords = listOf(
			RawOcrLine("I", 95f, 20f, 10f, 30f, 30f),
			RawOcrLine("DO", 95f, 35f, 10f, 65f, 30f),
		)
		val spacedBubble = PaddleOcrEngine.groupLinesIntoBubbles(spacedWords, 200, 100, null)
		assertEquals(1, spacedBubble.size)
		assertEquals("I DO", spacedBubble.single().text)
	}

	@Test
	fun `TextBlockFilters drops giant low-conf artwork noise and Asura credits page banners`() {
		assertFalse(
			TextBlockFilters.isUsableOcrLine(
				RawOcrLine("aday", 60f, 10f, 10f, 180f, 240f),
			),
		)
		assertFalse(
			TextBlockFilters.isUsableOcrLine(
				RawOcrLine("WOy", 72f, 10f, 10f, 60f, 40f),
			),
		)

		val creditsBanner = listOf(
			bubble("c0", "PR"),
			bubble("c1", "LEAFSKY"),
			bubble("c2", "DOTORI"),
			bubble("c3", "AsuraS cans"),
			bubble("c4", "RD"),
			bubble("c5", "RUE"),
			bubble("c6", "asurascans.com"),
		)
		assertTrue(TextBlockFilters.isCreditsOrPromoPage(creditsBanner))
		assertTrue(TextBlockFilters.filterBubbles(creditsBanner).isEmpty())
	}

	private fun line(text: String, conf: Float = 90f) = RawOcrLine(
		text = text,
		confidence = conf,
		x0 = 10f,
		y0 = 10f,
		x1 = 110f,
		y1 = 34f,
	)

	private fun bubble(id: String, text: String) = OcrBubbleGroup(
		id = id,
		box = NormalizedBox(10f, 10f, 100f, 200f),
		text = text,
		lines = listOf(OcrLine(NormalizedBox(10f, 10f, 100f, 200f), text, 0.95f)),
		kindHint = TextBlockKind.DIALOGUE,
	)

	private fun argb(r: Int, g: Int, b: Int): Int =
		(0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

	private fun fillRect(
		pixels: IntArray,
		width: Int,
		height: Int,
		x0: Int,
		y0: Int,
		x1: Int,
		y1: Int,
		color: Int,
	) {
		val cx0 = x0.coerceIn(0, width - 1)
		val cx1 = x1.coerceIn(0, width - 1)
		val cy0 = y0.coerceIn(0, height - 1)
		val cy1 = y1.coerceIn(0, height - 1)
		for (y in cy0..cy1) {
			val rowOff = y * width
			for (x in cx0..cx1) {
				pixels[rowOff + x] = color
			}
		}
	}
}
