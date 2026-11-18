package org.koitharu.kotatsu.reader.ui.pager.webtoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebtoonSwapScrollTest {

	@Test
	fun `remapSwapScroll keeps exact scroll when scroll range is unchanged`() {
		val remapped = WebtoonImageView.remapSwapScroll(
			savedScroll = 1425,
			savedRange = 5700,
			newRange = 5700,
		)
		assertEquals(1425, remapped)
	}

	@Test
	fun `remapSwapScroll maps proportionally when translated bitmap is downscaled`() {
		val remapped = WebtoonImageView.remapSwapScroll(
			savedScroll = 1500,
			savedRange = 6000,
			newRange = 3000,
		)
		assertEquals(750, remapped)
	}

	@Test
	fun `remapSwapScroll clamps to zero when new range is zero`() {
		assertEquals(0, WebtoonImageView.remapSwapScroll(savedScroll = 900, savedRange = 2000, newRange = 0))
	}

	@Test
	fun `remapSwapScroll clamps negative inputs safely`() {
		assertEquals(0, WebtoonImageView.remapSwapScroll(savedScroll = -50, savedRange = 1000, newRange = 1000))
		assertEquals(800, WebtoonImageView.remapSwapScroll(savedScroll = 1200, savedRange = 0, newRange = 800))
	}

	@Test
	fun `shouldSkipSwapScrollClamp skips only while swap height is frozen and image is not ready`() {
		assertTrue(WebtoonRecyclerView.shouldSkipSwapScrollClamp(isReady = false, isSwapHeightFrozen = true))
		assertFalse(WebtoonRecyclerView.shouldSkipSwapScrollClamp(isReady = false, isSwapHeightFrozen = false))
		assertFalse(WebtoonRecyclerView.shouldSkipSwapScrollClamp(isReady = true, isSwapHeightFrozen = true))
		assertFalse(WebtoonRecyclerView.shouldSkipSwapScrollClamp(isReady = true, isSwapHeightFrozen = false))
	}
}
