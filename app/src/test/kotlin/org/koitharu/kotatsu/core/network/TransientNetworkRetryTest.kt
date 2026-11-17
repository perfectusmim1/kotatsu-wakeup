package org.koitharu.kotatsu.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.exceptions.CloudFlareBlockedException
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException

class TransientNetworkRetryTest {

	@Test
	fun identifiesConnectionResetAndStreamErrors() {
		assertTrue(SocketException("Connection reset").isTransientConnectionError())
		assertTrue(SocketException("Connection reset by peer").isTransientConnectionError())
		assertTrue(SocketException("Broken pipe").isTransientConnectionError())
		assertTrue(StreamResetException(ErrorCode.CANCEL).isTransientConnectionError())
		assertTrue(EOFException("Unexpected EOF").isTransientConnectionError())
		assertTrue(IOException("wrapper", SocketException("Connection reset")).isTransientConnectionError())
	}

	@Test
	fun rejectsNonResetAndCloudFlareErrors() {
		assertFalse(UnknownHostException("example.com").isTransientConnectionError())
		assertFalse(CloudFlareBlockedException("https://example.com", null).isTransientConnectionError())
	}

	@Test
	fun retriesOnResetAndPassesForceFreshConnection() = runTest {
		val calls = mutableListOf<Boolean>()
		val result = retryOnTransientReset(maxRetries = 2) { forceFresh ->
			calls += forceFresh
			if (calls.size < 3) {
				throw SocketException("Connection reset")
			}
			"ok"
		}
		assertEquals("ok", result)
		assertEquals(listOf(false, true, true), calls)
	}
}
