package org.koitharu.kotatsu.bookmarks.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import org.koitharu.kotatsu.core.db.entity.MangaEntity

@Entity(
	tableName = "bookmarks",
	primaryKeys = ["manga_id", "page_id"],
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.RESTRICT,
		),
	],
)
data class BookmarkEntity(
	@ColumnInfo(name = "manga_id", index = true) val mangaId: Long,
	@ColumnInfo(name = "page_id", index = true) val pageId: Long,
	@ColumnInfo(name = "chapter_id") val chapterId: Long,
	@ColumnInfo(name = "page") val page: Int,
	@ColumnInfo(name = "scroll") val scroll: Int,
	@ColumnInfo(name = "image") val imageUrl: String,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "percent") val percent: Float,
	@ColumnInfo(name = "chapter_number", defaultValue = "-1") val chapterNumber: Float = -1f,
	@ColumnInfo(name = "chapter_volume", defaultValue = "0") val chapterVolume: Int = 0,
	@ColumnInfo(name = "chapter_title") val chapterTitle: String? = null,
	@ColumnInfo(name = "chapter_url") val chapterUrl: String? = null,
	@ColumnInfo(name = "chapter_branch") val chapterBranch: String? = null,
	@ColumnInfo(name = "chapter_index", defaultValue = "-1") val chapterIndex: Int = -1,
	@ColumnInfo(name = "chapters_count", defaultValue = "0") val chaptersCount: Int = 0,
)