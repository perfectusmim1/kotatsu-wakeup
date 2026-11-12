package org.koitharu.kotatsu.bookmarks.domain

import android.database.SQLException
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.bookmarks.data.toBookmark
import org.koitharu.kotatsu.bookmarks.data.toBookmarks
import org.koitharu.kotatsu.bookmarks.data.toEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.mapItems
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import javax.inject.Inject

enum class BookmarkSortOrder {
	CREATED_DESC,
	CREATED_ASC,
	CHAPTER_ASC,
}

@Reusable
class BookmarksRepository @Inject constructor(
	private val db: MangaDatabase,
) {

	fun observeBookmark(manga: Manga, chapterId: Long, page: Int): Flow<Bookmark?> {
		return db.getBookmarksDao().observe(manga.id, chapterId, page).map { it?.toBookmark(manga) }
	}

	fun observeBookmarks(manga: Manga): Flow<List<Bookmark>> {
		return db.getBookmarksDao().observe(manga.id).mapItems { it.toBookmark(manga) }
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
			val res = LinkedHashMap<Manga, List<Bookmark>>(map.size)
			val chaptersDao = db.getChaptersDao()
			for ((k, v) in map) {
				val cachedChapters = chaptersDao.findAll(k.manga.id).takeUnless { it.isEmpty() }
				val manga = k.toManga(cachedChapters)
				res[manga] = v.toBookmarks(manga)
			}
			res
		}
	}

	suspend fun getBookmarks(manga: Manga): List<Bookmark> {
		return db.getBookmarksDao().findAllByManga(manga.id).toBookmarks(manga)
	}

	suspend fun addBookmark(bookmark: Bookmark) {
		db.withTransaction {
			val tags = bookmark.manga.tags.toEntities()
			db.getTagsDao().upsert(tags)
			db.getMangaDao().upsert(bookmark.manga.toEntity(), tags)
			db.getBookmarksDao().insert(bookmark.toEntity())
		}
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
			}
			val existingByManga = mangaById.keys.associateWith { mangaId ->
				dao.findAllByManga(mangaId).associateBy { it.pageId }
			}
			val seenKeys = HashSet<Pair<Long, Long>>(bookmarks.size)
			for (bookmark in bookmarks) {
				val key = bookmark.manga.id to bookmark.pageId
				val entity = bookmark.toEntity()
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
		}
	}

	suspend fun updateBookmark(bookmark: Bookmark, imageUrl: String) {
		val entity = bookmark.toEntity().copy(
			imageUrl = imageUrl,
		)
		db.getBookmarksDao().upsert(listOf(entity))
	}

	suspend fun removeBookmark(mangaId: Long, chapterId: Long, page: Int) {
		check(db.getBookmarksDao().delete(mangaId, chapterId, page) != 0) {
			"Bookmark not found"
		}
	}

	suspend fun removeBookmark(bookmark: Bookmark) {
		removeBookmark(bookmark.manga.id, bookmark.chapterId, bookmark.page)
	}

	suspend fun removeBookmarks(ids: Set<Long>): ReversibleHandle {
		val entities = ArrayList<BookmarkEntity>(ids.size)
		db.withTransaction {
			val dao = db.getBookmarksDao()
			for (pageId in ids) {
				val e = dao.find(pageId)
				if (e != null) {
					entities.add(e)
				}
				dao.delete(pageId)
			}
		}
		return BookmarksRestorer(entities)
	}

	private inner class BookmarksRestorer(
		private val entities: Collection<BookmarkEntity>,
	) : ReversibleHandle {

		override suspend fun reverse() {
			db.withTransaction {
				for (e in entities) {
					try {
						db.getBookmarksDao().insert(e)
					} catch (e: SQLException) {
						e.printStackTraceDebug()
					}
				}
			}
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
		compareBy<Bookmark> { chapterOrderMap[it.chapterId] ?: Int.MAX_VALUE }
			.thenBy { it.percent }
			.thenBy { it.page }
			.thenBy { it.createdAt },
	)
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
