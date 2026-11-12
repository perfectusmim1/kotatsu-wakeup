package org.koitharu.kotatsu.reader.domain

import android.util.LongSparseArray
import androidx.annotation.CheckResult
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaAutoResolveCoordinator
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.data.erase.HiddenImagesRepository
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import javax.inject.Inject

private const val PAGES_TRIM_THRESHOLD = 120

/**
 * Leaves out [hidden] pages, except that a chapter always keeps one: the reader has no notion of a
 * chapter with no pages, and would report it as not found rather than move on.
 */
internal fun withoutHidden(pages: List<ReaderPage>, hidden: Set<Long>): List<ReaderPage> {
	if (hidden.isEmpty()) {
		return pages
	}
	val visibleChapters = pages.mapNotNullTo(HashSet()) { page -> page.chapterId.takeIf { page.id !in hidden } }
	val keptChapters = HashSet<Long>()
	return pages.filter { page ->
		page.id !in hidden || (page.chapterId !in visibleChapters && keptChapters.add(page.chapterId))
	}
}

/**
 * Where to go for a saved position in [pages]: the page itself or, if it has since been hidden, the next
 * one still there. Null if the chapter is not in the list at all.
 */
internal fun List<ReaderPage>.indexOfPosition(chapterId: Long, page: Int): Int? {
	var bestNextPos: Int? = null
	var bestNextIndex = Int.MAX_VALUE
	var maxPos: Int? = null
	var maxIndex = Int.MIN_VALUE
	for (i in indices) {
		val item = this[i]
		if (item.chapterId != chapterId || item.index < 0) {
			continue
		}
		if (item.index == page) {
			return i
		}
		if (item.index > page && item.index < bestNextIndex) {
			bestNextIndex = item.index
			bestNextPos = i
		}
		if (item.index > maxIndex) {
			maxIndex = item.index
			maxPos = i
		}
	}
	return bestNextPos ?: maxPos
}

@ViewModelScoped
class ChaptersLoader @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val captchaAutoResolveCoordinator: CaptchaAutoResolveCoordinator,
	private val hiddenImages: HiddenImagesRepository,
) {

	private val chapters = LongSparseArray<MangaChapter>()
	private val chapterPages = ChapterPages()
	private val mutex = Mutex()

	val size: Int
		get() = chapters.size()

	suspend fun init(manga: MangaDetails) = mutex.withLock {
		synchronized(chapters) {
			chapters.clear()
			manga.allChapters.forEach {
				chapters.put(it.id, it)
			}
		}
	}

	suspend fun loadPrevNextChapter(
		manga: MangaDetails,
		currentId: Long,
		isNext: Boolean,
		trimPages: Boolean = true,
	): Boolean {
		val chapters = manga.allChapters
		val predicate: (MangaChapter) -> Boolean = { it.id == currentId }
		val index = if (isNext) chapters.indexOfFirst(predicate) else chapters.indexOfLast(predicate)
		if (index == -1) return false
		val newChapter = chapters.getOrNull(if (isNext) index + 1 else index - 1) ?: return false
		val newPages = loadChapter(newChapter.id, mayStartVerification = false)
		mutex.withLock {
			synchronized(chapterPages) {
				if (trimPages && chapterPages.chaptersSize > 1) {
					// trim pages
					if (chapterPages.size > PAGES_TRIM_THRESHOLD) {
						if (isNext) {
							chapterPages.removeFirst()
						} else {
							chapterPages.removeLast()
						}
					}
				}
				if (isNext) {
					chapterPages.addLast(newChapter.id, newPages)
				} else {
					chapterPages.addFirst(newChapter.id, newPages)
				}
			}
		}
		return true
	}

	@CheckResult
	suspend fun loadSingleChapter(chapterId: Long): Boolean {
		val pages = loadChapter(chapterId, mayStartVerification = true)
		return mutex.withLock {
			synchronized(chapterPages) {
				chapterPages.clear()
				chapterPages.addLast(chapterId, pages)
				pages.isNotEmpty()
			}
		}
	}

	fun peekChapter(chapterId: Long): MangaChapter? = synchronized(chapters) {
		chapters[chapterId]
	}

	fun hasPages(chapterId: Long): Boolean = synchronized(chapterPages) {
		chapterId in chapterPages
	}

	fun hasLoadedPages(): Boolean = synchronized(chapterPages) {
		chapterPages.size > 0
	}

	fun getPages(chapterId: Long): List<MangaPage> = synchronized(chapterPages) {
		return chapterPages.subList(chapterId).map { it.toMangaPage() }
	}

	fun getPagesCount(chapterId: Long): Int = synchronized(chapterPages) {
		chapterPages.size(chapterId)
	}

	fun last() = synchronized(chapterPages) {
		chapterPages.last()
	}

	fun first() = synchronized(chapterPages) {
		chapterPages.first()
	}

	/**
	 * The pages to show, without those known to be nothing but a hidden image. They are only left out here
	 * and not when loading: `ReaderPage.index` and [getPagesCount] are what saved progress is made of, and
	 * must go on describing the chapter as the source serves it.
	 */
	suspend fun snapshot(): List<ReaderPage> {
		val pages = synchronized(chapterPages) { chapterPages.toList() }
		return withoutHidden(pages, hiddenImages.getHiddenPages())
	}

	fun observeHiddenPages() = hiddenImages.observeHiddenPages()

	/** The page right before or after another, hidden or not, as long as it is in the same chapter. */
	fun findNeighbour(pageId: Long, isBefore: Boolean): ReaderPage? {
		val pages = synchronized(chapterPages) { chapterPages.toList() }
		val index = pages.indexOfFirst { it.id == pageId }
		if (index < 0) {
			return null
		}
		return pages.getOrNull(if (isBefore) index - 1 else index + 1)?.takeIf { it.chapterId == pages[index].chapterId }
	}

	private suspend fun loadChapter(
		chapterId: Long,
		mayStartVerification: Boolean,
	): List<ReaderPage> {
		val chapter = checkNotNull(synchronized(chapters) { chapters[chapterId] }) { "Requested chapter not found" }
		val repo = mangaRepositoryFactory.create(chapter.source)
		val pages = captchaAutoResolveCoordinator.runWithVerification(
			source = chapter.source,
			mayStartVerification = mayStartVerification,
		) {
			repo.getPages(chapter)
		}
		return pages.mapIndexed { index, page ->
			ReaderPage(page, index, chapterId)
		}
	}
}
