package org.koitharu.kotatsu.bookmarks.domain

import android.database.SQLException
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.bookmarks.data.BookmarkThumbnailCache
import org.koitharu.kotatsu.bookmarks.data.BookmarkVault
import org.koitharu.kotatsu.bookmarks.data.toBookmark
import org.koitharu.kotatsu.bookmarks.data.toBookmarks
import org.koitharu.kotatsu.bookmarks.data.toEntity
import org.koitharu.kotatsu.bookmarks.domain.BookmarkMatcher.withChapterSnapshot
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_BOOKMARKS
import org.koitharu.kotatsu.core.db.TABLE_CHAPTERS
import org.koitharu.kotatsu.core.db.TABLE_MANGA
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaChapter
import org.koitharu.kotatsu.core.db.entity.toMangaChapters
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.findById
import javax.inject.Inject

enum class BookmarkSortOrder {
	CREATED_DESC,
	CREATED_ASC,
	CHAPTER_ASC,
}

@Reusable
class BookmarksRepository @Inject constructor(
	private val db: MangaDatabase,
	private val vault: BookmarkVault,
	private val thumbnailCache: BookmarkThumbnailCache,
) {

	fun observeBookmark(manga: Manga, chapterId: Long, page: Int): Flow<Bookmark?> {
		return db.invalidationTracker.createFlow(
			tables = arrayOf(TABLE_BOOKMARKS, TABLE_MANGA),
			emitInitialState = true,
		).map {
			val aliasIds = resolveBookmarkMangaIds(manga)
			db.getBookmarksDao()
				.findByMangaIdsAndPosition(aliasIds, chapterId, page)
				.firstOrNull()
				?.toBookmark(manga)
		}.distinctUntilChanged()
	}

	fun observeBookmarks(manga: Manga): Flow<List<Bookmark>> {
		return db.invalidationTracker.createFlow(
			tables = arrayOf(TABLE_BOOKMARKS, TABLE_MANGA),
			emitInitialState = true,
		).map {
			val aliasIds = resolveBookmarkMangaIds(manga)
			db.getBookmarksDao()
				.findAllByMangaIds(aliasIds)
				.toBookmarks(manga)
		}.distinctUntilChanged()
	}

	fun observeCachedChapters(manga: Manga): Flow<List<MangaChapter>> {
		return db.invalidationTracker.createFlow(
			tables = arrayOf(TABLE_CHAPTERS, TABLE_BOOKMARKS, TABLE_MANGA),
			emitInitialState = true,
		).map {
			findCachedChapters(manga)
		}.distinctUntilChanged()
	}

	suspend fun findCachedChapters(manga: Manga): List<MangaChapter> {
		val aliasIds = resolveBookmarkMangaIds(manga)
		val byManga = db.getChaptersDao().findAllByMangaIdsMap(aliasIds)
		val direct = byManga[manga.id]
		if (!direct.isNullOrEmpty()) {
			if (byManga.size == 1) {
				return direct.toMangaChapters()
			}
			val merged = LinkedHashMap<Long, MangaChapter>()
			for (ch in direct.toMangaChapters()) {
				merged[ch.id] = ch
			}
			for ((id, entities) in byManga) {
				if (id != manga.id) {
					for (ch in entities.toMangaChapters()) {
						merged.putIfAbsent(ch.id, ch)
					}
				}
			}
			return merged.values.toList()
		}
		for (id in aliasIds) {
			val list = byManga[id]
			if (!list.isNullOrEmpty()) {
				return list.toMangaChapters()
			}
		}
		return emptyList()
	}

	fun observeBookmarks(): Flow<Map<Manga, List<Bookmark>>> {
		return db.getBookmarksDao().observe().map { map ->
			val res = LinkedHashMap<Manga, List<Bookmark>>(map.size)
			for ((k, v) in map) {
				val manga = k.toManga()
				res[manga] = v.toBookmarks(manga)
			}
			res
		}
	}

	fun observeBookmarksWithChapters(): Flow<Map<Manga, List<Bookmark>>> {
		return db.getBookmarksDao().observe().map { map ->
			if (map.isEmpty()) {
				return@map emptyMap()
			}
			val chaptersByManga = db.getChaptersDao().findAllByMangaIdsMap(map.keys.map { it.manga.id })
			val res = LinkedHashMap<Manga, List<Bookmark>>(map.size)
			for ((k, v) in map) {
				val cachedChapters = chaptersByManga[k.manga.id]?.takeUnless { it.isEmpty() }
				val manga = k.toManga(cachedChapters)
				res[manga] = v.toBookmarks(manga)
			}
			res
		}
	}

	suspend fun getBookmarks(manga: Manga): List<Bookmark> {
		val aliasIds = resolveBookmarkMangaIds(manga)
		return db.getBookmarksDao().findAllByMangaIds(aliasIds).toBookmarks(manga)
	}

	suspend fun resolveBookmarkMangaIds(manga: Manga): Set<Long> {
		val result = LinkedHashSet<Long>(2)
		result.add(manga.id)
		if (manga.isLocal) {
			return result
		}
		val candidates = db.getMangaDao().findBookmarkedBySource(manga.source.name)
		for (candidate in candidates) {
			if (candidate.manga.id != manga.id && BookmarkMatcher.isSameSeriesOnSameSource(candidate.toManga(), manga)) {
				result.add(candidate.manga.id)
			}
		}
		return result
	}

	suspend fun addBookmark(bookmark: Bookmark) {
		val entity = enrichBookmarkEntity(bookmark)
		db.withTransaction {
			val tags = bookmark.manga.tags.toEntities()
			db.getTagsDao().upsert(tags)
			db.getMangaDao().upsert(bookmark.manga.toEntity(), tags)
			ensureChaptersCached(bookmark.manga)
			db.getBookmarksDao().upsert(entity)
		}
		vault.clearExplicitRemoval(listOf(entity))
		vault.syncFromDatabase(db)
	}

	suspend fun addBookmarks(bookmarks: Collection<Bookmark>): ReversibleHandle {
		if (bookmarks.isEmpty()) {
			return ReversibleHandle {}
		}
		val previousByKey = HashMap<Pair<Long, Long>, BookmarkEntity>()
		val inserted = ArrayList<BookmarkEntity>(bookmarks.size)
		val entitiesToUpsert = ArrayList<BookmarkEntity>(bookmarks.size)
		db.withTransaction {
			val dao = db.getBookmarksDao()
			val mangaById = bookmarks.associateBy { it.manga.id }
			for (item in mangaById.values) {
				val tags = item.manga.tags.toEntities()
				db.getTagsDao().upsert(tags)
				db.getMangaDao().upsert(item.manga.toEntity(), tags)
				ensureChaptersCached(item.manga)
			}
			val existingByManga = mangaById.keys.associateWith { mangaId ->
				dao.findAllByManga(mangaId).associateBy { it.pageId }
			}
			val seenKeys = HashSet<Pair<Long, Long>>(bookmarks.size)
			for (bookmark in bookmarks) {
				val key = bookmark.manga.id to bookmark.pageId
				val entity = enrichBookmarkEntity(bookmark)
				val prev = existingByManga[bookmark.manga.id]?.get(bookmark.pageId)
				if (prev != null) {
					previousByKey.putIfAbsent(key, prev)
				} else if (seenKeys.add(key)) {
					inserted.add(entity)
				}
				entitiesToUpsert.add(entity)
			}
			dao.upsert(entitiesToUpsert)
		}
		vault.clearExplicitRemoval(entitiesToUpsert)
		vault.syncFromDatabase(db)
		return ReversibleHandle {
			db.withTransaction {
				val dao = db.getBookmarksDao()
				for (entity in inserted) {
					dao.delete(entity)
				}
				if (previousByKey.isNotEmpty()) {
					dao.upsert(previousByKey.values)
				}
			}
			vault.recordExplicitRemoval(inserted)
			if (previousByKey.isNotEmpty()) {
				vault.clearExplicitRemoval(previousByKey.values)
			}
			vault.syncFromDatabase(db)
		}
	}

	suspend fun updateBookmark(
		bookmark: Bookmark,
		imageUrl: String,
		newChapterId: Long? = null,
		matchedChapter: MangaChapter? = null,
		chapters: List<MangaChapter>? = null,
	) {
		val aliasIds = resolveBookmarkMangaIds(bookmark.manga)
		db.withTransaction {
			val dao = db.getBookmarksDao()
			val stored = dao.find(bookmark.manga.id, bookmark.pageId)
				?: dao.findAllByMangaIds(aliasIds).firstOrNull { it.pageId == bookmark.pageId }
				?: bookmark.toEntity()
			var updated = stored.copy(imageUrl = imageUrl)
			if (matchedChapter != null && !chapters.isNullOrEmpty()) {
				updated = updated.withChapterSnapshot(matchedChapter, chapters)
			} else if (newChapterId != null) {
				updated = updated.copy(chapterId = newChapterId)
			}
			dao.upsert(updated)
		}
		vault.syncFromDatabase(db)
	}

	suspend fun removeBookmark(mangaId: Long, chapterId: Long, page: Int) {
		val removed = ArrayList<BookmarkEntity>()
		db.withTransaction {
			val dao = db.getBookmarksDao()
			val matches = dao.findByMangaIdsAndPosition(listOf(mangaId), chapterId, page)
			removed.addAll(matches)
			check(dao.delete(mangaId, chapterId, page) != 0) {
				"Bookmark not found"
			}
		}
		vault.recordExplicitRemoval(removed)
		vault.syncFromDatabase(db)
	}

	suspend fun removeBookmark(bookmark: Bookmark) {
		removeBookmark(bookmark.manga.id, bookmark.chapterId, bookmark.page)
	}

	suspend fun removeBookmarkForManga(
		manga: Manga,
		chapterId: Long,
		page: Int,
	): ReversibleHandle? {
		val aliasIds = resolveBookmarkMangaIds(manga)
		val removed = ArrayList<BookmarkEntity>()
		db.withTransaction {
			val dao = db.getBookmarksDao()
			val matches = dao.findByMangaIdsAndPosition(aliasIds, chapterId, page)
			if (matches.isEmpty()) {
				return@withTransaction
			}
			removed.addAll(matches)
			for (entity in matches) {
				dao.deleteByMangaAndPage(entity.mangaId, entity.pageId)
			}
		}
		if (removed.isEmpty()) {
			return null
		}
		vault.recordExplicitRemoval(removed)
		vault.syncFromDatabase(db)
		return BookmarksRestorer(removed)
	}

	suspend fun removeBookmarks(ids: Set<Long>): ReversibleHandle {
		val entities = ArrayList<BookmarkEntity>(ids.size)
		db.withTransaction {
			val dao = db.getBookmarksDao()
			for (pageId in ids) {
				val matches = dao.findAllByPageId(pageId)
				if (matches.isNotEmpty()) {
					entities.addAll(matches)
				}
				dao.delete(pageId)
			}
		}
		vault.recordExplicitRemoval(entities)
		vault.syncFromDatabase(db)
		return BookmarksRestorer(entities)
	}

	/**
	 * Resolves the live [MangaChapter] for a bookmark whose [requestedChapterId] is no longer present
	 * in [manga]. Checks both already-remapped rows (via [bookmarkPageId]) and fallback matching via
	 * cached chapters, stored chapter metadata, image URL hints, and saved percent, persisting any
	 * healed chapter mapping back to the database and vault.
	 */
	suspend fun resolveBookmarkChapter(
		manga: Manga,
		requestedChapterId: Long,
		page: Int,
		bookmarkPageId: Long = 0L,
	): MangaChapter? {
		val chapters = manga.chapters
		if (chapters.isNullOrEmpty()) {
			return null
		}
		chapters.findById(requestedChapterId)?.let { return it }

		val aliasIds = resolveBookmarkMangaIds(manga)
		val allBookmarks = db.getBookmarksDao().findAllByMangaIds(aliasIds)
		val targetBookmark = (if (bookmarkPageId != 0L) allBookmarks.firstOrNull { it.pageId == bookmarkPageId } else null)
			?: allBookmarks.firstOrNull { it.chapterId == requestedChapterId && it.page == page }
			?: allBookmarks.firstOrNull { it.chapterId == requestedChapterId }

		if (targetBookmark != null) {
			// If reconciliation already healed this bookmark's chapterId in the DB, use it directly.
			chapters.findById(targetBookmark.chapterId)?.let { return it }

			val cachedChapter = db.getChaptersDao()
				.find(targetBookmark.mangaId, targetBookmark.chapterId)
				?.toMangaChapter()
				?: db.getChaptersDao()
					.find(manga.id, requestedChapterId)
					?.toMangaChapter()
			val matched = BookmarkMatcher.findMatchingChapter(
				bookmark = targetBookmark,
				cachedChapter = cachedChapter,
				newChapters = chapters,
				preferredBranch = manga.getPreferredBranch(null),
			)
			if (matched != null) {
				val healed = targetBookmark.withChapterSnapshot(matched, chapters)
				db.withTransaction {
					db.getBookmarksDao().updateChapterAndMetadata(
						mangaId = healed.mangaId,
						pageId = healed.pageId,
						newChapterId = healed.chapterId,
						chapterNumber = healed.chapterNumber,
						chapterVolume = healed.chapterVolume,
						chapterTitle = healed.chapterTitle,
						chapterUrl = healed.chapterUrl,
						chapterBranch = healed.chapterBranch,
						chapterIndex = healed.chapterIndex,
						chaptersCount = healed.chaptersCount,
					)
				}
				vault.syncFromDatabase(db)
				return matched
			}
		}

		// Final fallback if requestedChapterId wasn't tied to a bookmark row: check cached chapters table.
		val cachedFallback = db.getChaptersDao().find(manga.id, requestedChapterId)?.toMangaChapter()
		if (cachedFallback != null) {
			val synthetic = BookmarkEntity(
				mangaId = manga.id,
				pageId = bookmarkPageId,
				chapterId = requestedChapterId,
				page = page,
				scroll = 0,
				imageUrl = "",
				createdAt = 0L,
				percent = -1f,
			)
			return BookmarkMatcher.findMatchingChapter(
				bookmark = synthetic,
				cachedChapter = cachedFallback,
				newChapters = chapters,
				preferredBranch = manga.getPreferredBranch(null),
			)
		}
		return null
	}

	private suspend fun enrichBookmarkEntity(bookmark: Bookmark): BookmarkEntity {
		val base = bookmark.toEntity()
		val hasMeta = (base.chapterUrl != null || base.chapterTitle != null || base.chapterNumber >= 0f) &&
			base.chapterIndex >= 0 &&
			base.chaptersCount > 0
		if (hasMeta) {
			return base
		}
		val chapters = bookmark.manga.chapters?.takeUnless { it.isEmpty() }
			?: db.getChaptersDao().findAll(bookmark.manga.id).takeUnless { it.isEmpty() }?.toMangaChapters()
		val snapshot = BookmarkMatcher.extractChapterSnapshot(chapters, bookmark.chapterId) ?: return base
		return base.copy(
			chapterNumber = snapshot.chapterNumber,
			chapterVolume = snapshot.chapterVolume,
			chapterTitle = snapshot.chapterTitle ?: base.chapterTitle,
			chapterUrl = snapshot.chapterUrl ?: base.chapterUrl,
			chapterBranch = snapshot.chapterBranch ?: base.chapterBranch,
			chapterIndex = snapshot.chapterIndex,
			chaptersCount = snapshot.chaptersCount,
		)
	}

	private suspend fun ensureChaptersCached(manga: Manga) {
		val chapters = manga.chapters
		if (manga.isLocal || chapters.isNullOrEmpty()) {
			return
		}
		val chaptersDao = db.getChaptersDao()
		if (chaptersDao.findAll(manga.id).isEmpty()) {
			chaptersDao.replaceAll(manga.id, chapters.withIndex().toEntities(manga.id))
		}
	}

	private inner class BookmarksRestorer(
		private val entities: Collection<BookmarkEntity>,
	) : ReversibleHandle {

		override suspend fun reverse() {
			db.withTransaction {
				for (e in entities) {
					try {
						db.getBookmarksDao().upsert(e)
					} catch (e: SQLException) {
						e.printStackTraceDebug()
					}
				}
			}
			vault.clearExplicitRemoval(entities)
			vault.syncFromDatabase(db)
		}
	}
}

fun List<Bookmark>.sortedByOrder(
	order: BookmarkSortOrder,
	chapterOrderMap: Map<Long, Int> = emptyMap(),
): List<Bookmark> = when (order) {
	BookmarkSortOrder.CREATED_DESC -> sortedWith(
		compareByDescending<Bookmark> { it.createdAt }.thenBy { it.page },
	)

	BookmarkSortOrder.CREATED_ASC -> sortedWith(
		compareBy<Bookmark> { it.createdAt }.thenBy { it.page },
	)

	BookmarkSortOrder.CHAPTER_ASC -> sortedWith(
		compareBy<Bookmark> {
			chapterOrderMap[it.chapterId]
				?: it.chapterIndex.takeIf { idx -> idx >= 0 }
				?: Int.MAX_VALUE
		}
			.thenBy {
				it.chapterNumber.takeIf { n -> n >= 0f }
					?: BookmarkMatcher.extractChapterNumberFromImageUrl(it.imageUrl)
					?: Float.MAX_VALUE
			}
			.thenBy { it.percent }
			.thenBy { it.page }
			.thenBy { it.createdAt },
	)
}

/**
 * Resolves unique chapter IDs to download for [bookmarks] of [manga], optionally including up to
 * [extraNextChapters] following chapters within the same branch of each bookmarked chapter.
 * Falls back to raw bookmark chapter IDs when [Manga.chapters] is null or empty.
 */
fun resolveBookmarkDownloadChapterIds(
	manga: Manga,
	bookmarks: List<Bookmark>,
	extraNextChapters: Int = 0,
): LongArray {
	if (bookmarks.isEmpty()) {
		return longArrayOf()
	}
	val chapters = manga.chapters
	if (chapters.isNullOrEmpty()) {
		val fallbackIds = LinkedHashSet<Long>(bookmarks.size)
		for (bookmark in bookmarks) {
			fallbackIds.add(bookmark.chapterId)
		}
		return fallbackIds.toLongArray()
	}
	val extra = extraNextChapters.coerceAtLeast(0)
	val chaptersById = chapters.associateBy(MangaChapter::id)
	val preferredBranch = manga.getPreferredBranch(null)
	val result = LinkedHashSet<Long>(bookmarks.size * (extra + 1))
	val resolveChapter: (Bookmark) -> MangaChapter? = { bookmark ->
		chaptersById[bookmark.chapterId] ?: BookmarkMatcher.findMatchingChapter(
			bookmark = bookmark.toEntity(),
			cachedChapter = null,
			newChapters = chapters,
			preferredBranch = preferredBranch,
		)
	}
	if (extra == 0) {
		for (bookmark in bookmarks) {
			val resolved = resolveChapter(bookmark) ?: continue
			result.add(resolved.id)
		}
		return result.toLongArray()
	}
	val chaptersByBranch = chapters.groupBy { it.branch }
	val indexInBranchById = HashMap<Long, Int>(chapters.size)
	for (branchChapters in chaptersByBranch.values) {
		branchChapters.forEachIndexed { index, chapter ->
			indexInBranchById[chapter.id] = index
		}
	}
	for (bookmark in bookmarks) {
		val bookmarkedChapter = resolveChapter(bookmark) ?: continue
		val branchChapters = chaptersByBranch[bookmarkedChapter.branch] ?: continue
		val idx = indexInBranchById[bookmarkedChapter.id] ?: continue
		val endIdx = (idx + extra).coerceAtMost(branchChapters.lastIndex)
		for (i in idx..endIdx) {
			result.add(branchChapters[i].id)
		}
	}
	return result.toLongArray()
}

fun Manga.matchesBookmarkQuery(normalizedQuery: String): Boolean {
	if (normalizedQuery.isEmpty()) return true
	return title.contains(normalizedQuery, ignoreCase = true) ||
		altTitles.any { it.contains(normalizedQuery, ignoreCase = true) }
}

fun MangaChapter.matchesBookmarkQuery(
	normalizedQuery: String,
	localizedTitle: String? = null,
): Boolean {
	if (normalizedQuery.isEmpty()) return true
	return title?.contains(normalizedQuery, ignoreCase = true) == true ||
		localizedTitle?.contains(normalizedQuery, ignoreCase = true) == true ||
		numberString()?.contains(normalizedQuery, ignoreCase = true) == true ||
		volumeString()?.contains(normalizedQuery, ignoreCase = true) == true ||
		branch?.contains(normalizedQuery, ignoreCase = true) == true ||
		scanlator?.contains(normalizedQuery, ignoreCase = true) == true
}
