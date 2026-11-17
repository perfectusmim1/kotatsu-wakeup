package org.koitharu.kotatsu.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage

class ReaderViewModelNavigationTest {

	@Test
	fun preservesTargetPageWhenPreviousChapterPrependsDuringWait() {
		val ch34 = List(134) { idx -> page(chapterId = 34L, index = idx) }
		val ch35 = List(91) { idx -> page(chapterId = 35L, index = idx) }
		val captured = ch34 + ch35 // size 225; index 136 = Ch35 page 3 (index 2)
		val ch33 = List(110) { idx -> page(chapterId = 33L, index = idx) }
		val current = ch33 + ch34 + ch35 // size 335; Ch35 page 3 shifted to index 246

		val resolved = ReaderViewModel.resolveCurrentPageRange(
			capturedPages = captured,
			currentPages = current,
			lowerPos = 136,
			upperPos = 136,
			capturedLowerPage = captured[136],
			capturedCenterPage = captured[136],
			capturedUpperPage = captured[136],
		)

		assertNotNull(resolved)
		assertEquals(35L, resolved!!.centerPage?.chapterId)
		assertEquals(2, resolved.centerPage?.index)
		assertEquals(246, resolved.lowerPos)
		assertEquals(246, resolved.upperPos)
	}

	@Test
	fun returnsNullWhenCapturedChapterWasReplaced() {
		val ch10 = List(10) { idx -> page(chapterId = 10L, index = idx) }
		val ch20 = List(10) { idx -> page(chapterId = 20L, index = idx) }

		val resolved = ReaderViewModel.resolveCurrentPageRange(
			capturedPages = ch10,
			currentPages = ch20,
			lowerPos = 2,
			upperPos = 2,
			capturedLowerPage = ch10[2],
			capturedCenterPage = ch10[2],
			capturedUpperPage = ch10[2],
		)

		assertNull(resolved)
	}

	@Test
	fun resolvesDirectRangeWhenListUnchanged() {
		val ch35 = List(20) { idx -> page(chapterId = 35L, index = idx) }

		val resolved = ReaderViewModel.resolveCurrentPageRange(
			capturedPages = ch35,
			currentPages = ch35,
			lowerPos = 4,
			upperPos = 5,
			capturedLowerPage = ch35[4],
			capturedCenterPage = ch35[4],
			capturedUpperPage = ch35[5],
		)

		assertNotNull(resolved)
		assertEquals(4, resolved!!.lowerPos)
		assertEquals(5, resolved.upperPos)
		assertEquals(4, resolved.centerPos)
		assertEquals(35L, resolved.centerPage?.chapterId)
		assertEquals(4, resolved.centerPage?.index)
	}

	private fun page(chapterId: Long, index: Int) = ReaderPage(
		id = chapterId * 1000L + index,
		url = "https://example.com/$chapterId/$index.jpg",
		preview = null,
		chapterId = chapterId,
		index = index,
		source = TestMangaSource,
	)
}
