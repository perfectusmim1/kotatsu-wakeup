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

private val SECOND_LEVEL_TLDS = setOf("co", "com", "net", "org", "edu", "gov", "ac", "ne", "or")

private val AD_TRACKER_HOST_KEYWORDS = arrayOf(
	"doubleclick",
	"googlesyndication",
	"googleadservices",
	"adservice",
	"popads",
	"popcash",
	"exoclick",
	"juicyads",
	"trafficjunky",
	"propellerads",
	"adsterra",
	"hilltopads",
	"tsyndicate",
	"taboola",
	"outbrain",
	"mgid",
	"yandex",
	"mc.yandex",
	"a-ads",
	"bet365",
)

internal fun isAllowedEvaluateNavigation(
	originalHost: String,
	requestHost: String,
	isMainFrame: Boolean,
): Boolean {
	val orig = originalHost.lowercase()
	val req = requestHost.lowercase()
	if (req == orig || req.endsWith(".$orig") || orig.endsWith(".$req")) {
		return true
	}
	if (req == "challenges.cloudflare.com" || req.endsWith(".cloudflare.com")) {
		return true
	}
	val originalLabel = extractSecondLevelLabel(orig)
	val requestLabel = extractSecondLevelLabel(req)
	if (originalLabel != null && originalLabel == requestLabel) {
		return true
	}
	return isMainFrame && !isBlockedAdOrTrackerHost(req)
}

internal fun extractSecondLevelLabel(host: String): String? {
	val parts = host.lowercase()
		.removePrefix("www.")
		.removePrefix("m.")
		.split('.')
		.filter { it.isNotEmpty() }
	if (parts.size < 2) return null
	val penultimate = parts[parts.size - 2]
	val index = if (parts.size >= 3 && penultimate in SECOND_LEVEL_TLDS) {
		parts.size - 3
	} else {
		parts.size - 2
	}
	return parts.getOrNull(index)?.takeIf { it.length >= 2 }
}

internal fun isBlockedAdOrTrackerHost(host: String): Boolean {
	val lower = host.lowercase()
	return AD_TRACKER_HOST_KEYWORDS.any { lower.contains(it) }
}

internal fun isCloudFlareChallengeBody(bodyPreview: String): Boolean =
	bodyPreview.contains("challenge-error-title") ||
		bodyPreview.contains("challenge-error-text") ||
		bodyPreview.contains("Just a moment...") ||
		bodyPreview.contains("blocked_why_headline") ||
		bodyPreview.contains("Sorry, you have been blocked") ||
		bodyPreview.contains("cf-challenge-running") ||
		bodyPreview.contains("/cdn-cgi/challenge-platform")
