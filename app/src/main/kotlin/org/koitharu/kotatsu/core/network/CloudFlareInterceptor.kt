package org.koitharu.kotatsu.core.network

import dagger.Lazy
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import okio.IOException
import org.koitharu.kotatsu.core.exceptions.CloudFlareBlockedException
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.network.webview.WebViewExecutor
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper

class CloudFlareInterceptor(
	private val webViewExecutorLazy: Lazy<WebViewExecutor>,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		return when (CloudFlareHelper.checkResponseForProtection(response)) {
			CloudFlareHelper.PROTECTION_BLOCKED -> response.closeThrowing(
				CloudFlareBlockedException(
					url = request.url.toString(),
					source = request.tag(MangaSource::class.java),
				),
			)

			CloudFlareHelper.PROTECTION_CAPTCHA -> {
				val source = request.tag(MangaSource::class.java)
				val allowChallenge = !isImageRequest(request)
				val bridgedResponse = runCatching {
					runBlocking {
						webViewExecutorLazy.get().executeCloudFlareRequest(
							request = request,
							source = source,
							allowChallenge = allowChallenge,
						)
					}
				}.getOrNull()
				if (bridgedResponse != null) {
					runCatching { response.close() }
					return bridgedResponse
				}
				response.closeThrowing(
					CloudFlareProtectedException(
						url = request.url.toString(),
						source = source,
						headers = request.headers,
					),
				)
			}

			else -> response
		}
	}

	private fun isImageRequest(request: okhttp3.Request): Boolean {
		val accept = request.header("Accept")
		if (accept != null && accept.contains("image/", ignoreCase = true) && !accept.contains("text/html", ignoreCase = true)) {
			return true
		}
		val path = request.url.encodedPath.lowercase()
		return path.endsWith(".jpg") ||
			path.endsWith(".jpeg") ||
			path.endsWith(".png") ||
			path.endsWith(".webp") ||
			path.endsWith(".avif") ||
			path.endsWith(".gif")
	}

	private fun Response.closeThrowing(error: IOException): Nothing {
		try {
			close()
		} catch (e: Exception) {
			error.addSuppressed(e)
		}
		throw error
	}
}
