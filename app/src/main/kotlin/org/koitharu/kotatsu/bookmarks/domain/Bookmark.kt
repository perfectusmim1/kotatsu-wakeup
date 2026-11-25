package org.koitharu.kotatsu.bookmarks.domain

import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.isImage
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaPage
import java.time.Instant
import kotlin.random.Random

data class Bookmark(
	val manga: Manga,
	val pageId: Long,
	val chapterId: Long,
	val page: Int,
	val scroll: Int,
	val imageUrl: String,
	val createdAt: Instant,
	val percent: Float,
	val chapterNumber: Float = -1f,
	val chapterVolume: Int = 0,
	val chapterTitle: String? = null,
	val chapterUrl: String? = null,
	val chapterBranch: String? = null,
	val chapterIndex: Int = -1,
	val chaptersCount: Int = 0,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is Bookmark &&
			manga.id == other.manga.id &&
			pageId == other.pageId
	}

	fun toMangaPage() = MangaPage(
		id = pageId,
		url = imageUrl,
		preview = imageUrl.takeIf {
			MimeTypes.getMimeTypeFromUrl(it)?.isImage == true
		},
		source = manga.source,
	)
}

fun List<Bookmark>.pickRandomBookmark(
	excludePageId: Long? = null,
	random: Random = Random.Default,
): Bookmark? {
	if (isEmpty()) return null
	if (size == 1 || excludePageId == null) {
		return random(random)
	}
	val filtered = filter { it.pageId != excludePageId }
	return if (filtered.isNotEmpty()) filtered.random(random) else random(random)
}
