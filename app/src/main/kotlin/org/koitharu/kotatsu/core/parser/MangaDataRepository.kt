package org.koitharu.kotatsu.core.parser

import androidx.collection.LongObjectMap
import androidx.collection.MutableLongObjectMap
import androidx.core.net.toUri
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.bookmarks.data.BookmarkThumbnailCache
import org.koitharu.kotatsu.bookmarks.data.BookmarkVault
import org.koitharu.kotatsu.bookmarks.domain.BookmarkMatcher
import org.koitharu.kotatsu.bookmarks.domain.BookmarkMatcher.needsChapterMetadataBackfill
import org.koitharu.kotatsu.bookmarks.domain.BookmarkMatcher.withChapterSnapshot
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITE_CATEGORIES
import org.koitharu.kotatsu.core.db.TABLE_PREFERENCES
import org.koitharu.kotatsu.core.db.entity.ContentRating
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaChapter
import org.koitharu.kotatsu.core.db.entity.toMangaChapters
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.findSameChapter
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.reader.domain.ReaderColorFilter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider

@Reusable
class MangaDataRepository @Inject constructor(
	private val db: MangaDatabase,
	private val resolverProvider: Provider<MangaLinkResolver>,
	private val appShortcutManagerProvider: Provider<AppShortcutManager>,
	private val bookmarkVault: BookmarkVault,
	private val bookmarkThumbnailCache: BookmarkThumbnailCache,
) {

	suspend fun saveReaderMode(manga: Manga, mode: ReaderMode) {
		db.withTransaction {
			storeManga(manga, replaceExisting = false)
			val entity = db.getPreferencesDao().find(manga.id) ?: newEntity(manga.id)
			db.getPreferencesDao().upsert(entity.copy(mode = mode.id))
		}
	}

	suspend fun saveColorFilter(manga: Manga, colorFilter: ReaderColorFilter?) {
		db.withTransaction {
			storeManga(manga, replaceExisting = false)
			val entity = db.getPreferencesDao().find(manga.id) ?: newEntity(manga.id)
			db.getPreferencesDao().upsert(
				entity.copy(
					cfBrightness = colorFilter?.brightness ?: 0f,
					cfContrast = colorFilter?.contrast ?: 0f,
					cfInvert = colorFilter?.isInverted == true,
					cfGrayscale = colorFilter?.isGrayscale == true,
				),
			)
		}
	}

	suspend fun resetColorFilters() {
		db.getPreferencesDao().resetColorFilters()
	}

	suspend fun getReaderMode(mangaId: Long): ReaderMode? {
		return db.getPreferencesDao().find(mangaId)?.let { ReaderMode.valueOf(it.mode) }
	}

	suspend fun getColorFilter(mangaId: Long): ReaderColorFilter? {
		return db.getPreferencesDao().find(mangaId)?.getColorFilterOrNull()
	}

	suspend fun getOverride(mangaId: Long): MangaOverride? {
		return db.getPreferencesDao().find(mangaId)?.getOverrideOrNull()
	}

	suspend fun getOverrides(): LongObjectMap<MangaOverride> {
		val entities = db.getPreferencesDao().getOverrides()
		val map = MutableLongObjectMap<MangaOverride>(entities.size)
		for (entity in entities) {
			map[entity.mangaId] = entity.getOverrideOrNull() ?: continue
		}
		return map
	}

	suspend fun setOverride(manga: Manga, override: MangaOverride?) {
		db.withTransaction {
			storeManga(manga, replaceExisting = false)
			val dao = db.getPreferencesDao()
			val entity = dao.find(manga.id) ?: newEntity(manga.id)
			dao.upsert(
				entity.copy(
					titleOverride = override?.title?.nullIfEmpty(),
					coverUrlOverride = override?.coverUrl?.nullIfEmpty(),
					contentRatingOverride = override?.contentRating?.name,
				),
			)
		}
	}

	fun observeColorFilter(mangaId: Long): Flow<ReaderColorFilter?> {
		return db.getPreferencesDao().observe(mangaId)
			.map { it?.getColorFilterOrNull() }
			.distinctUntilChanged()
	}

	suspend fun findMangaById(mangaId: Long, withChapters: Boolean): Manga? {
		val chapters = if (withChapters) {
			db.getChaptersDao().findAll(mangaId).takeUnless { it.isEmpty() }
		} else {
			null
		}
		return db.getMangaDao().find(mangaId)?.toManga(chapters)
	}

	suspend fun findMangaByPublicUrl(publicUrl: String): Manga? {
		return db.getMangaDao().findByPublicUrl(publicUrl)?.toManga()
	}

	suspend fun findLibraryMangaBySource(source: MangaSource): List<Manga> {
		return db.getMangaDao().findLibraryBySource(source.name).map { it.toManga() }
	}

	suspend fun findAllMangaBySource(source: MangaSource): List<Manga> {
		return db.getMangaDao().findAllBySource(source.name).map { it.toManga() }
	}

	suspend fun resolveIntent(intent: MangaIntent, withChapters: Boolean): Manga? = when {
		intent.manga != null -> intent.manga.withCachedChaptersIfNeeded(withChapters)
		intent.mangaId != 0L -> findMangaById(intent.mangaId, withChapters)
		intent.uri != null -> resolverProvider.get().resolve(intent.uri).withCachedChaptersIfNeeded(withChapters)
		else -> null
	}

	suspend fun storeManga(manga: Manga, replaceExisting: Boolean) {
		if (!replaceExisting && db.getMangaDao().find(manga.id) != null) {
			return
		}
		var bookmarksChanged = false
		db.withTransaction {
			// avoid storing local manga if remote one is already stored
			val existing = if (manga.isLocal) {
				db.getMangaDao().find(manga.id)?.manga
			} else {
				null
			}
			if (existing == null || existing.source == manga.source.name) {
				val tags = manga.tags.toEntities()
				db.getTagsDao().upsert(tags)
				db.getMangaDao().upsert(manga.toEntity(), tags)
				if (!manga.isLocal) {
					manga.chapters?.takeUnless { it.isEmpty() }?.let { chapters ->
						if (saveChapters(manga.id, chapters, manga.getPreferredBranch(null))) {
							bookmarksChanged = true
						}
					}
				}
			}
		}
		if (bookmarksChanged) {
			bookmarkVault.syncFromDatabase(db)
		}
	}

	/**
	 * Keeps the cover a source has now, if the manga is stored. A site that moves leaves everything saved from it
	 * with covers on its old address, however well the manga itself still opens. Overrides are stored apart from this.
	 */
	suspend fun updateCover(manga: Manga) {
		val coverUrl = manga.coverUrl
		if (!manga.isLocal && !coverUrl.isNullOrEmpty()) {
			db.getMangaDao().updateCover(manga.id, coverUrl, manga.largeCoverUrl)
		}
	}

	suspend fun updateChapters(manga: Manga) {
		val chapters = manga.chapters
		if (chapters.isNullOrEmpty()) {
			return
		}
		val movedThumbs = ArrayList<MoveRecord>()
		var bookmarksChanged = false
		db.withTransaction {
			val preferredBranch = manga.getPreferredBranch(null)
			if (manga.id in db.getMangaDao()) {
				if (saveChapters(manga.id, chapters, preferredBranch)) {
					bookmarksChanged = true
				}
			}
			if (!manga.isLocal) {
				if (reconcileSameSourceBookmarks(manga, chapters, preferredBranch, movedThumbs)) {
					bookmarksChanged = true
				}
			}
		}
		if (movedThumbs.isNotEmpty()) {
			for (move in movedThumbs) {
				bookmarkThumbnailCache.move(move.oldMangaId, move.oldPageId, move.newMangaId, move.newPageId)
			}
			bookmarkVault.recordMovedBookmarks(movedThumbs.map { it.oldMangaId to it.oldPageId }, db)
		} else if (bookmarksChanged) {
			bookmarkVault.syncFromDatabase(db)
		}
	}

	/**
	 * The chapter as it was in the chapter list last stored for [mangaId], which may hold ids that a fresher load
	 * of the source no longer has.
	 */
	suspend fun findCachedChapter(mangaId: Long, chapterId: Long): MangaChapter? {
		return db.getChaptersDao().find(mangaId, chapterId)?.toMangaChapter()
	}

	/**
	 * Returns cached chapters stored for [manga] or any same-source bookmarked alias of [manga].
	 * Used as a fallback when a broken source temporarily returns an empty chapter list so
	 * bookmark chapter headers, chapter counts, and migration remain intact.
	 */
	suspend fun findCachedChaptersForManga(manga: Manga): List<MangaChapter> {
		val direct = db.getChaptersDao().findAll(manga.id)
		if (direct.isNotEmpty()) {
			return direct.toMangaChapters()
		}
		if (manga.isLocal) {
			return emptyList()
		}
		val candidates = db.getMangaDao().findBookmarkedBySource(manga.source.name)
		for (candidate in candidates) {
			if (candidate.manga.id != manga.id && BookmarkMatcher.isSameSeriesOnSameSource(candidate.toManga(), manga)) {
				val aliasChapters = db.getChaptersDao().findAll(candidate.manga.id)
				if (aliasChapters.isNotEmpty()) {
					return aliasChapters.toMangaChapters()
				}
			}
		}
		return emptyList()
	}

	private suspend fun saveChapters(
		mangaId: Long,
		newChapters: List<MangaChapter>,
		preferredBranch: String? = null,
	): Boolean {
		if (newChapters.isEmpty()) {
			return false
		}
		keepHistoryChapter(mangaId, newChapters)
		val bookmarksChanged = keepBookmarksChapters(mangaId, newChapters, preferredBranch)
		db.getChaptersDao().replaceAll(mangaId, newChapters.withIndex().toEntities(mangaId))
		return bookmarksChanged
	}

	/**
	 * History only keeps a chapter id. When the source gives the history chapter a new id, the stored list is the
	 * last place that still says which chapter it was, so move the history onto that chapter of [newChapters]
	 * before the list is replaced. Otherwise the reading position is later guessed from a percentage, possibly
	 * in another branch.
	 */
	private suspend fun keepHistoryChapter(mangaId: Long, newChapters: List<MangaChapter>) {
		val history = db.getHistoryDao().find(mangaId) ?: return
		if (newChapters.findById(history.chapterId) != null) {
			return
		}
		val previous = findCachedChapter(mangaId, history.chapterId) ?: return
		val same = findMatchingChapter(newChapters, previous) ?: return
		db.getHistoryDao().update(history.copy(chapterId = same.id))
	}

	/**
	 * Remaps bookmarks whose chapter id disappeared after a source changed chapter URLs/IDs before old cached
	 * chapters are replaced, and backfills self-contained chapter metadata so future reloads never lose context.
	 * Never deletes any bookmark row.
	 */
	private suspend fun keepBookmarksChapters(
		mangaId: Long,
		newChapters: List<MangaChapter>,
		preferredBranch: String? = null,
	): Boolean {
		val bookmarksDao = db.getBookmarksDao()
		val bookmarks = bookmarksDao.findAllByManga(mangaId)
		if (bookmarks.isEmpty()) {
			return false
		}
		val newChaptersById = newChapters.associateBy(MangaChapter::id)
		val cachedById = db.getChaptersDao().findAll(mangaId).associateBy { it.chapterId }
		var changed = false
		for (bookmark in bookmarks) {
			val direct = newChaptersById[bookmark.chapterId]
			if (direct != null) {
				if (bookmark.needsChapterMetadataBackfill()) {
					val updated = bookmark.withChapterSnapshot(direct, newChapters)
					bookmarksDao.updateChapterAndMetadata(
						mangaId = updated.mangaId,
						pageId = updated.pageId,
						newChapterId = updated.chapterId,
						chapterNumber = updated.chapterNumber,
						chapterVolume = updated.chapterVolume,
						chapterTitle = updated.chapterTitle,
						chapterUrl = updated.chapterUrl,
						chapterBranch = updated.chapterBranch,
						chapterIndex = updated.chapterIndex,
						chaptersCount = updated.chaptersCount,
					)
					changed = true
				}
				continue
			}
			val cachedChapter = cachedById[bookmark.chapterId]?.toMangaChapter()
			val matched = BookmarkMatcher.findMatchingChapter(
				bookmark = bookmark,
				cachedChapter = cachedChapter,
				newChapters = newChapters,
				preferredBranch = preferredBranch,
			) ?: continue
			val updated = bookmark.withChapterSnapshot(matched, newChapters)
			bookmarksDao.updateChapterAndMetadata(
				mangaId = updated.mangaId,
				pageId = updated.pageId,
				newChapterId = updated.chapterId,
				chapterNumber = updated.chapterNumber,
				chapterVolume = updated.chapterVolume,
				chapterTitle = updated.chapterTitle,
				chapterUrl = updated.chapterUrl,
				chapterBranch = updated.chapterBranch,
				chapterIndex = updated.chapterIndex,
				chaptersCount = updated.chaptersCount,
			)
			changed = true
		}
		return changed
	}

	/**
	 * Reconciles bookmarks saved under an older manga_id of the same series on the same source (for example
	 * when a source migrated URL prefixes/slugs or switched www/mirror hosts, changing Manga.id).
	 *
	 * - Orphan bookmark-only alias rows (not in active favourites or history) are cleanly consolidated onto
	 *   [loadedManga].id with fresh chapter IDs and chapter metadata.
	 * - Active library alias rows (still referenced by favourites or history) keep their manga_id intact while
	 *   having their chapter IDs, chapter metadata, and cached chapter list kept up to date.
	 */
	private suspend fun reconcileSameSourceBookmarks(
		loadedManga: Manga,
		newChapters: List<MangaChapter>,
		preferredBranch: String?,
		movedThumbsOut: MutableList<MoveRecord>,
	): Boolean {
		val mangaDao = db.getMangaDao()
		val candidates = mangaDao.findBookmarkedBySource(loadedManga.source.name)
		if (candidates.isEmpty()) {
			return false
		}
		val aliases = candidates.filter { candidate ->
			candidate.manga.id != loadedManga.id &&
				BookmarkMatcher.isSameSeriesOnSameSource(candidate.toManga(), loadedManga)
		}
		if (aliases.isEmpty()) {
			return false
		}
		val bookmarksDao = db.getBookmarksDao()
		val chaptersDao = db.getChaptersDao()
		var changed = false

		for (alias in aliases) {
			val oldMangaId = alias.manga.id
			val oldBookmarks = bookmarksDao.findAllByManga(oldMangaId)
			if (oldBookmarks.isEmpty()) {
				continue
			}
			val isOldInActiveLibrary = mangaDao.isInActiveLibrary(oldMangaId)
			if (isOldInActiveLibrary) {
				// Keep bookmarks on the library entry, but remap their chapters and keep its chapter cache fresh.
				if (keepBookmarksChapters(oldMangaId, newChapters, preferredBranch)) {
					changed = true
				}
				chaptersDao.replaceAll(oldMangaId, newChapters.withIndex().toEntities(oldMangaId))
				continue
			}

			// Orphan bookmark-only alias: consolidate onto loadedManga.id without dropping a single bookmark.
			val tags = loadedManga.tags.toEntities()
			db.getTagsDao().upsert(tags)
			mangaDao.upsert(loadedManga.toEntity(), tags)
			val oldCachedById = chaptersDao.findAll(oldMangaId).associateBy { it.chapterId }
			val existingTarget = bookmarksDao.findAllByManga(loadedManga.id)
			val occupiedPageIds = existingTarget.mapTo(HashSet(existingTarget.size)) { it.pageId }
			val existingPositions = existingTarget.mapTo(HashSet(existingTarget.size)) { it.chapterId to it.page }

			for (oldBookmark in oldBookmarks) {
				val matched = BookmarkMatcher.findMatchingChapter(
					bookmark = oldBookmark,
					cachedChapter = oldCachedById[oldBookmark.chapterId]?.toMangaChapter(),
					newChapters = newChapters,
					preferredBranch = preferredBranch,
				)
				val updatedMeta = if (matched != null) {
					oldBookmark.withChapterSnapshot(matched, newChapters)
				} else {
					oldBookmark
				}
				val pos = updatedMeta.chapterId to updatedMeta.page
				if (pos in existingPositions) {
					// Identical bookmark position already exists on target; only delete duplicate after confirming target exists.
					bookmarksDao.deleteByMangaAndPage(oldMangaId, oldBookmark.pageId)
					movedThumbsOut.add(MoveRecord(oldMangaId, oldBookmark.pageId, loadedManga.id, oldBookmark.pageId))
					changed = true
					continue
				}
				var newPageId = oldBookmark.pageId
				while (newPageId in occupiedPageIds) {
					newPageId++
				}
				val movedEntity: BookmarkEntity = updatedMeta.copy(
					mangaId = loadedManga.id,
					pageId = newPageId,
				)
				bookmarksDao.upsert(movedEntity)
				bookmarksDao.deleteByMangaAndPage(oldMangaId, oldBookmark.pageId)
				occupiedPageIds.add(newPageId)
				existingPositions.add(pos)
				movedThumbsOut.add(MoveRecord(oldMangaId, oldBookmark.pageId, loadedManga.id, newPageId))
				changed = true
			}
			chaptersDao.replaceAll(loadedManga.id, newChapters.withIndex().toEntities(loadedManga.id))
		}
		return changed
	}

	private fun findMatchingChapter(newChapters: List<MangaChapter>, previous: MangaChapter): MangaChapter? {
		newChapters.findSameChapter(previous)?.let { return it }
		val sameBranch = newChapters.filter { it.branch == previous.branch }
		val pool = sameBranch.ifEmpty {
			if (newChapters.hasSingleBranch()) newChapters else emptyList()
		}
		if (pool.isEmpty()) {
			return null
		}
		val prevTitle = previous.title.normalizeChapterTitle()
		if (previous.number > 0f) {
			val byNumber = pool.filter { it.number == previous.number }
			if (byNumber.size == 1) {
				return byNumber.first()
			}
			if (byNumber.size > 1) {
				byNumber.singleOrNull { it.volume == previous.volume }?.let { return it }
				if (prevTitle.isNotEmpty()) {
					byNumber.singleOrNull { it.title.normalizeChapterTitle() == prevTitle }?.let { return it }
				}
			}
		}
		if (prevTitle.isNotEmpty()) {
			val byTitle = pool.filter { it.title.normalizeChapterTitle() == prevTitle }
			if (byTitle.size == 1) {
				return byTitle.first()
			}
			if (byTitle.size > 1 && previous.volume > 0) {
				byTitle.singleOrNull { it.volume == previous.volume }?.let { return it }
			}
		}
		if (previous.number <= 0f) {
			val parsedNumber = previous.title?.let { extractChapterNumber(it) }
			if (parsedNumber != null && parsedNumber > 0f) {
				pool.singleOrNull {
					it.number == parsedNumber && (previous.volume == 0 || it.volume == previous.volume)
				}?.let { return it }
			}
		}
		return null
	}

	private fun List<MangaChapter>.hasSingleBranch(): Boolean {
		if (isEmpty()) return true
		val firstBranch = first().branch
		return all { it.branch == firstBranch }
	}

	private fun String?.normalizeChapterTitle(): String {
		if (isNullOrBlank()) return ""
		return lowercase(Locale.ROOT)
			.replace(CHAPTER_TITLE_NORMALIZE_REGEX, " ")
			.trim()
	}

	private fun extractChapterNumber(title: String): Float? {
		return CHAPTER_NUMBER_REGEX.find(title)?.groupValues?.getOrNull(1)?.toFloatOrNull()
	}

	suspend fun gcChaptersCache() {
		db.getChaptersDao().gc()
	}

	suspend fun findTags(source: MangaSource): Set<MangaTag> {
		return db.getTagsDao().findTags(source.name).toMangaTags()
	}

	suspend fun cleanupLocalManga() {
		val dao = db.getMangaDao()
		val bookmarksDao = db.getBookmarksDao()
		val broken = dao.findAllBySource(LocalMangaSource.name)
			.filter { x ->
				x.manga.url.toUri().toFileOrNull()?.exists() == false &&
					!bookmarksDao.hasBookmarks(x.manga.id)
			}
		if (broken.isNotEmpty()) {
			dao.delete(broken.map { it.manga })
		}
	}

	suspend fun cleanupDatabase() {
		db.withTransaction {
			gcChaptersCache()
			val idsFromShortcuts = appShortcutManagerProvider.get().getMangaShortcuts()
			db.getMangaDao().cleanup(idsFromShortcuts)
		}
	}

	fun observeOverridesTrigger(emitInitialState: Boolean) = db.invalidationTracker.createFlow(
		tables = arrayOf(TABLE_PREFERENCES),
		emitInitialState = emitInitialState,
	)

	fun observeFavoritesTrigger(emitInitialState: Boolean) = db.invalidationTracker.createFlow(
		tables = arrayOf(TABLE_FAVOURITES, TABLE_FAVOURITE_CATEGORIES),
		emitInitialState = emitInitialState,
	)

	private suspend fun Manga.withCachedChaptersIfNeeded(flag: Boolean): Manga = if (flag && !isLocal && chapters.isNullOrEmpty()) {
		val cachedChapters = db.getChaptersDao().findAll(id)
		if (cachedChapters.isEmpty()) {
			this
		} else {
			copy(chapters = cachedChapters.toMangaChapters())
		}
	} else {
		this
	}

	private fun MangaPrefsEntity.getColorFilterOrNull(): ReaderColorFilter? {
		return if (cfBrightness != 0f || cfContrast != 0f || cfInvert || cfGrayscale || cfBookEffect) {
			ReaderColorFilter(
				brightness = cfBrightness,
				contrast = cfContrast,
				isInverted = cfInvert,
				isGrayscale = cfGrayscale,
				isBookBackground = cfBookEffect
			)
		} else {
			null
		}
	}

	private fun MangaPrefsEntity.getOverrideOrNull(): MangaOverride? {
		return if (titleOverride.isNullOrEmpty() && coverUrlOverride.isNullOrEmpty() && contentRatingOverride.isNullOrEmpty()) {
			null
		} else {
			MangaOverride(
				coverUrl = coverUrlOverride?.nullIfEmpty(),
				title = titleOverride?.nullIfEmpty(),
				contentRating = ContentRating(contentRatingOverride),
			)
		}
	}

	private fun newEntity(mangaId: Long) = MangaPrefsEntity(
		mangaId = mangaId,
		mode = -1,
		cfBrightness = ReaderColorFilter.EMPTY.brightness,
		cfContrast = ReaderColorFilter.EMPTY.contrast,
		cfInvert = ReaderColorFilter.EMPTY.isInverted,
		cfGrayscale = ReaderColorFilter.EMPTY.isGrayscale,
		cfBookEffect = ReaderColorFilter.EMPTY.isBookBackground,
		titleOverride = null,
		coverUrlOverride = null,
		contentRatingOverride = null,
	)

	private class MoveRecord(
		val oldMangaId: Long,
		val oldPageId: Long,
		val newMangaId: Long,
		val newPageId: Long,
	)

	private companion object {
		val CHAPTER_TITLE_NORMALIZE_REGEX = Regex("[\\p{Punct}\\s]+")
		val CHAPTER_NUMBER_REGEX = Regex("""(?:^|\b)(?:ch(?:apter)?\.?\s*)(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
	}
}
