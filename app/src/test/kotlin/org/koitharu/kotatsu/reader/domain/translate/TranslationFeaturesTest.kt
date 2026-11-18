package org.koitharu.kotatsu.reader.domain.translate

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.comments.SourceCommentsViewModel
import org.koitharu.kotatsu.reader.domain.translate.api.GeminiKeyPool
import org.koitharu.kotatsu.reader.domain.translate.api.InvalidDeeplApiKeyException
import org.koitharu.kotatsu.reader.domain.translate.api.MangaTranslationService
import org.koitharu.kotatsu.reader.domain.translate.api.MissingDeeplApiKeyException
import org.koitharu.kotatsu.reader.domain.translate.api.TranslationLanguages
import org.koitharu.kotatsu.reader.domain.translate.api.TranslationLanguages.toDeeplSourceCode
import org.koitharu.kotatsu.reader.domain.translate.model.GeminiThinkingLevel
import org.koitharu.kotatsu.reader.domain.translate.model.NormalizedBox
import org.koitharu.kotatsu.reader.domain.translate.model.OcrBubbleGroup
import org.koitharu.kotatsu.reader.domain.translate.model.OcrLine
import org.koitharu.kotatsu.reader.domain.translate.model.OcrSourceLanguage
import org.koitharu.kotatsu.reader.domain.translate.model.TextBlockKind
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationProvider
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationSettingsSnapshot
import java.util.Locale
import javax.inject.Provider

class TranslationFeaturesTest {

	private data class CapturedRequest(
		val url: String,
		val headers: Map<String, String>,
		val body: String,
	)

	private fun dummyBubble(id: String, text: String): OcrBubbleGroup {
		val box = NormalizedBox(0.1f, 0.1f, 0.3f, 0.4f)
		return OcrBubbleGroup(
			id = id,
			box = box,
			text = text,
			lines = listOf(OcrLine(box = box, text = text)),
			kindHint = TextBlockKind.DIALOGUE,
		)
	}

	private fun jsonResponse(request: Request, code: Int, jsonBody: String): Response {
		return Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message("OK")
			.body(jsonBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
			.build()
	}

	private fun readBody(request: Request): String {
		val body = request.body ?: return ""
		val buffer = Buffer()
		body.writeTo(buffer)
		return buffer.readUtf8()
	}

	private fun createService(
		handler: (Request, String) -> Response,
	): Pair<MangaTranslationService, MutableList<CapturedRequest>> {
		val captured = ArrayList<CapturedRequest>()
		val interceptor = Interceptor { chain ->
			val request = chain.request()
			val bodyText = readBody(request)
			val headerMap = request.headers.names().associateWith { request.header(it).orEmpty() }
			captured.add(CapturedRequest(request.url.toString(), headerMap, bodyText))
			handler(request, bodyText)
		}
		val client = OkHttpClient.Builder()
			.addInterceptor(interceptor)
			.build()
		val service = MangaTranslationService(
			baseHttpClientProvider = Provider { client },
			keyPool = GeminiKeyPool(),
		).apply {
			geminiApiBaseOverride = "https://mock.gemini.local/v1beta"
			deeplEndpointOverride = "https://mock.deepl.local/v2/translate"
			googleEndpointOverride = "https://mock.google.local/translate_a/single"
		}
		return service to captured
	}

	@Test
	fun deeplEndpointAndLanguageMappings_followApiContract() {
		assertEquals("https://api-free.deepl.com/v2/translate", MangaTranslationService.resolveDeeplEndpoint("abc123:fx"))
		assertEquals("https://api.deepl.com/v2/translate", MangaTranslationService.resolveDeeplEndpoint("abc123-pro"))

		assertEquals("EN-US", TranslationLanguages.toDeeplTargetCode("en"))
		assertEquals("PT-BR", TranslationLanguages.toDeeplTargetCode("pt"))
		assertEquals("ZH-HANS", TranslationLanguages.toDeeplTargetCode("zh"))
		assertEquals("ZH-HANT", TranslationLanguages.toDeeplTargetCode("zh-TW"))
		assertEquals("NB", TranslationLanguages.toDeeplTargetCode("no"))
		assertEquals("TR", TranslationLanguages.toDeeplTargetCode("tr"))

		assertEquals("EN", OcrSourceLanguage.ENGLISH.toDeeplSourceCode())
		assertEquals("KO", OcrSourceLanguage.KOREAN.toDeeplSourceCode())
		assertEquals("JA", OcrSourceLanguage.JAPANESE.toDeeplSourceCode())
		assertEquals("ZH", OcrSourceLanguage.CHINESE.toDeeplSourceCode())
		assertNull(OcrSourceLanguage.AUTO.toDeeplSourceCode())
		assertNull(OcrSourceLanguage.LATIN.toDeeplSourceCode())
	}

	@Test
	fun deeplProvider_translatesBatchAndRotatesOnQuotaError(): Unit = runBlocking {
		val (service, captured) = createService { request, _ ->
			val auth = request.header("Authorization").orEmpty()
			if (auth == "DeepL-Auth-Key deepl-free-key-0001:fx") {
				jsonResponse(request, 456, """{"message":"Quota exceeded"}""")
			} else {
				jsonResponse(
					request,
					200,
					"""{"translations":[{"detected_source_language":"EN","text":"Merhaba dünya!"},{"detected_source_language":"EN","text":"Nasılsın?"}]}""",
				)
			}
		}
		val settings = TranslationSettingsSnapshot(
			provider = TranslationProvider.DEEPL,
			sourceLang = OcrSourceLanguage.ENGLISH,
			targetLang = "tr",
			deeplApiKeys = listOf("deepl-free-key-0001:fx", "deepl-free-key-0002:fx"),
		)
		val result = service.translateBubbles(
			bubbles = listOf(
				dummyBubble("b1", "Hello world!"),
				dummyBubble("b2", "How are you?"),
			),
			settings = settings,
		)

		assertEquals(2, result.size)
		assertEquals("Merhaba dünya!", result[0].text)
		assertEquals("Nasılsın?", result[1].text)
		assertTrue(captured.any { it.headers["Authorization"] == "DeepL-Auth-Key deepl-free-key-0001:fx" })
		assertTrue(captured.any { it.headers["Authorization"] == "DeepL-Auth-Key deepl-free-key-0002:fx" })
		val successReq = captured.last()
		val reqJson = JSONObject(successReq.body)
		assertEquals("TR", reqJson.getString("target_lang"))
		assertEquals("EN", reqJson.getString("source_lang"))
		assertEquals(2, reqJson.getJSONArray("text").length())
	}

	@Test
	fun deeplProvider_missingOrInvalidKey_throwsTypedException(): Unit = runBlocking {
		val (service, _) = createService { request, _ ->
			jsonResponse(request, 403, """{"message":"Wrong endpoint or invalid key"}""")
		}
		var caughtMissing = false
		try {
			service.translateBubbles(
				bubbles = listOf(dummyBubble("b1", "Hello world!")),
				settings = TranslationSettingsSnapshot(
					provider = TranslationProvider.DEEPL,
					targetLang = "tr",
					deeplApiKeys = emptyList(),
				),
			)
		} catch (_: MissingDeeplApiKeyException) {
			caughtMissing = true
		}
		assertTrue(caughtMissing)

		var caughtInvalid = false
		try {
			service.translateBubbles(
				bubbles = listOf(dummyBubble("b1", "Hello world!")),
				settings = TranslationSettingsSnapshot(
					provider = TranslationProvider.DEEPL,
					targetLang = "tr",
					deeplApiKeys = listOf("deepl-bad-key-0000:fx"),
				),
			)
		} catch (_: InvalidDeeplApiKeyException) {
			caughtInvalid = true
		}
		assertTrue(caughtInvalid)
	}

	@Test
	fun geminiThinkingLevel_buildsExpectedConfigAndCacheSignatureAndFallsBackCleanly(): Unit = runBlocking {
		val highSettings = TranslationSettingsSnapshot(
			provider = TranslationProvider.GEMINI,
			sourceLang = OcrSourceLanguage.ENGLISH,
			targetLang = "tr",
			apiKeys = listOf("gemini-test-key-0001"),
			geminiThinkingLevel = GeminiThinkingLevel.HIGH,
		)
		val minimalSettings = highSettings.copy(geminiThinkingLevel = GeminiThinkingLevel.MINIMAL)

		assertNotEquals(highSettings.effectiveCacheSignature(), minimalSettings.effectiveCacheSignature())
		assertTrue(highSettings.effectiveCacheSignature().contains(":HIGH:"))

		val levelCfg = MangaTranslationService(
			baseHttpClientProvider = Provider { OkHttpClient() },
			keyPool = GeminiKeyPool(),
		).run {
			val levelJson = buildGeminiGenerationConfig(highSettings, MangaTranslationService.ThinkingMode.LEVEL)
			val budgetJson = buildGeminiGenerationConfig(highSettings, MangaTranslationService.ThinkingMode.BUDGET)
			val omittedJson = buildGeminiGenerationConfig(highSettings, MangaTranslationService.ThinkingMode.OMITTED)
			Triple(levelJson, budgetJson, omittedJson)
		}

		assertEquals("HIGH", levelCfg.first.getJSONObject("thinkingConfig").getString("thinkingLevel"))
		assertEquals(12288, levelCfg.second.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
		assertTrue(levelCfg.second.getInt("maxOutputTokens") >= 12288 + 4096)
		assertFalse(levelCfg.third.has("thinkingConfig"))

		var calls = 0
		val (service, captured) = createService { request, _ ->
			calls += 1
			if (calls == 1) {
				jsonResponse(
					request,
					400,
					"""{"error":{"code":400,"message":"Unknown name \"thinkingLevel\" at generation_config.thinking_config"}}""",
				)
			} else {
				val payload = JSONArray().put(
					JSONObject()
						.put("id", "b1")
						.put("text", "Merhaba")
						.put("kind", "dialogue")
						.put("trash", false),
				).toString()
				val geminiResp = JSONObject().put(
					"candidates",
					JSONArray().put(
						JSONObject().put(
							"content",
							JSONObject().put(
								"parts",
								JSONArray().put(JSONObject().put("text", payload)),
							),
						),
					),
				).toString()
				jsonResponse(request, 200, geminiResp)
			}
		}

		val translated = service.translateBubbles(
			bubbles = listOf(dummyBubble("b1", "Hello there!")),
			settings = highSettings,
		)
		assertEquals(1, translated.size)
		assertEquals("Merhaba", translated[0].text)
		assertEquals(2, captured.size)
		val firstGenCfg = JSONObject(captured[0].body).getJSONObject("generationConfig")
		val secondGenCfg = JSONObject(captured[1].body).getJSONObject("generationConfig")
		assertEquals("HIGH", firstGenCfg.getJSONObject("thinkingConfig").getString("thinkingLevel"))
		assertEquals(12288, secondGenCfg.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
	}

	@Test
	fun commentTranslation_usesConfiguredProviderOrFallsBackToGoogle_andResolvesAppLocale(): Unit = runBlocking {
		val (service, captured) = createService { request, _ ->
			val url = request.url.toString()
			when {
				url.contains("mock.deepl.local") -> jsonResponse(
					request,
					200,
					"""{"translations":[{"detected_source_language":"KO","text":"Bu bölüm harikaydı!"}]}""",
				)

				url.contains("mock.google.local") -> jsonResponse(
					request,
					200,
					"""[[["Bu bölüm çok iyiydi!","이번 화 진짜 좋았다!"]]]""",
				)

				else -> jsonResponse(request, 500, "{}")
			}
		}

		val deeplComment = service.translateCommentText(
			text = "이번 화 진짜 좋았다!",
			targetLang = "tr",
			settings = TranslationSettingsSnapshot(
				provider = TranslationProvider.DEEPL,
				deeplApiKeys = listOf("deepl-test:fx"),
			),
		)
		assertEquals("Bu bölüm harikaydı!", deeplComment)

		val unkeyedFallbackComment = service.translateCommentText(
			text = "이번 화 진짜 좋았다!",
			targetLang = "tr",
			settings = TranslationSettingsSnapshot(
				provider = TranslationProvider.GEMINI,
				apiKeys = emptyList(),
			),
		)
		assertEquals("Bu bölüm çok iyiydi!", unkeyedFallbackComment)
		assertTrue(captured.last().url.contains("tl=tr"))

		assertEquals("tr", SourceCommentsViewModel.resolveTargetLanguageFromLocale(Locale.forLanguageTag("tr-TR")))
		assertEquals("en", SourceCommentsViewModel.resolveTargetLanguageFromLocale(Locale.ENGLISH))
		assertEquals("zh-TW", SourceCommentsViewModel.resolveTargetLanguageFromLocale(Locale.forLanguageTag("zh-Hant-TW")))
		assertEquals("zh", SourceCommentsViewModel.resolveTargetLanguageFromLocale(Locale.forLanguageTag("zh-CN")))
		assertEquals("ja", SourceCommentsViewModel.resolveTargetLanguageFromLocale(Locale.JAPANESE))
	}
}
