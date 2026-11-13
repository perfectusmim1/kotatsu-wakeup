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
import org.koitharu.kotatsu.reader.domain.translate.ocr.TextBlockFilters
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
 * - **Gemini Flash-Lite** (`gemini-flash-lite-latest`, auto fallback to `gemini-2.5-flash-lite`)
 *   with Minimal thinking mode (`thinkingLevel: MINIMAL` -> `thinkingBudget: 0` -> omitted fallback),
 *   structured JSON batch output, ToonParrot v15 localization prompt, and [GeminiKeyPool] multi-key rotation.
 * - **Google Translate** (`translate.googleapis.com/translate_a/single?client=gtx`) direct HTTP GET
 *   with ToonParrot English OCR normalization (glued-word DP segmentation, ALL-CAPS sentence casing,
 *   OCR symbol/contraction repair), UTF-8 length-safe batching, and per-bubble fallback.
 */
@Singleton
class MangaTranslationService @Inject constructor(
	@BaseHttpClient private val baseHttpClientProvider: Provider<OkHttpClient>,
	private val keyPool: GeminiKeyPool,
) : ComicTextTranslator {

	private val baseCleanHttpClient: OkHttpClient by lazy {
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
		}.build()
	}

	private val geminiHttpClient: OkHttpClient by lazy {
		baseCleanHttpClient.newBuilder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(25, TimeUnit.SECONDS)
			.writeTimeout(15, TimeUnit.SECONDS)
			.callTimeout(30, TimeUnit.SECONDS)
			.build()
	}

	private val googleHttpClient: OkHttpClient by lazy {
		baseCleanHttpClient.newBuilder()
			.connectTimeout(8, TimeUnit.SECONDS)
			.readTimeout(10, TimeUnit.SECONDS)
			.writeTimeout(10, TimeUnit.SECONDS)
			.callTimeout(12, TimeUnit.SECONDS)
			.build()
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
		var otherFailureCount = 0
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
						return fillMissingOrUnchangedItemsWithGoogle(
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
								delay(computeTransientBackoffMs(attempt))
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
								otherFailureCount += 1
								if (otherFailureCount >= MAX_CLIENT_OR_PARSE_ATTEMPTS || attempt + 1 >= maxAttempts) {
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

				val client = withContext(Dispatchers.IO) { geminiHttpClient }
				val response = client.newCall(request).await()
				val statusCode = response.code
				val retryAfterHeader = response.header("Retry-After")
				val responseText = response.use { resp ->
					resp.body.string()
				}

				if (statusCode in 200..299) {
					val bubbles = batch.map { it.bubble }
					val cleanTexts = batch.associate { it.bubble.id to it.cleanText }
					val parsedItems = parseGeminiResponse(responseText, bubbles, cleanTexts, settings, targetLang)
					if (parsedItems != null) {
						Log.d(LOG_TAG, "Gemini HTTP $statusCode: parsed ${parsedItems.size}/${batch.size} via $model/$thinkingMode")
						return GeminiCallOutcome.Success(parsedItems)
					}
					Log.w(LOG_TAG, "Gemini HTTP $statusCode unparseable/empty/unchanged response via $model/$thinkingMode")
					lastFailure = GeminiCallOutcome.Failure(
						GeminiHttpErrorInfo(
							statusCode = statusCode,
							kind = GeminiHttpErrorKind.OTHER,
							message = "Gemini returned empty, blocked, unchanged, or invalid JSON response.",
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

	private suspend fun fillMissingOrUnchangedItemsWithGoogle(
		batch: List<PreparedBubble>,
		parsed: List<TranslatedTextItem>,
		settings: TranslationSettingsSnapshot,
		targetLang: String,
	): List<TranslatedTextItem> {
		val byId = LinkedHashMap<String, TranslatedTextItem>(parsed.size)
		for (item in parsed) {
			byId[item.id] = item
		}

		val needsFallback = batch.filter { prep ->
			val item = byId[prep.bubble.id] ?: return@filter true
			if (item.isTrash) return@filter false
			val unchanged = normalizeForCompare(item.text) == normalizeForCompare(prep.cleanText)
			unchanged && isMeaningfulForeignSentence(prep.cleanText, settings.sourceLang, targetLang)
		}

		if (needsFallback.isEmpty()) {
			return finalizeBatchItems(batch, byId, settings, targetLang)
		}

		Log.d(LOG_TAG, "Filling ${needsFallback.size}/${batch.size} missing/unchanged Gemini item(s) via Google Translate")
		val fallbackTranslated = runCatching {
			translateWithGoogle(needsFallback, settings, targetLang, allowAllUnchangedThrow = false)
		}.getOrElse { e ->
			if (e is CancellationException) throw e
			emptyList()
		}

		for (item in fallbackTranslated) {
			byId[item.id] = item
		}

		return finalizeBatchItems(batch, byId, settings, targetLang)
	}

	private fun finalizeBatchItems(
		batch: List<PreparedBubble>,
		byId: Map<String, TranslatedTextItem>,
		settings: TranslationSettingsSnapshot,
		targetLang: String,
	): List<TranslatedTextItem> {
		val resolvedMap = LinkedHashMap<String, TranslatedTextItem>(batch.size)
		for (prep in batch) {
			val existing = byId[prep.bubble.id]
			resolvedMap[prep.bubble.id] = if (existing != null) {
				finalizeSingleItem(existing, prep.cleanText, settings, targetLang)
			} else {
				TranslatedTextItem(
					id = prep.bubble.id,
					text = "",
					kind = prep.bubble.kindHint,
					isTrash = true,
				)
			}
		}

		val pairList = batch.map { it.bubble.id to it.cleanText }
		if (shouldRejectUnchangedBatch(pairList, resolvedMap, settings.sourceLang, targetLang)) {
			throw TranslationServiceException(
				statusCode = 502,
				isTransient = true,
				message = "Translation returned unchanged foreign dialogue.",
			)
		}

		return batch.mapNotNull { resolvedMap[it.bubble.id] }
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
			put("temperature", 0.2)
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

	private suspend fun translateWithGoogle(
		items: List<PreparedBubble>,
		settings: TranslationSettingsSnapshot,
		targetLang: String,
		allowAllUnchangedThrow: Boolean = true,
	): List<TranslatedTextItem> {
		if (items.isEmpty()) return emptyList()

		val chunks = chunkForGoogleGet(items, settings.sourceLang, targetLang)
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

		val byId = results.associateBy { it.id }
		if (allowAllUnchangedThrow) {
			val pairList = items.map { it.bubble.id to it.cleanText }
			if (shouldRejectUnchangedBatch(pairList, byId, settings.sourceLang, targetLang)) {
				throw TranslationServiceException(
					statusCode = 502,
					isTransient = true,
					message = "Google Translate returned unchanged foreign dialogue.",
				)
			}
		}

		return results.map { item ->
			val cleanOrig = items.firstOrNull { it.bubble.id == item.id }?.cleanText.orEmpty()
			finalizeSingleItem(item, cleanOrig, settings, targetLang)
		}
	}

	private suspend fun translateChunkWithGoogleMarkers(
		chunk: List<PreparedBubble>,
		settings: TranslationSettingsSnapshot,
		targetLang: String,
	): List<TranslatedTextItem>? {
		val normalizedInputs = chunk.map { prep ->
			prepareTextForGoogleTranslate(prep.cleanText, settings.sourceLang, targetLang)
		}
		val combinedQuery = buildString {
			for (i in normalizedInputs.indices) {
				if (i > 0) append("\n")
				append("⟦K")
				append(i)
				append("⟧ ")
				append(normalizedInputs[i])
			}
		}

		val rawTranslated = executeGoogleTranslateGet(
			query = combinedQuery,
			sourceLang = settings.sourceLang,
			targetLang = targetLang,
		)

		val parsedByIndex = parseMarkedGoogleOutput(rawTranslated, chunk.size) ?: return null
		val mapped = chunk.mapIndexed { index, prep ->
			val rawPiece = parsedByIndex[index].orEmpty()
			if (rawPiece.isBlank()) return null
			postProcessTranslatedItem(
				id = prep.bubble.id,
				translatedRaw = rawPiece,
				originalCleanText = prep.cleanText,
				kind = prep.bubble.kindHint,
				rawTrash = false,
				settings = settings,
				targetLang = targetLang,
			)
		}

		val pairList = chunk.map { it.bubble.id to it.cleanText }
		val resultMap = mapped.associateBy { it.id }
		if (shouldRejectUnchangedBatch(pairList, resultMap, settings.sourceLang, targetLang)) {
			return null
		}
		return mapped
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
		val normalizedQuery = prepareTextForGoogleTranslate(prep.cleanText, settings.sourceLang, targetLang)
		if (normalizedQuery.isBlank()) {
			return TranslatedTextItem(
				id = prep.bubble.id,
				text = "",
				kind = prep.bubble.kindHint,
				isTrash = true,
			)
		}

		val rawTranslated = executeGoogleTranslateGet(
			query = normalizedQuery,
			sourceLang = settings.sourceLang,
			targetLang = targetLang,
		)
		return postProcessTranslatedItem(
			id = prep.bubble.id,
			translatedRaw = rawTranslated.ifBlank { prep.cleanText },
			originalCleanText = prep.cleanText,
			kind = prep.bubble.kindHint,
			rawTrash = false,
			settings = settings,
			targetLang = targetLang,
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

				val client = withContext(Dispatchers.IO) { googleHttpClient }
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
					if (segment.isNull(0)) continue
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

	private fun chunkForGoogleGet(
		items: List<PreparedBubble>,
		sourceLang: OcrSourceLanguage,
		targetLang: String,
	): List<List<PreparedBubble>> {
		val chunks = ArrayList<List<PreparedBubble>>()
		var current = ArrayList<PreparedBubble>()
		var estimatedEncodedBytes = 0

		for (item in items) {
			val normalized = prepareTextForGoogleTranslate(item.cleanText, sourceLang, targetLang)
			val itemEncodedBytes = estimateUrlEncodedBytes(normalized) + 24
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

	private fun parseRetryAfterFromException(e: TooManyRequestExceptions): Long? {
		return e.getRetryDelay().takeIf { it > 0L }
	}

	private fun computeTransientBackoffMs(attempt: Int): Long {
		return min(MAX_TRANSIENT_BACKOFF_MS, (BASE_TRANSIENT_BACKOFF_MS * 1.6.pow(attempt)).toLong())
	}

	companion object {

		private const val LOG_TAG = "KotatsuTranslate"
		const val PRIMARY_GEMINI_MODEL: String = "gemini-flash-lite-latest"
		const val FALLBACK_GEMINI_MODEL: String = "gemini-2.5-flash-lite"

		private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta"
		private const val GOOGLE_TRANSLATE_ENDPOINT = "https://translate.googleapis.com/translate_a/single"
		private const val GOOGLE_USER_AGENT =
			"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

		private const val GEMINI_MAX_BATCH_ITEMS = 32
		private const val GEMINI_MAX_BATCH_CHARS = 12_000
		private const val MODEL_FALLBACK_CACHE_MS = 30L * 60L * 1000L
		private const val MAX_CLIENT_OR_PARSE_ATTEMPTS = 2

		private const val GOOGLE_MAX_BATCH_ITEMS = 10
		private const val GOOGLE_MAX_ENCODED_QUERY_BYTES = 1_100
		private const val GOOGLE_SINGLE_CONCURRENCY = 4
		private const val GOOGLE_MAX_ATTEMPTS = 3

		private const val BASE_TRANSIENT_BACKOFF_MS = 800.0
		private const val MAX_TRANSIENT_BACKOFF_MS = 3_500L

		private val INTACT_ENGLISH_WORDS = hashSetOf(
			"together", "understand", "undertake", "overcome", "everyone", "someone", "anyone", "nobody",
			"something", "anything", "everything", "nothing", "without", "within", "inside", "outside",
			"another", "whatever", "whenever", "wherever", "however", "therefore", "meanwhile", "otherwise",
			"instead", "already", "almost", "although", "always", "anyway", "became", "become", "before",
			"behind", "below", "beneath", "beside", "besides", "between", "beyond", "forever", "forgive",
			"forget", "forgot", "forward", "herself", "himself", "itself", "myself", "yourself", "themselves",
			"ourselves", "sometimes", "anywhere", "everywhere", "somewhere", "nowhere", "overnight", "overhead",
			"cannot", "today", "tonight", "tomorrow", "yesterday", "awakening", "background", "gentleman",
			"nevertheless", "nonetheless", "afterward", "afterwards", "suddenly", "originally", "completely",
			"unfortunately", "congratulations", "imagination", "information", "exclamation", "explanation",
			"disappear", "disappointed", "interesting", "important", "impossible", "difference", "different",
			"amplification",
		)

		private val SEGMENT_VOCAB = hashSetOf(
			"a", "i", "an", "the", "this", "that", "these", "those", "my", "your", "his", "her", "its", "our", "their",
			"is", "am", "are", "was", "were", "be", "been", "being", "have", "has", "had", "do", "does", "did",
			"can", "could", "will", "would", "shall", "should", "may", "might", "must",
			"in", "on", "at", "to", "for", "of", "with", "from", "by", "as", "into", "onto", "upon", "over", "under",
			"through", "about", "after", "before", "around", "against", "between", "even", "just", "only", "also",
			"still", "yet", "ever", "never", "always", "very", "too", "so", "more", "most", "less", "much", "many",
			"such", "some", "any", "no", "not", "yes", "all", "both", "each", "every", "other", "same", "own", "than",
			"and", "or", "but", "if", "then", "when", "where", "why", "how", "what", "who", "whom", "whose", "which",
			"while", "though", "since", "until", "again", "further", "once", "neither", "nor",
			"he", "she", "it", "we", "they", "you", "me", "him", "us", "them", "one", "two", "three", "first", "last",
			"small", "big", "large", "great", "good", "bad", "high", "low", "long", "short", "new", "old", "real", "true",
			"original", "martial", "unique", "special", "hidden", "normal", "strange", "weird", "ancient", "supreme",
			"simple", "easy", "crazy", "stupid", "broken", "because", "maybe", "probably", "sorry", "please", "thank", "thanks",
			"body", "soul", "mind", "heart", "hand", "eyes", "head", "blood", "bone", "sword", "constitution",
			"magic", "mana", "power", "force", "energy", "aura", "qi", "spirit", "skill", "skills", "level", "rank",
			"point", "points", "stat", "stats", "status", "system", "world", "human", "people", "person", "man", "woman",
			"boy", "girl", "master", "lord", "king", "queen", "prince", "princess", "knight", "wizard", "mage", "hero",
			"demon", "monster", "beast", "dragon", "skeleton", "ghost", "god", "angel", "devil", "dark", "light",
			"stimulus", "effect", "cause", "reason", "way", "time", "day", "night", "life", "death", "kill", "die", "dead",
			"draw", "amplify", "amplification", "absorb", "trigger", "activate", "boost", "grow", "growth", "increase",
			"ability", "abilities", "talent", "talents", "limit", "limits", "beyond", "reach", "raise", "protect", "heal",
			"make", "made", "take", "took", "give", "gave", "get", "got", "go", "went", "come", "came", "see", "saw",
			"look", "looked", "find", "found", "know", "knew", "think", "thought", "feel", "felt", "want", "wanted",
			"need", "needed", "use", "used", "try", "tried", "keep", "kept", "hold", "held", "turn", "turned",
			"leave", "left", "put", "set", "say", "said", "tell", "told", "ask", "asked", "work", "call", "called",
			"live", "lived", "move", "moved", "play", "wait", "stop", "help", "fight", "fought", "win", "won", "lose", "lost",
			"doing", "going", "coming", "saying", "looking", "trying", "being", "having", "everyone", "someone", "anyone",
			"nobody", "something", "anything", "everything", "nothing", "another",
			"able", "back", "down", "up", "out", "off", "away", "here", "there", "now", "well", "right", "left",
			"strong", "weak", "hard", "fast", "slow", "full", "empty", "free", "sure", "fine", "okay",
			"disguise", "chance", "mistake", "secret", "truth", "lie", "plan", "order", "quest", "reward", "danger",
			"enemy", "friend", "family", "brother", "sister", "father", "mother", "son", "daughter", "child", "kids",
			"place", "room", "door", "house", "city", "land", "empire", "clan", "sect", "school", "academy",
			"enough", "almost", "really", "actually", "simply", "merely", "already", "quite", "rather", "pretty",
			"unlike", "others", "proper", "principle", "story", "heavenly", "built", "basic", "physique",
		)

		private val UNAMBIGUOUS_CONTRACTION_MAP = mapOf(
			"dont" to "don't",
			"cant" to "can't",
			"didnt" to "didn't",
			"doesnt" to "doesn't",
			"isnt" to "isn't",
			"arent" to "aren't",
			"wasnt" to "wasn't",
			"werent" to "weren't",
			"havent" to "haven't",
			"hasnt" to "hasn't",
			"hadnt" to "hadn't",
			"wont" to "won't",
			"couldnt" to "couldn't",
			"wouldnt" to "wouldn't",
			"shouldnt" to "shouldn't",
			"mustnt" to "mustn't",
			"neednt" to "needn't",
			"youre" to "you're",
			"theyre" to "they're",
			"youve" to "you've",
			"weve" to "we've",
			"theyve" to "they've",
			"youll" to "you'll",
			"theyll" to "they'll",
			"youd" to "you'd",
			"theyd" to "they'd",
			"whats" to "what's",
			"thats" to "that's",
			"theres" to "there's",
			"wheres" to "where's",
			"heres" to "here's",
			"whos" to "who's",
			"hows" to "how's",
			"whens" to "when's",
			"whys" to "why's",
			"aint" to "ain't",
			"yall" to "y'all",
			"maam" to "ma'am",
			"oclock" to "o'clock",
			"hes" to "he's",
			"shes" to "she's",
			"ive" to "I've",
		)

		private val ASCII_ALPHA_WORD_10_48_REGEX = Regex("\\b[A-Za-z]{10,48}\\b")
		private val INTACT_ENGLISH_SUFFIX_REGEX = Regex(
			"(?:tion|sion|ment|ness|able|ible|ship|hood|ally|edly|fulness)$",
			RegexOption.IGNORE_CASE,
		)
		private val SAFE_Z_WORD_REGEX = Regex(
			"^(?:jazz|buzz|fuzz|fizz|quiz|whiz|frizz|blitz|ritz|waltz|quartz|topaz|klutz|chintz|pizzazz|ersatz|spritz|fez|wiz|biz|ad[sz]|plz|cuz|ghz|mhz|khz|hz)$",
			RegexOption.IGNORE_CASE,
		)
		private val Z_TO_QUESTION_STEM_REGEX = Regex(
			"^(?:huh|eh|uh|ah|what|why|how|who|when|where|really|right|wait|difficult|serious(?:ly)?|sure|true|wrong|done|mine|yours|it|that|this|you|he|she|we|they|now|here|there|then|so|more|else|again|already|yet)$",
			RegexOption.IGNORE_CASE,
		)
		private val CONSONANT_ENDING_REGEX = Regex("[bcdfghjklmnpqrstvwxy]$", RegexOption.IGNORE_CASE)
		private val NEXT_CHAR_AFTER_Z_REGEX = Regex("[A-Z0-9\"“'¿¡(]")
		private val ORPHAN_CONTRACTION_QUOTE_REGEX = Regex(
			"(?<=\\p{L})[\"“”](?=(?:s|t|m|d|re|ve|ll)\\b)",
			RegexOption.IGNORE_CASE,
		)
		private val INTERIOR_BANG_REGEX = Regex("(?<=[A-Za-z])!(?=[A-Za-z])")
		private val LEADING_BANG_PRONOUN_REGEX = Regex(
			"(?:^|(?<=\\s))!(?=(?:['\"“”’`](?:m|ll|ve|d)|[NFT]|[MS](?=['’]?))(?=\\s|$|[.!?,;:'\"]))",
			RegexOption.IGNORE_CASE,
		)
		private val INTERIOR_ONE_REGEX = Regex("(?<=[A-Za-z])1(?=[A-Za-z])")
		private val ONE_CONTRACTION_REGEX = Regex(
			"(?:^|(?<=\\s))1(?=['\"“”’`](?:m|ll|ve|d)\\b)",
			RegexOption.IGNORE_CASE,
		)
		private val STANDALONE_ONE_PRONOUN_REGEX = Regex(
			"(?:^|(?<=\\s))1(?=\\s+(?:AM|ARE|WAS|WERE|CAN|CAN'T|CANT|COULD|WILL|WON'T|WONT|WOULD|SHOULD|SHALL|MUST|HAVE|HAD|DO|DON'T|DONT|DID|DIDN'T|DIDNT|KNOW|THINK|WANT|NEED|SEE|FEEL|HEAR|GUESS|BELIEVE|SWEAR|PROMISE|HOPE|WISH|JUST|ONLY|STILL|REALLY|NEVER|ALWAYS|ALREADY)\\b)",
			RegexOption.IGNORE_CASE,
		)
		private val NUMERIC_LABEL_BEFORE_ONE_REGEX = Regex(
			"(?:lvl|lv|level|no\\.?|chapter|ch\\.?|ep\\.?|part|phase|stage|rank|tier|round|floor|#)\\s*$",
			RegexOption.IGNORE_CASE,
		)
		private val ONE_PREFIX_WORD_REGEX = Regex("(?:^|(?<=\\s))1(?=(?:N|F|S|T|T'S|TS)\\b)")
		private val INTERIOR_ZERO_REGEX = Regex("(?<=[A-Za-z])0(?=[A-Za-z])")
		private val ZERO_PREFIX_UPPER_WORD_REGEX = Regex("(?:^|(?<=\\s))0(?=(?:N|F|R|K|H)\\b)")
		private val ILL_DOT_REGEX = Regex("(?:^|(?<=\\s))[Il1][\"“”'`]?ll\\.(?=\\s+\\p{L})")
		private val CONTRACTION_DOT_REGEX = Regex(
			"\\b(i'll|i'm|i've|i'd|you're|we're|they're|he's|she's|it's|that's|what's|let's|don't|didn't|doesn't|isn't|aren't|wasn't|weren't|haven't|hasn't|hadn't|won't|can't|couldn't|wouldn't|shouldn't)\\.(?=\\s+\\p{L})",
			RegexOption.IGNORE_CASE,
		)
		private val TRAILING_Z_REGEX = Regex("\\b([A-Za-z]{2,})[zZ]\\b(?=(?:\\s|$|[.!?\"'”’]))")
		private val KNEW_IT_DOT_REGEX = Regex("\\b(knew|know|found\\s+out|realized)\\s+it\\.(?=\\s+was\\b)", RegexOption.IGNORE_CASE)
		private val UNAMBIGUOUS_CONTRACTION_REGEX = Regex(
			"\\b(${UNAMBIGUOUS_CONTRACTION_MAP.keys.joinToString("|")})\\b",
			RegexOption.IGNORE_CASE,
		)
		private val IM_PREDICATE_REGEX = Regex(
			"\\b(im)\\b(?=\\s+(?:sorry|not|going|gonna|here|there|fine|ok|okay|sure|ready|done|back|just|so|really|too|very|still|already|always|never|trying|getting|having|being|doing|making|taking|coming|leaving|looking|waiting|thinking|feeling|a|an|the|my|your|in|on|at|out|up|down|no|all|about|afraid|glad|happy|sad|angry|mad|tired|hungry|sick|dead|alive|safe|free|alone|wrong|right|late|early|serious|kidding|joking)\\b)",
			RegexOption.IGNORE_CASE,
		)
		private val IM_EXACT_UPPER_OR_LOWER_REGEX = Regex("\\b(?:IM|im)\\b")
		private val ITS_CONTRACTION_REGEX = Regex(
			"\\b(its)\\b(?=\\s+(?:a|an|the|not|no|so|too|very|really|just|only|even|still|already|always|never|all|over|done|fine|okay|ok|true|false|possible|impossible|obvious|clear|weird|strange|hard|easy|good|bad|better|worse|great|nice|sad|funny|scary|dangerous|important|enough|time|been|going|getting|coming|working|happening|starting|ending|my|your|his|her|our|their)\\b|[.!?]+\\s*$|[.!?]+$)",
			RegexOption.IGNORE_CASE,
		)
		private val LETS_CONTRACTION_REGEX = Regex(
			"\\b(lets)\\b(?=\\s+(?:go|see|get|do|try|make|take|have|start|begin|leave|head|move|talk|eat|check|find|wait|not|just|be)\\b)",
			RegexOption.IGNORE_CASE,
		)
		private val ID_CONTRACTION_REGEX = Regex(
			"\\b(id)\\b(?=\\s+(?:be|have|rather|better|like|love|hate|prefer|say|think|guess|bet|never|always|just|still|even)\\b)",
			RegexOption.IGNORE_CASE,
		)
		private val ILL_VERB_REGEX = Regex(
			"\\bill(?=\\s+(?:be|do|go|have|get|make|take|see|tell|give|come|show|try|let|find|use|kill|help|cut|handle|wait|say|stop|start|stay|leave|ask|keep|hold|put|bring|send)\\b)",
			RegexOption.IGNORE_CASE,
		)

		private val EVEM_REGEX = Regex("\\bevem\\b", RegexOption.IGNORE_CASE)
		private val DISCUISZE_REGEX = Regex("\\bdiscui[sz]e\\b", RegexOption.IGNORE_CASE)
		private val EVEN_A_SMALL_STIMULUS_CAN_REGEX = Regex("\\bEVENASMALLSTIMULUSS?CAN\\b", RegexOption.IGNORE_CASE)
		private val STIMULUSSCAN_REGEX = Regex("\\bSTIMULUSSCAN\\b", RegexOption.IGNORE_CASE)
		private val FOR_AMPLIFICATION_REGEX = Regex("\\bFORAMPLIFICATION\\b", RegexOption.IGNORE_CASE)
		private val SIMPLE_PRINCIPLE_REGEX = Regex("\\bSIMPLEPRINCIPLE\\b", RegexOption.IGNORE_CASE)
		private val ITS_A_CONSTITUTION_REGEX = Regex("\\bIT'SACONSTITUTION\\b", RegexOption.IGNORE_CASE)
		private val ASH_GRAY_GLUE_REGEX = Regex("\\bAsh\\s+Gray\\b", RegexOption.IGNORE_CASE)
		private val BE_CROSSING_SWORDS_REGEX = Regex("\\bbe\\s+crossing\\s+swords\\s+with\\b", RegexOption.IGNORE_CASE)
		private val CROSSING_SWORDS_REGEX = Regex("\\bcrossing\\s+swords\\s+with\\b", RegexOption.IGNORE_CASE)
		private val AT_ALL_DOT_REGEX = Regex("\\bat\\.\\s*all\\b", RegexOption.IGNORE_CASE)
		private val XIULIAN_REGEX = Regex("\\bxiulian\\b", RegexOption.IGNORE_CASE)
		private val LIL_REGEX = Regex("\\blil\\b", RegexOption.IGNORE_CASE)
		private val SEN_GE_REGEX = Regex("\\bsen[-\\s]ge\\b", RegexOption.IGNORE_CASE)
		private val OH_DAMN_REGEX = Regex("\\boh\\s+damn\\b\\s*[:;,]?", RegexOption.IGNORE_CASE)
		private val ASF_REGEX = Regex("\\bASF\\b", RegexOption.IGNORE_CASE)
		private val WTF_REGEX = Regex("\\bWTF\\b", RegexOption.IGNORE_CASE)
		private val OMG_REGEX = Regex("\\bOMG\\b", RegexOption.IGNORE_CASE)
		private val QUITE_THE_DISGUISE_REGEX = Regex("\\bthat's\\s+quite\\s+the\\s+disguise\\b", RegexOption.IGNORE_CASE)
		private val CONSTITUTION_BUILT_FOR_REGEX = Regex("\\bconstitution\\s+built\\s+for\\b", RegexOption.IGNORE_CASE)

		private val SENTENCE_START_CAPITALIZE_REGEX = Regex("(^|[.!?]\\s+)([\"'“‘(\\[]*)([a-z])")
		private val LEADING_ELLIPSIS_CAPITALIZE_REGEX = Regex("^(\\.\\.\\.|…)\\s*([a-z])")
		private val LONE_I_REGEX = Regex("\\bi\\b")
		private val I_CONTRACTION_REGEX = Regex("\\bi(?=['’](?:m|ll|ve|d)\\b)")

		private val TR_MY_DOT_REGEX = Regex("^([.…]*)my\\.\\s+(?=\\p{L})", RegexOption.IGNORE_CASE)
		private val TR_HEY_LOOK_REGEX = Regex("\\bhey[.,]\\s*look\\s*([.,!…]*)$", RegexOption.IGNORE_CASE)
		private val TR_ONLY_LOOK_REGEX = Regex("^look\\s*([.,!…]*)$", RegexOption.IGNORE_CASE)
		private val TR_CONFINED_REGEX = Regex("^confined\\?$", RegexOption.IGNORE_CASE)
		private val TR_MISS_PAIR_REGEX = Regex("\\bno one will miss (?:one|a) pair\\b", RegexOption.IGNORE_CASE)
		private val TR_POST_QUOTE_REGEX = Regex("(?<='\\p{L})[\"“”]")

		private val NON_ENGLISH_EUROPEAN_DIACRITICS_REGEX = Regex("[áéíóúüñ¿¡àâæèêëîïôœùûÿäößãõÁÉÍÓÚÜÑÀÂÆÈÊËÎÏÔŒÙÛŸÄÖÃÕ]")
		private val NON_ENGLISH_LATIN_STOPWORD_REGEX = Regex(
			"\\b(?:que|para|pero|como|cuando|donde|quien|esta|estos|estas|estoy|muy|mas|por|los|las|unas|unos|del|dans|avec|sans|mais|vous|nous|suis|sont|etre|faire|der|die|das|und|ist|nicht|ich|sie|wir|ein|eine|mit|auf|für|von|zu)\\b",
			RegexOption.IGNORE_CASE,
		)
		private val ENGLISH_HINT_STOPWORD_REGEX = Regex(
			"\\b(?:the|and|you|your|are|was|were|have|has|had|with|this|that|what|when|where|why|how|from|they|them|their|there|here|would|could|should|will|can't|don't|didn't|isn't|aren't|wasn't|i'm|you're|he's|she's|it's|we're|they're|not|but|for|just|know|think)\\b",
			RegexOption.IGNORE_CASE,
		)

		private val STRICT_TURKISH_CHARS = charArrayOf('ç', 'Ç', 'ğ', 'Ğ', 'ı', 'İ', 'ş', 'Ş')
		private val UNIQUE_TURKISH_CHARS = charArrayOf('ğ', 'Ğ', 'ı', 'İ', 'ş', 'Ş')
		private val TURKISH_STOPWORD_REGEX = Regex(
			"\\b(?:için|fakat|ancak|değil|kadar|sonra|önce|nasıl|neden|niye|nerede|benim|senin|onun|bizim|sizin|evet|hayır|tamam|lütfen|teşekkür|merhaba|olacak|olduğu|olarak|böyle|şöyle|keşke|acaba|belki|çünkü|rağmen|doğru|yanlış|güzel|büyük|küçük|ve|bir|bu|şu|ama|çok|daha|gibi)\\b",
			RegexOption.IGNORE_CASE,
		)
		private val FOREIGN_STOPWORD_FOR_TR_REGEX = Regex(
			"\\b(?:the|and|you|your|are|was|were|have|has|had|with|this|that|what|when|where|why|how|from|they|them|their|there|here|would|could|should|will|can't|don't|didn't|isn't|aren't|wasn't|i'm|you're|he's|she's|it's|we're|they're|el|la|los|las|unos|unas|del|por|con|sin|pero|como|cuando|donde|quien|que|mas|muy|este|esta|estos|estas|ese|esa|esos|esas|todo|toda|todos|todas|hacer|hace|hizo|haber|han|habia|soy|eres|somos|estoy|estan|estaba|tengo|tienes|tiene|tienen|tenia|voy|vas|van|puedo|puede|pueden|quiero|quiere|quieren|debi|debia|despues|antes|ahora|aqui|maldita|maldito|mierda|carajo|anos|ano)\\b",
			RegexOption.IGNORE_CASE,
		)

		private val ASH_GRAY_RESTORE_REGEX = Regex("\\bAshgray\\b", RegexOption.IGNORE_CASE)
		private val ILK_I_YI_REGEX = Regex("[iİ]lk'?[iİ]'?y[iİ]")
		private val AH_KAHRAMAN_REGEX = Regex("\\bAH KAHRAMAN\\b", RegexOption.IGNORE_CASE)
		private val CONSTITUTION_WORD_REGEX = Regex("constitution", RegexOption.IGNORE_CASE)
		private val CONSTITUTION_CONTEXT_REGEX = Regex(
			"(?:body|martial|unique|special|amplif|talent|spirit|qi|innate|divine|devil|heavenly|immortal|supreme|ancient|dragon|demon|phoenix|chaos|primordial|cultivat|meridian|dantian|physique|bloodline|yin|yang|aura|mana|soul|core|ether|elemental|thunder|flame|ice|sword)",
			RegexOption.IGNORE_CASE,
		)
		private val ANAYASA_WORD_REGEX = Regex(
			"\\banayasa(?:'?(?:ya|yı|sı|sını|sında|sından|sına|mız|nın|da|dan|m))?\\b",
			RegexOption.IGNORE_CASE,
		)

		private val SFX_TAG_ONLY_REGEX = Regex("^\\s*[\\[\\(*~]?\\s*sfx\\s*[\\]\\)*~]?\\s*[!?.…]*$", RegexOption.IGNORE_CASE)
		private val INLINE_SFX_TAG_REGEX = Regex("\\[\\s*sfx\\s*\\]", RegexOption.IGNORE_CASE)
		private val QUESTION_END_REGEX = Regex("\\?[?!….\"”’)]*$")
		private val PURE_PUNCT_OR_DIGITS_REGEX = Regex("^[\\s\\p{P}\\p{S}\\d_~`^+|\\\\/]+$")
		private val MULTILINE_SPACE_REGEX = Regex("\\s+")
		private val HORIZONTAL_SPACE_REGEX = Regex("[ \\t]{2,}")
		private val EXCESS_NEWLINES_REGEX = Regex("\\n{3,}")
		private val HTML_TAG_REGEX = Regex("<[^>]{1,48}>")
		private val ANGLE_BRACKET_REGEX = Regex("[<>]+")
		private val CODE_FENCE_BLOCK_REGEX = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
		private val COMPARE_STRIP_REGEX = Regex("[\\s\\p{P}\\p{S}]+")

		private val CREDITS_START_REGEX = Regex(
			"^(?:tl|ts|pr|rd|cl|clrd|qc|ed|typeset|translated|proofread|cleaned|redraw|edited|scanlated)\\s*(?:[:/\\-]|by)\\b",
			RegexOption.IGNORE_CASE,
		)
		private val CREDITS_INLINE_REGEX = Regex(
			"\\b(?:join\\s+our\\s+discord|discord\\.gg/|patreon\\.com/|ko-?fi\\.com/|buymeacoffee\\.com/|mangaupdates\\.com|read\\s+(?:first|more|latest|only)\\s+(?:at|on)|thank\\s+you\\s+for\\s+reading|support\\s+us\\s+on|translated\\s+by|typeset\\s+by|cleaned\\s+by|redrawn\\s+by|proofread\\s+by|scanlated\\s+by|please\\s+support\\s+the\\s+author|disaster\\s*scans|surya\\s*scans|templescan|thunderscans|philiascans|arven\\s*comics|rizz\\s*fables)\\b|^dsns$",
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
		private val VOCAL_FILLER_TOKEN_REGEX = Regex(
			"^(?:[aáà]+h+|[uúù]+h+|[oóò]+h+|e+h+|h+m+m*|m+h+|h+u+h+|(?:ha)+h*|(?:he)+h*|(?:ho)+h*|kya+h*|wa+h+|eek+|hmph+|mmmph+|pff+t*|tsk+|tch+|ugh+|agh+|argh+|urgh+|ergh+|ow+|aw+|oof+)[!~.?…]*$",
			RegexOption.IGNORE_CASE,
		)
		private val EXTRA_PURE_SFX_STEMS = hashSetOf(
			// Background action / expression / ambient SFX frequently left untranslated or mis-translated
			"gasp", "gasps", "grin", "grins", "smirk", "smirks", "shiver", "shivers", "stare", "stares",
			"glare", "glares", "squeak", "squeaks", "squawk", "rustle", "rustles", "creak", "creaks",
			"clatter", "clang", "clank", "thud", "slam", "whoosh", "swoosh", "swish", "crack", "crackle",
			"boom", "kaboom", "bam", "baam", "bang", "crash", "thump", "wham", "smack", "slap", "whack",
			"thwack", "crunch", "munch", "chomp", "slurp", "glug", "sizzle", "fizz", "poof", "zing", "zap",
			"vroom", "skrrt", "shing", "shwing", "schwing", "fwip", "fwoosh", "whir", "whirr", "patter",
			"startle", "startles", "flinch", "flinches", "shudder", "shudders", "tremble", "trembles",
			"twitch", "twitches", "jolt", "jolts", "rumble", "grumble", "rattle", "screech", "growl",
			"howl", "roar", "hiss", "buzz", "beep", "ding", "dong", "pling", "plink", "plop", "splat",
			"sploosh", "squelch", "squish", "thwip", "twang", "bonk", "boing", "phew", "wheeze", "sniffle",
			"achoo", "hic", "zzz", "bwahaha", "muahaha", "mwahaha", "kekeke", "kukuku", "blush",
			"snicker", "cackle", "gulp", "sigh", "groan", "grunt", "snort", "pant",
			// Korean / Japanese romanized SFX
			"kwang", "kwaang", "kung", "kuung", "dugeun", "dukgeun", "jjeoreong", "puk", "peok", "ssuk",
			"geuk", "kkeuk", "heok", "heuk", "deolkeok", "chirik", "sarak", "ttuk", "jjak", "gogo", "gogogo",
			"menacing", "badum", "badump", "lubdub", "doki", "dokidoki", "dokun", "sha", "shaa", "fwoom",
			// Turkish translated single-word SFX equivalents (when translated by GTX before filtering)
			"çat", "pat", "küt", "güm", "şak", "tak", "tık", "çatır", "çatırtı", "şırrak", "şangır",
			"gıcırt", "gıcırtı", "hışırt", "hışırtı", "vın", "vınn", "vız", "vızz", "hart", "kırt",
			"fış", "fışş", "cız", "cızz", "şlap", "sırıtış", "sırıtma", "irkilme", "irkilir", "titreme",
			"ürperti", "gıcırdama", "gümbürtü",
		)
		private val MEANINGFUL_SINGLE_WORDS = hashSetOf(
			"no", "yes", "help", "why", "what", "who", "where", "when", "how", "wait", "stop", "run",
			"die", "kill", "save", "go", "come", "look", "listen", "please", "sorry", "thanks", "hi",
			"hello", "bye", "alone", "murder", "truth", "mine", "yours", "done", "fine", "ok", "okay",
			"sure", "really", "hayır", "evet", "yardım", "neden", "niye", "ne", "kim", "nerede", "nasıl",
			"bekle", "dur", "kaç", "koş", "öl", "bak", "dinle", "lütfen", "üzgünüm", "merhaba", "yalnız",
			"tamam", "gerçekten", "imdat",
		)
		private val CONSONANT_STRETCH_SFX_REGEX = Regex(
			"^(?:sh+|z+|g+r+|k+r+|b+z+|f+s+h+|s+w+i+s+h+|w+h+o+s+h+|t+h+u+d+|b+o+m+|b+a+m+|p+o+w+|k+w+a+n+g+)$",
			RegexOption.IGNORE_CASE,
		)
		private val HYPHENATED_STUTTER_SFX_REGEX = Regex("^[A-Za-z]-[A-Za-z]{2,}$")
		private val NON_ASCII_LOWER_ALPHA_REGEX = Regex("[^a-zçğıöşü]")
		private val SFX_TOKEN_SPLIT_REGEX = Regex("[\\s¡¿.,!?:;…\\-_~*^'\"/\\\\()\\[\\]{}<>]+")
		private val ARTICLE_PREFIX_WORDS = hashSetOf("a", "an", "the")

		private val GOOGLE_MARKER_REGEX = Regex(
			"(?:^|\\s)[⟦\\[【]\\s*[Kk]\\s*(\\d+)\\s*[⟧\\]】]\\s*",
		)

		internal fun segmentGluedEnglishToken(token: String): String {
			if (!token.matches(ASCII_ALPHA_WORD_10_48_REGEX)) return token
			val lower = token.lowercase(Locale.ROOT)
			if (lower in INTACT_ENGLISH_WORDS || lower in SEGMENT_VOCAB) return token
			if (lower.length <= 14 && INTACT_ENGLISH_SUFFIX_REGEX.containsMatchIn(lower)) {
				return token
			}

			val n = lower.length
			val bestScores = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
			val prevIdx = IntArray(n + 1) { -1 }
			bestScores[0] = 0.0

			for (i in 0 until n) {
				if (bestScores[i] == Double.NEGATIVE_INFINITY) continue
				val maxEnd = minOf(n, i + 16)
				for (j in i + 1..maxEnd) {
					val sub = lower.substring(i, j)
					if (sub !in SEGMENT_VOCAB) continue
					if (sub.length == 1 && sub != "a" && sub != "i") continue
					val pieceScore = (sub.length * sub.length).toDouble() - (if (sub.length <= 2) 2.5 else 0.0)
					val nextScore = bestScores[i] + pieceScore
					if (nextScore > bestScores[j]) {
						bestScores[j] = nextScore
						prevIdx[j] = i
					}
				}
			}

			if (prevIdx[n] == -1) return token
			val parts = ArrayList<String>()
			var cur = n
			while (cur > 0) {
				val p = prevIdx[cur]
				if (p < 0) return token
				parts.add(lower.substring(p, cur))
				cur = p
			}
			parts.reverse()
			if (parts.size < 2) return token
			if (parts.none { it.length >= 4 }) return token
			return parts.joinToString(" ")
		}

		internal fun normalizeEnglishForGoogleTranslate(
			raw: String,
			targetLang: String = "tr",
		): String {
			var s = raw.trim().replace(MULTILINE_SPACE_REGEX, " ")
			if (s.isEmpty()) return ""

			// 1. Orphan quotes inside contractions ("THAT'S" -> THAT'S)
			s = ORPHAN_CONTRACTION_QUOTE_REGEX.replace(s, "'")

			// 2. OCR symbol noise fixes: ! -> i/I, 1 -> I, 0 -> O inside words
			s = INTERIOR_BANG_REGEX.replace(s, "i")
			s = LEADING_BANG_PRONOUN_REGEX.replace(s, "I")
			s = INTERIOR_ONE_REGEX.replace(s, "i")
			s = ONE_CONTRACTION_REGEX.replace(s, "I")
			s = STANDALONE_ONE_PRONOUN_REGEX.replace(s) { m ->
				val before = s.substring(0, m.range.first)
				if (NUMERIC_LABEL_BEFORE_ONE_REGEX.containsMatchIn(before)) {
					m.value
				} else {
					"I"
				}
			}
			s = ONE_PREFIX_WORD_REGEX.replace(s) { m ->
				val before = s.substring(0, m.range.first)
				if (NUMERIC_LABEL_BEFORE_ONE_REGEX.containsMatchIn(before)) m.value else "I"
			}
			s = INTERIOR_ZERO_REGEX.replace(s, "o")
			s = ZERO_PREFIX_UPPER_WORD_REGEX.replace(s, "O")

			// 3. OCR period glitches after contractions ("I'll. FINALLY", "Don't. WORRY")
			s = ILL_DOT_REGEX.replace(s, "I'll")
			s = CONTRACTION_DOT_REGEX.replace(s, "$1")

			// 4. OCR '?' -> 'z' at word end ("HOWz", "DiffiCultz", "HUHz")
			s = TRAILING_Z_REGEX.replace(s) { m ->
				val full = m.value
				val stem = m.groupValues[1]
				if (SAFE_Z_WORD_REGEX.matches(full)) {
					full
				} else if (Z_TO_QUESTION_STEM_REGEX.matches(stem)) {
					"$stem?"
				} else {
					val tail = s.substring((m.range.last + 1).coerceAtMost(s.length)).trimStart()
					val nextChar = tail.firstOrNull()
					if (CONSONANT_ENDING_REGEX.containsMatchIn(stem) &&
						(nextChar == null || NEXT_CHAR_AFTER_Z_REGEX.matches(nextChar.toString()))
					) {
						"$stem?"
					} else {
						full
					}
				}
			}

			// Check dominant uppercase BEFORE lowercase-producing idiom repairs, segmentation & contractions
			val shouldSentenceCase = isPredominantlyUppercase(s)

			// 5. Known comic OCR & idiom repairs before segmentation / casing
			s = EVEM_REGEX.replace(s, "even")
			s = DISCUISZE_REGEX.replace(s, "disguise")
			s = EVEN_A_SMALL_STIMULUS_CAN_REGEX.replace(s, "even a small stimulus can")
			s = STIMULUSSCAN_REGEX.replace(s, "stimulus can")
			s = FOR_AMPLIFICATION_REGEX.replace(s, "for amplification")
			s = SIMPLE_PRINCIPLE_REGEX.replace(s, "simple principle")
			s = ITS_A_CONSTITUTION_REGEX.replace(s, "it's a physique")
			s = ASH_GRAY_GLUE_REGEX.replace(s, "Ashgray")
			s = BE_CROSSING_SWORDS_REGEX.replace(s, "duel with")
			s = CROSSING_SWORDS_REGEX.replace(s, "dueling")
			s = KNEW_IT_DOT_REGEX.replace(s) { m -> "${m.groupValues[1]} it" }
			s = AT_ALL_DOT_REGEX.replace(s, "at all")
			s = XIULIAN_REGEX.replace(s, "cultivation")
			s = LIL_REGEX.replace(s, "little")
			s = SEN_GE_REGEX.replace(s, "Brother Sen")
			s = OH_DAMN_REGEX.replace(s, "oh crap,")
			s = ASF_REGEX.replace(s, "as hell")
			s = WTF_REGEX.replace(s, "what the hell")
			s = OMG_REGEX.replace(s, "oh my god")

			if (s.count { it == '"' } == 1) {
				s = s.replace("\"", "")
			}
			s = QUITE_THE_DISGUISE_REGEX.replace(s, "What a disguise")

			// 6. Contraction repair ("DONT" -> "don't", "CANT" -> "can't", "IM" -> "I'm", "YOURE" -> "you're", "ITS" -> "it's")
			s = repairMissingApostropheContractions(s, forceAllCapsContext = shouldSentenceCase)

			// 7. Split glued English tokens via DP vocabulary segmentation
			s = ASCII_ALPHA_WORD_10_48_REGEX.replace(s) { m -> segmentGluedEnglishToken(m.value) }
			s = CONSTITUTION_BUILT_FOR_REGEX.replace(s, "physique built for")

			// 8. ALL-CAPS -> natural sentence case (preserving 'I' and I'm/I'll/I've/I'd)
			if (shouldSentenceCase) {
				s = applySentenceCasePreservingPronounI(s)
			} else {
				// Even if mixed-case, normalize lowercase standalone 'i' before contractions
				s = I_CONTRACTION_REGEX.replace(s, "I")
			}

			// 9. Turkish-specific Google Translate disambiguation hints
			if (targetLang.equals("tr", ignoreCase = true)) {
				s = TR_MY_DOT_REGEX.replace(s) { m -> "${m.groupValues[1]}Oh my, " }
				s = TR_HEY_LOOK_REGEX.replace(s) { m -> "Hey, look here${m.groupValues[1]}" }
				s = TR_ONLY_LOOK_REGEX.replace(s) { m -> "Look here${m.groupValues[1]}" }
				s = TR_CONFINED_REGEX.replace(s, "Imprisoned?")
				s = TR_MISS_PAIR_REGEX.replace(s, "no one will notice a missing pair")
				s = TR_POST_QUOTE_REGEX.replace(s, "")
				s = ILL_VERB_REGEX.replace(s, "I'll")
			}

			return s.replace(MULTILINE_SPACE_REGEX, " ").trim()
		}

		internal fun prepareTextForGoogleTranslate(
			raw: String,
			sourceLang: OcrSourceLanguage = OcrSourceLanguage.AUTO,
			targetLang: String = "tr",
		): String {
			val clean = normalizeBubbleInput(raw)
			if (clean.isEmpty()) return ""

			val letters = clean.filter { it.isLetter() }
			if (letters.isEmpty()) return clean

			val nonLatinCount = letters.count { it.code > 0x024F }
			if (nonLatinCount > letters.length * 0.3) {
				return clean
			}

			if (sourceLang == OcrSourceLanguage.ENGLISH) {
				return normalizeEnglishForGoogleTranslate(clean, targetLang)
			}

			val looksNonEnglishLatin = NON_ENGLISH_EUROPEAN_DIACRITICS_REGEX.containsMatchIn(clean) ||
				(NON_ENGLISH_LATIN_STOPWORD_REGEX.containsMatchIn(clean) && !ENGLISH_HINT_STOPWORD_REGEX.containsMatchIn(clean))

			if (looksNonEnglishLatin) {
				return if (isPredominantlyUppercase(clean)) {
					clean.lowercase(Locale.ROOT).replaceFirstChar { ch ->
						if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
					}
				} else {
					clean
				}
			}

			return normalizeEnglishForGoogleTranslate(clean, targetLang)
		}

		private fun isPredominantlyUppercase(text: String): Boolean {
			var upperCount = 0
			var lowerCount = 0
			var hasInteriorMixedCase = false
			for (i in text.indices) {
				val ch = text[i]
				when {
					ch in 'A'..'Z' -> {
						upperCount += 1
						if (i > 0 && text[i - 1] in 'a'..'z') {
							hasInteriorMixedCase = true
						}
					}

					ch in 'a'..'z' -> lowerCount += 1
				}
			}
			return upperCount >= 2 &&
				(lowerCount <= maxOf(1, (upperCount * 0.18).toInt()) ||
					((hasInteriorMixedCase || upperCount >= 6) && lowerCount <= upperCount * 0.45))
		}

		private fun repairMissingApostropheContractions(
			input: String,
			forceAllCapsContext: Boolean,
		): String {
			var s = UNAMBIGUOUS_CONTRACTION_REGEX.replace(input) { m ->
				val lower = m.value.lowercase(Locale.ROOT)
				val replacement = UNAMBIGUOUS_CONTRACTION_MAP[lower] ?: return@replace m.value
				preserveContractionCase(m.value, replacement, forceAllCapsContext)
			}

			s = IM_PREDICATE_REGEX.replace(s) { m ->
				preserveContractionCase(m.value, "I'm", forceAllCapsContext)
			}
			if (forceAllCapsContext) {
				s = IM_EXACT_UPPER_OR_LOWER_REGEX.replace(s, "I'm")
			}

			s = ITS_CONTRACTION_REGEX.replace(s) { m ->
				preserveContractionCase(m.value, "it's", forceAllCapsContext)
			}
			s = LETS_CONTRACTION_REGEX.replace(s) { m ->
				preserveContractionCase(m.value, "let's", forceAllCapsContext)
			}
			s = ID_CONTRACTION_REGEX.replace(s) { m ->
				preserveContractionCase(m.value, "I'd", forceAllCapsContext)
			}
			s = ILL_VERB_REGEX.replace(s, "I'll")
			return s
		}

		private fun preserveContractionCase(
			original: String,
			replacement: String,
			forceAllCapsContext: Boolean,
		): String {
			if (replacement.startsWith("I'") || replacement.startsWith("i'")) {
				return "I" + replacement.substring(1)
			}
			if (forceAllCapsContext) return replacement
			val first = original.firstOrNull() ?: return replacement
			return if (first.isUpperCase()) {
				replacement.replaceFirstChar { it.uppercaseChar() }
			} else {
				replacement
			}
		}

		private fun applySentenceCasePreservingPronounI(input: String): String {
			var s = input.lowercase(Locale.US)
			s = SENTENCE_START_CAPITALIZE_REGEX.replace(s) { m ->
				"${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3].uppercase(Locale.US)}"
			}
			s = LEADING_ELLIPSIS_CAPITALIZE_REGEX.replace(s) { m ->
				"${m.groupValues[1]}${m.groupValues[2].uppercase(Locale.US)}"
			}
			s = LONE_I_REGEX.replace(s, "I")
			s = I_CONTRACTION_REGEX.replace(s, "I")
			return s
		}

		internal fun buildGeminiSystemPrompt(
			sourceLangLabel: String,
			targetLangLabel: String,
			targetLangCode: String,
			skipPureSfx: Boolean,
			filterPromoAndCredits: Boolean,
		): String = buildString {
			appendLine("You are an expert manga, manhwa, and webtoon localization translator translating OCR text blocks from $sourceLangLabel into natural, fluent, and idiomatic $targetLangLabel ($targetLangCode).")
			appendLine()
			appendLine("CONTEXT, CONTINUITY & OCR REPAIR:")
			appendLine("- Entries are ordered in page reading order; use surrounding bubbles to keep speaker tone, pronouns, politeness register, and story continuity consistent.")
			appendLine("- Infer and repair OCR typos, broken hyphens, missing apostrophes (e.g. DONT, CANT, IM, YOURE), symbol swaps (L!KE, HOWz, 1'M), or glued ALL-CAPS words (EVENASMALLSTIMULUSCAN) cleanly while translating.")
			appendLine()
			appendLine("NAMES, HONORIFICS & GAME/FANTASY TERMS:")
			appendLine("- Keep personal character names (e.g., Jiang Cheng, Kim Jeonghyuk, Sung Jinwoo) and Asian honorifics (-san, -kun, -chan, -sama, -sensei, -senpai, Oppa, Hyung, Unnie, Noona, Sunbae, -ssi, -nim) in their original romanized spelling without literally translating family-style suffixes.")
			appendLine("- ALWAYS translate descriptive nicknames, usernames, monster/species names, item/weapon names, skill names, guild/rank names, titles, system alerts, and RPG abbreviations like LVL (e.g., \"OWNER: JUST A DUMB CUTIE (LVL: 1)\" -> translate the entire phrase including \"JUST A DUMB CUTIE\" and \"LVL: 1\"; \"LEVEL: LVL 1\" -> translate both).")
			if (targetLangCode.equals("tr", ignoreCase = true)) {
				appendLine("- In Turkish fantasy/martial/system webtoons, translate \"constitution\" as \"bünye\" (never \"anayasa\"), \"cultivation\"/\"xiulian\" as \"gelişim\", and avoid stiff dictionary infinitives for short lines (e.g. \"LOOK.\" -> \"Bak.\", \"CONFINED?\" -> \"Tutsak mı?\").")
			}
			appendLine()
			appendLine("BUBBLE CONCISENESS & PLAIN TEXT:")
			appendLine("- Keep translations concise, punchy, and conversational so they fit comfortably inside speech bubbles and RPG status rows.")
			appendLine("- Output plain text ONLY (never emit HTML tags, markdown formatting, translator notes, or explanatory glosses).")
			appendLine("- Never echo the source sentence unchanged unless it is strictly a personal proper noun, numeral, or universal loanword.")
			appendLine()
			appendLine("CLASSIFICATION (kind), SFX & TRASH FILTERING:")
			appendLine("- Valid kind values: \"dialogue\", \"narration\", \"sfx\", \"emphasis\".")
			if (filterPromoAndCredits) {
				appendLine("- Set \"trash\": true and \"text\": \"\" for scanlation credits, staff recruitment, website URLs/watermarks, Discord/Patreon promos, or stray page numbers.")
			} else {
				appendLine("- Preserve credits/promo text if readable; set \"trash\": true only for completely unreadable OCR gibberish.")
			}
			if (skipPureSfx) {
				appendLine("- STRICT SFX RULE: If an entry is purely an onomatopoeia, sound effect, visual action cue, or non-verbal noise without dialogue/story meaning (e.g., \"BOOM\", \"BAM\", \"CRACK!\", \"WHOOSH\", \"SLAM\", \"CLANK\", \"THUD\", \"GASP\", \"GRIN\", \"SMIRK\", \"SHIVER\", \"STARE\", \"RUSTLE\", \"CREAK\", \"SQUEAK\", \"TAP TAP\", \"STARTLE!\", \"Flinch\", \"Twitch\", \"Patter\", \"Rumble\", \"Zzzt\", \"Doki doki\", \"Kwaang\"), set \"trash\": true, \"kind\": \"sfx\", and \"text\": \"[SFX]\". NEVER translate pure sound effects into local words like \"Güm\", \"Sırıtış\", \"İrkilir\", or \"Bakış\".")
				appendLine("- MEANINGFUL LINES MUST BE TRANSLATED: NEVER skip meaningful statements, short exclamations, questions, dialogue, narration, or RPG stats (e.g., \"NO!\", \"HELP!\", \"WHY?\", \"WAIT!\", \"STOP!\", \"ALONE?\", \"FIRST MURDER.\", \"HEALTH: 100\") — always translate those into $targetLangLabel with \"trash\": false.")
			} else {
				appendLine("- Keep sound effects: translate or stylize SFX naturally into $targetLangLabel with \"kind\": \"sfx\" and \"trash\": false.")
			}
			append("- Return strictly a JSON array: [{\"id\":\"...\",\"text\":\"...\",\"kind\":\"dialogue|narration|sfx|emphasis\",\"trash\":false}].")
		}

		internal fun buildGeminiResponseSchema(): JSONObject {
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

		internal fun buildPermissiveSafetySettings(): JSONArray {
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

		internal fun parseGeminiResponse(
			responseBody: String,
			bubbles: List<OcrBubbleGroup>,
			cleanTextsById: Map<String, String> = bubbles.associate { it.id to normalizeBubbleInput(it.text) },
			settings: TranslationSettingsSnapshot = TranslationSettingsSnapshot(),
			targetLang: String = settings.targetLang,
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
						if (part.isNull("text")) continue
						append(part.optString("text", ""))
					}
				}.trim()
			}.getOrNull().orEmpty()

			if (rawContent.isBlank()) return null

			val jsonArray = extractJsonArray(rawContent) ?: return null
			if (jsonArray.length() == 0 && bubbles.isNotEmpty()) return null

			val bubbleById = bubbles.associateBy { it.id }
			val resultsById = LinkedHashMap<String, TranslatedTextItem>(bubbles.size)

			for (i in 0 until jsonArray.length()) {
				val obj = jsonArray.optJSONObject(i)
				if (obj == null) {
					// Fallback if Gemini returned a flat string array matching bubble count
					if (jsonArray.length() == bubbles.size && i < bubbles.size && !jsonArray.isNull(i)) {
						val str = jsonArray.optString(i, "").trim()
						val bubble = bubbles[i]
						if (bubble.id !in resultsById) {
							val cleanOrig = cleanTextsById[bubble.id] ?: normalizeBubbleInput(bubble.text)
							val item = postProcessTranslatedItem(
								id = bubble.id,
								translatedRaw = str,
								originalCleanText = cleanOrig,
								kind = bubble.kindHint,
								rawTrash = str.isBlank(),
								settings = settings,
								targetLang = targetLang,
							)
							if (str.isNotBlank() || item.isTrash) {
								resultsById[bubble.id] = item
							}
						}
					}
					continue
				}

				var id = if (obj.isNull("id")) "" else obj.optString("id", "").trim()
				if (id.isEmpty() || id !in bubbleById) {
					if (jsonArray.length() == bubbles.size && i < bubbles.size && bubbles[i].id !in resultsById) {
						id = bubbles[i].id
					} else {
						continue
					}
				}
				val bubble = bubbleById[id] ?: continue
				val cleanOrig = cleanTextsById[id] ?: normalizeBubbleInput(bubble.text)
				val rawKind = if (obj.isNull("kind")) "" else obj.optString("kind", "")
				val parsedKind = parseKind(rawKind, bubble.kindHint)
				val rawText = if (obj.isNull("text")) "" else obj.optString("text", "")
				val rawTrash = obj.optBoolean("trash", false)

				// If Gemini returned an empty string with trash=false on real dialogue, omit it so Google fallback translates it.
				val cleanedPreview = cleanTranslatedOutput(rawText)
				if (!rawTrash && cleanedPreview.isBlank() &&
					!isPureSfxOrNoise(cleanOrig, bubble.kindHint) &&
					!isPromoOrCredit(cleanOrig, settings.sourceLang)
				) {
					continue
				}

				resultsById[id] = postProcessTranslatedItem(
					id = id,
					translatedRaw = rawText,
					originalCleanText = cleanOrig,
					kind = parsedKind,
					rawTrash = rawTrash,
					settings = settings,
					targetLang = targetLang,
				)
			}

			if (resultsById.isEmpty()) return null

			val originals = bubbles.map { it.id to (cleanTextsById[it.id] ?: normalizeBubbleInput(it.text)) }
			if (shouldRejectUnchangedBatch(originals, resultsById, settings.sourceLang, targetLang)) {
				return null
			}

			return resultsById.values.toList()
		}

		internal fun extractJsonArray(raw: String): JSONArray? {
			var trimmed = raw.trim()
			if (trimmed.isEmpty()) return null

			val fencedMatch = CODE_FENCE_BLOCK_REGEX.find(trimmed)
			if (fencedMatch != null) {
				trimmed = fencedMatch.groupValues[1].trim()
			} else if (trimmed.startsWith("```")) {
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
					obj.optJSONArray("data")?.let { return it }
				}
			}

			val start = trimmed.indexOf('[')
			val end = trimmed.lastIndexOf(']')
			if (start >= 0 && end > start) {
				return runCatching { JSONArray(trimmed.substring(start, end + 1)) }.getOrNull()
			}
			return null
		}

		internal fun parseMarkedGoogleOutput(
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

		internal fun evaluatePrefilter(
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

		internal fun isPromoOrCredit(
			text: String,
			sourceLang: OcrSourceLanguage,
		): Boolean {
			if (TextBlockFilters.isCreditOrPromoText(text)) return true
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

		internal fun isPureSfxOrNoise(
			text: String,
			kindHint: TextBlockKind = TextBlockKind.DIALOGUE,
		): Boolean {
			val trimmed = text.trim()
			if (trimmed.isEmpty()) return true
			if (SFX_TAG_ONLY_REGEX.matches(trimmed)) return true
			if (SFX_WRAPPED_REGEX.matches(trimmed)) return true
			if (TextBlockFilters.isPureSoundEffect(trimmed)) return true

			val hasQuestionMark = QUESTION_END_REGEX.containsMatchIn(trimmed)
			val words = trimmed.split(' ').filter { it.isNotBlank() }
			if (words.isEmpty()) return true

			val strippedTokens = SFX_TOKEN_SPLIT_REGEX.split(trimmed)
				.map { it.trim().lowercase(Locale.ROOT).replace("\u0307", "") }
				.filter { it.isNotEmpty() }
			if (strippedTokens.isEmpty()) return true

			if (strippedTokens.any { it in MEANINGFUL_SINGLE_WORDS }) {
				return false
			}

			if (!hasQuestionMark && words.size <= 4 && words.all { VOCAL_FILLER_TOKEN_REGEX.matches(it) }) {
				return true
			}

			if (!hasQuestionMark && strippedTokens.size <= 4) {
				val allExtraSfx = strippedTokens.all { tok ->
					val alpha = NON_ASCII_LOWER_ALPHA_REGEX.replace(tok, "")
					alpha.isEmpty() ||
						alpha in EXTRA_PURE_SFX_STEMS ||
						CONSONANT_STRETCH_SFX_REGEX.matches(alpha) ||
						(HYPHENATED_STUTTER_SFX_REGEX.matches(tok) &&
							tok.substringAfter('-').lowercase(Locale.ROOT) in EXTRA_PURE_SFX_STEMS)
				}
				if (allExtraSfx) return true
			}

			return false
		}

		internal fun isLikelyAlreadyTranslated(
			text: String,
			sourceLang: OcrSourceLanguage = OcrSourceLanguage.AUTO,
			targetLang: String = "tr",
		): Boolean {
			val t = text.trim()
			if (t.isEmpty()) return true

			val target = TranslationLanguages.normalizeTargetCode(targetLang).lowercase(Locale.ROOT)
			if (target == sourceLang.code.lowercase(Locale.ROOT)) {
				return true
			}
			if (target == "zh-tw" && sourceLang == OcrSourceLanguage.CHINESE) {
				return false
			}

			val letters = t.filter { it.isLetter() }
			if (letters.isEmpty()) return false
			val letterCount = letters.length

			return when (target) {
				"tr" -> {
					if (NON_ENGLISH_EUROPEAN_DIACRITICS_REGEX.containsMatchIn(t)) return false
					if (FOREIGN_STOPWORD_FOR_TR_REGEX.containsMatchIn(t)) return false
					val latinCount = letters.count { it.code in 0x0041..0x024F }
					if (latinCount < letterCount * 0.8) return false
					val uniqueTr = letters.count { it in UNIQUE_TURKISH_CHARS }
					val strictTr = letters.count { it in STRICT_TURKISH_CHARS }
					val hasTrWord = TURKISH_STOPWORD_REGEX.containsMatchIn(t)
					uniqueTr >= 2 || strictTr >= 2 || (strictTr >= 1 && hasTrWord)
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

		internal fun cleanTranslatedOutput(raw: String): String {
			if (raw.isBlank()) return ""
			val unescaped = raw
				.replace("&nbsp;", " ")
				.replace("&amp;", "&")
				.replace("&quot;", "\"")
				.replace("&#39;", "'")
				.replace("&apos;", "'")
				.replace("&lt;", " ")
				.replace("&gt;", " ")
				.replace(HTML_TAG_REGEX, " ")
				.replace(ANGLE_BRACKET_REGEX, " ")
				.replace("\r\n", "\n")
				.replace('\r', '\n')
			val normalizedLines = unescaped.split('\n').joinToString("\n") { line ->
				HORIZONTAL_SPACE_REGEX.replace(line, " ").trim()
			}
			return EXCESS_NEWLINES_REGEX.replace(normalizedLines, "\n\n").trim()
		}

		internal fun postProcessTranslatedText(
			translated: String,
			originalText: String,
			targetLang: String = "tr",
		): String {
			var s = cleanTranslatedOutput(translated)
			if (s.isEmpty()) return ""
			if (SFX_TAG_ONLY_REGEX.matches(s)) return "[SFX]"
			if (INLINE_SFX_TAG_REGEX.containsMatchIn(s)) {
				s = INLINE_SFX_TAG_REGEX.replace(s, " ").replace(HORIZONTAL_SPACE_REGEX, " ").trim()
				if (s.isEmpty()) return "[SFX]"
			}

			if (targetLang.equals("tr", ignoreCase = true)) {
				s = ASH_GRAY_RESTORE_REGEX.replace(s, "Ash Gray")
				s = ILK_I_YI_REGEX.replace(s, "ilkeyi")
				s = AH_KAHRAMAN_REGEX.replace(s, "AH LANET")
				s = XIULIAN_REGEX.replace(s, "gelişim")

				if (CONSTITUTION_WORD_REGEX.containsMatchIn(originalText) &&
					CONSTITUTION_CONTEXT_REGEX.containsMatchIn(originalText)
				) {
					s = ANAYASA_WORD_REGEX.replace(s) { m ->
						val isAllCaps = m.value.any { it.isLetter() } && m.value == m.value.uppercase(Locale.ROOT)
						if (isAllCaps) "BÜNYE" else "Bünye"
					}
				}
			}

			return s.replace(HORIZONTAL_SPACE_REGEX, " ").trim()
		}

		internal fun postProcessTranslatedItem(
			id: String,
			translatedRaw: String,
			originalCleanText: String,
			kind: TextBlockKind = TextBlockKind.DIALOGUE,
			rawTrash: Boolean = false,
			settings: TranslationSettingsSnapshot = TranslationSettingsSnapshot(),
			targetLang: String = settings.targetLang,
		): TranslatedTextItem {
			val processed = postProcessTranslatedText(translatedRaw, originalCleanText, targetLang)
			val isSfxSentinel = SFX_TAG_ONLY_REGEX.matches(processed)

			if (isSfxSentinel) {
				return TranslatedTextItem(
					id = id,
					text = "",
					kind = TextBlockKind.SFX,
					isTrash = true,
				)
			}

			val normalizedTarget = TranslationLanguages.normalizeTargetCode(targetLang).lowercase(Locale.ROOT)
			val residualUntranslatedCjk = when {
				normalizedTarget != "ko" && processed.any(::isHangul) -> true
				normalizedTarget != "ja" && processed.any(::isKana) -> true
				normalizedTarget != "zh" && normalizedTarget != "zh-tw" && normalizedTarget != "ja" && processed.any(::isHan) -> true
				else -> false
			}

			val shouldDropAsSfx = settings.skipPureSfx && (
				isPureSfxOrNoise(processed, kind) ||
					isPureSfxOrNoise(originalCleanText, kind) ||
					(kind == TextBlockKind.SFX && !isMeaningfulDialogueText(originalCleanText, processed))
				)

			val shouldDropAsPromo = settings.filterPromoAndCredits &&
				(isPromoOrCredit(processed, settings.sourceLang) || isPromoOrCredit(originalCleanText, settings.sourceLang))

			val finalTrash = when {
				rawTrash && kind == TextBlockKind.SFX && !settings.skipPureSfx && processed.isNotEmpty() -> false
				rawTrash -> true
				processed.isBlank() -> true
				shouldDropAsSfx -> true
				shouldDropAsPromo -> true
				residualUntranslatedCjk && processed.filter { it.isLetter() }.all { isCjkOrHangul(it) } -> true
				else -> false
			}

			return TranslatedTextItem(
				id = id,
				text = if (finalTrash) "" else processed,
				kind = if (shouldDropAsSfx) TextBlockKind.SFX else kind,
				isTrash = finalTrash,
			)
		}

		internal fun finalizeSingleItem(
			item: TranslatedTextItem,
			originalCleanText: String,
			settings: TranslationSettingsSnapshot,
			targetLang: String,
		): TranslatedTextItem {
			if (item.isTrash) return item
			if (originalCleanText.isNotEmpty() &&
				normalizeForCompare(item.text) == normalizeForCompare(originalCleanText) &&
				!isLikelyAlreadyTranslated(originalCleanText, settings.sourceLang, targetLang)
			) {
				// Leave original artwork untouched if a single token/name came back identical
				return item.copy(text = "", isTrash = true)
			}
			// Also guard against unknown lowercase OCR garble preceded by an English article
			// (e.g. "The cocsio." -> Google returns "cocsio." verbatim).
			val origWords = originalCleanText.trim().split(MULTILINE_SPACE_REGEX).filter { it.isNotEmpty() }
			if (origWords.size == 2 &&
				origWords[0].lowercase(Locale.ROOT) in ARTICLE_PREFIX_WORDS &&
				origWords[1].any { it.isLetter() } &&
				origWords[1].none { it.isDigit() } &&
				origWords[1].firstOrNull { it.isLetter() }?.isLowerCase() == true
			) {
				val tail = origWords[1]
				val transNorm = normalizeForCompare(item.text)
				val tailNorm = normalizeForCompare(tail)
				if (tailNorm.isNotEmpty() &&
					transNorm == tailNorm &&
					tailNorm !in MEANINGFUL_SINGLE_WORDS &&
					!isLikelyAlreadyTranslated(tail, settings.sourceLang, targetLang)
				) {
					return item.copy(text = "", isTrash = true)
				}
			}
			return item
		}

		private fun isMeaningfulDialogueText(
			originalText: String,
			translatedText: String,
		): Boolean {
			val trimmedOrig = originalText.trim()
			val trimmedTrans = translatedText.trim()
			if (QUESTION_END_REGEX.containsMatchIn(trimmedOrig) || QUESTION_END_REGEX.containsMatchIn(trimmedTrans)) return true
			val origTokens = SFX_TOKEN_SPLIT_REGEX.split(trimmedOrig)
				.map { it.trim().lowercase(Locale.ROOT).replace("\u0307", "") }
				.filter { it.isNotEmpty() }
			if (origTokens.any { it in MEANINGFUL_SINGLE_WORDS }) return true
			val transTokens = SFX_TOKEN_SPLIT_REGEX.split(trimmedTrans)
				.map { it.trim().lowercase(Locale.ROOT).replace("\u0307", "") }
				.filter { it.isNotEmpty() }
			if (transTokens.any { it in MEANINGFUL_SINGLE_WORDS }) return true
			return origTokens.size >= 2 &&
				origTokens.distinct().size >= 2 &&
				!isPureSfxOrNoise(trimmedOrig, TextBlockKind.DIALOGUE) &&
				!isPureSfxOrNoise(trimmedTrans, TextBlockKind.DIALOGUE)
		}

		internal fun shouldRejectUnchangedBatch(
			originals: List<Pair<String, String>>,
			results: Map<String, TranslatedTextItem>,
			sourceLang: OcrSourceLanguage,
			targetLang: String,
		): Boolean {
			val normalizedTarget = TranslationLanguages.normalizeTargetCode(targetLang).lowercase(Locale.ROOT)
			if (sourceLang.code.equals(normalizedTarget, ignoreCase = true)) return false

			var checkedForeign = 0
			var unchangedCount = 0
			var hasTranslatableSentence = false

			for ((id, origClean) in originals) {
				val item = results[id] ?: continue
				if (item.isTrash) continue
				if (isLikelyAlreadyTranslated(origClean, sourceLang, targetLang)) continue
				if (normalizedTarget == "en" && !origClean.any { it.code > 0x024F } &&
					ENGLISH_HINT_STOPWORD_REGEX.containsMatchIn(origClean)
				) {
					continue
				}
				val words = origClean.split(' ').filter { it.length >= 2 }
				val cjkCount = origClean.count(::isCjkOrHangul)
				val isSentence = words.size >= 3 ||
					cjkCount >= 4 ||
					ENGLISH_HINT_STOPWORD_REGEX.containsMatchIn(origClean) ||
					FOREIGN_STOPWORD_FOR_TR_REGEX.containsMatchIn(origClean)
				if (!isSentence) continue

				hasTranslatableSentence = true
				checkedForeign += 1
				if (normalizeForCompare(item.text) == normalizeForCompare(origClean)) {
					unchangedCount += 1
				}
			}

			if (!hasTranslatableSentence || checkedForeign == 0) return false
			if (checkedForeign >= 2 && unchangedCount == checkedForeign) return true
			if (checkedForeign == 1 && unchangedCount == 1) {
				val singleOrig = originals.firstOrNull { (id, orig) ->
					val res = results[id]
					res != null && !res.isTrash &&
						(orig.split(' ').count { it.length >= 2 } >= 4 || orig.count(::isCjkOrHangul) >= 5)
				}?.second
				if (singleOrig != null && isMeaningfulForeignSentence(singleOrig, sourceLang, targetLang)) {
					return true
				}
			}
			return false
		}

		private fun isMeaningfulForeignSentence(
			text: String,
			sourceLang: OcrSourceLanguage,
			targetLang: String,
		): Boolean {
			if (isLikelyAlreadyTranslated(text, sourceLang, targetLang)) return false
			val words = text.split(' ').filter { it.length >= 2 }
			if (words.size >= 3) return true
			return ENGLISH_HINT_STOPWORD_REGEX.containsMatchIn(text) ||
				FOREIGN_STOPWORD_FOR_TR_REGEX.containsMatchIn(text) ||
				text.any(::isCjkOrHangul)
		}

		internal fun normalizeBubbleInput(raw: String): String {
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

		internal fun normalizeForCompare(text: String): String {
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

		private fun isHangul(ch: Char): Boolean = ch in '\uAC00'..'\uD7AF' || ch in '\u1100'..'\u11FF' || ch in '\u3130'..'\u318F'
		private fun isKana(ch: Char): Boolean = ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF'
		private fun isHan(ch: Char): Boolean = ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF'
		private fun isCjkOrKana(ch: Char): Boolean = isHan(ch) || isKana(ch)
		private fun isCjkOrHangul(ch: Char): Boolean = isHan(ch) || isKana(ch) || isHangul(ch)
	}
}
