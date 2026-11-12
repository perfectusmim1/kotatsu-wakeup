package org.koitharu.kotatsu.settings.storage.directories

import android.net.Uri
import android.os.StatFs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.computeSize
import org.koitharu.kotatsu.core.util.ext.isReadable
import org.koitharu.kotatsu.core.util.ext.isWriteable
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MangaDirectoriesViewModel @Inject constructor(
    private val storageManager: LocalStorageManager,
    private val settings: AppSettings,
) : BaseViewModel() {

    val items = MutableStateFlow(emptyList<DirectoryConfigModel>())
    private var loadingJob: Job? = null

    init {
        loadList()
    }

    fun updateList() {
        loadList()
    }

    fun onCustomDirectoryPicked(uri: Uri) {
        launchLoadingJob(Dispatchers.Default) {
            loadingJob?.cancelAndJoin()
            storageManager.tryTakePermissions(uri)
            val dir = storageManager.resolveUri(uri)
            val appDirs = storageManager.getApplicationStorageDirs()
            if (dir.isWriteable()) {
                if (dir !in appDirs) {
                    settings.userSpecifiedMangaDirectories += dir
                    storageManager.setDirIsNoMediaSafe(dir)
                    loadList()
                }
                return@launchLoadingJob
            }
            val fallbackDir = storageManager.findWriteableAppExternalDirForVolume(dir)
            if (dir.isReadable() && dir !in appDirs) {
                settings.userSpecifiedMangaDirectories += dir
                loadList()
            }
            if (fallbackDir != null) {
                throw ContentUnavailableException(
                    storageManager.getSdCardWriteGuidanceMessage(dir, fallbackDir),
                )
            } else {
                throw AccessDeniedException(dir)
            }
        }
    }

    fun onRemoveClick(directory: File) {
        settings.userSpecifiedMangaDirectories -= directory
        if (settings.mangaStorageDir == directory) {
            settings.mangaStorageDir = null
        }
        loadList()
    }

    private fun loadList() {
        val prevJob = loadingJob
        loadingJob = launchJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            val downloadDir = storageManager.getDefaultWriteableDir()
            val applicationDirs = storageManager.getApplicationStorageDirs()
            val customDirs = settings.userSpecifiedMangaDirectories - applicationDirs
            items.value = buildList(applicationDirs.size + customDirs.size) {
                applicationDirs.mapTo(this) { dir ->
                    dir.toDirectoryModel(
                        isDefault = dir == downloadDir,
                        isAppPrivate = true,
                    )
                }
                customDirs.mapTo(this) { dir ->
                    dir.toDirectoryModel(
                        isDefault = dir == downloadDir,
                        isAppPrivate = false,
                    )
                }
            }
        }
    }

    private suspend fun File.toDirectoryModel(
        isDefault: Boolean,
        isAppPrivate: Boolean,
    ) = DirectoryConfigModel(
        title = storageManager.getDirectoryDisplayName(this, isFullPath = false),
        path = this,
        isDefault = isDefault,
        isAccessible = isReadable() && isWriteable(),
        isAppPrivate = isAppPrivate,
        size = computeSize(),
        available = runCatching { StatFs(this.absolutePath).availableBytes }.getOrDefault(0L),
    )
}
