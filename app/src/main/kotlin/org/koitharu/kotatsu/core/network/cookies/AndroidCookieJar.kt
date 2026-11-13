package org.koitharu.kotatsu.core.network.cookies

import android.webkit.CookieManager
import androidx.annotation.WorkerThread
import androidx.core.util.Predicate
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AndroidCookieJar : MutableCookieJar {

	private val cookieManager = CookieManager.getInstance()

	@WorkerThread
	override fun loadForRequest(url: HttpUrl): List<Cookie> {
		val rawCookie = cookieManager.getCookie(url.toString()) ?: return emptyList()
		return rawCookie.split(';').mapNotNull {
			Cookie.parse(url, it.trim())
		}
	}

	@WorkerThread
	override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		if (cookies.isEmpty()) {
			return
		}
		val urlString = url.toString()
		for (cookie in cookies) {
			cookieManager.setCookie(urlString, cookie.toString())
		}
		cookieManager.flush()
	}

	@WorkerThread
	override fun removeCookies(url: HttpUrl, predicate: Predicate<Cookie>?) {
		val host = url.host.trimStart('.')
		if (host.isEmpty()) {
			return
		}
		val domains = linkedSetOf(host)
		val privateDomain = url.topPrivateDomain()?.trimStart('.')?.takeIf { it.isNotEmpty() }
		if (privateDomain != null) {
			domains.add(privateDomain)
		}
		if (!host.startsWith("www.") && privateDomain == host) {
			domains.add("www.$host")
		}
		val candidateUrls = linkedSetOf(url.toString())
		for (domain in domains) {
			candidateUrls.add("https://$domain/")
			candidateUrls.add("http://$domain/")
		}
		var removedAny = false
		for (candidateUrl in candidateUrls) {
			val candidateHttpUrl = candidateUrl.toHttpUrlOrNull() ?: continue
			val rawCookie = cookieManager.getCookie(candidateUrl) ?: continue
			val cookies = rawCookie.split(';').mapNotNull { part ->
				Cookie.parse(candidateHttpUrl, part.trim())
			}
			for (cookie in cookies) {
				if (predicate != null && !predicate.test(cookie)) {
					continue
				}
				expireCookieAcrossVariants(candidateUrl, candidateHttpUrl.encodedPath, cookie, domains)
				removedAny = true
			}
		}
		if (removedAny) {
			cookieManager.flush()
		}
	}

	override suspend fun clear() = suspendCoroutine<Boolean> { continuation ->
		cookieManager.removeAllCookies { removed ->
			cookieManager.flush()
			continuation.resume(removed)
		}
	}

	private fun expireCookieAcrossVariants(
		urlString: String,
		encodedPath: String,
		cookie: Cookie,
		domains: Set<String>,
	) {
		val base = "${cookie.name}=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"
		val isHttps = urlString.startsWith("https://", ignoreCase = true)
		val paths = linkedSetOf("/")
		if (encodedPath.isNotEmpty() && encodedPath != "/") {
			paths.add(encodedPath)
		}
		for (path in paths) {
			cookieManager.setCookie(urlString, "$base; Path=$path")
			if (isHttps) {
				cookieManager.setCookie(urlString, "$base; Path=$path; Secure")
			}
			for (domain in domains) {
				cookieManager.setCookie(urlString, "$base; Domain=$domain; Path=$path")
				cookieManager.setCookie(urlString, "$base; Domain=.$domain; Path=$path")
				if (isHttps) {
					cookieManager.setCookie(urlString, "$base; Domain=$domain; Path=$path; Secure")
					cookieManager.setCookie(urlString, "$base; Domain=.$domain; Path=$path; Secure")
				}
			}
		}
	}
}
