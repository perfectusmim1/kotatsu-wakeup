package org.koitharu.kotatsu.bookmarks.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.details.ui.pager.bookmarks.buildBookmarkListModels
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.time.Instant

class BookmarkMatcherTest {

	@Test
	fun sameSeriesMatchesToonilyWebtoonToSerieHashMigration() {
		val stored = manga(
			id = 1001L,
			title = "Secret Class",
			url = "/webtoon/secret-class/",
			publicUrl = "https://toonily.com/webtoon/secret-class/",
			source = TestMangaSource,
		)
		val fresh = manga(
			id = 2002L,
			title = "Secret Class",
			url = "/serie/secret-class-8f2a1c/",
			publicUrl = "https://toonily.com/serie/secret-class-8f2a1c/",
			source = TestMangaSource,
		)
		assertTrue(BookmarkMatcher.isSameSeriesOnSameSource(stored, fresh))
	}

	@Test
	fun sameSeriesMatchesHostAndTrailingSlashDriftOnMadara() {
		val stored = manga(
			id = 1001L,
			title = "Some Series",
			url = "https://toongod.org/webtoon/some-series",
			publicUrl = "https://toongod.org/webtoon/some-series",
			source = TestMangaSource,
		)
		val fresh = manga(
			id = 2002L,
			title = "Some Series (Updated)",
			url = "/webtoon/some-series/",
			publicUrl = "https://www.toongod.org/webtoon/some-series/",
			source = TestMangaSource,
		)
		assertTrue(BookmarkMatcher.isSameSeriesOnSameSource(stored, fresh))
	}

	@Test
	fun sameSeriesDoesNotMatchAcrossDifferentSources() {
		val otherSource = object : MangaSource {
			override val name: String = "OTHER_SOURCE"
		}
		val stored = manga(
			id = 1001L,
			title = "Secret Class",
			url = "/webtoon/secret-class/",
			publicUrl = "https://a.example/webtoon/secret-class/",
			source = TestMangaSource,
		)
		val other = manga(
			id = 2002L,
			title = "Secret Class",
			url = "/webtoon/secret-class/",
			publicUrl = "https://b.example/webtoon/secret-class/",
			source = otherSource,
		)
		assertFalse(BookmarkMatcher.isSameSeriesOnSameSource(stored, other))
	}

	@Test
	fun sameSeriesDoesNotMatchUnrelatedSeriesOnSameSource() {
		val stored = manga(
			id = 1001L,
			title = "Secret Class",
			url = "/webtoon/secret-class/",
			publicUrl = "https://toonily.com/webtoon/secret-class/",
			source = TestMangaSource,
		)
		val unrelated = manga(
			id = 2002L,
			title = "Silent War",
			url = "/webtoon/silent-war/",
			publicUrl = "https://toonily.com/webtoon/silent-war/",
			source = TestMangaSource,
		)
		assertFalse(BookmarkMatcher.isSameSeriesOnSameSource(stored, unrelated))
	}

	@Test
	fun chapterRemapsByStoredMetadataEvenWithoutCachedChapters() {
		val newChapters = (1..50).map { i ->
			chapter(
				id = 9000L + i,
				title = "Chapter $i",
				number = i.toFloat(),
				url = "/serie/secret-class-a1b2c3/chapter-$i/?style=list",
			)
		}
		val bookmark = BookmarkEntity(
			mangaId = 1001L,
			pageId = 555L,
			chapterId = 1024L, // stale old id
			page = 7,
			scroll = 0,
			imageUrl = "https://cdn.example/img/07.jpg",
			createdAt = 1_700_000_000_000L,
			percent = 0.47f,
			chapterNumber = 24f,
			chapterVolume = 0,
			chapterTitle = "Chapter 24",
			chapterUrl = "/webtoon/secret-class/chapter-24/?style=list",
			chapterBranch = null,
			chapterIndex = 23,
			chaptersCount = 50,
		)
		val matched = BookmarkMatcher.findMatchingChapter(
			bookmark = bookmark,
			cachedChapter = null,
			newChapters = newChapters,
		)
		assertNotNull(matched)
		assertEquals(9024L, matched!!.id)
	}

	@Test
	fun legacyOrphanedBookmarkRecoversExactChapterFromImageUrl() {
		val newChapters = (1..50).map { i ->
			chapter(
				id = 5000L + i,
				title = "Chapter $i",
				number = i.toFloat(),
				url = "/manga/foo/chapter-$i/",
			)
		}
		val bookmark = BookmarkEntity(
			mangaId = 1001L,
			pageId = 777L,
			chapterId = 99999L, // old orphaned id, no cached chapter, no metadata
			page = 3,
			scroll = 0,
			imageUrl = "https://cdn.mangadna.com/wp-content/uploads/WP-manga/data/manga_abc/chapter-17/04.jpg",
			createdAt = 1_700_000_000_000L,
			percent = (16f / 50f) + (4f / 20f) * (1f / 50f),
		)
		val matched = BookmarkMatcher.findMatchingChapter(
			bookmark = bookmark,
			cachedChapter = null,
			newChapters = newChapters,
		)
		assertNotNull(matched)
		assertEquals(5017L, matched!!.id)
	}

	@Test
	fun legacyOrphanedBookmarkRecoversExactChapterFromPercentWhenCountUnchanged() {
		val total = 60
		val newChapters = (1..total).map { i ->
			chapter(
				id = 7000L + i,
				title = "Episode $i",
				number = i.toFloat(),
				url = "/manga/bar/ep-$i/",
			)
		}
		for (chapterIdx in listOf(0, 1, 14, 29, 58, 59)) {
			for (pageIdx in listOf(0, 9, 19)) {
				val percent = (1f / total) * chapterIdx + (1f / total) * ((pageIdx + 1) / 20f)
				val bookmark = BookmarkEntity(
					mangaId = 1001L,
					pageId = 800L + chapterIdx,
					chapterId = 123456L, // stale id, no metadata, opaque image URL
					page = pageIdx,
					scroll = 0,
					imageUrl = "https://cdn.example/opaque/hash9988.webp",
					createdAt = 1_700_000_000_000L,
					percent = percent,
				)
				val matched = BookmarkMatcher.findMatchingChapter(
					bookmark = bookmark,
					cachedChapter = null,
					newChapters = newChapters,
				)
				assertNotNull("Failed for chapterIdx=$chapterIdx pageIdx=$pageIdx", matched)
				assertEquals(
					"Mismatch for chapterIdx=$chapterIdx pageIdx=$pageIdx",
					7000L + chapterIdx + 1,
					matched!!.id,
				)
			}
		}
	}

	@Test
	fun percentFallbackDoesNotMisMatchTruncatedChapterLoad() {
		val truncated = listOf(chapter(id = 1L, title = "Chapter 1", number = 1f, url = "/ch-1"))
		val bookmark = BookmarkEntity(
			mangaId = 1001L,
			pageId = 10L,
			chapterId = 999L,
			page = 2,
			scroll = 0,
			imageUrl = "https://cdn.example/opaque.jpg",
			createdAt = 1L,
			percent = 0.25f,
			chaptersCount = 100,
		)
		val matched = BookmarkMatcher.findMatchingChapter(
			bookmark = bookmark,
			cachedChapter = null,
			newChapters = truncated,
		)
		assertNull(matched)
	}

	@Test
	fun resolveBookmarkDownloadChapterIdsFallsBackToMatcherWhenIdChanged() {
		val newChapters = (1..10).map { i ->
			chapter(id = 3000L + i, title = "Chapter $i", number = i.toFloat(), url = "/serie/x/chapter-$i/")
		}
		val m = manga(
			id = 1001L,
			title = "Test",
			url = "/serie/x/",
			publicUrl = "https://example.com/serie/x/",
			source = TestMangaSource,
			chapters = newChapters,
		)
		val staleBookmark = Bookmark(
			manga = m,
			pageId = 42L,
			chapterId = 999999L,
			page = 2,
			scroll = 0,
			imageUrl = "https://cdn.example/x.jpg",
			createdAt = Instant.now(),
			percent = 0.42f,
			chapterNumber = 5f,
			chapterTitle = "Chapter 5",
			chapterUrl = "/webtoon/x/chapter-5/",
			chapterIndex = 4,
			chaptersCount = 10,
		)
		val ids = resolveBookmarkDownloadChapterIds(m, listOf(staleBookmark), extraNextChapters = 1)
		assertEquals(listOf(3005L, 3006L), ids.toList())
	}

	@Test
	fun bookmarkGroupingPreservesChapterHeadersWhenSourceHasZeroChapters() {
		val deadManga = manga(
			id = 1001L,
			title = "Broken Source Series",
			url = "/manga/broken/",
			publicUrl = "https://example.com/manga/broken/",
			source = TestMangaSource,
			chapters = emptyList(),
		)
		val b1 = Bookmark(
			manga = deadManga,
			pageId = 101L,
			chapterId = 5005L,
			page = 4,
			scroll = 0,
			imageUrl = "https://cdn.example/opaque/1.jpg",
			createdAt = Instant.ofEpochSecond(1_700_000_100L),
			percent = 0.05f,
			chapterNumber = 5f,
			chapterVolume = 0,
			chapterTitle = "Chapter 5: The Start",
			chapterUrl = "/manga/broken/chapter-5/",
			chapterIndex = 4,
			chaptersCount = 100,
		)
		val b2 = Bookmark(
			manga = deadManga,
			pageId = 102L,
			chapterId = 5042L,
			page = 9,
			scroll = 0,
			imageUrl = "https://cdn.example/wp-content/chapter-42/09.jpg",
			createdAt = Instant.ofEpochSecond(1_700_000_200L),
			percent = 0.42f,
		)
		val models = buildBookmarkListModels(
			manga = deadManga,
			bookmarks = listOf(b2, b1),
			cachedChapters = emptyList(),
			rawQuery = "",
			order = BookmarkSortOrder.CHAPTER_ASC,
		)
		val headers = models.filterIsInstance<ListHeader>()
		assertEquals(2, headers.size)
		assertFalse(
			"Must not collapse into generic bookmarks header",
			headers.any { it == ListHeader(R.string.bookmarks) },
		)
		// First group should be chapter 5, second should be chapter 42
		assertEquals(
			ListHeader(BookmarkMatcher.reconstructChapter(b1)!!),
			headers[0],
		)
		assertEquals(
			ListHeader(BookmarkMatcher.reconstructChapter(b2)!!),
			headers[1],
		)
		assertEquals(42f, BookmarkMatcher.reconstructChapter(b2)!!.number, 0.001f)
	}

	@Test
	fun bookmarkGroupingUsesCachedChaptersWhenRemoteReturnsEmpty() {
		val deadManga = manga(
			id = 1001L,
			title = "Cached Fallback Series",
			url = "/manga/cached/",
			publicUrl = "https://example.com/manga/cached/",
			source = TestMangaSource,
			chapters = emptyList(),
		)
		val cached12 = chapter(id = 8012L, title = "Chapter 12: Climax", number = 12f, url = "/manga/cached/ch-12/")
		val cached25 = chapter(id = 8025L, title = "Chapter 25: Finale", number = 25f, url = "/manga/cached/ch-25/")
		val b12 = Bookmark(
			manga = deadManga,
			pageId = 201L,
			chapterId = 8012L,
			page = 2,
			scroll = 0,
			imageUrl = "https://cdn.example/opaque/a.jpg",
			createdAt = Instant.ofEpochSecond(1_700_000_100L),
			percent = 0.3f,
		)
		val b25 = Bookmark(
			manga = deadManga,
			pageId = 202L,
			chapterId = 8025L,
			page = 6,
			scroll = 0,
			imageUrl = "https://cdn.example/opaque/b.jpg",
			createdAt = Instant.ofEpochSecond(1_700_000_200L),
			percent = 0.8f,
		)
		val models = buildBookmarkListModels(
			manga = deadManga,
			bookmarks = listOf(b12, b25),
			cachedChapters = listOf(cached12, cached25),
			rawQuery = "climax",
			order = BookmarkSortOrder.CHAPTER_ASC,
		)
		val headers = models.filterIsInstance<ListHeader>()
		assertEquals(listOf(ListHeader(cached12)), headers)
		assertEquals(listOf(b12), models.filterIsInstance<Bookmark>())
	}

	@Test
	fun bookmarkGroupingHealsDriftedChapterIdToLiveChapterHeader() {
		val liveChapters = (1..20).map { i ->
			chapter(id = 9000L + i, title = "Chapter $i", number = i.toFloat(), url = "/serie/abc/chapter-$i/")
		}
		val liveManga = manga(
			id = 2002L,
			title = "Drifted Series",
			url = "/serie/abc/",
			publicUrl = "https://example.com/serie/abc/",
			source = TestMangaSource,
			chapters = liveChapters,
		)
		val staleBookmark = Bookmark(
			manga = liveManga,
			pageId = 301L,
			chapterId = 11111L, // old ID not in liveChapters
			page = 5,
			scroll = 0,
			imageUrl = "https://cdn.example/opaque.jpg",
			createdAt = Instant.ofEpochSecond(1_700_000_100L),
			percent = 0.5f,
			chapterNumber = 10f,
			chapterTitle = "Chapter 10",
			chapterUrl = "/webtoon/abc/chapter-10/",
			chapterIndex = 9,
			chaptersCount = 20,
		)
		val models = buildBookmarkListModels(
			manga = liveManga,
			bookmarks = listOf(staleBookmark),
			cachedChapters = emptyList(),
			rawQuery = "",
			order = BookmarkSortOrder.CHAPTER_ASC,
		)
		assertEquals(listOf(ListHeader(liveChapters[9])), models.filterIsInstance<ListHeader>())
	}

	private fun manga(
		id: Long,
		title: String,
		url: String,
		publicUrl: String,
		source: MangaSource,
		chapters: List<MangaChapter>? = null,
	) = Manga(
		id = id,
		title = title,
		altTitles = emptySet(),
		url = url,
		publicUrl = publicUrl,
		rating = -1f,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = source,
		chapters = chapters,
	)

	private fun chapter(
		id: Long,
		title: String?,
		number: Float,
		url: String,
		volume: Int = 0,
		branch: String? = null,
	) = MangaChapter(
		id = id,
		title = title,
		number = number,
		volume = volume,
		url = url,
		scanlator = null,
		uploadDate = 0L,
		branch = branch,
		source = TestMangaSource,
	)
}
