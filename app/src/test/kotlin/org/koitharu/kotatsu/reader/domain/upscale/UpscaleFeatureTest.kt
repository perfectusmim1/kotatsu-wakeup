package org.koitharu.kotatsu.reader.domain.upscale

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.pickRandomBookmark
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.reader.domain.upscale.model.UpscaleMode
import java.time.Instant
import kotlin.random.Random

class UpscaleFeatureTest {

	@Test
	fun stripPlannerSelfCheckPasses() {
		StripPlanner.runSelfCheck()
	}

	@Test
	fun stripPlannerCoversVeryTallWebtoonWithoutGapsOrOverlaps() {
		val height = 18_432
		val strips = StripPlanner.planStrips(height)
		assertEquals(18, strips.size)
		assertEquals(0, strips.first().validY0)
		assertEquals(height, strips.last().validY1)
		for (i in 1 until strips.size) {
			assertEquals(strips[i - 1].validY1, strips[i].validY0)
		}
		assertEquals(0, strips.first().cropTop)
		assertEquals(StripPlanner.STRIP_OVERLAP, strips.first().cropBottom)
		assertEquals(StripPlanner.STRIP_OVERLAP, strips.last().cropTop)
		assertEquals(0, strips.last().cropBottom)
	}

	@Test
	fun smartHighResSkipFollowsExactPixelMath() {
		assertTrue(StripPlanner.shouldSkipHighResPage(sourceWidth = 1080, screenWidthPx = 1080, upscaleHighRes = false))
		assertTrue(StripPlanner.shouldSkipHighResPage(sourceWidth = 1440, screenWidthPx = 1080, upscaleHighRes = false))
		assertFalse(StripPlanner.shouldSkipHighResPage(sourceWidth = 720, screenWidthPx = 1080, upscaleHighRes = false))
		assertFalse(StripPlanner.shouldSkipHighResPage(sourceWidth = 1440, screenWidthPx = 1080, upscaleHighRes = true))
	}

	@Test
	fun stripOutputSizePreservesNative2xAndRespectsMaxTexture() {
		val normal = StripPlanner.computeStripOutputSize(srcW = 720, validH = 1024, maxTexSize = 4096)
		assertEquals(720, normal.inputWidth)
		assertEquals(1440, normal.outWidth)
		assertEquals(2048, normal.outHeight)

		val oversized = StripPlanner.computeStripOutputSize(srcW = 5000, validH = 1024, maxTexSize = 4096)
		assertEquals(2048, oversized.inputWidth)
		assertEquals(4096, oversized.outWidth)
		assertTrue(oversized.outHeight <= 4096)

		val aiOptimized = StripPlanner.computeStripOutputSize(
			srcW = 1080,
			validH = StripPlanner.AI_STRIP_HEIGHT,
			maxTexSize = 4096,
			maxInputWidthOverride = StripPlanner.AI_MAX_INPUT_WIDTH,
		)
		assertEquals(512, aiOptimized.inputWidth)
		assertEquals(1024, aiOptimized.outWidth)
		val aiStrips = StripPlanner.planStripsForMode(4800, UpscaleMode.AI)
		assertEquals(10, aiStrips.size)
		val firstAiStrip = aiStrips.first()
		val scaledAiInH = ((firstAiStrip.y1 - firstAiStrip.y0) * aiOptimized.inputScale).toInt()
		val aiTiles = StripPlanner.planTiles(aiOptimized.inputWidth, scaledAiInH)
		assertTrue("AI strip must fit in at most 4 ONNX tiles, got ${aiTiles.size}", aiTiles.size <= 4)
	}

	@Test
	fun upscaleModeParsingHandlesAllCases() {
		assertEquals(UpscaleMode.OFF, UpscaleMode.fromId(null))
		assertEquals(UpscaleMode.OFF, UpscaleMode.fromId("unknown"))
		assertEquals(UpscaleMode.OFF, UpscaleMode.fromId("off"))
		assertEquals(UpscaleMode.FAST, UpscaleMode.fromId("fast"))
		assertEquals(UpscaleMode.FAST, UpscaleMode.fromId("FAST"))
		assertEquals(UpscaleMode.AI, UpscaleMode.fromId("ai"))
		assertEquals(UpscaleMode.AI, UpscaleMode.fromId("  AI  "))
		assertFalse(UpscaleMode.OFF.isEnabled)
		assertTrue(UpscaleMode.FAST.isEnabled)
		assertTrue(UpscaleMode.AI.isEnabled)
	}

	@Test
	fun dynamicPriorityGateRunsClosestStripFirstAndHandlesCancellation() = runBlocking {
		val gate = DynamicPriorityGate(maxPermits = 1)
		val firstStarted = CompletableDeferred<Unit>()
		val releaseFirst = CompletableDeferred<Unit>()
		val executionOrder = ArrayList<String>()

		val holderJob = launch {
			gate.withPermit({ 0f }) {
				executionOrder.add("holder")
				firstStarted.complete(Unit)
				releaseFirst.await()
			}
		}
		firstStarted.await()

		val farJob = async {
			gate.withPermit({ 2000f }) {
				executionOrder.add("far")
			}
		}
		val cancelledJob = launch {
			gate.withPermit({ 10f }) {
				executionOrder.add("cancelled")
			}
		}
		val nearJob = async {
			gate.withPermit({ 50f }) {
				executionOrder.add("near")
			}
		}

		repeat(5) { yield() }
		assertTrue(gate.hasWaiterWithPriorityBelow(100f))
		assertFalse(gate.hasWaiterWithPriorityBelow(5f))
		cancelledJob.cancelAndJoin()
		releaseFirst.complete(Unit)

		holderJob.join()
		nearJob.await()
		farJob.await()

		assertEquals(listOf("holder", "near", "far"), executionOrder)
	}

	@Test
	fun pickRandomBookmarkAvoidsImmediateRepeatWhenMultipleExist() {
		val empty: List<Bookmark> = emptyList()
		assertNull(empty.pickRandomBookmark())

		val b1 = dummyBookmark(pageId = 101L)
		val b2 = dummyBookmark(pageId = 202L)
		val b3 = dummyBookmark(pageId = 303L)

		assertEquals(b1, listOf(b1).pickRandomBookmark(excludePageId = 101L, random = Random(42)))

		val list = listOf(b1, b2, b3)
		val rng = Random(7)
		repeat(20) {
			val picked = list.pickRandomBookmark(excludePageId = 202L, random = rng)
			assertNotNull(picked)
			assertNotEquals(202L, picked?.pageId)
		}
	}

	private fun dummyBookmark(pageId: Long): Bookmark {
		val source = object : MangaSource {
			override val name: String = "TEST_SOURCE"
		}
		val manga = Manga(
			id = 1L,
			title = "Test Manga",
			altTitles = emptySet(),
			url = "/manga/1",
			publicUrl = "https://example.com/manga/1",
			rating = 0f,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
		return Bookmark(
			manga = manga,
			pageId = pageId,
			chapterId = 10L,
			page = 0,
			scroll = 0,
			imageUrl = "https://example.com/$pageId.jpg",
			createdAt = Instant.EPOCH,
			percent = 0f,
		)
	}
}
