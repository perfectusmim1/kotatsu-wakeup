package org.koitharu.kotatsu.reader.domain.translate.cache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationCacheStore
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationSettingsSnapshot

@Singleton
class TranslatedPageCache @Inject constructor(
	@ApplicationContext private val context: Context,
) : TranslationCacheStore {

	private val mutex = Mutex()

	private val cacheDir: File by lazy {
		File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
	}

	override fun buildCacheKey(
		pageIdentity: String,
		settings: TranslationSettingsSnapshot,
	): String {
		val payload = "$pageIdentity|${settings.effectiveCacheSignature()}|v$RENDERER_VERSION"
		return sha256Hex(payload)
	}

	override suspend fun getCachedFile(cacheKey: String): File? = withContext(Dispatchers.IO) {
		mutex.withLock {
			if (!cacheDir.exists()) return@withLock null
			val file = fileForKey(cacheKey)
			if (file.isFile && file.length() > 0L) {
				file.setLastModified(System.currentTimeMillis())
				return@withLock file
			}
			if (file.exists()) {
				file.delete()
			}
			null
		}
	}

	override suspend fun getOrCreateTargetFile(cacheKey: String): File = withContext(Dispatchers.IO) {
		mutex.withLock {
			if (!cacheDir.exists()) {
				cacheDir.mkdirs()
			}
			val target = fileForKey(cacheKey)
			pruneStaleTempsAndEnforceLruLocked(exclude = target)
			target
		}
	}

	override suspend fun clearAll(): Long = withContext(Dispatchers.IO) {
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
			if (file.name.contains(".tmp.")) {
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
		const val CACHE_DIR_NAME = "translated_pages"
		const val RENDERER_VERSION = 3
		const val MAX_CACHE_BYTES = 256L * 1024L * 1024L
		const val TRIM_TARGET_BYTES = 216L * 1024L * 1024L
		const val STALE_TMP_AGE_MS = 5L * 60L * 1000L
		val SAFE_KEY_REGEX = Regex("^[a-zA-Z0-9_-]{8,128}$")
		val HEX_CHARS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
	}
}
