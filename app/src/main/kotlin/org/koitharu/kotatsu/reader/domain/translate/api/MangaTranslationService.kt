package org.koitharu.kotatsu.reader.domain.translate.api

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.network.CloudFlareInterceptor
import org.koitharu.kotatsu.core.network.RateLimitInterceptor
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.reader.domain.translate.api.TranslationLanguages.toGeminiPromptLabel
import org.koitharu.kotatsu.reader.domain.translate.api.TranslationLanguages.toGoogleSourceCode
import org.koitharu.kotatsu.reader.domain.translate.model.ComicTextTranslator
import org.koitharu.kotatsu.reader.domain.translate.model.OcrBubbleGroup
import org.koitharu.kotatsu.reader.domain.translate.model.OcrSourceLanguage
import org.koitharu.kotatsu.reader.domain.translate.model.TextBlockKind
import org.koitharu.kotatsu.reader.domain.translate.model.TranslatedTextItem
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationProvider
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationSettingsSnapshot
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * Comic/manga speech bubble translator supporting:
 * - **Gemini 3.5 Flash Lite** (`gemini-3.5-flash-lite`, auto fallback to `gemini-flash-lite-latest`)
 *   with Minimal thinking mode (`thinkingLevel: MINIMAL` -> `thinkingBudget: 0` -> omitted fallback),
 *   structured JSON batch output, and [GeminiKeyPool] multi-key rotation.
 * - **Google Translate** (`translate.googleapis.com/translate_a/single?client=gtx`) direct HTTP GET
 *   with UTF-8 length-safe batching and per-bubble fallback.
 */
@Singleton
class MangaTranslationService @Inject constructor(
	@BaseHttpClient private val baseHttpClientProvider: Provider<OkHttpClient>,
	private val keyPool: GeminiKeyPool,
) : ComicTextTranslator {

	private val httpClient: OkHttpClient by lazy {
		baseHttpClientProvider.get().newBuilder().apply {
			// Avoid RateLimitInterceptor throwing before reading Gemini's 429 retryDelay JSON,
			// avoid CloudFlareInterceptor interference, and prevent debug CurlLogging from printing x-goog-api-key.
			interceptors().removeAll { interceptor ->
				interceptor is RateLimitInterceptor ||
					interceptor is CloudFlareInterceptor ||
					interceptor::class.java.simpleName == "CurlLoggingInterceptor"
			}
			cache(null)
			cookieJar(CookieJar.NO_COOKIES)
			connectTimeout(15, TimeUnit.SECONDS)
			readTimeout(60, TimeUnit.SECONDS)
			writeTimeout(20, TimeUnit.SECONDS)
		}.build()
	}

	@Volatile
	private var fallbackModelUntilMs: Long = 0L

	private val preferredThinkingModeByModel = ConcurrentHashMap<String, ThinkingMode>()

	private enum class ThinkingMode {
		MINIMAL_LEVEL,
		ZERO_BUDGET,
		OMITTED,
	}

	private data class PreparedBubble(
		val bubble: OcrBubbleGroup,
		val cleanText: String,
	)

	private sealed interface GeminiCallOutcome {
		data class Success(val items: List<TranslatedTextItem>) : GeminiCallOutcome
		data class Failure(val info: GeminiHttpErrorInfo) : GeminiCallOutcome
	}

	override suspend fun translateBubbles(
		bubbles: List<OcrBubbleGroup>,
		settings: TranslationSettingsSnapshot,
	): List<TranslatedTextItem> {
		if (bubbles.isEmpty()) return emptyList()

		val targetLang = TranslationLanguages.normalizeTargetCode(settings.targetLang)
		if (targetLang.isBlank()) {
			return bubbles.map {
				TranslatedTextItem(
					id = it.id,
					text = normalizeBubbleInput(it.text),
					kind = it.kindHint,
					isTrash = false,
				)
			}
		}

		val resolvedResults = HashMap<String, TranslatedTextItem>(bubbles.size)
		val toTranslate = ArrayList<PreparedBubble>(bubbles.size)

		for (bubble in bubbles) {
			val clean = normalizeBubbleInput(bubble.text)
			val prefiltered = evaluatePrefilter(clean, bubble, settings, targetLang)
			if (prefiltered != null) {
				resolvedResults[bubble.id] = prefiltered
			} else {
				toTranslate.add(PreparedBubble(bubble = bubble, cleanText = clean))
			}
		}

		if (toTranslate.isNotEmpty()) {
			Log.d(
				LOG_TAG,
				"translateBubbles: total=${bubbles.size}, prefiltered=${bubbles.size - toTranslate.size}, " +
					"toTranslate=${toTranslate.size}, provider=${settings.provider}, " +
					"source=${settings.sourceLang} -> $targetLang",
			)
			val translated = when (settings.provider) {
				TranslationProvider.GEMINI -> translateWithGeminiOrFallback(
					items = toTranslate,
					settings = settings,
					targetLang = targetLang,
				)

				TranslationProvider.GOOGLE -> translateWithGoogle(
					items = toTranslate,
					settings = settings,
					targetLang = targetLang,
				)
			}
			for (item in translated) {
				resolvedResults[item.id] = item
			}
		} else {
			Log.d(
				LOG_TAG,
				"translateBubbles: all ${bubbles.size} bubble(s) handled by prefilter " +
					"(trash=${resolvedResults.values.count { it.isTrash }})",
			)
		}

		return bubbles.map { bubble ->
			resolvedResults[bubble.id] ?: TranslatedTextItem(
				id = bubble.id,
				text = normalizeBubbleInput(bubble.text),
				kind = bubble.kindHint,
				isTrash = false,
			)
		}
	}

	private suspend fun translateWithGeminiOrFallback(
		items: List<PreparedBubble>,
		settings: TranslationSettingsSnapshot,
		targetLang: String,
	): List<TranslatedTextItem> {
		val keys = GeminiKeyPool.normalizeApiKeysList(settings.apiKeys)
		if (keys.isEmpty()) {
			throw MissingGeminiApiKeyException()
		}

		val batches = chunkForGemini(items)
		val output = ArrayList<TranslatedTextItem>(items.size)

		for (batch in batches) {
			try {
				val batchResult = translateBatchWithGeminiPool(
					batch = batch,
					settings = settings,
					targetLang = targetLang,
					keys = keys,
				)
				output.addAll(batchResult)
			} catch (e: CancellationException) {
				throw e
			} catch (e: MissingGeminiApiKeyException) {
				throw e
			} catch (e: InvalidGeminiApiKeyException) {
				throw e
			} catch (e: TranslationException) {
				Log.w(
					LOG_TAG,
					"Gemini failed (${e.message}); falling back to Google Translate for ${batch.size} bubble(s)",
					e,
				)
				// All Gemini keys hit quota/cooldown or transient failure -> seamless Google Translate fallback
				output.addAll(
					try {
						translateWithGoogle(batch, settings, targetLang)
					} catch (googleError: CancellationException) {
						throw googleError
					} catch (googleError: Throwable) {
						e.addSuppressed(googleError)
						throw e
					},
				)
			}
		}

		return output
	}

	private suspend fun translateBatchWithGeminiPool(
		batch: List<PreparedBubble>,
		settings: TranslationSettingsSnapshot,
		targetLang: String,
		keys: List<String>,
	): List<TranslatedTextItem> {
		val maxAttempts = maxOf(6, keys.size * 3)
		var lastErrorInfo: GeminiHttpErrorInfo? = null
		var lastThrowable: Throwable? = null
		var attempt = 0

		while (attempt < maxAttempts) {
			val lease = keyPool.acquireKey(keys)
			var keyReleased = false
			try {
				val outcome = try {
					executeGeminiWithModelAndThinkingFallback(
						batch = batch,
						settings = settings,
						targetLang = targetLang,
						apiKey = lease.key,
					)
				} catch (e: TooManyRequestExceptions) {
					val delayMs = parseRetryAfterFromException(e)
					GeminiCallOutcome.Failure(
						GeminiHttpErrorInfo(
							statusCode = 429,
							kind = GeminiHttpErrorKind.RATE_LIMIT,
							message = e.message ?: "HTTP 429 Rate Limited",
							retryDelayMs = delayMs,
						),
					)
				} catch (e: IOException) {
					GeminiCallOutcome.Failure(
						GeminiHttpErrorInfo(
							statusCode = 0,
							kind = GeminiHttpErrorKind.TRANSIENT,
							message = e.message ?: "Network I/O error",
						),
					)
				}

				when (outcome) {
					is GeminiCallOutcome.Success -> {
						keyPool.markSuccess(lease.key)
						return fillMissingItemsWithGoogle(
							batch = batch,
							parsed = outcome.items,
							settings = settings,
							targetLang = targetLang,
						)
					}

					is GeminiCallOutcome.Failure -> {
						lastErrorInfo = outcome.info
						when (outcome.info.kind) {
							GeminiHttpErrorKind.INVALID_KEY -> {
								keyPool.markInvalid(lease.key, outcome.info.message)
							}

							GeminiHttpErrorKind.RATE_LIMIT -> {
								keyPool.markRateLimited(
									key = lease.key,
									retryDelayMs = outcome.info.retryDelayMs,
									errorMessage = outcome.info.message,
								)
							}

							GeminiHttpErrorKind.TRANSIENT -> {
								keyPool.releaseKey(lease.key)
								keyReleased = true
								val backoffMs = computeTransientBackoffMs(attempt)
								delay(backoffMs)
							}

							GeminiHttpErrorKind.MODEL_NOT_FOUND -> {
								throw TranslationServiceException(
									statusCode = outcome.info.statusCode,
									isTransient = false,
									message = "Gemini model unavailable: ${outcome.info.message}",
								)
							}

							GeminiHttpErrorKind.THINKING_CONFIG_UNSUPPORTED,
							GeminiHttpErrorKind.OTHER,
							-> {
								if (attempt + 1 >= maxAttempts) {
									throw TranslationServiceException(
										statusCode = outcome.info.statusCode,
										isTransient = false,
										message = "Gemini request rejected (${outcome.info.statusCode}): ${outcome.info.message}",
									)
								}
								keyPool.releaseKey(lease.key)
								keyReleased = true
								delay(computeTransientBackoffMs(attempt))
							}
						}
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: TranslationException) {
				throw e
			} catch (e: Throwable) {
				lastThrowable = e
				keyPool.releaseKey(lease.key)
				keyReleased = true
				delay(computeTransientBackoffMs(attempt))
			} finally {
				if (!keyReleased) {
					keyPool.releaseKey(lease.key)
				}
			}

			attempt += 1
		}

		val finalInfo = lastErrorInfo
		throw TranslationServiceException(
			statusCode = finalInfo?.statusCode ?: 503,
			isTransient = finalInfo?.kind == GeminiHttpErrorKind.TRANSIENT || finalInfo?.kind == GeminiHttpErrorKind.RATE_LIMIT,
			message = finalInfo?.message ?: lastThrowable?.message ?: "Gemini translation failed after $maxAttempts attempts.",
			cause = lastThrowable,
		)
	}

	private suspend fun executeGeminiWithModelAndThinkingFallback(
		batch: List<PreparedBubble>,
		settings: TranslationSettingsSnapshot,
		targetLang: String,
		apiKey: String,
	): GeminiCallOutcome {
		val now = System.currentTimeMillis()
		val initialModel = if (now < fallbackModelUntilMs) FALLBACK_GEMINI_MODEL else PRIMARY_GEMINI_MODEL
		val modelsToTry = if (initialModel == PRIMARY_GEMINI_MODEL) {
			listOf(PRIMARY_GEMINI_MODEL, FALLBACK_GEMINI_MODEL)
		} else {
			listOf(FALLBACK_GEMINI_MODEL)
		}

		var lastFailure: GeminiCallOutcome.Failure? = null

		for (model in modelsToTry) {
			var thinkingMode = preferredThinkingModeByModel[model] ?: ThinkingMode.MINIMAL_LEVEL

			while (true) {
				val bodyJson = buildGeminiPayload(
					batch = batch,
					settings = settings,
					targetLang = targetLang,
					thinkingMode = thinkingMode,
				)
				val url = "$GEMINI_API_BASE/models/$model:generateContent"
				val request = Request.Builder()
					.url(url)
					.header("x-goog-api-key", apiKey)
					.header("Content-Type", "application/json; charset=utf-8")
					.header("Accept", "application/json")
					.post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
					.build()

				val client = withContext(Dispatchers.IO) { httpClient }
				val response = client.newCall(request).await()
				val statusCode = response.code
				val retryAfterHeader = response.header("Retry-After")
				val responseText = response.use { resp ->
					resp.body.string()
				}

				if (statusCode in 200..299) {
					val parsedItems = parseGeminiResponse(responseText, batch, settings, targetLang)
					if (parsedItems != null) {
						Log.d(LOG_TAG, "Gemini HTTP $statusCode: parsed ${parsedItems.size}/${batch.size} via $model/$thinkingMode")
						return GeminiCallOutcome.Success(parsedItems)
					}
					Log.w(LOG_TAG, "Gemini HTTP $statusCode unparseable/empty response via $model/$thinkingMode")
					lastFailure = GeminiCallOutcome.Failure(
						GeminiHttpErrorInfo(
							statusCode = statusCode,
							kind = GeminiHttpErrorKind.OTHER,
							message = "Gemini returned empty, blocked, or invalid JSON response.",
						),
					)
					break
				}
				Log.w(LOG_TAG, "Gemini HTTP $statusCode error via $model/$thinkingMode: ${responseText.take(200)}")

				val errorInfo = GeminiKeyPool.classifyGeminiHttpError(
					statusCode = statusCode,
					responseBody = responseText,
					retryAfterHeader = retryAfterHeader,
				)

				if (errorInfo.kind == GeminiHttpErrorKind.THINKING_CONFIG_UNSUPPORTED) {
					val downgraded = when (thinkingMode) {
						ThinkingMode.MINIMAL_LEVEL -> ThinkingMode.ZERO_BUDGET
						ThinkingMode.ZERO_BUDGET -> ThinkingMode.OMITTED
						ThinkingMode.OMITTED -> null
					}
					if (downgraded != null) {
						thinkingMode = downgraded
						preferredThinkingModeByModel[model] = downgraded
						continue
					}
				}

				if (errorInfo.kind == GeminiHttpErrorKind.MODEL_NOT_FOUND && model == PRIMARY_GEMINI_MODEL) {
					fallbackModelUntilMs = System.currentTimeMillis() + MODEL_FALLBACK_CACHE_MS
					lastFailure = GeminiCallOutcome.Failure(errorInfo)
					break
				}

				return GeminiCallOutcome.Failure(errorInfo)
			}
		}

		return lastFailure ?: GeminiCallOutcome.Failure(
			GeminiHttpErrorInfo(
				statusCode = 500,
				kind = GeminiHttpErrorKind.TRANSIENT,
				message = "No Gemini model succeeded.",
			),
		)
	}

	private suspend fun fillMissingItemsWithGoogle(
		batch: List<PreparedBubble>,
		parsed: List<TranslatedTextItem>,
		settings: TranslationSettingsSnapshot,
		targetLang: String,
	): List<TranslatedTextItem> {
		val byId = LinkedHashMap<String, TranslatedTextItem>(parsed.size)
		for (item in parsed) {
			byId[item.id] = item
		}

		val missing = batch.filter { it.bubble.id !in byId }
		if (missing.isEmpty()) {
			return batch.mapNotNull { byId[it.bubble.id] }
		}

		val fallbackTranslated = runCatching {
			translateWithGoogle(missing, settings, targetLang)
		}.getOrElse { e ->
			if (e is CancellationException) throw e
			emptyList()
		}

		for (item in fallbackTranslated) {
			byId[item.id] = item
		}

		return batch.map { prep ->
			byId[prep.bubble.id] ?: TranslatedTextItem(
				id = prep.bubble.id,
				text = prep.cleanText,
				kind = prep.bubble.kindHint,
				isTrash = false,
			)
		}
	}

	private fun buildGeminiPayload(
		batch: List<PreparedBubble>,
		settings: TranslationSettingsSnapshot,
		targetLang: String,
		thinkingMode: ThinkingMode,
	): JSONObject {
		val targetName = TranslationLanguages.getPromptLanguageName(targetLang)
		val sourceName = settings.sourceLang.toGeminiPromptLabel()

		val systemPrompt = buildGeminiSystemPrompt(
			sourceLangLabel = sourceName,
			targetLangLabel = targetName,
			targetLangCode = targetLang,
			skipPureSfx = settings.skipPureSfx,
			filterPromoAndCredits = settings.filterPromoAndCredits,
		)

		val inputArray = JSONArray()
		for (item in batch) {
			inputArray.put(
				JSONObject().apply {
					put("id", item.bubble.id)
					put("hint", item.bubble.kindHint.name.lowercase(Locale.ROOT))
					put("text", item.cleanText)
				},
			)
		}

		val userPrompt = buildString {
			append("Translate the following ")
			append(batch.size)
			append(" manga/webtoon speech bubble entries from ")
			append(sourceName)
			append(" into ")
			append(targetName)
			append(" (")
			append(targetLang)
			append("). Preserve every entry's exact \"id\" and return a JSON array of objects with keys id, text, kind, trash.\n")
			append(inputArray.toString())
		}

		val generationConfig = JSONObject().apply {
			put("temperature", 0.25)
			put("topP", 0.9)
			put("maxOutputTokens", 4096)
			put("responseMimeType", "application/json")
			put("responseSchema", buildGeminiResponseSchema())
			when (thinkingMode) {
				ThinkingMode.MINIMAL_LEVEL -> {
					put("thinkingConfig", JSONObject().put("thinkingLevel", "MINIMAL"))
				}

				ThinkingMode.ZERO_BUDGET -> {
					put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
				}

				ThinkingMode.OMITTED -> Unit
			}
		}

		return JSONObject().apply {
			put(
				"systemInstruction",
				JSONObject().put(
					"parts",
					JSONArray().put(JSONObject().put("text", systemPrompt)),
				),
			)
			put(
				"contents",
				JSONArray().put(
					JSONObject()
						.put("role", "user")
						.put("parts", JSONArray().put(JSONObject().put("text", userPrompt))),
				),
			)
			put("generationConfig", generationConfig)
			put("safetySettings", buildPermissiveSafetySettings())
		}
	}

	private fun buildGeminiSystemPrompt(
		sourceLangLabel: String,
		targetLangLabel: String,
		targetLangCode: String,
		skipPureSfx: Boolean,
		filterPromoAndCredits: Boolean,
	): String = buildString {
		appendLine("You are a veteran manga, manhwa, and webtoon localization translator translating OCR text blocks from $sourceLangLabel into natural, conversational $targetLangLabel ($targetLangCode).")
		appendLine()
		appendLine("CONTEXT & FLOW:")
		appendLine("- Entries come in page reading order; use surrounding bubbles to keep pronouns, speaker tone, politeness register, and continuity consistent.")
		appendLine("- Cleanly infer and fix minor OCR typos, broken hyphenation, or missing spaces while translating.")
		appendLine()
		appendLine("NAMES, HONORIFICS & GAME/FANTASY TERMS:")
		appendLine("- Keep character names and Asian honorifics natural for comic readers (e.g., -san, -kun, -chan, -sama, -sensei, -senpai, Oppa, Hyung, Unnie, Noona, Sunbae, -ssi, -nim) adapted appropriately for $targetLangLabel without literally translating family-style suffixes.")
		appendLine("- DO translate descriptive nicknames, titles, guild/rank names, monster/skill names, system alerts, and RPG UI terms (e.g., LVL, HP, Quest, Status Window) into $targetLangLabel.")
		appendLine()
		appendLine("BUBBLE CONCISENESS & FORMATTING:")
		appendLine("- Keep translated lines concise, idiomatic, and punchy so they fit comfortably inside speech bubbles without stiff literal phrasing.")
		appendLine("- Return plain text only (never output HTML tags, markdown, translator notes, or explanatory glosses).")
		appendLine("- Never leave spoken dialogue empty or echo the source sentence unchanged unless it is strictly a proper noun, numeral, or universal loanword.")
		appendLine()
		appendLine("CLASSIFICATION (kind) & TRASH RULES:")
		appendLine("- Valid kind values: \"dialogue\", \"narration\", \"sfx\", \"emphasis\".")
		if (filterPromoAndCredits) {
			appendLine("- Set \"trash\": true and \"text\": \"\" for scanlation credits, staff recruitment, website URLs/watermarks, Discord/Patreon promos, or stray page numbers.")
		} else {
			appendLine("- Preserve credits/promo text if readable; set \"trash\": true only for completely unreadable OCR gibberish.")
		}
		if (skipPureSfx) {
			appendLine("- Set \"trash\": true, \"kind\": \"sfx\", and \"text\": \"\" ONLY for pure standalone sound effects/onomatopoeia (e.g., BAM, WHOOSH, THUD, DOKIDOKI) or meaningless non-verbal vocal fillers. Keep real conversational reactions like \"Huh?\", \"What?!\", \"Tch!\", or \"Wait!\" as \"dialogue\" with \"trash\": false.")
		} else {
			appendLine("- Keep sound effects: translate or stylize SFX naturally into $targetLangLabel with \"kind\": \"sfx\" and \"trash\": false.")
		}
		append("- Return strictly a JSON array: [{\"id\":\"...\",\"text\":\"...\",\"kind\":\"dialogue|narration|sfx|emphasis\",\"trash\":false}].")
	}

	private fun buildGeminiResponseSchema(): JSONObject {
		val kindEnum = JSONArray()
			.put("dialogue")
			.put("narration")
			.put("sfx")
			.put("emphasis")

		val itemProperties = JSONObject()
			.put("id", JSONObject().put("type", "STRING"))
			.put("text", JSONObject().put("type", "STRING"))
			.put("kind", JSONObject().put("type", "STRING").put("enum", kindEnum))
			.put("trash", JSONObject().put("type", "BOOLEAN"))

		val itemSchema = JSONObject()
			.put("type", "OBJECT")
			.put("properties", itemProperties)
			.put("required", JSONArray().put("id").put("text").put("kind").put("trash"))

		return JSONObject()
			.put("type", "ARRAY")
			.put("items", itemSchema)
	}

	private fun buildPermissiveSafetySettings(): JSONArray {
		val categories = listOf(
			"HARM_CATEGORY_HARASSMENT",
			"HARM_CATEGORY_HATE_SPEECH",
			"HARM_CATEGORY_SEXUALLY_EXPLICIT",
			"HARM_CATEGORY_DANGEROUS_CONTENT",
		)
		return JSONArray().apply {
			for (category in categories) {
				put(
					JSONObject()
						.put("category", category)
						.put("threshold", "BLOCK_NONE"),
				)
			}
		}
	}

	private fun parseGeminiResponse(
		responseBody: String,
		batch: List<PreparedBubble>,
		settings: TranslationSettingsSnapshot,
		targetLang: String,
	): List<TranslatedTextItem>? {
		if (responseBody.isBlank()) return null

		val rawContent = runCatching {
			val root = JSONObject(responseBody)
			val candidates = root.optJSONArray("candidates") ?: return@runCatching null
			if (candidates.length() == 0) return@runCatching null
			val first = candidates.optJSONObject(0) ?: return@runCatching null
			val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: return@runCatching null
			buildString {
				for (i in 0 until parts.length()) {
					val part = parts.optJSONObject(i) ?: continue
					if (part.optBoolean("thought", false)) continue
					append(part.optString("text", ""))
				}
			}.trim()
		}.getOrNull().orEmpty()

		if (rawContent.isBlank()) return null

		val jsonArray = extractJsonArray(rawContent) ?: return null
		if (jsonArray.length() == 0 && batch.isNotEmpty()) return null

		val batchById = batch.associateBy { it.bubble.id }
		val resultsById = LinkedHashMap<String, TranslatedTextItem>(batch.size)

		for (i in 0 until jsonArray.length()) {
			val obj = jsonArray.optJSONObject(i) ?: continue
			var id = obj.optString("id", "").trim()
			if (id.isEmpty() || id !in batchById) {
				if (jsonArray.length() == batch.size && i < batch.size && batch[i].bubble.id !in resultsById) {
					id = batch[i].bubble.id
				} else {
					continue
				}
			}
			val prep = batchById[id] ?: continue
			val parsedKind = parseKind(obj.optString("kind", ""), prep.bubble.kindHint)
			val cleanedText = cleanTranslatedOutput(obj.optString("text", ""))
			val rawTrash = obj.optBoolean("trash", false)

			val finalTrash = when {
				rawTrash && parsedKind == TextBlockKind.SFX && !settings.skipPureSfx && cleanedText.isNotEmpty() -> false
				rawTrash -> true
				cleanedText.isBlank() -> true
				else -> false
			}

			val finalText = if (finalTrash) "" else cleanedText
			resultsById[id] = TranslatedTextItem(
				id = id,
				text = finalText,
				kind = parsedKind,
				isTrash = finalTrash,
			)
		}

		if (resultsById.isEmpty()) return null

		// Guard against LLM returning multiple multi-word English lines verbatim without translating
		if (shouldRejectUnchangedBatch(batch, resultsById, settings.sourceLang, targetLang)) {
			return null
		}

		return resultsById.values.toList()
	}

	private fun extractJsonArray(raw: String): JSONArray? {
		var trimmed = raw.trim()
		if (trimmed.startsWith("```")) {
			trimmed = trimmed
				.removePrefix("```json")
				.removePrefix("```JSON")
				.removePrefix("```")
				.trimStart()
			if (trimmed.endsWith("```")) {
				trimmed = trimmed.removeSuffix("```").trimEnd()
			}
		}

		runCatching {
			if (trimmed.startsWith("[")) {
				return JSONArray(trimmed)
			}
			if (trimmed.startsWith("{")) {
				val obj = JSONObject(trimmed)
				obj.optJSONArray("items")?.let { return it }
				obj.optJSONArray("translations")?.let { return it }
				obj.optJSONArray("results")?.let { return it }
			}
		}

		val start = trimmed.indexOf('[')
		val end = trimmed.lastIndexOf(']')
		if (start >= 0 && end > start) {
			return runCatching { JSONArray(trimmed.substring(start, end + 1)) }.getOrNull()
		}
		return null
	}

	private suspend fun translateWithGoogle(
		items: List<PreparedBubble>,
		settings: TranslationSettingsSnapshot,
		targetLang: String,
	): List<TranslatedTextItem> {
		if (items.isEmpty()) return emptyList()

		val chunks = chunkForGoogleGet(items)
		val results = ArrayList<TranslatedTextItem>(items.size)

		for (chunk in chunks) {
			if (chunk.size == 1) {
				val single = translateSingleItemWithGoogle(chunk[0], settings, targetLang)
				results.add(single)
			} else {
				val batchTranslated = runCatching {
					translateChunkWithGoogleMarkers(chunk, settings, targetLang)
				}.getOrElse { e ->
					if (e is CancellationException) throw e
					Log.w(LOG_TAG, "Google marker batch failed (${e.message}); falling back to individual GETs", e)
					null
				}

				if (batchTranslated != null) {
					Log.d(LOG_TAG, "Google marker batch translated ${batchTranslated.size} bubble(s)")
					results.addAll(batchTranslated)
				} else {
					Log.d(LOG_TAG, "Google marker parse missed; translating ${chunk.size} bubble(s) individually")
					results.addAll(translateChunkIndividuallyWithGoogle(chunk, settings, targetLang))
				}
			}
		}

		return results
	}

	private suspend fun translateChunkWithGoogleMarkers(
		chunk: List<PreparedBubble>,
		settings: TranslationSettingsSnapshot,
		targetLang: String,
	): List<TranslatedTextItem>? {
		val combinedQuery = buildString {
			for (i in chunk.indices) {
				if (i > 0) append("\n")
				append("⟦K")
				append(i)
				append("⟧ ")
				append(chunk[i].cleanText)
			}
		}

		val rawTranslated = executeGoogleTranslateGet(
			query = combinedQuery,
			sourceLang = settings.sourceLang,
			targetLang = targetLang,
		)

		val parsedByIndex = parseMarkedGoogleOutput(rawTranslated, chunk.size) ?: return null
		return chunk.mapIndexed { index, prep ->
			val text = cleanTranslatedOutput(parsedByIndex[index].orEmpty())
			if (text.isEmpty()) return null
			TranslatedTextItem(
				id = prep.bubble.id,
				text = text,
				kind = prep.bubble.kindHint,
				isTrash = false,
			)
		}
	}

	private fun parseMarkedGoogleOutput(
		raw: String,
		expectedCount: Int,
	): Map<Int, String>? {
		if (raw.isBlank()) return null
		val validMatches = GOOGLE_MARKER_REGEX.findAll(raw).mapNotNull { match ->
			val idx = match.groupValues.getOrNull(1)?.toIntOrNull()
			if (idx != null && idx in 0 until expectedCount) idx to match else null
		}.toList()
		if (validMatches.size != expectedCount) return null

		val segments = HashMap<Int, String>(expectedCount)
		for (i in validMatches.indices) {
			val (idx, match) = validMatches[i]
			if (idx in segments) return null
			val contentStart = match.range.last + 1
			val contentEnd = if (i + 1 < validMatches.size) validMatches[i + 1].second.range.first else raw.length
			val piece = if (contentStart <= contentEnd) {
				raw.substring(contentStart, contentEnd).trim()
			} else {
				""
			}
			segments[idx] = piece
		}

		if (segments.size != expectedCount) return null
		for (i in 0 until expectedCount) {
			if (segments[i].isNullOrBlank()) return null
		}
		return segments
	}

	private suspend fun translateChunkIndividuallyWithGoogle(
		chunk: List<PreparedBubble>,
		settings: TranslationSettingsSnapshot,
		targetLang: String,
	): List<TranslatedTextItem> = coroutineScope {
		val semaphore = Semaphore(GOOGLE_SINGLE_CONCURRENCY)
		chunk.map { prep ->
			async {
				semaphore.withPermit {
					translateSingleItemWithGoogle(prep, settings, targetLang)
				}
			}
		}.awaitAll()
	}

	private suspend fun translateSingleItemWithGoogle(
		prep: PreparedBubble,
		settings: TranslationSettingsSnapshot,
		targetLang: String,
	): TranslatedTextItem {
		val rawTranslated = executeGoogleTranslateGet(
			query = prep.cleanText,
			sourceLang = settings.sourceLang,
			targetLang = targetLang,
		)
		val cleaned = cleanTranslatedOutput(rawTranslated).ifBlank { prep.cleanText }
		return TranslatedTextItem(
			id = prep.bubble.id,
			text = cleaned,
			kind = prep.bubble.kindHint,
			isTrash = false,
		)
	}

	private suspend fun executeGoogleTranslateGet(
		query: String,
		sourceLang: OcrSourceLanguage,
		targetLang: String,
	): String {
		val url = GOOGLE_TRANSLATE_ENDPOINT.toHttpUrl().newBuilder()
			.addQueryParameter("client", "gtx")
			.addQueryParameter("sl", sourceLang.toGoogleSourceCode())
			.addQueryParameter("tl", TranslationLanguages.toGoogleTargetCode(targetLang))
			.addQueryParameter("dt", "t")
			.addQueryParameter("q", query)
			.build()

		var lastError: Throwable? = null
		for (attempt in 0 until GOOGLE_MAX_ATTEMPTS) {
			try {
				val request = Request.Builder()
					.url(url)
					.header("User-Agent", GOOGLE_USER_AGENT)
					.header("Accept", "application/json")
					.get()
					.build()

				val client = withContext(Dispatchers.IO) { httpClient }
				val response = client.newCall(request).await()
				val statusCode = response.code
				val body = response.use { resp ->
					resp.body.string()
				}

				if (statusCode in 200..299) {
					return parseGoogleTranslateJson(body)
				}
				Log.w(LOG_TAG, "Google Translate HTTP $statusCode (attempt ${attempt + 1}/$GOOGLE_MAX_ATTEMPTS): ${body.take(160)}")

				val isTransient = statusCode == 429 || statusCode == 408 || statusCode in 500..599
				val error = TranslationServiceException(
					statusCode = statusCode,
					isTransient = isTransient,
					message = "Google Translate failed with HTTP $statusCode",
				)
				if (!isTransient || attempt + 1 >= GOOGLE_MAX_ATTEMPTS) {
					throw error
				}
				lastError = error
			} catch (e: CancellationException) {
				throw e
			} catch (e: TooManyRequestExceptions) {
				if (attempt + 1 >= GOOGLE_MAX_ATTEMPTS) {
					throw TranslationServiceException(
						statusCode = 429,
						isTransient = true,
						message = e.message ?: "Google Translate rate limited (HTTP 429)",
						cause = e,
					)
				}
				lastError = e
			} catch (e: TranslationException) {
				throw e
			} catch (e: IOException) {
				if (attempt + 1 >= GOOGLE_MAX_ATTEMPTS) {
					throw TranslationServiceException(
						statusCode = 0,
						isTransient = true,
						message = e.message ?: "Google Translate network error",
						cause = e,
					)
				}
				lastError = e
			}

			val backoffMs = minOf(3_200L, (700L * 2.0.pow(attempt.toDouble())).toLong())
			delay(backoffMs)
		}

		throw TranslationServiceException(
			statusCode = 503,
			isTransient = true,
			message = lastError?.message ?: "Google Translate failed",
			cause = lastError,
		)
	}

	private fun parseGoogleTranslateJson(body: String): String {
		if (body.isBlank()) {
			throw TranslationServiceException(502, isTransient = true, message = "Google Translate returned empty body")
		}
		return runCatching {
			val root = JSONArray(body)
			val sentences = root.optJSONArray(0) ?: return ""
			buildString {
				for (i in 0 until sentences.length()) {
					val segment = sentences.optJSONArray(i) ?: continue
					val piece = segment.optString(0, "")
					if (piece.isNotEmpty() && piece != "null") {
						append(piece)
					}
				}
			}
		}.getOrElse { e ->
			throw TranslationServiceException(
				statusCode = 502,
				isTransient = true,
				message = "Failed to parse Google Translate response",
				cause = e,
			)
		}
	}

	private fun chunkForGemini(items: List<PreparedBubble>): List<List<PreparedBubble>> {
		if (items.size <= GEMINI_MAX_BATCH_ITEMS) {
			val totalChars = items.sumOf { it.cleanText.length }
			if (totalChars <= GEMINI_MAX_BATCH_CHARS) return listOf(items)
		}

		val chunks = ArrayList<List<PreparedBubble>>()
		var current = ArrayList<PreparedBubble>()
		var currentChars = 0

		for (item in items) {
			val itemLen = item.cleanText.length
			if (current.isNotEmpty() && (current.size >= GEMINI_MAX_BATCH_ITEMS || currentChars + itemLen > GEMINI_MAX_BATCH_CHARS)) {
				chunks.add(current)
				current = ArrayList()
				currentChars = 0
			}
			current.add(item)
			currentChars += itemLen
		}
		if (current.isNotEmpty()) {
			chunks.add(current)
		}
		return chunks
	}

	private fun chunkForGoogleGet(items: List<PreparedBubble>): List<List<PreparedBubble>> {
		val chunks = ArrayList<List<PreparedBubble>>()
		var current = ArrayList<PreparedBubble>()
		var estimatedEncodedBytes = 0

		for (item in items) {
			val itemEncodedBytes = estimateUrlEncodedBytes(item.cleanText) + 24
			if (current.isNotEmpty() &&
				(current.size >= GOOGLE_MAX_BATCH_ITEMS || estimatedEncodedBytes + itemEncodedBytes > GOOGLE_MAX_ENCODED_QUERY_BYTES)
			) {
				chunks.add(current)
				current = ArrayList()
				estimatedEncodedBytes = 0
			}
			current.add(item)
			estimatedEncodedBytes += itemEncodedBytes
		}
		if (current.isNotEmpty()) {
			chunks.add(current)
		}
		return chunks
	}

	private fun estimateUrlEncodedBytes(text: String): Int {
		var bytes = 0
		for (ch in text) {
			val code = ch.code
			bytes += when {
				code < 0x80 -> if (ch.isLetterOrDigit() || ch == ' ') 1 else 3
				code < 0x800 -> 6
				else -> 9
			}
		}
		return bytes
	}

	private fun evaluatePrefilter(
		cleanText: String,
		bubble: OcrBubbleGroup,
		settings: TranslationSettingsSnapshot,
		targetLang: String,
	): TranslatedTextItem? {
		if (cleanText.isBlank() || PURE_PUNCT_OR_DIGITS_REGEX.matches(cleanText)) {
			return TranslatedTextItem(
				id = bubble.id,
				text = "",
				kind = bubble.kindHint,
				isTrash = true,
			)
		}

		if (settings.filterPromoAndCredits && isPromoOrCredit(cleanText, settings.sourceLang)) {
			return TranslatedTextItem(
				id = bubble.id,
				text = "",
				kind = bubble.kindHint,
				isTrash = true,
			)
		}

		if (settings.skipPureSfx && isPureSfxOrNoise(cleanText, bubble.kindHint)) {
			return TranslatedTextItem(
				id = bubble.id,
				text = "",
				kind = TextBlockKind.SFX,
				isTrash = true,
			)
		}

		if (isLikelyAlreadyTranslated(cleanText, settings.sourceLang, targetLang)) {
			return TranslatedTextItem(
				id = bubble.id,
				text = cleanText,
				kind = bubble.kindHint,
				isTrash = false,
			)
		}

		return null
	}

	private fun isPromoOrCredit(
		text: String,
		sourceLang: OcrSourceLanguage,
	): Boolean {
		if (CREDITS_START_REGEX.containsMatchIn(text)) return true
		if (CREDITS_INLINE_REGEX.containsMatchIn(text)) return true
		if (WATERMARK_DOMAIN_REGEX.containsMatchIn(text) && text.split(' ').size <= 8) return true

		if (sourceLang == OcrSourceLanguage.ENGLISH) {
			val hasCjkOrHangul = text.any(::isCjkOrHangul)
			val hasAsciiLetters = text.any { it in 'a'..'z' || it in 'A'..'Z' }
			if (hasCjkOrHangul && !hasAsciiLetters) return true
		}

		return false
	}

	private fun isPureSfxOrNoise(
		text: String,
		kindHint: TextBlockKind,
	): Boolean {
		if (SFX_WRAPPED_REGEX.matches(text)) return true
		val words = text.split(' ').filter { it.isNotBlank() }
		if (words.isNotEmpty() && words.all { VOCAL_OR_SFX_TOKEN_REGEX.matches(it) }) {
			return true
		}
		if (kindHint == TextBlockKind.SFX && words.size <= 3 && text.length <= 16 && !text.contains('?')) {
			return true
		}
		return false
	}

	private fun isLikelyAlreadyTranslated(
		text: String,
		sourceLang: OcrSourceLanguage,
		targetLang: String,
	): Boolean {
		val target = TranslationLanguages.normalizeTargetCode(targetLang).lowercase(Locale.ROOT)
		if (target == sourceLang.code.lowercase(Locale.ROOT)) {
			return true
		}
		if (target == "zh-tw" && sourceLang == OcrSourceLanguage.CHINESE) {
			return false
		}

		val letters = text.filter { it.isLetter() }
		if (letters.isEmpty()) return false
		val letterCount = letters.length

		return when (target) {
			"tr" -> {
				val turkishSpecific = letters.count { it in TURKISH_UNIQUE_CHARS }
				val latinCount = letters.count { it.code in 0x0041..0x024F }
				val hasStopword = TURKISH_STOPWORD_REGEX.containsMatchIn(text)
				latinCount >= letterCount * 0.8 && (turkishSpecific >= 2 || (turkishSpecific >= 1 && hasStopword))
			}

			"ko" -> letters.count(::isHangul) > letterCount * 0.5
			"ja" -> letters.any(::isKana) && letters.count(::isCjkOrKana) > letterCount * 0.5
			"zh", "zh-tw" -> !letters.any(::isHangul) && !letters.any(::isKana) &&
				letters.count(::isHan) > letterCount * 0.5

			"ru", "uk", "bg", "sr", "be", "kk", "ky", "mk" -> {
				sourceLang != OcrSourceLanguage.CYRILLIC &&
					letters.count { it in '\u0400'..'\u04FF' } > letterCount * 0.6
			}

			"ar", "fa", "ur" -> {
				sourceLang != OcrSourceLanguage.ARABIC &&
					letters.count { it in '\u0600'..'\u06FF' } > letterCount * 0.6
			}

			"he" -> letters.count { it in '\u0590'..'\u05FF' } > letterCount * 0.6
			"th" -> letters.count { it in '\u0E00'..'\u0E7F' } > letterCount * 0.6
			else -> false
		}
	}

	private fun shouldRejectUnchangedBatch(
		batch: List<PreparedBubble>,
		results: Map<String, TranslatedTextItem>,
		sourceLang: OcrSourceLanguage,
		targetLang: String,
	): Boolean {
		if (sourceLang.code.equals(targetLang, ignoreCase = true)) return false
		var checkedMultiWord = 0
		var unchangedCount = 0

		for (prep in batch) {
			val words = prep.cleanText.split(' ').filter { it.length >= 2 }
			if (words.size < 3) continue
			val item = results[prep.bubble.id] ?: continue
			if (item.isTrash) continue
			checkedMultiWord += 1
			if (normalizeForCompare(item.text) == normalizeForCompare(prep.cleanText)) {
				unchangedCount += 1
			}
		}

		return checkedMultiWord >= 3 && unchangedCount == checkedMultiWord
	}

	private fun normalizeBubbleInput(raw: String): String {
		if (raw.isBlank()) return ""
		return raw
			.replace('\u00A0', ' ')
			.replace("\u200B", "")
			.replace("\uFEFF", "")
			.replace("\r\n", "\n")
			.replace('\r', '\n')
			.replace(MULTILINE_SPACE_REGEX, " ")
			.trim()
	}

	private fun cleanTranslatedOutput(raw: String): String {
		if (raw.isBlank()) return ""
		return raw
			.replace(HTML_TAG_REGEX, " ")
			.replace("\r\n", "\n")
			.replace('\r', '\n')
			.replace(HORIZONTAL_SPACE_REGEX, " ")
			.replace(EXCESS_NEWLINES_REGEX, "\n\n")
			.trim()
	}

	private fun normalizeForCompare(text: String): String {
		return text.lowercase(Locale.ROOT)
			.replace(COMPARE_STRIP_REGEX, "")
			.trim()
	}

	private fun parseKind(
		raw: String,
		fallback: TextBlockKind,
	): TextBlockKind = when (raw.trim().lowercase(Locale.ROOT)) {
		"dialogue" -> TextBlockKind.DIALOGUE
		"narration" -> TextBlockKind.NARRATION
		"sfx" -> TextBlockKind.SFX
		"emphasis" -> TextBlockKind.EMPHASIS
		else -> fallback
	}

	private fun parseRetryAfterFromException(e: TooManyRequestExceptions): Long? {
		return e.getRetryDelay().takeIf { it > 0L }
	}

	private fun computeTransientBackoffMs(attempt: Int): Long {
		return min(MAX_TRANSIENT_BACKOFF_MS, (BASE_TRANSIENT_BACKOFF_MS * 1.6.pow(attempt)).toLong())
	}

	private fun isHangul(ch: Char): Boolean = ch in '\uAC00'..'\uD7AF' || ch in '\u1100'..'\u11FF' || ch in '\u3130'..'\u318F'
	private fun isKana(ch: Char): Boolean = ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF'
	private fun isHan(ch: Char): Boolean = ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF'
	private fun isCjkOrKana(ch: Char): Boolean = isHan(ch) || isKana(ch)
	private fun isCjkOrHangul(ch: Char): Boolean = isHan(ch) || isKana(ch) || isHangul(ch)

	companion object {

		private const val LOG_TAG = "KotatsuTranslate"
		const val PRIMARY_GEMINI_MODEL: String = "gemini-3.5-flash-lite"
		const val FALLBACK_GEMINI_MODEL: String = "gemini-flash-lite-latest"

		private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta"
		private const val GOOGLE_TRANSLATE_ENDPOINT = "https://translate.googleapis.com/translate_a/single"
		private const val GOOGLE_USER_AGENT =
			"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

		private const val GEMINI_MAX_BATCH_ITEMS = 32
		private const val GEMINI_MAX_BATCH_CHARS = 12_000
		private const val MODEL_FALLBACK_CACHE_MS = 30L * 60L * 1000L

		private const val GOOGLE_MAX_BATCH_ITEMS = 10
		private const val GOOGLE_MAX_ENCODED_QUERY_BYTES = 1_100
		private const val GOOGLE_SINGLE_CONCURRENCY = 4
		private const val GOOGLE_MAX_ATTEMPTS = 3

		private const val BASE_TRANSIENT_BACKOFF_MS = 800.0
		private const val MAX_TRANSIENT_BACKOFF_MS = 3_500L

		private val TURKISH_UNIQUE_CHARS = charArrayOf('ğ', 'Ğ', 'ş', 'Ş', 'ı', 'İ')
		private val TURKISH_STOPWORD_REGEX = Regex(
			"\\b(?:ve|bir|bu|şu|değil|ne|için|ben|sen|ama|fakat|çok|daha|gibi|olarak)\\b",
			RegexOption.IGNORE_CASE,
		)

		private val PURE_PUNCT_OR_DIGITS_REGEX = Regex("^[\\s\\p{P}\\p{S}\\d_~`^+|\\\\/]+$")
		private val MULTILINE_SPACE_REGEX = Regex("\\s+")
		private val HORIZONTAL_SPACE_REGEX = Regex("[ \\t]{2,}")
		private val EXCESS_NEWLINES_REGEX = Regex("\\n{3,}")
		private val HTML_TAG_REGEX = Regex("<[^>]{1,32}>")
		private val COMPARE_STRIP_REGEX = Regex("[\\s\\p{P}\\p{S}]+")

		private val CREDITS_START_REGEX = Regex(
			"^(?:tl|ts|pr|rd|cl|clrd|qc|ed|typeset|translated|proofread|cleaned|redraw|edited|scanlated)\\s*(?:[:/\\-]|by)\\b",
			RegexOption.IGNORE_CASE,
		)
		private val CREDITS_INLINE_REGEX = Regex(
			"\\b(?:join\\s+our\\s+discord|discord\\.gg/|patreon\\.com/|ko-fi\\.com/|buymeacoffee\\.com/|mangaupdates\\.com|read\\s+(?:first|more|latest|only)\\s+(?:at|on)|thank\\s+you\\s+for\\s+reading|support\\s+us\\s+on|translated\\s+by|typeset\\s+by|cleaned\\s+by|redrawn\\s+by|proofread\\s+by|scanlated\\s+by|please\\s+support\\s+the\\s+author)\\b",
			RegexOption.IGNORE_CASE,
		)
		private val WATERMARK_DOMAIN_REGEX = Regex(
			"\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|net|org|io|co|me|gg|xyz|top|club|site|online|info|cc|tk|ml|to|tv)\\b",
			RegexOption.IGNORE_CASE,
		)
		private val SFX_WRAPPED_REGEX = Regex(
			"^(?:\\*[^*]{1,24}\\*|~[^~]{1,24}~|\\(\\s*sfx[^)]{0,24}\\)|\\[\\s*sfx[^\\]]{0,24}\\])+$",
			RegexOption.IGNORE_CASE,
		)
		private val VOCAL_OR_SFX_TOKEN_REGEX = Regex(
			"^(?:[aáà]+h+|[uúù]+h+|[oóò]+h+|e+h+|h+m+|m+h+|pff+t*|tsk+|gasp+|sigh+|grunt+|groan+|huff+|pant+|sob+|yawn+|cough+|sneeze+|achoo+|bam+|boom+|kaboom+|pow+|bang+|crash+|thud+|slam+|wham+|smack+|slap+|crack+|snap+|pop+|click+|tick+|drip+|drop+|splash+|splat+|squish+|crunch+|who+sh+|swo+sh+|swish+|fwoosh+|zoom+|ring+|ding+|buzz+|beep+|creak+|rumble+|tremble+|shake+|shiver+|gulp+|glug+|munch+|chomp+|slurp+|sniff+|snort+|tap+|clack+|clink+|clang+|thump+|ba-?dum+|lub-?dub+|dokun+|doki+|z+z+t*|zz+|kya+h*|eek+|wah+|ugh+|grr+|heh+|(?:ha)+h*|shing+|fwip+|whir+)[!~.?…]*$",
			RegexOption.IGNORE_CASE,
		)

		private val GOOGLE_MARKER_REGEX = Regex(
			"(?:^|\\n)\\s*[⟦\\[]\\s*[Kk]\\s*(\\d+)\\s*[⟧\\]]\\s*",
		)
	}
}
