package org.koitharu.kotatsu.details.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommunityRepository @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	private val infoCache = HashMap<InfoCacheKey, CachedInfo>()
	private val cacheMutex = Mutex()

	fun capabilities(source: MangaSource): SourceCommunityCapabilities? {
		return providerFor(source)?.capabilities
	}

	fun getUserAgent(source: MangaSource): String? {
		val repo = mangaRepositoryFactory.create(source) as? ParserMangaRepository ?: return null
		return repo.getRequestHeaders()[CommonHeaders.USER_AGENT]
	}

	suspend fun getInfo(
		manga: Manga,
		chapter: MangaChapter? = null,
		force: Boolean = false,
	): SourceMangaCommunityInfo? {
		val provider = providerFor(manga.source) ?: return null
		val key = InfoCacheKey(manga.source.name, manga.url, chapter?.url)
		if (!force) {
			val cached = cacheMutex.withLock {
				val entry = infoCache[key]
				if (entry != null && System.currentTimeMillis() - entry.cachedAtMs < CACHE_TTL_MS) {
					entry.info
				} else {
					infoCache.remove(key)
					null
				}
			}
			if (cached != null) {
				return cached
			}
		}
		val info = provider.getMangaCommunityInfo(manga, chapter)
		cacheMutex.withLock {
			infoCache[key] = CachedInfo(info, System.currentTimeMillis())
		}
		return info
	}

	suspend fun submitRating(
		manga: Manga,
		score: Int,
		chapter: MangaChapter? = null,
	): SourceMangaCommunityInfo {
		val provider = requireProvider(manga.source)
		val updated = provider.submitRating(manga, score, chapter)
		cacheMutex.withLock {
			invalidateMangaLocked(manga.source, manga.url)
			val key = InfoCacheKey(manga.source.name, manga.url, chapter?.url)
			infoCache[key] = CachedInfo(updated, System.currentTimeMillis())
		}
		return updated
	}

	suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter? = null,
		page: Int = 1,
		sort: SourceCommentSort = SourceCommentSort.NEWEST,
		cursor: String? = null,
	): SourceCommentPage {
		return requireProvider(manga.source).getComments(
			manga = manga,
			chapter = chapter,
			page = page,
			sort = sort,
			cursor = cursor,
		)
	}

	suspend fun getReplies(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter? = null,
		page: Int = 1,
		cursor: String? = null,
	): SourceCommentPage {
		return requireProvider(manga.source).getReplies(
			manga = manga,
			comment = comment,
			chapter = chapter,
			page = page,
			cursor = cursor,
		)
	}

	suspend fun postComment(
		manga: Manga,
		text: String,
		chapter: MangaChapter? = null,
		parentComment: SourceComment? = null,
		isSpoiler: Boolean = false,
	): SourceComment {
		val created = requireProvider(manga.source).postComment(
			manga = manga,
			text = text,
			chapter = chapter,
			parentComment = parentComment,
			isSpoiler = isSpoiler,
		)
		invalidateManga(manga.source, manga.url)
		return created
	}

	suspend fun editComment(
		manga: Manga,
		comment: SourceComment,
		newText: String,
		chapter: MangaChapter? = null,
	): SourceComment {
		return requireProvider(manga.source).editComment(
			manga = manga,
			comment = comment,
			newText = newText,
			chapter = chapter,
		)
	}

	suspend fun deleteComment(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter? = null,
	) {
		requireProvider(manga.source).deleteComment(
			manga = manga,
			comment = comment,
			chapter = chapter,
		)
		invalidateManga(manga.source, manga.url)
	}

	suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean = true,
		chapter: MangaChapter? = null,
	): SourceComment {
		return requireProvider(manga.source).toggleCommentLike(
			manga = manga,
			comment = comment,
			isLike = isLike,
			chapter = chapter,
		)
	}

	suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter? = null,
	): SourceWidgetContext? {
		return providerFor(manga.source)?.resolveWidgetContext(manga, chapter)
	}

	suspend fun invalidate(source: MangaSource) {
		cacheMutex.withLock {
			val sourceName = source.name
			val iterator = infoCache.keys.iterator()
			while (iterator.hasNext()) {
				if (iterator.next().sourceName == sourceName) {
					iterator.remove()
				}
			}
		}
	}

	private suspend fun invalidateManga(source: MangaSource, mangaUrl: String) {
		cacheMutex.withLock {
			invalidateMangaLocked(source, mangaUrl)
		}
	}

	private fun invalidateMangaLocked(source: MangaSource, mangaUrl: String) {
		val sourceName = source.name
		val iterator = infoCache.keys.iterator()
		while (iterator.hasNext()) {
			val key = iterator.next()
			if (key.sourceName == sourceName && key.mangaUrl == mangaUrl) {
				iterator.remove()
			}
		}
	}

	private fun providerFor(source: MangaSource): MangaSourceCommunityProvider? {
		val repository = mangaRepositoryFactory.create(source) as? ParserMangaRepository ?: return null
		return repository.getCommunityProvider()
	}

	private fun requireProvider(source: MangaSource): MangaSourceCommunityProvider {
		return checkNotNull(providerFor(source)) {
			"Community provider is not available for ${source.name}"
		}
	}

	private data class InfoCacheKey(
		val sourceName: String,
		val mangaUrl: String,
		val chapterUrl: String?,
	)

	private data class CachedInfo(
		val info: SourceMangaCommunityInfo,
		val cachedAtMs: Long,
	)

	private companion object {
		const val CACHE_TTL_MS = 120_000L
	}
}
