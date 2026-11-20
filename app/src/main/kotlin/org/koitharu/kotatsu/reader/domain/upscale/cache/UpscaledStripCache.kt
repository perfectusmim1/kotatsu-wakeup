package org.koitharu.kotatsu.reader.domain.upscale.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.reader.domain.upscale.model.UpscaleMode
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * İyileştirilmiş (upscale edilmiş) şeritleri disk üzerinde WebP olarak önbelleğe alır.
 * Özellikle Yapay Zekâ (Real-ESRGAN) modunda yukarı geri kaydırıldığında şeridin
 * tekrar ONNX üzerinden hesaplanmasını önler (~3ms diskten okuma).
 */
@Singleton
class UpscaledStripCache @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val mutex = Mutex()

	private val cacheDir: File by lazy {
		File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
	}

	fun buildStripKey(
		pageIdentity: String,
		stripIndex: Int,
		mode: UpscaleMode,
		outWidth: Int,
		outHeight: Int,
	): String {
		val payload = "$pageIdentity|s=$stripIndex|m=${mode.id}|${outWidth}x$outHeight|v$CACHE_VERSION"
		return sha256Hex(payload)
	}

	suspend fun getCachedBitmap(cacheKey: String): Bitmap? = withContext(Dispatchers.IO) {
		val file = mutex.withLock {
			if (!cacheDir.exists()) return@withLock null
			val candidate = fileForKey(cacheKey)
			if (candidate.isFile && candidate.length() > 0L) {
				candidate.setLastModified(System.currentTimeMillis())
				candidate
			} else {
				if (candidate.exists()) {
					candidate.delete()
				}
				null
			}
		} ?: return@withContext null

		runCatching {
			val opts = BitmapFactory.Options().apply {
				inPreferredConfig = Bitmap.Config.RGB_565
			}
			BitmapFactory.decodeFile(file.absolutePath, opts)
		}.getOrNull()
	}

	suspend fun putCachedBitmap(cacheKey: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
		if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return@withContext
		val target = mutex.withLock {
			if (!cacheDir.exists()) {
				cacheDir.mkdirs()
			}
			val file = fileForKey(cacheKey)
			pruneStaleTempsAndEnforceLruLocked(exclude = file)
			file
		}
		val tempFile = File(target.parentFile, "${target.name}.tmp")
		runCatching {
			@Suppress("DEPRECATION")
			val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				Bitmap.CompressFormat.WEBP_LOSSY
			} else {
				Bitmap.CompressFormat.WEBP
			}
			FileOutputStream(tempFile).use { out ->
				bitmap.compress(format, WEBP_QUALITY, out)
				out.fd.sync()
			}
			mutex.withLock {
				if (target.exists()) {
					target.delete()
				}
				if (!tempFile.renameTo(target)) {
					tempFile.copyTo(target, overwrite = true)
					tempFile.delete()
				}
			}
		}.onFailure {
			tempFile.delete()
		}
	}

	suspend fun clearAll(): Long = withContext(Dispatchers.IO) {
		mutex.withLock {
			val files = cacheDir.listFiles() ?: return@withLock 0L
			var freedBytes = 0L
			for (file in files) {
				if (!file.isFile) continue
				val size = file.length()
				if (file.delete()) {
					freedBytes += size
				}
			}
			freedBytes
		}
	}

	private fun pruneStaleTempsAndEnforceLruLocked(exclude: File) {
		val files = cacheDir.listFiles() ?: return
		val now = System.currentTimeMillis()
		val cachedEntries = ArrayList<File>(files.size)
		var totalBytes = 0L

		for (file in files) {
			if (!file.isFile) continue
			if (file.name.endsWith(".tmp")) {
				if (now - file.lastModified() > STALE_TMP_AGE_MS) {
					file.delete()
				}
				continue
			}
			totalBytes += file.length()
			cachedEntries.add(file)
		}

		if (totalBytes <= MAX_CACHE_BYTES) return

		val evictable = cachedEntries
			.filter { it.name != exclude.name }
			.sortedBy { it.lastModified() }

		for (victim in evictable) {
			if (totalBytes <= TRIM_TARGET_BYTES) break
			val size = victim.length()
			if (victim.delete()) {
				totalBytes -= size
			}
		}
	}

	private fun fileForKey(cacheKey: String): File {
		val safeName = if (SAFE_KEY_REGEX.matches(cacheKey)) {
			cacheKey
		} else {
			sha256Hex(cacheKey)
		}
		return File(cacheDir, "$safeName.webp")
	}

	private fun sha256Hex(input: String): String {
		val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
		val sb = StringBuilder(digest.size * 2)
		for (b in digest) {
			val v = b.toInt() and 0xFF
			sb.append(HEX_CHARS[v ushr 4])
			sb.append(HEX_CHARS[v and 0x0F])
		}
		return sb.toString()
	}

	private companion object {
		const val CACHE_DIR_NAME = "upscaled_strips_v3"
		const val CACHE_VERSION = 3
		const val WEBP_QUALITY = 94
		const val MAX_CACHE_BYTES = 160L * 1024L * 1024L
		const val TRIM_TARGET_BYTES = 128L * 1024L * 1024L
		const val STALE_TMP_AGE_MS = 5L * 60L * 1000L
		val SAFE_KEY_REGEX = Regex("^[a-zA-Z0-9_-]{8,128}$")
		val HEX_CHARS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
	}
}
