package org.koitharu.kotatsu.bookmarks.data

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Persistent thumbnail storage for bookmarks under [Context.getFilesDir] (outside cacheDir),
 * so clearing image/page caches or CDN link expiration never causes bookmark previews to break.
 */
@Singleton
class BookmarkThumbnailCache @Inject constructor(
	@ApplicationContext context: Context,
) {

	private val dir = File(context.filesDir, DIR_NAME)

	fun getFile(mangaId: Long, pageId: Long): File? {
		val direct = fileFor(mangaId, pageId)
		if (direct.isFile && direct.length() > 0L) {
			return direct
		}
		val suffix = "_${pageId}.jpg"
		return dir.listFiles()?.firstOrNull { it.isFile && it.length() > 0L && it.name.endsWith(suffix) }
	}

	suspend fun saveBitmap(
		mangaId: Long,
		pageId: Long,
		bitmap: Bitmap,
	) = withContext(Dispatchers.IO) {
		val dest = fileFor(mangaId, pageId)
		if (dest.isFile && dest.length() > 0L) {
			return@withContext
		}
		runCatching {
			if (!dir.exists()) {
				dir.mkdirs()
			}
			val scaled = scaleDownIfNeeded(bitmap)
			val tmp = File(dir, "${dest.name}.tmp")
			try {
				tmp.outputStream().use { out ->
					scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
				}
				if (!tmp.renameTo(dest)) {
					tmp.copyTo(dest, overwrite = true)
					tmp.delete()
				}
			} finally {
				if (scaled !== bitmap && !scaled.isRecycled) {
					scaled.recycle()
				}
				if (tmp.exists()) {
					tmp.delete()
				}
			}
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	suspend fun move(
		oldMangaId: Long,
		oldPageId: Long,
		newMangaId: Long,
		newPageId: Long,
	) = withContext(Dispatchers.IO) {
		if (oldMangaId == newMangaId && oldPageId == newPageId) return@withContext
		runCatching {
			val src = fileFor(oldMangaId, oldPageId)
			if (!src.isFile || src.length() == 0L) return@withContext
			val dst = fileFor(newMangaId, newPageId)
			if (!dir.exists()) {
				dir.mkdirs()
			}
			if (!dst.exists()) {
				if (!src.renameTo(dst)) {
					src.copyTo(dst, overwrite = true)
					src.delete()
				}
			} else {
				src.delete()
			}
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	suspend fun pruneUnused(activePairs: Set<Pair<Long, Long>>) = withContext(Dispatchers.IO) {
		runCatching {
			val files = dir.listFiles() ?: return@withContext
			val activeNames = activePairs.mapTo(HashSet(activePairs.size)) { (mId, pId) -> fileName(mId, pId) }
			for (file in files) {
				if (file.isFile && file.name.endsWith(".jpg") && file.name !in activeNames) {
					file.delete()
				}
			}
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	private fun scaleDownIfNeeded(source: Bitmap): Bitmap {
		val maxDim = max(source.width, source.height)
		if (maxDim <= MAX_THUMB_DIMENSION || source.width <= 0 || source.height <= 0) {
			return source
		}
		val ratio = MAX_THUMB_DIMENSION.toFloat() / maxDim.toFloat()
		val targetW = (source.width * ratio).roundToInt().coerceAtLeast(1)
		val targetH = (source.height * ratio).roundToInt().coerceAtLeast(1)
		return Bitmap.createScaledBitmap(source, targetW, targetH, true)
	}

	private fun fileFor(mangaId: Long, pageId: Long): File = File(dir, fileName(mangaId, pageId))

	private fun fileName(mangaId: Long, pageId: Long): String = "${mangaId}_${pageId}.jpg"

	private companion object {
		const val DIR_NAME = "bookmark_thumbs"
		const val MAX_THUMB_DIMENSION = 480
		const val JPEG_QUALITY = 82
	}
}
