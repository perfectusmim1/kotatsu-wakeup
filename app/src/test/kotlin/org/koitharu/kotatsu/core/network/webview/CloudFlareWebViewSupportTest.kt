package org.koitharu.kotatsu.core.network.webview

import org.junit.Assert.assertEquals
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
	fun `cloudflare state script includes turkish and turnstile markers`() {
		assertTrue(CF_STATE_JS.contains("bir dakika"))
		assertTrue(CF_STATE_JS.contains("bir saniye"))
		assertTrue(CF_STATE_JS.contains("lütfen bekleyin"))
		assertTrue(CF_STATE_JS.contains("#challenge-form"))
		assertTrue(CF_STATE_JS.contains("cf-turnstile-response"))
		assertTrue(CF_STATE_JS.contains("challenges.cloudflare.com"))
	}
}
