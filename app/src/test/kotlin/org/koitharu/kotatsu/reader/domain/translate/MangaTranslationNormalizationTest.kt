package org.koitharu.kotatsu.reader.domain.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.reader.domain.translate.api.MangaTranslationService
import org.koitharu.kotatsu.reader.domain.translate.model.NormalizedBox
import org.koitharu.kotatsu.reader.domain.translate.model.OcrBubbleGroup
import org.koitharu.kotatsu.reader.domain.translate.model.OcrLine
import org.koitharu.kotatsu.reader.domain.translate.model.OcrSourceLanguage
import org.koitharu.kotatsu.reader.domain.translate.model.TextBlockKind
import org.koitharu.kotatsu.reader.domain.translate.model.TranslatedTextItem
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationSettingsSnapshot

class MangaTranslationNormalizationTest {

	private fun dummyBubble(
		id: String,
		text: String,
		kindHint: TextBlockKind = TextBlockKind.DIALOGUE,
	): OcrBubbleGroup {
		val box = NormalizedBox(0.1f, 0.1f, 0.2f, 0.4f)
		return OcrBubbleGroup(
			id = id,
			box = box,
			text = text,
			lines = listOf(OcrLine(box = box, text = text)),
			kindHint = kindHint,
		)
	}

	@Test
	fun segmentGluedEnglishToken_splitsGluedComicWordsAndKeepsIntactWords() {
		assertEquals(
			"even a small stimulus can",
			MangaTranslationService.segmentGluedEnglishToken("EVENASMALLSTIMULUSCAN"),
		)
		assertEquals(
			"for amplification",
			MangaTranslationService.segmentGluedEnglishToken("FORAMPLIFICATION"),
		)
		assertEquals(
			"simple principle",
			MangaTranslationService.segmentGluedEnglishToken("SIMPLEPRINCIPLE"),
		)
		assertEquals(
			"UNDERSTAND",
			MangaTranslationService.segmentGluedEnglishToken("UNDERSTAND"),
		)
		assertEquals(
			"CONGRATULATIONS",
			MangaTranslationService.segmentGluedEnglishToken("CONGRATULATIONS"),
		)
		assertEquals(
			"AMPLIFICATION",
			MangaTranslationService.segmentGluedEnglishToken("AMPLIFICATION"),
		)
		assertEquals(
			"HELLO",
			MangaTranslationService.segmentGluedEnglishToken("HELLO"),
		)
	}

	@Test
	fun normalizeEnglishForGoogleTranslate_repairsAllCapsContractionsAndOcrNoise() {
		assertEquals(
			"I don't know what you are talking about!",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("I DONT KNOW WHAT YOU ARE TALKING ABOUT!"),
		)
		assertEquals(
			"I'm sorry, I can't help you right now.",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("IM SORRY, I CANT HELP YOU RIGHT NOW."),
		)
		assertEquals(
			"You're going to pay for that!",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("YOURE GOING TO PAY FOR THAT!"),
		)
		assertEquals(
			"Even a small stimulus can amplify everything.",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("EVENASMALLSTIMULUSCAN AMPLIFY EVERYTHING."),
		)
		assertEquals(
			"It's a physique for amplification.",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("IT'SACONSTITUTION FORAMPLIFICATION."),
		)
		assertEquals(
			"I'm not like you",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("!M NOT L!KE YOU"),
		)
		assertEquals(
			"I'm not like you",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("1'M NOT L1KE YOU"),
		)
		assertEquals(
			"I know",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("1 KNOW"),
		)
		assertEquals(
			"Lvl 1 quest",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("LVL 1 QUEST"),
		)
		assertEquals(
			"How?",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("HOWz"),
		)
		assertEquals(
			"DiffiCult?",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("DiffiCultz"),
		)
		assertEquals(
			"Jazz",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("JAZZ"),
		)
		assertEquals(
			"It's a trap!",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("ITS A TRAP!"),
		)
		assertEquals(
			"Its power is huge.",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("ITS POWER IS HUGE."),
		)
		assertEquals(
			"I'll kill you!",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("ILL KILL YOU!"),
		)
		assertEquals(
			"Let's go!",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("LETS GO!"),
		)
		assertEquals(
			"I'd rather die.",
			MangaTranslationService.normalizeEnglishForGoogleTranslate("ID RATHER DIE."),
		)
	}

	@Test
	fun prepareTextForGoogleTranslate_leavesNonEnglishScriptsAndEuropeanLatinSafe() {
		assertEquals(
			"これはテストです！",
			MangaTranslationService.prepareTextForGoogleTranslate(
				raw = "これはテストです！",
				sourceLang = OcrSourceLanguage.AUTO,
				targetLang = "tr",
			),
		)
		assertEquals(
			"C'est très bien.",
			MangaTranslationService.prepareTextForGoogleTranslate(
				raw = "C'est très bien.",
				sourceLang = OcrSourceLanguage.AUTO,
				targetLang = "tr",
			),
		)
		assertEquals(
			"Para siempre",
			MangaTranslationService.prepareTextForGoogleTranslate(
				raw = "PARA SIEMPRE",
				sourceLang = OcrSourceLanguage.AUTO,
				targetLang = "tr",
			),
		)
	}

	@Test
	fun isLikelyAlreadyTranslated_detectsTurkishWithoutFalsePositives() {
		assertTrue(
			MangaTranslationService.isLikelyAlreadyTranslated(
				text = "Bu son şansımız!",
				sourceLang = OcrSourceLanguage.AUTO,
				targetLang = "tr",
			),
		)
		assertTrue(
			MangaTranslationService.isLikelyAlreadyTranslated(
				text = "Para için mi?",
				sourceLang = OcrSourceLanguage.AUTO,
				targetLang = "tr",
			),
		)
		assertFalse(
			MangaTranslationService.isLikelyAlreadyTranslated(
				text = "What are you doing right now?",
				sourceLang = OcrSourceLanguage.AUTO,
				targetLang = "tr",
			),
		)
		assertFalse(
			MangaTranslationService.isLikelyAlreadyTranslated(
				text = "Ça va?",
				sourceLang = OcrSourceLanguage.AUTO,
				targetLang = "tr",
			),
		)
		assertFalse(
			MangaTranslationService.isLikelyAlreadyTranslated(
				text = "¿Qué pasa amigo?",
				sourceLang = OcrSourceLanguage.AUTO,
				targetLang = "tr",
			),
		)
		assertTrue(
			MangaTranslationService.isLikelyAlreadyTranslated(
				text = "これは日本語です",
				sourceLang = OcrSourceLanguage.AUTO,
				targetLang = "ja",
			),
		)
		assertTrue(
			MangaTranslationService.isLikelyAlreadyTranslated(
				text = "지금 당장 나가!",
				sourceLang = OcrSourceLanguage.AUTO,
				targetLang = "ko",
			),
		)
	}

	@Test
	fun prefilterAndSfxCheck_dropsPureSfxAndPromosWhilePreservingMeaningfulExclamations() {
		val pureSfxSamples = listOf(
			"BOOM!",
			"BAM!",
			"CRACK!",
			"WHOOSH",
			"SLAM",
			"CLANK",
			"THUD",
			"GASP",
			"GRIN",
			"SMIRK",
			"SHIVER",
			"STARE",
			"RUSTLE",
			"CREAK",
			"SQUEAK",
			"TAP TAP",
			"KWAANG!",
			"*gulp*",
			"[SFX]",
		)
		for (sample in pureSfxSamples) {
			assertTrue(
				"Expected pure SFX/noise to be skipped: $sample",
				MangaTranslationService.isPureSfxOrNoise(sample),
			)
		}

		val meaningfulLines = listOf(
			"NO!",
			"HELP!",
			"WHY?",
			"WAIT!",
			"STOP!",
			"ALONE?",
			"FIRST MURDER.",
			"HUH?!",
			"WHAT?!",
			"OWNER: JUST A DUMB CUTIE (LVL: 1)",
		)
		val settings = TranslationSettingsSnapshot(
			sourceLang = OcrSourceLanguage.ENGLISH,
			targetLang = "tr",
			skipPureSfx = true,
			filterPromoAndCredits = true,
		)
		for (line in meaningfulLines) {
			assertFalse(
				"Meaningful exclamation/dialogue must not be treated as SFX: $line",
				MangaTranslationService.isPureSfxOrNoise(line),
			)
			assertNull(
				"Meaningful line must pass prefilter for translation: $line",
				MangaTranslationService.evaluatePrefilter(
					cleanText = line,
					bubble = dummyBubble("b_${line.hashCode()}", line),
					settings = settings,
					targetLang = "tr",
				),
			)
		}

		val promoItem = MangaTranslationService.evaluatePrefilter(
			cleanText = "Join our discord.gg/webtoon",
			bubble = dummyBubble("promo", "Join our discord.gg/webtoon"),
			settings = settings,
			targetLang = "tr",
		)
		assertNotNull(promoItem)
		assertTrue(promoItem!!.isTrash)

		val bangSfxPrefilter = MangaTranslationService.evaluatePrefilter(
			cleanText = "BOOM!",
			bubble = dummyBubble("sfx1", "BOOM!", TextBlockKind.SFX),
			settings = settings,
			targetLang = "tr",
		)
		assertNotNull(bangSfxPrefilter)
		assertTrue(bangSfxPrefilter!!.isTrash)
		assertEquals(TextBlockKind.SFX, bangSfxPrefilter.kind)
	}

	@Test
	fun postProcessTranslatedTextAndItem_fixesWebtoonTermsStripsHtmlAndMarksSfxTrash() {
		assertEquals(
			"Özel Bir Bünye",
			MangaTranslationService.postProcessTranslatedText(
				translated = "Özel Bir Anayasa",
				originalText = "A Unique Martial Constitution",
				targetLang = "tr",
			),
		)
		assertEquals(
			"EŞSİZ BÜNYE",
			MangaTranslationService.postProcessTranslatedText(
				translated = "EŞSİZ ANAYASA",
				originalText = "UNIQUE CONSTITUTION FOR AMPLIFICATION",
				targetLang = "tr",
			),
		)
		assertEquals(
			"Anayasa her vatandaşı korur.",
			MangaTranslationService.postProcessTranslatedText(
				translated = "Anayasa her vatandaşı korur.",
				originalText = "The Constitution protects every citizen.",
				targetLang = "tr",
			),
		)
		assertEquals(
			"Ash Gray ile gelişim çalış!",
			MangaTranslationService.postProcessTranslatedText(
				translated = "<b>Ashgray ile xiulian çalış!</b>",
				originalText = "Cultivate with Ash Gray!",
				targetLang = "tr",
			),
		)
		assertEquals(
			"AH LANET! Bu ilkeyi seç!",
			MangaTranslationService.postProcessTranslatedText(
				translated = "AH KAHRAMAN! Bu İlk'i'yi seç!",
				originalText = "OH DAMN! Pick that principle!",
				targetLang = "tr",
			),
		)
		assertEquals(
			"[SFX]",
			MangaTranslationService.postProcessTranslatedText(
				translated = "[SFX]",
				originalText = "KWAANG",
				targetLang = "tr",
			),
		)

		val settings = TranslationSettingsSnapshot(targetLang = "tr", skipPureSfx = true)
		val sfxItem = MangaTranslationService.postProcessTranslatedItem(
			id = "b1",
			translatedRaw = "[SFX]",
			originalCleanText = "GRIN",
			kind = TextBlockKind.DIALOGUE,
			settings = settings,
		)
		assertTrue(sfxItem.isTrash)
		assertEquals("", sfxItem.text)
		assertEquals(TextBlockKind.SFX, sfxItem.kind)

		val trMisTranslatedSfx = MangaTranslationService.postProcessTranslatedItem(
			id = "b2",
			translatedRaw = "Sırıtış",
			originalCleanText = "SMIRK",
			kind = TextBlockKind.SFX,
			settings = settings,
		)
		assertTrue(trMisTranslatedSfx.isTrash)
		assertEquals(TextBlockKind.SFX, trMisTranslatedSfx.kind)

		val meaningfulEvenWithSfxHint = MangaTranslationService.postProcessTranslatedItem(
			id = "b3",
			translatedRaw = "İLK CİNAYET.",
			originalCleanText = "FIRST MURDER.",
			kind = TextBlockKind.SFX,
			settings = settings,
		)
		assertFalse(meaningfulEvenWithSfxHint.isTrash)
		assertEquals("İLK CİNAYET.", meaningfulEvenWithSfxHint.text)
	}

	@Test
	fun parseGeminiResponseAndMarkers_handlesCodeFencesHtmlMissingIdsAndUnchangedBatches() {
		val b1 = dummyBubble("b1", "UNIQUE CONSTITUTION!")
		val b2 = dummyBubble("b2", "KWAANG!")
		val b3 = dummyBubble("b3", "FIRST MURDER.")

		val responseJson = """
			{
			  "candidates": [{
			    "content": {
			      "parts": [{
			        "text": "```json\n[{\"id\":\"b1\",\"text\":\"<b>Eşsiz Bünye!</b>\",\"kind\":\"dialogue\",\"trash\":false},{\"id\":\"b2\",\"text\":\"[SFX]\",\"kind\":\"sfx\",\"trash\":false},{\"id\":\"wrong_id\",\"text\":\"İlk Cinayet.\",\"kind\":\"emphasis\",\"trash\":false}]\n```"
			      }]
			    }
			  }]
			}
		""".trimIndent()

		val parsed = MangaTranslationService.parseGeminiResponse(
			responseBody = responseJson,
			bubbles = listOf(b1, b2, b3),
			settings = TranslationSettingsSnapshot(sourceLang = OcrSourceLanguage.ENGLISH, targetLang = "tr"),
		)
		assertNotNull(parsed)
		assertEquals(3, parsed!!.size)
		assertEquals("Eşsiz Bünye!", parsed[0].text)
		assertFalse(parsed[0].isTrash)
		assertTrue(parsed[1].isTrash)
		assertEquals("", parsed[1].text)
		assertEquals("b3", parsed[2].id)
		assertEquals("İlk Cinayet.", parsed[2].text)

		val marked = MangaTranslationService.parseMarkedGoogleOutput(
			raw = "⟦K0⟧ İlk balon çevirisi\n[K1] İkinci balon çevirisi",
			expectedCount = 2,
		)
		assertNotNull(marked)
		assertEquals("İlk balon çevirisi", marked!![0])
		assertEquals("İkinci balon çevirisi", marked[1])

		// Reject when meaningful English sentences are echoed untranslated
		val echoedOriginals = listOf(
			"e1" to "I don't understand what you are doing.",
			"e2" to "Why did you come here alone?",
		)
		val echoedResults = mapOf(
			"e1" to TranslatedTextItem("e1", "I don't understand what you are doing."),
			"e2" to TranslatedTextItem("e2", "Why did you come here alone?"),
		)
		assertTrue(
			MangaTranslationService.shouldRejectUnchangedBatch(
				originals = echoedOriginals,
				results = echoedResults,
				sourceLang = OcrSourceLanguage.ENGLISH,
				targetLang = "tr",
			),
		)

		// Do not reject when only a short proper name stays unchanged and dialogue translated
		val nameAndDialogueOriginals = listOf(
			"n1" to "Sung Jinwoo",
			"n2" to "Where are we going next?",
		)
		val nameAndDialogueResults = mapOf(
			"n1" to TranslatedTextItem("n1", "Sung Jinwoo"),
			"n2" to TranslatedTextItem("n2", "Bundan sonra nereye gidiyoruz?"),
		)
		assertFalse(
			MangaTranslationService.shouldRejectUnchangedBatch(
				originals = nameAndDialogueOriginals,
				results = nameAndDialogueResults,
				sourceLang = OcrSourceLanguage.ENGLISH,
				targetLang = "tr",
			),
		)
	}

	@Test
	fun finalizeSingleItem_dropsArticleLowercaseOcrGarbleEchoWhilePreservingTranslationsAndNames() {
		val settings = TranslationSettingsSnapshot(
			sourceLang = OcrSourceLanguage.ENGLISH,
			targetLang = "tr",
		)

		val garbleEcho = MangaTranslationService.finalizeSingleItem(
			item = TranslatedTextItem(id = "g1", text = "cocsio."),
			originalCleanText = "The cocsio.",
			settings = settings,
			targetLang = "tr",
		)
		assertTrue(garbleEcho.isTrash)
		assertEquals("", garbleEcho.text)

		val translatedArticleNoun = MangaTranslationService.finalizeSingleItem(
			item = TranslatedTextItem(id = "g2", text = "Dünya."),
			originalCleanText = "The world.",
			settings = settings,
			targetLang = "tr",
		)
		assertFalse(translatedArticleNoun.isTrash)
		assertEquals("Dünya.", translatedArticleNoun.text)

		val capitalizedProperNoun = MangaTranslationService.finalizeSingleItem(
			item = TranslatedTextItem(id = "g3", text = "Sangah."),
			originalCleanText = "The Sangah.",
			settings = settings,
			targetLang = "tr",
		)
		assertFalse(capitalizedProperNoun.isTrash)
		assertEquals("Sangah.", capitalizedProperNoun.text)
	}
}
