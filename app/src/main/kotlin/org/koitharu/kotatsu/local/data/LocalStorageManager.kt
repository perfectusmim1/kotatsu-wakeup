package org.koitharu.kotatsu.local.data

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import dagger.Reusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.Cache
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.exceptions.NonFileUriException
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.computeSize
import org.koitharu.kotatsu.core.util.ext.getStorageName
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isReadable
import org.koitharu.kotatsu.core.util.ext.isWriteable
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.resolveFile
import org.koitharu.kotatsu.core.util.ext.takeIfWriteable
import org.koitharu.kotatsu.parsers.util.mapToSet
import java.io.File
import javax.inject.Inject

private const val DIR_NAME = "manga"
private const val NOMEDIA = ".nomedia"
private const val CACHE_DISK_PERCENTAGE = 0.02
private const val CACHE_SIZE_MIN: Long = 10 * 1024 * 1024 // 10MB
private const val CACHE_SIZE_MAX: Long = 250 * 1024 * 1024 // 250MB

@Reusable
class LocalStorageManager @Inject constructor(
    @LocalizedAppContext private val context: Context,
    private val settings: AppSettings,
) {

	val contentResolver: ContentResolver
		get() = context.contentResolver

	@WorkerThread
	fun createHttpCache(): Cache {
		val directory = File(context.externalCacheDir ?: context.cacheDir, "http")
		directory.mkdirs()
		val maxSize = calculateDiskCacheSize(directory)
		return Cache(directory, maxSize)
	}

	suspend fun computeCacheSize(cache: CacheDir) = withContext(Dispatchers.IO) {
		getCacheDirs(cache.dir).sumOf { it.computeSize() }
	}

	suspend fun computeCacheSize() = withContext(Dispatchers.IO) {
		getCacheDirs().sumOf { it.computeSize() }
	}

	suspend fun computeStorageSize() = withContext(Dispatchers.IO) {
		getConfiguredStorageDirs().sumOf { it.computeSize() }
	}

	suspend fun computeAvailableSize() = runInterruptible(Dispatchers.IO) {
		getConfiguredStorageDirs().mapToSet { it.freeSpace }.sum()
	}

	suspend fun clearCache(cache: CacheDir) = runInterruptible(Dispatchers.IO) {
		getCacheDirs(cache.dir).forEach { it.deleteRecursively() }
	}

	suspend fun getReadableDirs(): List<File> = runInterruptible(Dispatchers.IO) {
		getConfiguredStorageDirs()
			.filter { it.isReadable() }
	}

	suspend fun getWriteableDirs(): List<File> = runInterruptible(Dispatchers.IO) {
		getConfiguredStorageDirs()
			.filter { it.isWriteable() }
	}

	suspend fun getDefaultWriteableDir(): File? = runInterruptible(Dispatchers.IO) {
		val preferredDir = settings.mangaStorageDir?.takeIfWriteable()
		preferredDir ?: getFallbackStorageDir()?.takeIfWriteable()
	}

	suspend fun getApplicationStorageDirs(): Set<File> = runInterruptible(Dispatchers.IO) {
		getAvailableStorageDirs()
	}

	suspend fun resolveUri(uri: Uri): File = runInterruptible(Dispatchers.IO) {
		if (uri.isFileUri()) {
			uri.toFile()
		} else {
			uri.resolveFile(context) ?: throw NonFileUriException(uri)
		}
	}

	suspend fun setDirIsNoMedia(dir: File) = runInterruptible(Dispatchers.IO) {
		File(dir, NOMEDIA).createNewFile()
	}

	suspend fun setDirIsNoMediaSafe(dir: File) = runInterruptible(Dispatchers.IO) {
		runCatching {
			File(dir, NOMEDIA).createNewFile()
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	fun takePermissions(uri: Uri) {
		val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
		contentResolver.takePersistableUriPermission(uri, flags)
	}

	fun tryTakePermissions(uri: Uri): Boolean = runCatching {
		takePermissions(uri)
		true
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrDefault(false)

	suspend fun findWriteableAppExternalDirForVolume(dir: File): File? = runInterruptible(Dispatchers.IO) {
		val targetCanonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
		val storageManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
		} else {
			null
		}
		val targetVolumeUuid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			runCatching { storageManager?.getStorageVolume(dir)?.uuid }.getOrNull()
		} else {
			null
		}
		context.getExternalFilesDirs(DIR_NAME).firstOrNull { appDir ->
			if (appDir == null) return@firstOrNull false
			val isRemovable = runCatching { Environment.isExternalStorageRemovable(appDir) }.getOrDefault(false)
			val isNonPrimary = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
				runCatching { storageManager?.getStorageVolume(appDir)?.isPrimary == false }.getOrDefault(false)
			if (!isRemovable && !isNonPrimary) return@firstOrNull false

			val sameVolumeByUuid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && targetVolumeUuid != null) {
				val appUuid = runCatching { storageManager?.getStorageVolume(appDir)?.uuid }.getOrNull()
				appUuid != null && appUuid.equals(targetVolumeUuid, ignoreCase = true)
			} else {
				false
			}
			val sameVolumeByPath = run {
				val appCanonical = runCatching { appDir.canonicalPath }.getOrDefault(appDir.absolutePath)
				val androidDataIndex = appCanonical.indexOf("/Android/data/")
				if (androidDataIndex > 0) {
					val volumeRoot = appCanonical.substring(0, androidDataIndex)
					targetCanonical == volumeRoot || targetCanonical.startsWith(volumeRoot + File.separator)
				} else {
					false
				}
			}
			(sameVolumeByUuid || sameVolumeByPath) && (appDir.exists() || appDir.mkdirs()) && appDir.isWriteable()
		}
	}

	fun getSdCardWriteGuidanceMessage(pickedDir: File, fallbackDir: File): String {
		val header = context.getString(R.string.no_write_permission_to_file)
		val fallbackLine = context.getString(
			R.string.inline_preference_pattern,
			context.getString(R.string.download_default_directory),
			fallbackDir.absolutePath,
		)
		return "$header (${pickedDir.name})\n$fallbackLine"
	}

	fun isOnExternalStorage(file: File): Boolean {
		return !file.absolutePath.contains(context.packageName)
	}

	fun hasExternalStoragePermission(isReadOnly: Boolean): Boolean {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			Environment.isExternalStorageManager()
		} else {
			val permission = if (isReadOnly) {
				Manifest.permission.READ_EXTERNAL_STORAGE
			} else {
				Manifest.permission.WRITE_EXTERNAL_STORAGE
			}
			ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
		}
	}

	suspend fun getDirectoryDisplayName(dir: File, isFullPath: Boolean): String = runInterruptible(Dispatchers.IO) {
		val packageName = context.packageName
		if (dir.absolutePath.contains(packageName)) {
			dir.getStorageName(context)
		} else if (isFullPath) {
			dir.path
		} else {
			dir.name
		}
	}

	@WorkerThread
	private fun getConfiguredStorageDirs(): MutableSet<File> {
		val set = getAvailableStorageDirs()
		set.addAll(settings.userSpecifiedMangaDirectories)
		return set
	}

	@WorkerThread
	private fun getAvailableStorageDirs(): MutableSet<File> {
		val result = LinkedHashSet<File>()
		result += File(context.filesDir, DIR_NAME)
		context.getExternalFilesDirs(DIR_NAME).filterNotNullTo(result)
		result.retainAll { it.exists() || it.mkdirs() }
		return result
	}

	@WorkerThread
	private fun getFallbackStorageDir(): File? {
		return context.getExternalFilesDir(DIR_NAME) ?: File(context.filesDir, DIR_NAME).takeIf {
			it.exists() || it.mkdirs()
		}
	}

	@WorkerThread
	private fun getCacheDirs(subDir: String): MutableSet<File> {
		val result = LinkedHashSet<File>()
		result += File(context.cacheDir, subDir)
		context.externalCacheDirs.mapNotNullTo(result) {
			File(it ?: return@mapNotNullTo null, subDir)
		}
		return result
	}

	@WorkerThread
	private fun getCacheDirs(): MutableSet<File> {
		val result = LinkedHashSet<File>()
		result += context.cacheDir
		context.externalCacheDirs.filterNotNullTo(result)
		return result
	}

	private fun calculateDiskCacheSize(cacheDirectory: File): Long {
		return try {
			val cacheDir = StatFs(cacheDirectory.absolutePath)
			val size = CACHE_DISK_PERCENTAGE * cacheDir.blockCountLong * cacheDir.blockSizeLong
			return size.toLong().coerceIn(CACHE_SIZE_MIN, CACHE_SIZE_MAX)
		} catch (_: Exception) {
			CACHE_SIZE_MIN
		}
	}
}
