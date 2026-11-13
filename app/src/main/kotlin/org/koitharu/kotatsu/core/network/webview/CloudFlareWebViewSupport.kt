package org.koitharu.kotatsu.core.network.webview

import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

internal const val ANDROID_UA_TOKEN = "Android"

private val NON_CHROMIUM_UA_TOKENS = arrayOf(
	"Firefox/",
	"FxiOS",
	"Gecko/",
	"Windows",
	"Macintosh",
	"X11;",
)

private val CHROME_MAJOR_VERSION_REGEX = Regex("""Chrome/(\d+)""")

/**
 * Aligns a configured parser User-Agent with the active WebView Chromium engine.
 * Cloudflare validates TLS/HTTP2 fingerprints and `Sec-CH-UA` headers against the User-Agent's OS
 * and Chromium major version; stale or non-Chromium desktop/Firefox UAs fail Turnstile checks.
 */
internal fun alignUserAgentWithEngine(
	candidate: String?,
	engineUserAgent: String?,
): String? {
	val trimmedCandidate = candidate?.trim()?.takeIf { it.isNotEmpty() } ?: return null
	val engineUa = engineUserAgent?.trim()?.takeIf {
		it.isNotEmpty() && ANDROID_UA_TOKEN in it
	} ?: return trimmedCandidate

	val isNonAndroidUa = ANDROID_UA_TOKEN !in trimmedCandidate
	val isNonChromiumUa = NON_CHROMIUM_UA_TOKENS.any { it in trimmedCandidate }
	val engineChromeMajor = extractChromeMajorVersion(engineUa)
	val candidateChromeMajor = extractChromeMajorVersion(trimmedCandidate)
	val isChromeMajorMismatch = engineChromeMajor != null && candidateChromeMajor != engineChromeMajor

	return if (isNonAndroidUa || isNonChromiumUa || isChromeMajorMismatch) {
		engineUa
	} else {
		trimmedCandidate
	}
}

internal fun extractChromeMajorVersion(userAgent: String): String? =
	CHROME_MAJOR_VERSION_REGEX.find(userAgent)?.groupValues?.getOrNull(1)

/**
 * Strips the WebView `X-Requested-With` header natively through Chromium when requested by a source,
 * avoiding OkHttp main-frame request hijacking which alters TLS fingerprints during Cloudflare challenges.
 */
internal fun applyCloudFlareRequestedWithPolicy(
	settings: WebSettings,
	stripRequestedWith: Boolean,
): Boolean {
	if (!stripRequestedWith) {
		return false
	}
	if (!WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
		return false
	}
	WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
	return true
}
