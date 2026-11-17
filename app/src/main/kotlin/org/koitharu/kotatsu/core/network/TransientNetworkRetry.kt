package org.koitharu.kotatsu.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http2.StreamResetException
import okio.IOException
import org.koitharu.kotatsu.core.exceptions.CloudFlareException
import java.io.EOFException
import java.net.ProtocolException
import java.net.SocketException

internal const val DEFAULT_TRANSIENT_RESET_RETRIES = 2
private const val HEADER_CONNECTION = "Connection"

internal fun Throwable.isTransientConnectionError(): Boolean {
	for (cause in generateSequence(this) { it.cause }.take(8)) {
		when (cause) {
			is CancellationException,
			is InterruptedException,
			is CloudFlareException -> return false
		}
	}
	for (cause in generateSequence(this) { it.cause }.take(8)) {
		when (cause) {
			is StreamResetException,
			is EOFException -> return true

			is SocketException -> {
				val msg = cause.message?.lowercase().orEmpty()
				if (
					msg.isEmpty() ||
					"reset" in msg ||
					"broken pipe" in msg ||
					"abort" in msg ||
					"closed" in msg
				) {
					return true
				}
			}

			is ProtocolException -> {
				val msg = cause.message?.lowercase().orEmpty()
				if ("unexpected end of stream" in msg || "reset" in msg) {
					return true
				}
			}
		}
	}
	return false
}

internal suspend fun <T> retryOnTransientReset(
	maxRetries: Int = DEFAULT_TRANSIENT_RESET_RETRIES,
	block: suspend (forceFreshConnection: Boolean) -> T,
): T {
	var attempt = 0
	while (true) {
		try {
			return block(attempt > 0)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			currentCoroutineContext().ensureActive()
			if (attempt >= maxRetries || !e.isTransientConnectionError()) {
				throw e
			}
			attempt++
			delay(150L * attempt)
		}
	}
}

internal fun Request.withFreshConnectionIf(forceFresh: Boolean): Request {
	if (!forceFresh || header(HEADER_CONNECTION) != null) {
		return this
	}
	return newBuilder()
		.header(HEADER_CONNECTION, "close")
		.build()
}

class TransientNetworkRetryInterceptor(
	private val maxRetries: Int = DEFAULT_TRANSIENT_RESET_RETRIES,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (request.method != "GET" && request.method != "HEAD") {
			return chain.proceed(request)
		}
		var attempt = 0
		while (true) {
			try {
				val currentRequest = request.withFreshConnectionIf(attempt > 0)
				return chain.proceed(currentRequest)
			} catch (e: IOException) {
				if (chain.call().isCanceled() || attempt >= maxRetries || !e.isTransientConnectionError()) {
					throw e
				}
				attempt++
				try {
					Thread.sleep(150L * attempt)
				} catch (_: InterruptedException) {
					Thread.currentThread().interrupt()
					throw e
				}
			}
		}
	}
}
