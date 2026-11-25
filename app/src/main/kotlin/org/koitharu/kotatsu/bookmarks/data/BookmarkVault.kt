package org.koitharu.kotatsu.bookmarks.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable, cache-independent snapshot of all user bookmarks stored under [Context.getFilesDir].
 *
 * Bookmarks are only removed from this vault when explicitly deleted by the user (recorded in
 * [VaultSnapshot.removedKeys]). On startup, [verifyAndRestore] checks that every non-removed
 * bookmark in the vault is still present in SQLite and automatically restores any missing entry.
 */
@Singleton
class BookmarkVault @Inject constructor(
	@ApplicationContext context: Context,
) {

	private val vaultFile = File(context.filesDir, VAULT_FILE_NAME)
	private val mutex = Mutex()
	private val json = Json {
		encodeDefaults = true
		ignoreUnknownKeys = true
	}

	suspend fun syncFromDatabase(db: MangaDatabase) = withContext(Dispatchers.IO) {
		mutex.withLock {
			runCatching {
				val current = readSnapshotLocked()
				val dbMap = db.getBookmarksDao().findAllWithManga()
				val merged = LinkedHashMap<String, VaultEntry>(dbMap.size + current.entries.size)

				// Keep any existing vault entries that were NOT explicitly removed by the user.
				for (entry in current.entries) {
					val key = bookmarkKey(entry.bookmark.mangaId, entry.bookmark.pageId)
					if (key !in current.removedKeys) {
						merged[key] = entry
					}
				}

				// Overlay fresh rows from the database (removing stale keys for the same bookmark position if remapped).
				val removedKeys = current.removedKeys.toMutableSet()
				for ((mangaWithTags, bookmarks) in dbMap) {
					val manga = mangaWithTags.manga.toVaultManga()
					for (bookmark in bookmarks) {
						val key = bookmarkKey(bookmark.mangaId, bookmark.pageId)
						removedKeys.remove(key)
						merged[key] = VaultEntry(
							manga = manga,
							bookmark = bookmark.toVaultBookmark(),
						)
					}
				}

				writeSnapshotLocked(
					VaultSnapshot(
						version = VAULT_VERSION,
						entries = merged.values.toList(),
						removedKeys = removedKeys.takeLastBounded(MAX_TOMBSTONES),
					),
				)
			}.onFailure {
				it.printStackTraceDebug()
			}
		}
	}

	suspend fun recordExplicitRemoval(entities: Collection<BookmarkEntity>) = withContext(Dispatchers.IO) {
		if (entities.isEmpty()) return@withContext
		mutex.withLock {
			runCatching {
				val current = readSnapshotLocked()
				val keysToRemove = entities.mapTo(HashSet(entities.size)) { bookmarkKey(it.mangaId, it.pageId) }
				val remainingEntries = current.entries.filterNot {
					bookmarkKey(it.bookmark.mangaId, it.bookmark.pageId) in keysToRemove
				}
				val updatedRemoved = LinkedHashSet<String>(current.removedKeys.size + keysToRemove.size).apply {
					addAll(current.removedKeys)
					addAll(keysToRemove)
				}
				writeSnapshotLocked(
					VaultSnapshot(
						version = VAULT_VERSION,
						entries = remainingEntries,
						removedKeys = updatedRemoved.takeLastBounded(MAX_TOMBSTONES),
					),
				)
			}.onFailure {
				it.printStackTraceDebug()
			}
		}
	}

	suspend fun clearExplicitRemoval(entities: Collection<BookmarkEntity>) = withContext(Dispatchers.IO) {
		if (entities.isEmpty()) return@withContext
		mutex.withLock {
			runCatching {
				val current = readSnapshotLocked()
				val keysToRestore = entities.mapTo(HashSet(entities.size)) { bookmarkKey(it.mangaId, it.pageId) }
				val updatedRemoved = current.removedKeys - keysToRestore
				if (updatedRemoved.size != current.removedKeys.size) {
					writeSnapshotLocked(
						current.copy(removedKeys = updatedRemoved),
					)
				}
			}.onFailure {
				it.printStackTraceDebug()
			}
		}
	}

	suspend fun recordMovedBookmarks(
		oldKeys: Collection<Pair<Long, Long>>,
		db: MangaDatabase,
	) = withContext(Dispatchers.IO) {
		mutex.withLock {
			runCatching {
				val current = readSnapshotLocked()
				val movedKeySet = oldKeys.mapTo(HashSet(oldKeys.size)) { (mId, pId) -> bookmarkKey(mId, pId) }
				val prunedEntries = current.entries.filterNot {
					bookmarkKey(it.bookmark.mangaId, it.bookmark.pageId) in movedKeySet
				}
				writeSnapshotLocked(
					current.copy(entries = prunedEntries),
				)
			}.onFailure {
				it.printStackTraceDebug()
			}
		}
		syncFromDatabase(db)
	}

	suspend fun verifyAndRestore(db: MangaDatabase): Int = withContext(Dispatchers.IO) {
		var restoredCount = 0
		mutex.withLock {
			runCatching {
				if (!vaultFile.exists()) {
					// First run: seed vault from existing database bookmarks
					val dbMap = db.getBookmarksDao().findAllWithManga()
					val entries = ArrayList<VaultEntry>()
					for ((mangaWithTags, bookmarks) in dbMap) {
						val manga = mangaWithTags.manga.toVaultManga()
						for (b in bookmarks) {
							entries.add(VaultEntry(manga, b.toVaultBookmark()))
						}
					}
					writeSnapshotLocked(VaultSnapshot(version = VAULT_VERSION, entries = entries))
					return@withLock
				}

				val snapshot = readSnapshotLocked()
				if (snapshot.entries.isEmpty()) {
					return@withLock
				}
				val existingEntities = db.getBookmarksDao().findAllEntities()
				val existingKeys = existingEntities.mapTo(HashSet(existingEntities.size)) {
					bookmarkKey(it.mangaId, it.pageId)
				}
				val existingPageIds = existingEntities.mapTo(HashSet(existingEntities.size)) { it.pageId }
				val missing = snapshot.entries.filter { entry ->
					val key = bookmarkKey(entry.bookmark.mangaId, entry.bookmark.pageId)
					key !in snapshot.removedKeys &&
						key !in existingKeys &&
						entry.bookmark.pageId !in existingPageIds
				}
				if (missing.isNotEmpty()) {
					db.withTransaction {
						val mangaDao = db.getMangaDao()
						val bookmarksDao = db.getBookmarksDao()
						for (entry in missing) {
							if (mangaDao.find(entry.manga.id) == null) {
								mangaDao.upsert(entry.manga.toMangaEntity(), null)
							}
							bookmarksDao.upsert(entry.bookmark.toBookmarkEntity())
							restoredCount++
						}
					}
					Log.i(TAG, "Restored $restoredCount missing bookmark(s) from persistent vault")
				}
			}.onFailure {
				it.printStackTraceDebug()
			}
		}
		syncFromDatabase(db)
		restoredCount
	}

	private fun readSnapshotLocked(): VaultSnapshot {
		if (!vaultFile.exists() || vaultFile.length() == 0L) {
			return VaultSnapshot()
		}
		return runCatching {
			json.decodeFromString<VaultSnapshot>(vaultFile.readText())
		}.getOrElse {
			it.printStackTraceDebug()
			VaultSnapshot()
		}
	}

	private fun writeSnapshotLocked(snapshot: VaultSnapshot) {
		val parent = vaultFile.parentFile
		if (parent != null && !parent.exists()) {
			parent.mkdirs()
		}
		val tmp = File(vaultFile.parentFile, "$VAULT_FILE_NAME.tmp")
		tmp.writeText(json.encodeToString(snapshot))
		if (!tmp.renameTo(vaultFile)) {
			tmp.copyTo(vaultFile, overwrite = true)
			tmp.delete()
		}
	}

	private fun Set<String>.takeLastBounded(maxSize: Int): Set<String> {
		if (size <= maxSize) return this
		return toList().takeLast(maxSize).toSet()
	}

	private fun bookmarkKey(mangaId: Long, pageId: Long): String = "$mangaId:$pageId"

	@Serializable
	private data class VaultSnapshot(
		@SerialName("version") val version: Int = VAULT_VERSION,
		@SerialName("entries") val entries: List<VaultEntry> = emptyList(),
		@SerialName("removed_keys") val removedKeys: Set<String> = emptySet(),
	)

	@Serializable
	private data class VaultEntry(
		@SerialName("manga") val manga: VaultManga,
		@SerialName("bookmark") val bookmark: VaultBookmark,
	)

	@Serializable
	private data class VaultManga(
		@SerialName("id") val id: Long,
		@SerialName("title") val title: String,
		@SerialName("alt_titles") val altTitles: String? = null,
		@SerialName("url") val url: String,
		@SerialName("public_url") val publicUrl: String,
		@SerialName("rating") val rating: Float = -1f,
		@SerialName("nsfw") val isNsfw: Boolean = false,
		@SerialName("content_rating") val contentRating: String? = null,
		@SerialName("cover_url") val coverUrl: String = "",
		@SerialName("large_cover_url") val largeCoverUrl: String? = null,
		@SerialName("state") val state: String? = null,
		@SerialName("authors") val authors: String? = null,
		@SerialName("source") val source: String,
	) {
		fun toMangaEntity() = MangaEntity(
			id = id,
			title = title,
			altTitles = altTitles,
			url = url,
			publicUrl = publicUrl,
			rating = rating,
			isNsfw = isNsfw,
			contentRating = contentRating,
			coverUrl = coverUrl,
			largeCoverUrl = largeCoverUrl,
			state = state,
			authors = authors,
			source = source,
		)
	}

	@Serializable
	private data class VaultBookmark(
		@SerialName("manga_id") val mangaId: Long,
		@SerialName("page_id") val pageId: Long,
		@SerialName("chapter_id") val chapterId: Long,
		@SerialName("page") val page: Int,
		@SerialName("scroll") val scroll: Int,
		@SerialName("image") val imageUrl: String,
		@SerialName("created_at") val createdAt: Long,
		@SerialName("percent") val percent: Float,
		@SerialName("chapter_number") val chapterNumber: Float = -1f,
		@SerialName("chapter_volume") val chapterVolume: Int = 0,
		@SerialName("chapter_title") val chapterTitle: String? = null,
		@SerialName("chapter_url") val chapterUrl: String? = null,
		@SerialName("chapter_branch") val chapterBranch: String? = null,
		@SerialName("chapter_index") val chapterIndex: Int = -1,
		@SerialName("chapters_count") val chaptersCount: Int = 0,
	) {
		fun toBookmarkEntity() = BookmarkEntity(
			mangaId = mangaId,
			pageId = pageId,
			chapterId = chapterId,
			page = page,
			scroll = scroll,
			imageUrl = imageUrl,
			createdAt = createdAt,
			percent = percent,
			chapterNumber = chapterNumber,
			chapterVolume = chapterVolume,
			chapterTitle = chapterTitle,
			chapterUrl = chapterUrl,
			chapterBranch = chapterBranch,
			chapterIndex = chapterIndex,
			chaptersCount = chaptersCount,
		)
	}

	private fun MangaEntity.toVaultManga() = VaultManga(
		id = id,
		title = title,
		altTitles = altTitles,
		url = url,
		publicUrl = publicUrl,
		rating = rating,
		isNsfw = isNsfw,
		contentRating = contentRating,
		coverUrl = coverUrl,
		largeCoverUrl = largeCoverUrl,
		state = state,
		authors = authors,
		source = source,
	)

	private fun BookmarkEntity.toVaultBookmark() = VaultBookmark(
		mangaId = mangaId,
		pageId = pageId,
		chapterId = chapterId,
		page = page,
		scroll = scroll,
		imageUrl = imageUrl,
		createdAt = createdAt,
		percent = percent,
		chapterNumber = chapterNumber,
		chapterVolume = chapterVolume,
		chapterTitle = chapterTitle,
		chapterUrl = chapterUrl,
		chapterBranch = chapterBranch,
		chapterIndex = chapterIndex,
		chaptersCount = chaptersCount,
	)

	private companion object {
		const val TAG = "BookmarkVault"
		const val VAULT_VERSION = 1
		const val VAULT_FILE_NAME = "bookmarks_vault.json"
		const val MAX_TOMBSTONES = 2_000
	}
}
