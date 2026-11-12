package org.koitharu.kotatsu.settings.storage

import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.isWriteable
import org.koitharu.kotatsu.local.data.LocalStorageManager
import javax.inject.Inject

@HiltViewModel
class MangaDirectorySelectViewModel @Inject constructor(
	private val storageManager: LocalStorageManager,
	private val settings: AppSettings,
) : BaseViewModel() {

	val items = MutableStateFlow(emptyList<DirectoryModel>())
	val onDismissDialog = MutableEventFlow<Unit>()
	val onPickDirectory = MutableEventFlow<Unit>()
	val onFallbackDirectorySelected = MutableEventFlow<String>()

	init {
		refresh()
	}

	fun onItemClick(item: DirectoryModel) {
		if (item.file != null) {
			settings.mangaStorageDir = item.file
			onDismissDialog.call(Unit)
		} else {
			onPickDirectory.call(Unit)
		}
	}

	fun onCustomDirectoryPicked(uri: Uri) {
		launchJob(Dispatchers.Default) {
			storageManager.tryTakePermissions(uri)
			val dir = storageManager.resolveUri(uri)
			val appDirs = storageManager.getApplicationStorageDirs()
			if (dir.isWriteable()) {
				if (dir !in appDirs) {
					settings.userSpecifiedMangaDirectories += dir
					storageManager.setDirIsNoMediaSafe(dir)
				}
				settings.mangaStorageDir = dir
				onDismissDialog.call(Unit)
				return@launchJob
			}
			val fallbackDir = storageManager.findWriteableAppExternalDirForVolume(dir)
			if (fallbackDir != null) {
				storageManager.setDirIsNoMediaSafe(fallbackDir)
				settings.mangaStorageDir = fallbackDir
				onFallbackDirectorySelected.call(
					storageManager.getSdCardWriteGuidanceMessage(dir, fallbackDir),
				)
				onDismissDialog.call(Unit)
			} else {
				throw AccessDeniedException(dir)
			}
		}
	}

	fun refresh() {
		launchJob(Dispatchers.Default) {
			val defaultValue = storageManager.getDefaultWriteableDir()
			val available = storageManager.getWriteableDirs()
			items.value = buildList(available.size + 1) {
				available.mapTo(this) { dir ->
					DirectoryModel(
						title = storageManager.getDirectoryDisplayName(dir, isFullPath = false),
						titleRes = 0,
						file = dir,
						isChecked = dir == defaultValue,
						isAvailable = true,
						isRemovable = false,
					)
				}
				this += DirectoryModel(
					title = null,
					titleRes = R.string.pick_custom_directory,
					file = null,
					isChecked = false,
					isAvailable = true,
					isRemovable = false,
				)
			}
		}
	}
}
