package org.koitharu.kotatsu.reader.domain.translate.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

sealed class TranslationException(
	message: String,
	cause: Throwable? = null,
) : IOException(message, cause)

class MissingGeminiApiKeyException(
	message: String = "Gemini API key is missing. Add at least one API key in translation settings or switch to Google Translate.",
) : TranslationException(message)

class InvalidGeminiApiKeyException(
	val invalidKeysCount: Int,
	message: String = "All configured Gemini API key(s) ($invalidKeysCount) were rejected as invalid or unauthorized.",
) : TranslationException(message)

class GeminiQuotaExhaustedException(
	val retryDelayMs: Long,
	message: String = "All Gemini API keys are rate-limited or out of quota.",
	cause: Throwable? = null,
) : TranslationException(message, cause)

class TranslationServiceException(
	val statusCode: Int,
	val isTransient: Boolean,
	message: String,
	cause: Throwable? = null,
) : TranslationException(message, cause)

enum class GeminiHttpErrorKind {
	INVALID_KEY,
	RATE_LIMIT,
	MODEL_NOT_FOUND,
	THINKING_CONFIG_UNSUPPORTED,
	TRANSIENT,
	OTHER,
}

data class GeminiHttpErrorInfo(
	val statusCode: Int,
	val kind: GeminiHttpErrorKind,
	val message: String,
	val retryDelayMs: Long? = null,
)

data class AcquiredGeminiKey(
	val key: String,
	val maskedKey: String,
	val usableKeyCount: Int,
	val totalKeyCount: Int,
)

data class GeminiKeyPoolStats(
	val totalCount: Int,
	val usableCount: Int,
	val readyCount: Int,
	val coolingDownCount: Int,
	val invalidCount: Int,
	val soonestCooldownRemainingMs: Long,
)

/**
 * Thread-safe multi-API key pool for Gemini translation requests.
 *
 * Distributes load across healthy keys (least in-flight, earliest dispatch window, round-robin),
 * enforces per-key dispatch spacing, applies exponential/Retry-After cooldowns on HTTP 429,
 * and isolates invalid/unauthorized keys permanently until [reset] or replaced.
 */
@Singleton
class GeminiKeyPool @Inject constructor() {

	private val mutex = Mutex()
	private val slots = LinkedHashMap<String, KeySlotState>()
	private var pickSequence = 0L

	private class KeySlotState(
		val key: String,
		var cooldownUntilMs: Long = 0L,
		var nextDispatchAtMs: Long = 0L,
		var inFlight: Int = 0,
		var rateLimitStrikes: Int = 0,
		var permanentlyInvalid: Boolean = false,
		var invalidReason: String? = null,
		var lastPickedSeq: Long = 0L,
	)

	private sealed interface AcquireStep {
		data class Reserved(
			val key: String,
			val waitMs: Long,
			val usableKeyCount: Int,
			val totalKeyCount: Int,
		) : AcquireStep

		data class CooldownWait(
			val waitMs: Long,
			val usableKeyCount: Int,
		) : AcquireStep
	}

	/**
	 * Acquires an available Gemini API key lease from [rawKeys], waiting briefly if all keys
	 * are pacing or on a short cooldown within [maxWaitMs].
	 *
	 * Callers MUST invoke [releaseKey] in a `finally` block once the request finishes.
	 */
	suspend fun acquireKey(
		rawKeys: List<String>,
		maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
	): AcquiredGeminiKey {
		val keys = normalizeApiKeysList(rawKeys)
		if (keys.isEmpty()) {
			throw MissingGeminiApiKeyException()
		}

		var remainingWaitBudgetMs = maxWaitMs.coerceAtLeast(0L)
		while (true) {
			val step = mutex.withLock {
				val now = System.currentTimeMillis()
				pruneIfOversizedLocked(keys)
				val activeSlots = keys.map { key ->
					slots.getOrPut(key) { KeySlotState(key) }
				}
				val usableSlots = activeSlots.filter { !it.permanentlyInvalid }
				if (usableSlots.isEmpty()) {
					val reasons = activeSlots.mapNotNull { it.invalidReason }.distinct().take(2)
					val detail = if (reasons.isNotEmpty()) " (${reasons.joinToString("; ")})" else ""
					throw InvalidGeminiApiKeyException(
						invalidKeysCount = activeSlots.size,
						message = "All ${activeSlots.size} Gemini API key(s) are invalid or unauthorized$detail.",
					)
				}

				val readySlots = usableSlots.filter { it.cooldownUntilMs <= now }
				if (readySlots.isNotEmpty()) {
					val chosen = readySlots.minWith(
						compareBy<KeySlotState>(
							{ it.inFlight },
							{ it.nextDispatchAtMs },
							{ it.lastPickedSeq },
						),
					)
					val dispatchWaitMs = (chosen.nextDispatchAtMs - now).coerceAtLeast(0L)
					val spacingMs = if (usableSlots.size > 1) {
						MULTI_KEY_DISPATCH_INTERVAL_MS
					} else {
						SINGLE_KEY_DISPATCH_INTERVAL_MS
					}
					chosen.inFlight += 1
					chosen.nextDispatchAtMs = maxOf(now, chosen.nextDispatchAtMs) + spacingMs
					pickSequence += 1L
					chosen.lastPickedSeq = pickSequence

					AcquireStep.Reserved(
						key = chosen.key,
						waitMs = dispatchWaitMs,
						usableKeyCount = usableSlots.size,
						totalKeyCount = activeSlots.size,
					)
				} else {
					val soonestCooldownUntil = usableSlots.minOf { it.cooldownUntilMs }
					AcquireStep.CooldownWait(
						waitMs = (soonestCooldownUntil - now).coerceAtLeast(1L),
						usableKeyCount = usableSlots.size,
					)
				}
			}

			when (step) {
				is AcquireStep.Reserved -> {
					if (step.waitMs > 0L) {
						try {
							delay(step.waitMs)
						} catch (t: Throwable) {
							releaseKey(step.key)
							throw t
						}
					}
					return AcquiredGeminiKey(
						key = step.key,
						maskedKey = maskApiKey(step.key),
						usableKeyCount = step.usableKeyCount,
						totalKeyCount = step.totalKeyCount,
					)
				}

				is AcquireStep.CooldownWait -> {
					if (remainingWaitBudgetMs <= 0L || step.waitMs > maxWaitMs) {
						val seconds = ((step.waitMs + 999L) / 1000L).coerceAtLeast(1L)
						throw GeminiQuotaExhaustedException(
							retryDelayMs = step.waitMs,
							message = "All ${step.usableKeyCount} Gemini API key(s) are rate-limited (~${seconds}s cooldown remaining).",
						)
					}
					val sleepMs = min(step.waitMs + 50L, remainingWaitBudgetMs)
					delay(sleepMs)
					remainingWaitBudgetMs -= sleepMs
				}
			}
		}
	}

	suspend fun releaseKey(key: String) {
		mutex.withLock {
			val slot = slots[key] ?: return
			slot.inFlight = (slot.inFlight - 1).coerceAtLeast(0)
		}
	}

	suspend fun markSuccess(key: String) {
		mutex.withLock {
			val slot = slots[key] ?: return
			slot.rateLimitStrikes = 0
			slot.cooldownUntilMs = 0L
		}
	}

	/**
	 * Marks [key] as rate-limited (HTTP 429 / RESOURCE_EXHAUSTED) and returns the applied cooldown (ms).
	 */
	suspend fun markRateLimited(
		key: String,
		retryDelayMs: Long? = null,
		errorMessage: String = "",
	): Long {
		return mutex.withLock {
			val now = System.currentTimeMillis()
			val slot = slots.getOrPut(key) { KeySlotState(key) }
			val parsedDelay = retryDelayMs?.takeIf { it > 0L } ?: parseRetryDelayMs(errorMessage)
			val exponentialMs = min(
				MAX_EXP_COOLDOWN_MS,
				BASE_RATE_LIMIT_COOLDOWN_MS * (1L shl slot.rateLimitStrikes.coerceAtMost(2)),
			)
			val cooldownMs = parsedDelay?.let { (it + 250L).coerceIn(MIN_RETRY_COOLDOWN_MS, MAX_SERVER_COOLDOWN_MS) }
				?: exponentialMs
			slot.rateLimitStrikes = (slot.rateLimitStrikes + 1).coerceAtMost(8)
			slot.cooldownUntilMs = maxOf(slot.cooldownUntilMs, now + cooldownMs)
			cooldownMs
		}
	}

	/**
	 * Permanently disables [key] from future rotations until [reset] is called.
	 */
	suspend fun markInvalid(
		key: String,
		reason: String,
	) {
		mutex.withLock {
			val slot = slots.getOrPut(key) { KeySlotState(key) }
			slot.permanentlyInvalid = true
			slot.invalidReason = reason.take(240)
		}
	}

	suspend fun getStats(rawKeys: List<String>): GeminiKeyPoolStats {
		val keys = normalizeApiKeysList(rawKeys)
		if (keys.isEmpty()) {
			return GeminiKeyPoolStats(0, 0, 0, 0, 0, 0L)
		}
		return mutex.withLock {
			val now = System.currentTimeMillis()
			val active = keys.map { slots[it] ?: KeySlotState(it) }
			val usable = active.filter { !it.permanentlyInvalid }
			val ready = usable.count { it.cooldownUntilMs <= now }
			val cooling = usable.filter { it.cooldownUntilMs > now }
			val soonest = cooling.minOfOrNull { (it.cooldownUntilMs - now).coerceAtLeast(0L) } ?: 0L
			GeminiKeyPoolStats(
				totalCount = active.size,
				usableCount = usable.size,
				readyCount = ready,
				coolingDownCount = cooling.size,
				invalidCount = active.size - usable.size,
				soonestCooldownRemainingMs = soonest,
			)
		}
	}

	suspend fun reset(keys: Collection<String>? = null) {
		mutex.withLock {
			if (keys == null) {
				slots.clear()
			} else {
				keys.forEach { slots.remove(it.trim()) }
			}
		}
	}

	private fun pruneIfOversizedLocked(activeKeys: List<String>) {
		if (slots.size <= MAX_TRACKED_SLOTS) return
		val activeSet = activeKeys.toHashSet()
		val iterator = slots.entries.iterator()
		while (iterator.hasNext() && slots.size > MAX_TRACKED_SLOTS) {
			val entry = iterator.next()
			if (entry.key !in activeSet && entry.value.inFlight == 0) {
				iterator.remove()
			}
		}
	}

	companion object {

		const val MIN_KEY_LENGTH: Int = 12
		private const val SINGLE_KEY_DISPATCH_INTERVAL_MS: Long = 140L
		private const val MULTI_KEY_DISPATCH_INTERVAL_MS: Long = 80L
		private const val MIN_RETRY_COOLDOWN_MS: Long = 6_000L
		private const val BASE_RATE_LIMIT_COOLDOWN_MS: Long = 6_000L
		private const val MAX_EXP_COOLDOWN_MS: Long = 25_000L
		private const val MAX_SERVER_COOLDOWN_MS: Long = 60_000L
		private const val DEFAULT_MAX_WAIT_MS: Long = 5_000L
		private const val MAX_TRACKED_SLOTS: Int = 64

		private val SPLIT_REGEX = Regex("[\\s,;]+")
		private val RETRY_IN_SECONDS_REGEX = Regex(
			"(?:retry\\s+(?:in|after)\\s*|retryDelay[\"':\\s]+)([0-9]+(?:\\.[0-9]+)?)\\s*s",
			RegexOption.IGNORE_CASE,
		)

		fun parseApiKeysInput(raw: String): List<String> {
			if (raw.isBlank()) return emptyList()
			val seen = LinkedHashSet<String>()
			for (token in raw.split(SPLIT_REGEX)) {
				val cleaned = stripWrappingQuotes(token.trim())
				if (cleaned.length >= MIN_KEY_LENGTH) {
					seen.add(cleaned)
				}
			}
			return seen.toList()
		}

		fun normalizeApiKeysList(keys: List<String>): List<String> {
			if (keys.isEmpty()) return emptyList()
			val seen = LinkedHashSet<String>()
			for (entry in keys) {
				for (parsed in parseApiKeysInput(entry)) {
					seen.add(parsed)
				}
			}
			return seen.toList()
		}

		fun maskApiKey(key: String): String {
			val trimmed = key.trim()
			if (trimmed.length <= 8) return "****"
			return "${trimmed.take(4)}...${trimmed.takeLast(4)}"
		}

		fun classifyGeminiHttpError(
			statusCode: Int,
			responseBody: String,
			retryAfterHeader: String? = null,
		): GeminiHttpErrorInfo {
			val parsed = parseGeminiErrorJson(responseBody)
			val errorMessage = parsed.message.ifBlank {
				responseBody.trim().ifBlank { "HTTP $statusCode" }
			}
			val rpcStatus = parsed.status.uppercase(Locale.ROOT)
			val lowerMsg = errorMessage.lowercase(Locale.ROOT)
			val lowerBody = responseBody.lowercase(Locale.ROOT)
			val combinedLower = "$lowerMsg\n$lowerBody"

			val retryDelayMs = parsed.retryDelayMs
				?: parseRetryAfterHeaderMs(retryAfterHeader)
				?: parseRetryDelayMs(errorMessage)

			val kind = when {
				isFatalKeyError(statusCode, rpcStatus, combinedLower) -> GeminiHttpErrorKind.INVALID_KEY
				isRateLimitError(statusCode, rpcStatus, combinedLower) -> GeminiHttpErrorKind.RATE_LIMIT
				isThinkingConfigError(statusCode, combinedLower) -> GeminiHttpErrorKind.THINKING_CONFIG_UNSUPPORTED
				isModelNotFoundError(statusCode, rpcStatus, combinedLower) -> GeminiHttpErrorKind.MODEL_NOT_FOUND
				isTransientStatusOrMessage(statusCode, rpcStatus, combinedLower) -> GeminiHttpErrorKind.TRANSIENT
				else -> GeminiHttpErrorKind.OTHER
			}

			return GeminiHttpErrorInfo(
				statusCode = statusCode,
				kind = kind,
				message = errorMessage,
				retryDelayMs = retryDelayMs,
			)
		}

		fun parseRetryDelayMs(text: String): Long? {
			if (text.isBlank()) return null
			val match = RETRY_IN_SECONDS_REGEX.find(text) ?: return null
			val seconds = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
			if (seconds <= 0.0) return null
			return (seconds * 1000.0).toLong()
		}

		fun parseRetryAfterHeaderMs(headerValue: String?): Long? {
			val trimmed = headerValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
			trimmed.toLongOrNull()?.let { seconds ->
				return if (seconds > 0L) seconds * 1000L else null
			}
			return runCatching {
				val epochMs = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
					.toInstant()
					.toEpochMilli()
				(epochMs - System.currentTimeMillis()).takeIf { it > 0L }
			}.getOrNull()
		}

		private fun stripWrappingQuotes(value: String): String {
			var result = value
			while (result.length > 1) {
				val first = result.first()
				val last = result.last()
				if ((first == '"' || first == '\'') && first == last) {
					result = result.substring(1, result.length - 1).trim()
				} else {
					break
				}
			}
			return result
		}

		private data class ParsedGeminiError(
			val status: String = "",
			val message: String = "",
			val retryDelayMs: Long? = null,
		)

		private fun parseGeminiErrorJson(body: String): ParsedGeminiError {
			if (body.isBlank() || !body.trimStart().startsWith('{')) {
				return ParsedGeminiError()
			}
			return runCatching {
				val root = JSONObject(body)
				val err = root.optJSONObject("error") ?: root
				val status = err.optString("status", "")
				val message = err.optString("message", "")
				var retryDelayMs: Long? = null
				val details = err.optJSONArray("details")
				if (details != null) {
					for (i in 0 until details.length()) {
						val detail = details.optJSONObject(i) ?: continue
						val retryDelay = detail.optString("retryDelay", "")
						if (retryDelay.isNotBlank()) {
							retryDelayMs = parseProtoDurationMs(retryDelay)
							if (retryDelayMs != null) break
						}
					}
				}
				ParsedGeminiError(
					status = status,
					message = message,
					retryDelayMs = retryDelayMs,
				)
			}.getOrDefault(ParsedGeminiError())
		}

		private fun parseProtoDurationMs(raw: String): Long? {
			val cleaned = raw.trim().removeSuffix("s").trim()
			val seconds = cleaned.toDoubleOrNull() ?: return null
			return (seconds * 1000.0).toLong().takeIf { it > 0L }
		}

		private fun isFatalKeyError(
			statusCode: Int,
			rpcStatus: String,
			lowerText: String,
		): Boolean {
			if (statusCode == 401 || statusCode == 403) return true
			if (rpcStatus == "PERMISSION_DENIED" || rpcStatus == "UNAUTHENTICATED") return true
			return lowerText.contains("api_key_invalid") ||
				lowerText.contains("api key not valid") ||
				lowerText.contains("api key expired") ||
				lowerText.contains("invalid api key")
		}

		private fun isRateLimitError(
			statusCode: Int,
			rpcStatus: String,
			lowerText: String,
		): Boolean {
			if (statusCode == 429) return true
			if (rpcStatus == "RESOURCE_EXHAUSTED") return true
			return lowerText.contains("quota") ||
				lowerText.contains("rate limit") ||
				lowerText.contains("rate-limit") ||
				lowerText.contains("too many requests")
		}

		private fun isThinkingConfigError(
			statusCode: Int,
			lowerText: String,
		): Boolean {
			if (statusCode != 400) return false
			return lowerText.contains("thinkingconfig") ||
				lowerText.contains("thinkinglevel") ||
				lowerText.contains("thinkingbudget") ||
				(lowerText.contains("unknown name") && lowerText.contains("thinking"))
		}

		private fun isModelNotFoundError(
			statusCode: Int,
			rpcStatus: String,
			lowerText: String,
		): Boolean {
			if (statusCode == 404 || rpcStatus == "NOT_FOUND") return true
			if (statusCode == 400 && lowerText.contains("models/")) {
				return lowerText.contains("not found") ||
					lowerText.contains("not supported") ||
					lowerText.contains("does not exist") ||
					lowerText.contains("deprecated")
			}
			return false
		}

		private fun isTransientStatusOrMessage(
			statusCode: Int,
			rpcStatus: String,
			lowerText: String,
		): Boolean {
			if (statusCode == 408 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504) {
				return true
			}
			if (rpcStatus == "UNAVAILABLE" || rpcStatus == "DEADLINE_EXCEEDED" || rpcStatus == "INTERNAL") {
				return true
			}
			return lowerText.contains("overloaded") ||
				lowerText.contains("temporarily unavailable") ||
				lowerText.contains("timed out") ||
				lowerText.contains("timeout")
		}
	}
}
