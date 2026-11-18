package org.koitharu.kotatsu.core.ui.image

import android.graphics.Bitmap
import android.os.Build
import coil3.size.Size
import coil3.transform.Transformation

class TrimTransformation(
	private val tolerance: Int = 20,
) : Transformation() {

	override val cacheKey: String = "${javaClass.name}-$tolerance"

	override suspend fun transform(input: Bitmap, size: Size): Bitmap {
		val width = input.width
		val height = input.height
		if (width <= 0 || height <= 0 || tolerance < 0) {
			return input
		}
		val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
			input.config == Bitmap.Config.HARDWARE
		val src = if (isHardware) {
			input.copy(Bitmap.Config.ARGB_8888, false) ?: return input
		} else {
			input
		}

		try {
			var left = 0
			var top = 0
			var right = 0
			var bottom = 0
			val colBuf = IntArray(height)

			// Left
			for (x in 0 until width) {
				src.getPixels(colBuf, 0, 1, x, 0, 1, height)
				if (isSpanUniform(colBuf, tolerance)) {
					left++
				} else {
					break
				}
			}
			if (left == width) {
				return input
			}

			// Right
			for (x in width - 1 downTo left + 1) {
				src.getPixels(colBuf, 0, 1, x, 0, 1, height)
				if (isSpanUniform(colBuf, tolerance)) {
					right++
				} else {
					break
				}
			}

			val rowBuf = IntArray(width)

			// Top
			for (y in 0 until height) {
				src.getPixels(rowBuf, 0, width, 0, y, width, 1)
				if (isSpanUniform(rowBuf, tolerance)) {
					top++
				} else {
					break
				}
			}
			if (top == height) {
				return input
			}

			// Bottom
			for (y in height - 1 downTo top + 1) {
				src.getPixels(rowBuf, 0, width, 0, y, width, 1)
				if (isSpanUniform(rowBuf, tolerance)) {
					bottom++
				} else {
					break
				}
			}

			val outWidth = width - left - right
			val outHeight = height - top - bottom
			return if ((left != 0 || right != 0 || top != 0 || bottom != 0) && outWidth > 0 && outHeight > 0) {
				Bitmap.createBitmap(src, left, top, outWidth, outHeight)
			} else {
				input
			}
		} finally {
			if (src !== input) {
				src.recycle()
			}
		}
	}

	private fun isSpanUniform(buf: IntArray, tolerance: Int): Boolean {
		val first = buf[0]
		val fr = (first ushr 16) and 0xFF
		val fg = (first ushr 8) and 0xFF
		val fb = first and 0xFF
		val fa = first ushr 24
		for (i in 1 until buf.size) {
			val c = buf[i]
			if (c == first) continue
			val dr = ((c ushr 16) and 0xFF) - fr
			if (dr > tolerance || dr < -tolerance) return false
			val dg = ((c ushr 8) and 0xFF) - fg
			if (dg > tolerance || dg < -tolerance) return false
			val db = (c and 0xFF) - fb
			if (db > tolerance || db < -tolerance) return false
			val da = (c ushr 24) - fa
			if (da > tolerance || da < -tolerance) return false
		}
		return true
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false
		other as TrimTransformation
		return tolerance == other.tolerance
	}

	override fun hashCode(): Int = tolerance

	companion object {
		val DEFAULT = TrimTransformation()
		val DEFAULT_LIST = listOf(DEFAULT)
	}
}
