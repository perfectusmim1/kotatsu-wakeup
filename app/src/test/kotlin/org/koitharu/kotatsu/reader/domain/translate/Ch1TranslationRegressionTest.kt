package org.koitharu.kotatsu.reader.domain.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.reader.domain.translate.api.MangaTranslationService
import org.koitharu.kotatsu.reader.domain.translate.model.NormalizedBox
import org.koitharu.kotatsu.reader.domain.translate.model.OcrBubbleGroup
import org.koitharu.kotatsu.reader.domain.translate.model.OcrLine
import org.koitharu.kotatsu.reader.domain.translate.model.OcrSourceLanguage
import org.koitharu.kotatsu.reader.domain.translate.model.TextBlockKind
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationSettingsSnapshot
import org.koitharu.kotatsu.reader.domain.translate.ocr.PaddleOcrEngine
import org.koitharu.kotatsu.reader.domain.translate.ocr.RawOcrLine
import org.koitharu.kotatsu.reader.domain.translate.ocr.TextBlockFilters
import org.koitharu.kotatsu.reader.domain.translate.render.BubbleInpainter

class Ch1TranslationRegressionTest {

	private fun argb(r: Int, g: Int, b: Int): Int =
		(0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

	private fun fillRect(
		pixels: IntArray,
		w: Int,
		h: Int,
		x0: Int,
		y0: Int,
		x1: Int,
		y1: Int,
		color: Int,
	) {
		val cx0 = x0.coerceIn(0, w - 1)
		val cx1 = x1.coerceIn(0, w - 1)
		val cy0 = y0.coerceIn(0, h - 1)
		val cy1 = y1.coerceIn(0, h - 1)
		for (y in cy0..cy1) {
			val off = y * w
			for (x in cx0..cx1) {
				pixels[off + x] = color
			}
		}
	}

	private fun bubble(id: String, text: String, yMin: Float, yMax: Float): OcrBubbleGroup =
		OcrBubbleGroup(
			id = id,
			box = NormalizedBox(yMin = yMin, xMin = 100f, yMax = yMax, xMax = 600f),
			text = text,
			lines = listOf(
				OcrLine(
					box = NormalizedBox(yMin = yMin, xMin = 100f, yMax = yMax, xMax = 600f),
					text = text,
					confidence = 0.95f,
				),
			),
			kindHint = TextBlockKind.DIALOGUE,
		)

	@Test
	fun `isDarkStrokeWithWhiteHaloOnScene distinguishes white-halo dark text from white-fill dark-outline`() {
		val w = 220
		val h = 120
		// 1. Dark stroke wrapped in white halo over mid-gray wall ("I'm really okay.")
		val haloScene = IntArray(w * h) { argb(150, 150, 155) }
		// Thick white outer glow blocks with centered black glyph strokes (white -> dark -> white)
		for (k in 0 until 6) {
			val xBase = 20 + k * 28
			fillRect(haloScene, w, h, xBase, 30, xBase + 16, 85, argb(254, 254, 254))
			fillRect(haloScene, w, h, xBase + 5, 36, xBase + 11, 79, argb(8, 8, 8))
		}
		assertTrue(
			BubbleInpainter.isDarkStrokeWithWhiteHaloOnScene(
				pixels = haloScene,
				ww = w,
				wh = h,
				cx0 = 15,
				cy0 = 25,
				cx1 = 200,
				cy1 = 90,
				bgLumInit = 151f,
			),
		)

		// 2. Dark black glyphs directly on yellow memo paper (no bright white cores -> false for light-first!)
		val yellowMemo = IntArray(w * h) { argb(230, 220, 160) }
		for (k in 0 until 6) {
			val xBase = 20 + k * 28
			fillRect(yellowMemo, w, h, xBase, 35, xBase + 12, 80, argb(20, 15, 10))
			// Enclosed counter hole inside 'o'/'a' stays yellow bg (230,220,160)
			fillRect(yellowMemo, w, h, xBase + 4, 45, xBase + 8, 70, argb(230, 220, 160))
		}
		assertTrue(
			BubbleInpainter.isDarkStrokeWithWhiteHaloOnScene(
				pixels = yellowMemo,
				ww = w,
				wh = h,
				cx0 = 15,
				cy0 = 30,
				cx1 = 200,
				cy1 = 85,
				bgLumInit = 216f,
			),
		)

		// 3. Genuine white-fill + dark-outline comic lettering over mid-tone bg -> must NOT trigger darkHalo guard!
		val whiteFill = IntArray(w * h) { argb(120, 130, 140) }
		for (k in 0 until 6) {
			val xBase = 20 + k * 28
			fillRect(whiteFill, w, h, xBase, 30, xBase + 14, 85, argb(12, 12, 12))
			fillRect(whiteFill, w, h, xBase + 3, 34, xBase + 11, 81, argb(252, 252, 252))
		}
		assertFalse(
			BubbleInpainter.isDarkStrokeWithWhiteHaloOnScene(
				pixels = whiteFill,
				ww = w,
				wh = h,
				cx0 = 15,
				cy0 = 25,
				cx1 = 200,
				cy1 = 90,
				bgLumInit = 128f,
			),
		)
	}

	@Test
	fun `groupLinesIntoBubbles merges wide-spaced IN FRONT OF separates bracketed headers and drops speedline noise`() {
		// 1. Wide-spaced "IN" "FRONT" "OF" inside white bubble + "YOU!!!" stacked below
		val bw = 500
		val bh = 300
		val bubblePixels = IntArray(bw * bh) { argb(255, 255, 255) }
		val inFrontYou = listOf(
			RawOcrLine("NO...", 96f, x0 = 205f, y0 = 60f, x1 = 295f, y1 = 86f),
			RawOcrLine("IN", 96f, x0 = 120f, y0 = 92f, x1 = 160f, y1 = 116f),
			RawOcrLine("FRONT", 96f, x0 = 182f, y0 = 92f, x1 = 305f, y1 = 116f), // hGap=22 <= 24*0.95=22.8
			RawOcrLine("OF", 96f, x0 = 326f, y0 = 92f, x1 = 375f, y1 = 116f), // hGap=21
			RawOcrLine("YOU!!!", 97f, x0 = 180f, y0 = 121f, x1 = 315f, y1 = 148f),
		)
		for (line in inFrontYou) {
			fillRect(
				bubblePixels,
				bw,
				bh,
				line.x0.toInt() + 4,
				line.y0.toInt() + 4,
				line.x1.toInt() - 4,
				line.y1.toInt() - 4,
				argb(15, 15, 15),
			)
		}
		val mergedInFront = PaddleOcrEngine.groupLinesIntoBubbles(inFrontYou, bw, bh, bubblePixels)
		assertEquals(1, mergedInFront.size)
		assertEquals("NO... IN FRONT OF YOU!!!", mergedInFront.single().text)

		// 2. Bracketed idiom heading stays separate from body & wide text does not merge offset narrow edge fragment
		val headingAndOvertime = listOf(
			RawOcrLine("[KEEPING A LOW PROFILE]", 97f, x0 = 60f, y0 = 30f, x1 = 440f, y1 = 72f),
			RawOcrLine("PLAYING IT SAFE, LAYING LOW", 97f, x0 = 90f, y0 = 84f, x1 = 410f, y1 = 104f),
			RawOcrLine("FROM CITY HALL", 96f, x0 = 40f, y0 = 180f, x1 = 460f, y1 = 216f),
			RawOcrLine("ITIONS", 92f, x0 = 360f, y0 = 224f, x1 = 480f, y1 = 254f),
		)
		val groupedHead = PaddleOcrEngine.groupLinesIntoBubbles(headingAndOvertime, bw, 320, null)
		assertEquals(4, groupedHead.size)
		assertEquals("[KEEPING A LOW PROFILE]", groupedHead[0].text)
		assertEquals("PLAYING IT SAFE, LAYING LOW", groupedHead[1].text)
		assertEquals("FROM CITY HALL", groupedHead[2].text)
		assertEquals("ITIONS", groupedHead[3].text)

		// 3. Textured radial speedline artwork pseudo-word ("OKOLLER", "LEKE") is filtered while clean dialogue stays
		val noisyArt = IntArray(300 * 200) { idx ->
			val x = idx % 300
			if (x % 3 == 0) argb(90, 90, 100) else argb(235, 235, 240)
		}
		assertTrue(
			PaddleOcrEngine.isLikelyNonBubblePseudoWord(
				pixels = noisyArt,
				imgW = 300,
				imgH = 200,
				x0 = 90f,
				y0 = 70f,
				x1 = 190f,
				y1 = 96f,
				mergedLineCount = 1,
				text = "OKOLLER",
				avgConf = 0.78f,
			),
		)
		assertFalse(
			PaddleOcrEngine.isLikelyNonBubblePseudoWord(
				pixels = noisyArt,
				imgW = 300,
				imgH = 200,
				x0 = 90f,
				y0 = 70f,
				x1 = 190f,
				y1 = 96f,
				mergedLineCount = 1,
				text = "Sigh...",
				avgConf = 0.78f,
			),
		)
	}

	@Test
	fun `TextBlockFilters strips embedded OmegaScans banner and preserves Sigh and bright-vs-outdoor barrier`() {
		val pageBubbles = listOf(
			bubble("b0", "AMEGA SCANS", 15f, 38f),
			bubble("b1", "I WANT TO WORK QUIETLY", 52f, 70f),
			bubble("b2", "TL Staff", 75f, 88f),
			bubble("b3", "PR Staff", 75f, 88f),
			bubble("b4", "READ EARLY ON OMEGASCANS.ORG", 102f, 118f),
			bubble("b5", "IT'S ALL I HOPE FOR.", 185f, 220f),
			bubble("b6", "Sigh...", 420f, 445f),
		)
		val kept = TextBlockFilters.filterBubbles(pageBubbles, skipPureSfx = true, filterPromoAndCredits = true)
		assertEquals(listOf("IT'S ALL I HOPE FOR.", "Sigh..."), kept.map { it.text })

		// Outdoor snowy park (lum ~190) above pure white speech bubble (lum = 255) must trigger vertical barrier
		val w = 300
		val h = 180
		val scene = IntArray(w * h) { argb(255, 255, 255) }
		fillRect(scene, w, h, 0, 0, w - 1, 74, argb(188, 190, 195))
		val lineA = RawOcrLine("Any item for a thousand won", 88f, 60f, 30f, 240f, 54f)
		val lineB = RawOcrLine("WHAT IS THAT?", 97f, 65f, 92f, 235f, 120f)
		assertTrue(PaddleOcrEngine.hasVerticalBubbleBarrier(scene, w, h, lineA, lineB))
	}

	@Test
	fun `MangaTranslationService catches partial English clauses preserves ellipsis fixes Him and translates He`() {
		assertEquals("en", MangaTranslationService.resolveEffectiveGoogleSourceCode("⟦K0⟧ 1. Press the button.", OcrSourceLanguage.AUTO))
		assertEquals("auto", MangaTranslationService.resolveEffectiveGoogleSourceCode("⟦K0⟧ 지금은 안 돼", OcrSourceLanguage.AUTO))

		assertTrue(
			MangaTranslationService.hasUntranslatedEnglishClause(
				translatedText = "1 PRESS THE BUTTON TO ACTIVATE THE PENDANT AND HAVE THE OTHER PERSON LOOK AT IT FOR MORE THAN 3 SECONDS. 2. VERİLEN ÖNERİNİN SÜRE SINIRI 3 DAKİKADIR.",
				originalCleanText = "1. PRESS THE BUTTON TO ACTIVATE THE PENDANT AND HAVE THE OTHER PERSON LOOK AT IT FOR MORE THAN 3 SECONDS. 2. THE TIME LIMIT...",
				targetLang = "tr",
			),
		)
		assertTrue(
			MangaTranslationService.hasUntranslatedEnglishClause(
				translatedText = "YOU SAVE THE HYPNOSIS YOU PERFORMED AS A SETUP PLAY. DAHA SONRA YENİ BİR HİPNOZ GERÇEKLEŞTİREBİLİRSİNİZ 4 GIVING AN UNREASONABLE SUGGESTION DURING HYPNOSIS-",
				originalCleanText = "YOU SAVE THE HYPNOSIS YOU PERFORMED AS A SETUP PLAY. THEN YOU CAN PERFORM A NEW HYPNOSIS. 4. GIVING AN UNREASONABLE SUGGESTION DURING HYPNOSIS-",
				targetLang = "tr",
			),
		)
		assertFalse(
			MangaTranslationService.hasUntranslatedEnglishClause(
				translatedText = "1. Düğmeye basın ve diğer kişinin 3 saniyeden uzun süre kolyeye bakmasını sağlayın. 2. Verilen telkinin süre sınırı 3 dakikadır.",
				originalCleanText = "1. Press the button and have the other person look at the pendant for more than 3 seconds.",
				targetLang = "tr",
			),
		)

		// Pronoun "He..." MUST NOT be misclassified as laugh "hehe" SFX
		assertFalse(MangaTranslationService.isPureSfxOrNoise("HE..."))
		assertFalse(MangaTranslationService.isPureSfxOrNoise("Sigh..."))
		assertTrue(MangaTranslationService.isPureSfxOrNoise("hehe..."))

		// Leading ellipsis restoration + "Hım?" -> "Hmm?"
		assertEquals(
			"...Kelimenin tam anlamıyla her şey!!!",
			MangaTranslationService.postProcessTranslatedText(
				translated = ".Kelimenin tam anlamıyla her şey!!!",
				originalText = "...LITERALLY EVERYTHING!!!",
				targetLang = "tr",
			),
		)
		assertEquals(
			"Hmm?",
			MangaTranslationService.postProcessTranslatedText(
				translated = "Hım?",
				originalText = "HM?",
				targetLang = "tr",
			),
		)
	}

	@Test
	fun `Ch1 v3 det overlap keeps dialogue inside giant SFX and separates occluded overtime columns`() {
		// Giant SFX (0,793-700,1461) must NOT swallow crisp smaller dialogue lines HUH?, NO.., IN FRONT OF
		val giantSfx = PaddleOcrEngine.DetRegion(0f, 793f, 700f, 1461f)
		val huh = PaddleOcrEngine.DetRegion(468f, 819f, 612f, 872f)
		val noLine = PaddleOcrEngine.DetRegion(289f, 1358f, 385f, 1411f)
		val inFrontOf = PaddleOcrEngine.DetRegion(192f, 1406f, 517f, 1462f)
		assertFalse(PaddleOcrEngine.shouldSuppressDetRegionOverlap(huh, giantSfx))
		assertFalse(PaddleOcrEngine.shouldSuppressDetRegionOverlap(giantSfx, noLine))
		assertFalse(PaddleOcrEngine.shouldSuppressDetRegionOverlap(giantSfx, inFrontOf))
		// Exact duplicate detection across slices is still deduplicated
		val noSlice1 = PaddleOcrEngine.DetRegion(288f, 1358f, 387f, 1411f)
		assertTrue(PaddleOcrEngine.shouldSuppressDetRegionOverlap(noLine, noSlice1))

		// OVERTIME WORK ... FROM + M CITY HALL must NOT merge with occluded head columns NATIO/ITIONS/RELAT/AGGING/KING
		val w = 700
		val h = 1100
		val scene = IntArray(w * h) { argb(236, 232, 240) }
		// Dark character head/hair between x=245..435, y=420..820
		fillRect(scene, w, h, 245, 420, 435, 820, argb(38, 30, 32))
		val overtimeLines = listOf(
			RawOcrLine("COMPILING DOCUMENT", 99f, 10f, 250f, 690f, 320f),
			RawOcrLine("FROM", 100f, 94f, 326f, 304f, 400f),
			RawOcrLine("M CITY HALL", 98f, 242f, 326f, 621f, 400f),
			RawOcrLine("NATIO", 100f, 20f, 405f, 237f, 484f),
			RawOcrLine("ITIONS", 100f, 451f, 406f, 690f, 483f),
			RawOcrLine("RELAT", 94f, 6f, 483f, 205f, 566f),
			RawOcrLine("NGGING.", 95f, 445f, 486f, 695f, 566f),
			RawOcrLine("KING", 91f, 417f, 563f, 574f, 647f),
		)
		val grouped = PaddleOcrEngine.groupLinesIntoBubbles(overtimeLines, w, h, scene)
		assertEquals(1, grouped.size)
		assertEquals("COMPILING DOCUMENT FROM CITY HALL", grouped[0].text)
	}

	@Test
	fun `Ch1 v3 OCR cleaning, footer promo banner, Sigh translation, and dark-bg ME white fill`() {
		assertEquals("...LITERALLY EVERYTHING!!!", TextBlockFilters.cleanOcrText(".LITERALLY EVERYTHING!!!"))
		assertEquals("No", TextBlockFilters.cleanOcrText("h No"))

		// Footer "OTHER SERIES WE DO!" + credits must be stripped without dropping story dialogue above
		val footerPageBubbles = listOf(
			bubble("b1", "WHAT SHALL WE DO FIRST?", 343f, 351f),
			bubble("b2", "TO BE CONTINUED..", 469f, 475f),
			bubble("b3", "Writer | Pyunghyung | Typesetter Haji", 480f, 511f),
			bubble("b8", "OTHER SERIES WE DO!", 726f, 731f),
			bubble("b9", "WONDERFUL NEW WORLD", 754f, 763f),
			bubble("b10", "GLORY HOLE", 756f, 763f),
		)
		val keptFooter = TextBlockFilters.filterBubbles(footerPageBubbles, skipPureSfx = true, filterPromoAndCredits = true)
		assertEquals(listOf("WHAT SHALL WE DO FIRST?", "TO BE CONTINUED.."), keptFooter.map { it.text })

		// Leading dot in translation & Sigh... preservation
		assertEquals(
			"...kelimenin tam anlamıyla her şey!!!",
			MangaTranslationService.postProcessTranslatedText(".kelimenin tam anlamıyla her şey!!!", ".LITERALLY EVERYTHING!!!", "tr"),
		)
		assertEquals(
			"İç çekiş...",
			MangaTranslationService.postProcessTranslatedText("Sigh...", "Sigh...", "tr"),
		)
		val sighAh = MangaTranslationService.postProcessTranslatedItem(
			id = "b5",
			translatedRaw = "Ah...",
			originalCleanText = "Sigh...",
			kind = TextBlockKind.DIALOGUE,
			rawTrash = false,
			settings = TranslationSettingsSnapshot(skipPureSfx = true),
			targetLang = "tr",
		)
		assertFalse(sighAh.isTrash)
		assertEquals("Ah...", sighAh.text)

		// "ME?" white fill + black outline on mid-dark gray scene (bgLum ~121) must NOT trip isDarkStrokeWithWhiteHaloOnScene
		val w = 120
		val h = 70
		val meScene = IntArray(w * h) { argb(121, 121, 124) }
		fillRect(meScene, w, h, 20, 12, 100, 58, argb(12, 12, 12))
		fillRect(meScene, w, h, 26, 18, 94, 52, argb(255, 255, 255))
		assertFalse(BubbleInpainter.isDarkStrokeWithWhiteHaloOnScene(meScene, w, h, 18, 10, 102, 60, 121f))

		// Actual "ME?" black stroke inside white halo on gray scene -> whiteTouchesMid > blackTouchesMid -> midScene white fill must be false
		assertFalse(
			BubbleInpainter.isWhiteFillDarkOutlineMidScene(
				bgLumInit = 123f,
				flankMeanLum = 130f,
				flankWhiteRatio = 0.0f,
				coreWhiteRatio = 0.14f,
				whiteCoreCount = 80,
				whiteTouchesDarkOutline = 90,
				brighterCount = 120,
				blackTouchesMid = 5,
				whiteTouchesMid = 65,
			),
		)
		// Single-letter spurious SFX "A" -> rejected; QR/signboard low-conf pattern -> rejected
		assertTrue(TextBlockFilters.shouldSkipBlock("A"))
		assertFalse(TextBlockFilters.isUsableOcrLine(RawOcrLine("A", 90f, 100f, 100f, 220f, 297f)))
		val qrScene = IntArray(120 * 120) { i ->
			val x = i % 120
			val y = i / 120
			if (((x / 8) + (y / 8)) % 2 == 0) argb(15, 15, 15) else argb(245, 245, 245)
		}
		assertTrue(
			PaddleOcrEngine.isLikelyQrOrPatternNoise(
				pixels = qrScene,
				imgW = 120,
				imgH = 120,
				x0 = 12f,
				y0 = 12f,
				x1 = 108f,
				y1 = 108f,
				lines = listOf(RawOcrLine("UR", 37f, 12f, 12f, 60f, 50f), RawOcrLine("JEFE", 62f, 15f, 52f, 100f, 100f)),
				text = "UR JEFE",
				avgConf = 0.495f,
			),
		)
		assertTrue(
			PaddleOcrEngine.isLikelyQrOrPatternNoise(
				pixels = qrScene,
				imgW = 120,
				imgH = 120,
				x0 = 10f,
				y0 = 10f,
				x1 = 110f,
				y1 = 110f,
				lines = listOf(
					RawOcrLine("Lt", 91f, 10f, 10f, 45f, 40f),
					RawOcrLine("T8o", 52f, 10f, 42f, 70f, 75f),
					RawOcrLine("gaSOFLAEF", 65f, 35f, 78f, 110f, 108f),
				),
				text = "Lt T8o gaSOFLAEF",
				avgConf = 0.693f,
			),
		)
	}

	@Test
	fun pureBlackWhiteTextDoesNotTriggerWhiteHaloAroundDarkStrokes() {
		// Page 1 "[SAFE AND COMPLACENT]" / "[KEEPING A LOW PROFILE]" / "MY NAME IS MAENG DOYOON"
		// have bgLumInit == 0f with large jetBlackCoreCount and anti-aliased mid-tone touches.
		// White halo detection must NEVER treat pure-black background as dark strokes.
		assertFalse(
			BubbleInpainter.hasWhiteHaloAroundDarkStrokesOnScene(
				bgLumInit = 0f,
				jetBlackCoreCount = 10705,
				whiteTouchesDarkOutline = 2117,
				whiteTouchesMid = 1190,
				blackTouchesMid = 1186,
				coreSamples = 17500,
			),
		)
		// Mid-tone scene with dark text + white halo ("I'm really okay." / "ME?") MUST trigger true.
		assertTrue(
			BubbleInpainter.hasWhiteHaloAroundDarkStrokesOnScene(
				bgLumInit = 120f,
				jetBlackCoreCount = 1877,
				whiteTouchesDarkOutline = 536,
				whiteTouchesMid = 668,
				blackTouchesMid = 284,
				coreSamples = 6540,
			),
		)
	}
}
