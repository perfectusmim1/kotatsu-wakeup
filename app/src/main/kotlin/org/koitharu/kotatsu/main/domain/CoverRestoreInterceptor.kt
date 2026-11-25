package org.koitharu.kotatsu.main.domain

import androidx.collection.ArraySet
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.toBitmap
import org.koitharu.kotatsu.bookmarks.data.BookmarkThumbnailCache
import org.koitharu.kotatsu.bookmarks.data.toEntity
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.BookmarkMatcher
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.bookmarkKey
import org.koitharu.kotatsu.core.util.ext.mangaKey
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.util.Collections
import javax.inject.Inject

class CoverRestoreInterceptor @Inject constructor(
	private val dataRepository: MangaDataRepository,
	private val bookmarksRepository: BookmarksRepository,
	private val repositoryFactory: MangaRepository.Factory,
	private val bookmarkThumbnailCache: BookmarkThumbnailCache,
) : Interceptor {

	private val blacklist = Collections.synchronizedSet(ArraySet<String>())

	override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
		val request = chain.request
		val bookmark = request.extras[bookmarkKey]
		val result = chain.proceed()

		if (result is SuccessResult && bookmark != null && request.data !is File) {
			cacheBookmarkThumbnail(bookmark, result)
			return result
		}

		if (result is ErrorResult && result.throwable.shouldRestore()) {
			if (bookmark != null && request.data !is File) {
				val restored = restoreBookmark(bookmark)
				if (restored != null) {
					val retried = chain.withRequest(
						request.newBuilder()
							.data(restored.toMangaPage())
							.build(),
					).proceed()
					if (retried is SuccessResult) {
						cacheBookmarkThumbnail(restored, retried)
						return retried
					}
				}
				val cachedFile = bookmarkThumbnailCache.getFile(bookmark.manga.id, bookmark.pageId)
				if (cachedFile != null) {
					val fallbackResult = chain.withRequest(
						request.newBuilder()
							.data(cachedFile)
							.build(),
					).proceed()
					if (fallbackResult is SuccessResult) {
						return fallbackResult
					}
				}
				return result
			}
			request.extras[mangaKey]?.let {
				return if (restoreManga(it)) {
					chain.withRequest(request.newBuilder().build()).proceed()
				} else {
					result
				}
			}
		}
		return result
	}

	private suspend fun cacheBookmarkThumbnail(bookmark: Bookmark, result: SuccessResult) {
		if (bookmarkThumbnailCache.getFile(bookmark.manga.id, bookmark.pageId) != null) {
			return
		}
		runCatchingCancellable {
			val bitmap = result.image.toBitmap()
			bookmarkThumbnailCache.saveBitmap(bookmark.manga.id, bookmark.pageId, bitmap)
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	private suspend fun restoreManga(manga: Manga): Boolean {
		val key = manga.publicUrl
		if (!blacklist.add(key)) {
			return false
		}
		val restored = runCatchingCancellable {
			restoreMangaImpl(manga)
		}.onFailure { e ->
			e.printStackTraceDebug()
		}.getOrDefault(false)
		if (restored) {
			blacklist.remove(key)
		}
		return restored
	}

	private suspend fun restoreMangaImpl(manga: Manga): Boolean {
		if (dataRepository.findMangaById(manga.id, withChapters = false) == null || manga.isLocal) {
			return false
		}
		val repo = repositoryFactory.create(manga.source)
		val list = repo.getList(0, SortOrder.RELEVANCE, MangaListFilter(query = manga.title))
		val fixed = list.find { it.id == manga.id }
		if (fixed == null) {
			// Ids come from urls, so a site that moved lists the same manga under another id. Stored is the one to keep.
			val moved = list.singleOrNull { it.title == manga.title } ?: return false
			if (moved.coverUrl.isNullOrEmpty() || moved.coverUrl == manga.coverUrl) {
				return false
			}
			dataRepository.updateCover(manga.copy(coverUrl = moved.coverUrl, largeCoverUrl = moved.largeCoverUrl))
			return true
		}
		return if (fixed != manga) {
			dataRepository.storeManga(fixed, replaceExisting = true)
			fixed.coverUrl != manga.coverUrl
		} else {
			false
		}
	}

	private suspend fun restoreBookmark(bookmark: Bookmark): Bookmark? {
		val key = "${bookmark.manga.id}:${bookmark.pageId}:${bookmark.imageUrl}"
		if (!blacklist.add(key)) {
			return null
		}
		val restored = runCatchingCancellable {
			restoreBookmarkImpl(bookmark)
		}.onFailure { e ->
			e.printStackTraceDebug()
		}.getOrNull()
		if (restored != null) {
			blacklist.remove(key)
		}
		return restored
	}

	private suspend fun restoreBookmarkImpl(bookmark: Bookmark): Bookmark? {
		if (bookmark.manga.isLocal) {
			return null
		}
		val repo = repositoryFactory.create(bookmark.manga.source)
		val details = repo.getDetails(bookmark.manga)
		val chapters = details.chapters.orEmpty()
		if (chapters.isEmpty()) {
			return null
		}
		val cachedChapter = dataRepository.findCachedChapter(bookmark.manga.id, bookmark.chapterId)
		val chapter = chapters.findById(bookmark.chapterId)
			?: BookmarkMatcher.findMatchingChapter(
				bookmark = bookmark.toEntity(),
				cachedChapter = cachedChapter,
				newChapters = chapters,
				preferredBranch = details.getPreferredBranch(null),
			)
			?: return null
		val pages = repo.getPages(chapter)
		if (pages.isEmpty()) {
			return null
		}
		val page = pages[bookmark.page.coerceIn(0, pages.lastIndex)]
		val imageUrl = page.preview.ifNullOrEmpty { page.url }
		if (imageUrl != bookmark.imageUrl || chapter.id != bookmark.chapterId) {
			bookmarksRepository.updateBookmark(
				bookmark = bookmark,
				imageUrl = imageUrl,
				newChapterId = chapter.id,
				matchedChapter = chapter,
				chapters = chapters,
			)
			return bookmark.copy(
				chapterId = chapter.id,
				imageUrl = imageUrl,
			)
		}
		return null
	}

	private fun Throwable.shouldRestore(): Boolean {
		return this is Exception // any Exception but not Error
	}
}
