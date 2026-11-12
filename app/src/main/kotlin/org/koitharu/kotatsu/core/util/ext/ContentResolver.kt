package org.koitharu.kotatsu.core.util.ext

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.net.toFile
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.removeSuffix
import java.io.File
import java.lang.reflect.Array as ArrayReflect

private const val PRIMARY_VOLUME_NAME = "primary"
private const val HOME_VOLUME_NAME = "home"
private const val RAW_DOC_PREFIX = "raw:"
private const val ANDROID_DATA_DIR_SEGMENT = "/Android/data/"

fun Uri.resolveFile(context: Context): File? {
	val docId = runCatching { DocumentsContract.getTreeDocumentId(this) }.getOrNull() ?: return null
	if (docId.startsWith(RAW_DOC_PREFIX, ignoreCase = true)) {
		return File(docId.substring(RAW_DOC_PREFIX.length))
	}
	val volumeId = getVolumeIdFromDocId(docId) ?: return null
	val volumePath = getVolumePath(volumeId, context)?.removeSuffix(File.separatorChar) ?: return null
	val documentPath = getDocumentPathFromDocId(docId).removeSuffix(File.separatorChar)

	return File(
		if (documentPath.isNotEmpty()) {
			if (documentPath.startsWith(File.separator)) {
				volumePath + documentPath
			} else {
				volumePath + File.separator + documentPath
			}
		} else {
			volumePath
		},
	)
}

fun ContentResolver.getFileDisplayName(uri: Uri): String? = runCatching {
	if (uri.isFileUri()) {
		return@runCatching uri.toFile().name
	}
	query(uri, null, null, null, null)?.use { cursor ->
		if (cursor.moveToFirst()) {
			cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
		} else {
			null
		}
	}
}.onFailure { e ->
	e.printStackTraceDebug()
}.getOrNull()

private fun getVolumePath(volumeId: String, context: Context): String? {
	return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
		getVolumePathForAndroid11AndAbove(volumeId, context)
	} else {
		getVolumePathBeforeAndroid11(volumeId, context)
	}
}


private fun getVolumePathBeforeAndroid11(volumeId: String, context: Context): String? = runCatching {
	if (volumeId.equals(HOME_VOLUME_NAME, ignoreCase = true)) {
		@Suppress("DEPRECATION")
		return@runCatching Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).path
	}
	val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
	val isPrimaryVolumeId = volumeId.equals(PRIMARY_VOLUME_NAME, ignoreCase = true)

	// On API 24..29 (especially MIUI / Android 10), prefer StorageManager.storageVolumes when available
	// and match UUIDs case-insensitively before falling back to legacy getVolumeList() reflection.
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
		val fromVolumes = storageManager.storageVolumes.firstNotNullOfOrNull { volume ->
			val matches = if (volume.isPrimary) {
				isPrimaryVolumeId
			} else {
				val uuid = volume.uuid
				uuid != null && uuid.equals(volumeId, ignoreCase = true)
			}
			if (matches) extractStorageVolumePath(volume) else null
		}
		if (!fromVolumes.isNullOrEmpty()) {
			return@runCatching fromVolumes
		}
	}

	val fromReflection = getVolumePathViaReflection(storageManager, volumeId, isPrimaryVolumeId)
	if (!fromReflection.isNullOrEmpty()) {
		return@runCatching fromReflection
	}

	if (isPrimaryVolumeId) {
		@Suppress("DEPRECATION")
		return@runCatching Environment.getExternalStorageDirectory().path
	}

	getVolumePathFromAppExternalDirs(storageManager, volumeId, context)
}.onFailure {
	it.printStackTraceDebug()
}.getOrNull()

@SuppressLint("DiscouragedPrivateApi")
private fun getVolumePathViaReflection(
	storageManager: StorageManager,
	volumeId: String,
	isPrimaryVolumeId: Boolean,
): String? = runCatching {
	val storageVolumeClazz = Class.forName("android.os.storage.StorageVolume")
	val getVolumeList = storageManager.javaClass.getMethod("getVolumeList")
	val getUuid = storageVolumeClazz.getMethod("getUuid")
	val isPrimary = storageVolumeClazz.getMethod("isPrimary")
	val result = getVolumeList.invoke(storageManager) ?: return@runCatching null
	val length = ArrayReflect.getLength(result)
	(0 until length).firstNotNullOfOrNull { i ->
		val storageVolumeElement = ArrayReflect.get(result, i) ?: return@firstNotNullOfOrNull null
		val uuid = getUuid.invoke(storageVolumeElement) as? String
		val primary = isPrimary.invoke(storageVolumeElement) as? Boolean == true
		val matches = (primary && isPrimaryVolumeId) || (uuid != null && uuid.equals(volumeId, ignoreCase = true))
		if (matches) extractStorageVolumePath(storageVolumeElement) else null
	}
}.onFailure {
	it.printStackTraceDebug()
}.getOrNull()

@SuppressLint("DiscouragedPrivateApi")
private fun extractStorageVolumePath(storageVolume: Any): String? {
	val clazz = storageVolume.javaClass
	runCatching {
		val getPath = clazz.getMethod("getPath")
		(getPath.invoke(storageVolume) as? String)?.nullIfEmpty()
	}.getOrNull()?.let { return it }

	return runCatching {
		val getPathFile = clazz.getMethod("getPathFile")
		(getPathFile.invoke(storageVolume) as? File)?.path?.nullIfEmpty()
	}.getOrNull()
}

private fun getVolumePathFromAppExternalDirs(
	storageManager: StorageManager,
	volumeId: String,
	context: Context,
): String? {
	val isPrimaryVolumeId = volumeId.equals(PRIMARY_VOLUME_NAME, ignoreCase = true)
	for (dir in context.getExternalFilesDirs(null)) {
		if (dir == null) continue
		val path = dir.path
		val idx = path.indexOf(ANDROID_DATA_DIR_SEGMENT)
		if (idx <= 0) continue
		val rootPath = path.substring(0, idx)
		val rootFile = File(rootPath)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			val volume = storageManager.getStorageVolume(rootFile)
			if (volume != null) {
				val matches = if (volume.isPrimary) {
					isPrimaryVolumeId
				} else {
					volume.uuid?.equals(volumeId, ignoreCase = true) == true
				}
				if (matches) return rootPath
			}
		}
		if (!isPrimaryVolumeId && rootFile.name.equals(volumeId, ignoreCase = true)) {
			return rootPath
		}
	}
	if (!isPrimaryVolumeId) {
		File("/storage").listFiles()?.firstOrNull {
			it.isDirectory && it.name.equals(volumeId, ignoreCase = true)
		}?.let { return it.path }
	}
	return null
}

@RequiresApi(Build.VERSION_CODES.R)
private fun getVolumePathForAndroid11AndAbove(volumeId: String, context: Context): String? = runCatching {
	if (volumeId.equals(HOME_VOLUME_NAME, ignoreCase = true)) {
		@Suppress("DEPRECATION")
		return@runCatching Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).path
	}
	val isPrimaryVolumeId = volumeId.equals(PRIMARY_VOLUME_NAME, ignoreCase = true)
	val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
	storageManager.storageVolumes.firstNotNullOfOrNull { volume ->
		if (volume.isPrimary && isPrimaryVolumeId) {
			volume.directory?.path
		} else {
			val uuid = volume.uuid
			if (uuid != null && uuid.equals(volumeId, ignoreCase = true)) volume.directory?.path else null
		}
	} ?: getVolumePathFromAppExternalDirs(storageManager, volumeId, context)
}.onFailure {
	it.printStackTraceDebug()
}.getOrNull()

private fun getVolumeIdFromDocId(docId: String): String? {
	return docId.substringBefore(':').nullIfEmpty()
}

private fun getDocumentPathFromDocId(docId: String): String {
	return if (':' in docId) {
		docId.substringAfter(':').nullIfEmpty() ?: File.separator
	} else {
		File.separator
	}
}
