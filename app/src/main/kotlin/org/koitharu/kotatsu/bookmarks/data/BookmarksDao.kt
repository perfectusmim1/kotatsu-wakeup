package org.koitharu.kotatsu.bookmarks.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.koitharu.kotatsu.core.db.entity.MangaWithTags

@Dao
abstract class BookmarksDao {

	@Query("SELECT * FROM bookmarks WHERE page_id = :pageId LIMIT 1")
	abstract suspend fun find(pageId: Long): BookmarkEntity?

	@Query("SELECT * FROM bookmarks WHERE page_id = :pageId")
	abstract suspend fun findAllByPageId(pageId: Long): List<BookmarkEntity>

	@Query("SELECT * FROM bookmarks WHERE manga_id = :mangaId AND page_id = :pageId LIMIT 1")
	abstract suspend fun find(mangaId: Long, pageId: Long): BookmarkEntity?

	@Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE manga_id = :mangaId)")
	abstract suspend fun hasBookmarks(mangaId: Long): Boolean

	@Transaction
	@Query(
		"SELECT * FROM manga JOIN bookmarks ON bookmarks.manga_id = manga.manga_id ORDER BY percent LIMIT :limit OFFSET :offset",
	)
	abstract suspend fun findAll(offset: Int, limit: Int): Map<MangaWithTags, List<BookmarkEntity>>

	@Transaction
	@Query(
		"SELECT * FROM manga JOIN bookmarks ON bookmarks.manga_id = manga.manga_id ORDER BY percent",
	)
	abstract suspend fun findAllWithManga(): Map<MangaWithTags, List<BookmarkEntity>>

	@Query("SELECT * FROM bookmarks")
	abstract suspend fun findAllEntities(): List<BookmarkEntity>

	@Query("SELECT * FROM bookmarks WHERE manga_id = :mangaId AND chapter_id = :chapterId AND page = :page ORDER BY created_at DESC LIMIT 1")
	abstract fun observe(mangaId: Long, chapterId: Long, page: Int): Flow<BookmarkEntity?>

	@Query("SELECT * FROM bookmarks WHERE manga_id IN (:mangaIds) AND chapter_id = :chapterId AND page = :page ORDER BY created_at DESC LIMIT 1")
	abstract fun observeByMangaIds(mangaIds: Collection<Long>, chapterId: Long, page: Int): Flow<BookmarkEntity?>

	@Query("SELECT * FROM bookmarks WHERE manga_id IN (:mangaIds) AND chapter_id = :chapterId AND page = :page ORDER BY created_at DESC")
	abstract suspend fun findByMangaIdsAndPosition(mangaIds: Collection<Long>, chapterId: Long, page: Int): List<BookmarkEntity>

	@Query("SELECT * FROM bookmarks WHERE manga_id = :mangaId ORDER BY percent")
	abstract fun observe(mangaId: Long): Flow<List<BookmarkEntity>>

	@Query("SELECT * FROM bookmarks WHERE manga_id IN (:mangaIds) ORDER BY percent")
	abstract fun observeByMangaIds(mangaIds: Collection<Long>): Flow<List<BookmarkEntity>>

	@Query("SELECT * FROM bookmarks WHERE manga_id = :mangaId ORDER BY percent")
	abstract suspend fun findAllByManga(mangaId: Long): List<BookmarkEntity>

	@Query("SELECT * FROM bookmarks WHERE manga_id IN (:mangaIds) ORDER BY percent")
	abstract suspend fun findAllByMangaIds(mangaIds: Collection<Long>): List<BookmarkEntity>

	@Transaction
	@Query(
		"SELECT * FROM manga JOIN bookmarks ON bookmarks.manga_id = manga.manga_id ORDER BY percent",
	)
	abstract fun observe(): Flow<Map<MangaWithTags, List<BookmarkEntity>>>

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun insert(entity: BookmarkEntity)

	@Upsert
	abstract suspend fun upsert(entity: BookmarkEntity)

	@Delete
	abstract suspend fun delete(entity: BookmarkEntity)

	@Query("DELETE FROM bookmarks WHERE page_id = :pageId")
	abstract suspend fun delete(pageId: Long): Int

	@Query("DELETE FROM bookmarks WHERE manga_id = :mangaId AND page_id = :pageId")
	abstract suspend fun deleteByMangaAndPage(mangaId: Long, pageId: Long): Int

	@Query("DELETE FROM bookmarks WHERE manga_id = :mangaId AND chapter_id = :chapterId AND page = :page")
	abstract suspend fun delete(mangaId: Long, chapterId: Long, page: Int): Int

	@Upsert
	abstract suspend fun upsert(bookmarks: Collection<BookmarkEntity>)

	@Query(
		"""
		UPDATE bookmarks
		SET chapter_id = :newChapterId,
			chapter_number = :chapterNumber,
			chapter_volume = :chapterVolume,
			chapter_title = :chapterTitle,
			chapter_url = :chapterUrl,
			chapter_branch = :chapterBranch,
			chapter_index = :chapterIndex,
			chapters_count = :chaptersCount
		WHERE manga_id = :mangaId AND page_id = :pageId
		""",
	)
	abstract suspend fun updateChapterAndMetadata(
		mangaId: Long,
		pageId: Long,
		newChapterId: Long,
		chapterNumber: Float,
		chapterVolume: Int,
		chapterTitle: String?,
		chapterUrl: String?,
		chapterBranch: String?,
		chapterIndex: Int,
		chaptersCount: Int,
	)

	@Query("UPDATE bookmarks SET chapter_id = :newChapterId WHERE manga_id = :mangaId AND chapter_id = :oldChapterId")
	protected abstract suspend fun updateChapterIdInternal(
		mangaId: Long,
		oldChapterId: Long,
		newChapterId: Long,
	)

	@Transaction
	open suspend fun updateChapterId(mangaId: Long, oldChapterId: Long, newChapterId: Long) {
		if (oldChapterId == newChapterId) {
			return
		}
		// Never delete bookmarks automatically during chapter remapping; primary key is (manga_id, page_id).
		updateChapterIdInternal(mangaId, oldChapterId, newChapterId)
	}

	fun dump(): Flow<Pair<MangaWithTags, List<BookmarkEntity>>> = flow {
		val window = 4
		var offset = 0
		while (currentCoroutineContext().isActive) {
			val list = findAll(offset, window)
			if (list.isEmpty()) {
				break
			}
			offset += window
			list.forEach { emit(it.key to it.value) }
		}
	}
}
