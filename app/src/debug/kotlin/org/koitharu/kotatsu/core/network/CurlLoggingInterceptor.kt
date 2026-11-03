package org.koitharu.kotatsu.core.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.koitharu.kotatsu.core.network.CommonHeaders.ACCEPT_ENCODING

class CurlLoggingInterceptor(
	private val curlOptions: String? = null
) : Interceptor {

	private val escapeRegex = Regex("([\\[\\]\"])")

	// Logcat is readable by anyone with adb or a bug report. The community server's bearer secret is
	// the account itself, and sync logins post a password, so neither may appear here.
	private val redactedHeaders = setOf("authorization", "proxy-authorization")
	private val redactedBodyFields = Regex(
		"(\"(?:password|secret|token|access_token|refresh_token|ssaid|drm_id)\"\\s*:\\s*)\"[^\"]*\"",
		RegexOption.IGNORE_CASE,
	)

	override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request()).also {
		logRequest(it.networkResponse?.request ?: it.request)
	}

	private fun logRequest(request: Request) {
		var isCompressed = false

		val curlCmd = StringBuilder()
		curlCmd.append("curl")
		if (curlOptions != null) {
			curlCmd.append(' ').append(curlOptions)
		}
		curlCmd.append(" -X ").append(request.method)

		for ((name, value) in request.headers) {
			if (name.equals(ACCEPT_ENCODING, ignoreCase = true) && value.equals("gzip", ignoreCase = true)) {
				isCompressed = true
			}
			val shown = if (name.lowercase() in redactedHeaders) "REDACTED" else value.escape()
			curlCmd.append(" -H \"").append(name).append(": ").append(shown).append('\"')
		}

		val body = request.body
		if (body != null) {
			val buffer = Buffer()
			body.writeTo(buffer)
			val charset = body.contentType()?.charset() ?: Charsets.UTF_8
			curlCmd.append(" --data-raw '")
				.append(
					buffer.readString(charset)
						.replace(redactedBodyFields) { it.groupValues[1] + "\"REDACTED\"" }
						.replace("\n", "\\n"),
				)
				.append("'")
		}
		if (isCompressed) {
			curlCmd.append(" --compressed")
		}
		curlCmd.append(" \"").append(request.url.toString().escape()).append('"')

		log("---cURL (" + request.url + ")")
		log(curlCmd.toString())
	}

	private fun String.escape() = replace(escapeRegex) { match ->
		"\\" + match.value
	}

	private fun log(msg: String) {
		Log.d("CURL", msg)
	}
}
