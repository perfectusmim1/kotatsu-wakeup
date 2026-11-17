package org.koitharu.kotatsu.core.network.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudFlareWebViewSupportTest {

	private val engineUa =
		"Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.135 Mobile Safari/537.36"

	@Test
	fun `preserves android chrome user agent with matching major version`() {
		val candidate =
			"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
		assertEquals(candidate, alignUserAgentWithEngine(candidate, engineUa))
	}

	@Test
	fun `realigns stale android chrome user agent to engine user agent`() {
		val stale =
			"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
		assertEquals(engineUa, alignUserAgentWithEngine(stale, engineUa))
	}

	@Test
	fun `realigns desktop or firefox user agent to engine user agent`() {
		val desktopChrome =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
		val mobileFirefox =
			"Mozilla/5.0 (Android 14; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0"

		assertEquals(engineUa, alignUserAgentWithEngine(desktopChrome, engineUa))
		assertEquals(engineUa, alignUserAgentWithEngine(mobileFirefox, engineUa))
	}

	@Test
	fun `handles null and blank candidates safely`() {
		assertNull(alignUserAgentWithEngine(null, engineUa))
		assertNull(alignUserAgentWithEngine("   ", engineUa))
	}

	@Test
	fun `cloudflare state script includes turkish and turnstile and block markers`() {
		assertTrue(CF_STATE_JS.contains("bir dakika"))
		assertTrue(CF_STATE_JS.contains("bir saniye"))
		assertTrue(CF_STATE_JS.contains("lütfen bekleyin"))
		assertTrue(CF_STATE_JS.contains("#challenge-form"))
		assertTrue(CF_STATE_JS.contains("cf-turnstile-response"))
		assertTrue(CF_STATE_JS.contains("challenges.cloudflare.com"))
		assertTrue(CF_STATE_JS.contains("blocked_why_headline"))
		assertTrue(CF_STATE_JS.contains("Sorry, you have been blocked"))
	}

	@Test
	fun `evaluate navigation allows mirror and subdomain redirects while blocking ads and external subframes`() {
		assertTrue(isAllowedEvaluateNavigation("okutoon.com", "okutoon.net", isMainFrame = true))
		assertTrue(isAllowedEvaluateNavigation("www.okutoon.com", "okutoon.com", isMainFrame = false))
		assertTrue(isAllowedEvaluateNavigation("okutoon.com", "cdn.okutoon.org", isMainFrame = false))
		assertTrue(isAllowedEvaluateNavigation("sadscans.net", "challenges.cloudflare.com", isMainFrame = false))
		assertTrue(isAllowedEvaluateNavigation("eleceedtr.com", "nabicix.com", isMainFrame = true))
		assertFalse(isAllowedEvaluateNavigation("okutoon.com", "googleadservices.com", isMainFrame = true))
		assertFalse(isAllowedEvaluateNavigation("okutoon.com", "popads.net", isMainFrame = true))
		assertFalse(isAllowedEvaluateNavigation("okutoon.com", "unrelated.example.org", isMainFrame = false))
	}

	@Test
	fun `cloudflare challenge body detector catches waf blocks and challenges`() {
		assertTrue(isCloudFlareChallengeBody("<h2 data-translate=\"blocked_why_headline\">Why have I been blocked?</h2>"))
		assertTrue(isCloudFlareChallengeBody("<html><body><h1>Sorry, you have been blocked</h1></body></html>"))
		assertTrue(isCloudFlareChallengeBody("<title>Just a moment...</title>"))
		assertFalse(isCloudFlareChallengeBody("<html><body><div>Manga Chapter 1</div></body></html>"))
	}
}
