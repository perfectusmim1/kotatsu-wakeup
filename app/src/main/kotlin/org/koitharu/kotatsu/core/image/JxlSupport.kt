package org.koitharu.kotatsu.core.image

import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug

/**
 * Whether JPEG XL can be decoded on this device.
 *
 * The app ships ARM libraries only (`abiFilters` in app/build.gradle); x86 devices run them through
 * Android's ARM translation. Should loading still fail - a translation layer missing something, a
 * stripped install - this keeps it to one image failing. It has to be asked before the first
 * `JxlCoder` call: its class initializer loads the library, and a failure there is an Error that
 * would take the image pipeline down rather than fail one image.
 *
 * Only asked once an image has been recognised as JPEG XL, so nobody else pays for loading it.
 */
object JxlSupport {

	val isAvailable: Boolean by lazy {
		try {
			System.loadLibrary("jxlcoder")
			true
		} catch (e: UnsatisfiedLinkError) {
			e.printStackTraceDebug()
			false
		}
	}
}
