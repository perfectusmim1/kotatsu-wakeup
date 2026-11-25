package org.koitharu.kotatsu.main.domain

import androidx.collection.ArraySet
import androidx.collection.LruCache
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
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
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
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
	private val recoveredCovers = LruCache<Long, String>(MAX_RECOVERED_COVER_CACHE)

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
			request.extras[mangaKey]?.let { manga ->
				val recovered = restoreMangaCover(manga, chain, request)
				if (recovered != null) {
					return recovered
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

	private suspend fun restoreMangaCover(
		manga: Manga,
		chain: Interceptor.Chain,
		request: ImageRequest,
	): SuccessResult? {
		if (manga.isLocal) {
			return null
		}
		val failedUrl = (request.data as? String)?.trim().orEmpty()
		val cachedRecovery = synchronized(recoveredCovers) { recoveredCovers[manga.id] }
		if (!cachedRecovery.isNullOrEmpty() && cachedRecovery != failedUrl) {
			val cachedRetry = chain.withRequest(
				request.newBuilder()
					.data(cachedRecovery)
					.build(),
			).proceed()
			if (cachedRetry is SuccessResult) {
				return cachedRetry
			}
		}
		val key = "${manga.id}:$failedUrl:${manga.publicUrl}"
		if (!blacklist.add(key)) {
			return null
		}
		val success = runCatchingCancellable {
			restoreMangaImpl(manga, failedUrl, chain, request)
		}.onFailure { e ->
			e.printStackTraceDebug()
		}.getOrNull()
		if (success != null) {
			blacklist.remove(key)
		}
		return success
	}

	private suspend fun restoreMangaImpl(
		manga: Manga,
		failedUrl: String,
		chain: Interceptor.Chain,
		request: ImageRequest,
	): SuccessResult? {
		val repo = repositoryFactory.create(manga.source)
		val isStoredInDb = dataRepository.findMangaById(manga.id, withChapters = false) != null
		val attempted = HashSet<String>(6).apply {
			if (failedUrl.isNotEmpty()) add(failedUrl)
		}

		suspend fun tryCoverCandidate(candidateUrl: String?, largeCandidateUrl: String? = null): SuccessResult? {
			val url = candidateUrl?.trim()?.nullIfEmpty() ?: return null
			if (!attempted.add(url)) {
				return null
			}
			val retried = chain.withRequest(
				request.newBuilder()
					.data(url)
					.build(),
			).proceed()
			if (retried is SuccessResult) {
				synchronized(recoveredCovers) {
					recoveredCovers.put(manga.id, url)
				}
				if (isStoredInDb) {
					dataRepository.updateCover(
						manga.copy(
							coverUrl = url,
							largeCoverUrl = largeCandidateUrl?.trim()?.nullIfEmpty() ?: url,
						),
					)
				}
				return retried
			}
			return null
		}

		// Tier 1: Uncropped WordPress thumbnail variant (e.g. thumbnail-193x278.jpg -> thumbnail.jpg)
		stripWpResizeSuffix(failedUrl)?.let { uncropped ->
			tryCoverCandidate(uncropped)?.let { return it }
		}

		// Tier 2: Source search for stored library/bookmark/history items whose URL or cover shifted
		if (isStoredInDb && manga.title.isNotBlank()) {
			val list = runCatchingCancellable {
				repo.getList(0, SortOrder.RELEVANCE, MangaListFilter(query = manga.title))
			}.getOrDefault(emptyList())
			val fixed = list.find { it.id == manga.id }
			if (fixed != null) {
				tryCoverCandidate(fixed.largeCoverUrl, fixed.largeCoverUrl)?.let {
					dataRepository.storeManga(fixed, replaceExisting = true)
					return it
				}
				tryCoverCandidate(fixed.coverUrl, fixed.largeCoverUrl)?.let {
					dataRepository.storeManga(fixed, replaceExisting = true)
					return it
				}
			} else {
				val moved = list.singleOrNull { it.title == manga.title }
					?: list.firstOrNull { BookmarkMatcher.isSameSeriesOnSameSource(manga, it) }
				if (moved != null) {
					tryCoverCandidate(moved.largeCoverUrl, moved.largeCoverUrl)?.let { return it }
					tryCoverCandidate(moved.coverUrl, moved.largeCoverUrl)?.let { return it }
				}
			}
		}

		// Tier 3: Fresh details fetch (refreshes stale covers and provides og:image/largeCoverUrl)
		val details = runCatchingCancellable {
			repo.getDetails(manga)
		}.getOrNull() ?: return null

		tryCoverCandidate(details.largeCoverUrl, details.largeCoverUrl)?.let { return it }
		tryCoverCandidate(details.coverUrl, details.largeCoverUrl)?.let { return it }
		stripWpResizeSuffix(details.largeCoverUrl)?.let { tryCoverCandidate(it)?.let { ok -> return ok } }
		stripWpResizeSuffix(details.coverUrl)?.let { tryCoverCandidate(it)?.let { ok -> return ok } }

		// Tier 4: Source cover file itself is 404 on server — fall back to the first readable chapter page
		val chapters = details.chapters.orEmpty()
		if (chapters.isEmpty()) {
			return null
		}
		for (chapter in chapters.take(2)) {
			val pages = runCatchingCancellable { repo.getPages(chapter) }.getOrNull().orEmpty()
			val firstPage = pages.firstOrNull() ?: continue
			val resolvedUrl = firstPage.preview?.nullIfEmpty() ?: runCatchingCancellable {
				repo.getPageUrl(firstPage)
			}.getOrNull()?.nullIfEmpty() ?: continue
			tryCoverCandidate(resolvedUrl)?.let { return it }
		}
		return null
	}

	private fun stripWpResizeSuffix(url: String?): String? {
		val raw = url?.trim()?.nullIfEmpty() ?: return null
		val stripped = WP_RESIZE_SUFFIX_REGEX.replace(raw, "$1")
		return stripped.takeIf { it != raw }
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

	private companion object {
		const val MAX_RECOVERED_COVER_CACHE = 256
		val WP_RESIZE_SUFFIX_REGEX = Regex("""-\d+x\d+(\.[a-zA-Z0-9]+(?:$|\?))""")
	}
}
