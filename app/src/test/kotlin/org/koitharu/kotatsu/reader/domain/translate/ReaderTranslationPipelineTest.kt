package org.koitharu.kotatsu.reader.domain.translate

import android.graphics.Bitmap
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.lifecycle.RetainedLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.domain.translate.model.ComicTextTranslator
import org.koitharu.kotatsu.reader.domain.translate.model.LocalOcrEngine
import org.koitharu.kotatsu.reader.domain.translate.model.NormalizedBox
import org.koitharu.kotatsu.reader.domain.translate.model.OcrBubbleGroup
import org.koitharu.kotatsu.reader.domain.translate.model.OcrSourceLanguage
import org.koitharu.kotatsu.reader.domain.translate.model.TranslatedPageRenderer
import org.koitharu.kotatsu.reader.domain.translate.model.TranslatedTextItem
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationCacheStore
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationProvider
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationSettingsSnapshot
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationStage
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderTranslationPipelineTest {

	@get:Rule
	val tempFolder = TemporaryFolder()

	private lateinit var lifecycle: FakeActivityRetainedLifecycle
	private val unsafeAllocate = run {
		val unsafeClass = Class.forName("sun.misc.Unsafe")
		val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
		val instance = field.get(null)
		val method = unsafeClass.getMethod("allocateInstance", Class::class.java)
		instance to method
	}

	@Before
	fun setUp() {
		Dispatchers.setMain(UnconfinedTestDispatcher())
		lifecycle = FakeActivityRetainedLifecycle()
	}

	@After
	fun tearDown() {
		lifecycle.clear()
		Dispatchers.resetMain()
	}

	@Test
	fun pipelineOverlap_nextPageOcrCompletesWhileFirstPageAwaitsTranslationNetwork(): Unit = runBlocking {
		withTimeout(10_000L) {
			val env = createCoordinator(autoTranslate = true, prefetchNextPage = true)
			val page1 = createPage(1L, index = 0)
			val page2 = createPage(2L, index = 1)

			val page1TranslateStarted = CompletableDeferred<Unit>()
			val page1TranslateRelease = CompletableDeferred<Unit>()
			val page2OcrStarted = CompletableDeferred<Unit>()
			val page2OcrFinished = CompletableDeferred<Unit>()

			env.translator.enteredCallbacks[1L] = page1TranslateStarted
			env.translator.releaseGates[1L] = page1TranslateRelease
			env.ocrEngine.enteredCallbacks[2L] = page2OcrStarted
			env.ocrEngine.finishedCallbacks[2L] = page2OcrFinished

			env.coordinator.updateReadingWindow(
				activePages = listOf(page1.toReaderPage()),
				prefetchPages = emptyList(),
			)

			// Wait until page 1 finished OCR and is actively blocked inside translateBubbles
			page1TranslateStarted.await()
			assertFalse(page1TranslateRelease.isCompleted)

			// Now schedule page 2 while page 1 is still awaiting network translation
			env.coordinator.updateReadingWindow(
				activePages = listOf(page1.toReaderPage(), page2.toReaderPage()),
				prefetchPages = emptyList(),
			)

			page2OcrStarted.await()
			page2OcrFinished.await()

			// Page 2 OCR started and completed strictly while page 1's translateBubbles was still blocked
			assertFalse(page1TranslateRelease.isCompleted)
			assertEquals(1, env.ocrEngine.maxConcurrentOcr.get())

			page1TranslateRelease.complete(Unit)

			awaitPageDone(env.coordinator, 1L)
			awaitPageDone(env.coordinator, 2L)
			env.coordinator.activeTranslatedPages.first { 1L in it && 2L in it }
			Unit
		}
	}

	@Test
	fun networkTranslationConcurrency_allowsFourConcurrentTranslationsWhileSerializingOcr(): Unit = runBlocking {
		withTimeout(10_000L) {
			val env = createCoordinator(autoTranslate = true, prefetchNextPage = true)
			val pages = (10L..14L).mapIndexed { idx, id -> createPage(id, index = idx) }
			val releaseAll = CompletableDeferred<Unit>()
			val activeInTranslate = AtomicInteger(0)
			val fourConcurrentReached = CompletableDeferred<Unit>()

			for (page in pages) {
				env.translator.releaseGates[page.id] = releaseAll
				env.translator.onEnterHook[page.id] = {
					if (activeInTranslate.incrementAndGet() == 4) {
						fourConcurrentReached.complete(Unit)
					}
				}
				env.translator.onExitHook[page.id] = {
					activeInTranslate.decrementAndGet()
				}
			}

			env.coordinator.updateReadingWindow(
				activePages = pages.map { it.toReaderPage() },
				prefetchPages = emptyList(),
			)

			fourConcurrentReached.await()
			while (env.ocrEngine.completedPages.size < pages.size) {
				delay(5)
			}
			assertEquals(1, env.ocrEngine.maxConcurrentOcr.get())
			assertEquals(4, env.translator.maxConcurrentTranslate.get())
			assertEquals(5, env.ocrEngine.completedPages.size)

			releaseAll.complete(Unit)
			for (page in pages) {
				awaitPageDone(env.coordinator, page.id)
			}
		}
	}

	@Test
	fun onPagePreloaded_autoTranslatesPreloadedPageWhenEnabledAndPrefetchNextPageTrue(): Unit = runBlocking {
		withTimeout(10_000L) {
			val env = createCoordinator(autoTranslate = true, prefetchNextPage = true)
			val page1 = createPage(101L, index = 0)
			val preloadedPage = createPage(102L, index = 1)

			env.coordinator.updateReadingWindow(
				activePages = listOf(page1.toReaderPage()),
				prefetchPages = emptyList(),
			)
			awaitPageDone(env.coordinator, page1.id)

			env.coordinator.onPagePreloaded(preloadedPage)
			awaitPageDone(env.coordinator, preloadedPage.id)

			val state = env.coordinator.pageStates.value[preloadedPage.id]
			assertEquals(TranslationStage.DONE, state?.stage)
			env.coordinator.activeTranslatedPages.first { preloadedPage.id in it }
			Unit
		}
	}

	@Test
	fun onPagePreloaded_doesNotStartTranslationWhenDisabledOrPrefetchSettingFalse() = runBlocking {
		withTimeout(10_000L) {
			// Case 1: translation disabled (autoTranslate = false)
			val disabledEnv = createCoordinator(autoTranslate = false, prefetchNextPage = true)
			val pDisabled = createPage(201L, index = 0)
			disabledEnv.coordinator.onPagePreloaded(pDisabled)
			delay(100)
			assertTrue(disabledEnv.ocrEngine.startedPages.isEmpty())
			assertTrue(disabledEnv.coordinator.pageStates.value.isEmpty())

			// Case 2: translation enabled, prefetchNextPage = false
			val noPrefetchEnv = createCoordinator(autoTranslate = true, prefetchNextPage = false)
			val activePage = createPage(202L, index = 0)
			val windowPrefetchPage = createPage(203L, index = 1)
			val preloadedPage = createPage(204L, index = 2)

			noPrefetchEnv.coordinator.updateReadingWindow(
				activePages = listOf(activePage.toReaderPage()),
				prefetchPages = listOf(windowPrefetchPage.toReaderPage()),
			)
			awaitPageDone(noPrefetchEnv.coordinator, activePage.id)

			noPrefetchEnv.coordinator.onPagePreloaded(preloadedPage)
			delay(100)

			assertEquals(listOf(activePage.id), noPrefetchEnv.ocrEngine.startedPages.toList())
			assertNull(noPrefetchEnv.coordinator.pageStates.value[windowPrefetchPage.id])
			assertNull(noPrefetchEnv.coordinator.pageStates.value[preloadedPage.id])
		}
	}

	@Test
	fun activePage_takesPriorityOverQueuedPreloadedPageForOcr() = runBlocking {
		withTimeout(10_000L) {
			val env = createCoordinator(autoTranslate = true, prefetchNextPage = true)
			val initialActive = createPage(301L, index = 0)
			val preloadedPage = createPage(302L, index = 1)
			val urgentActivePage = createPage(303L, index = 2)

			val initialOcrEntered = CompletableDeferred<Unit>()
			val initialOcrRelease = CompletableDeferred<Unit>()
			env.ocrEngine.enteredCallbacks[initialActive.id] = initialOcrEntered
			env.ocrEngine.releaseGates[initialActive.id] = initialOcrRelease

			env.coordinator.updateReadingWindow(
				activePages = listOf(initialActive.toReaderPage()),
				prefetchPages = emptyList(),
			)
			initialOcrEntered.await()

			// Schedule low-priority preloaded page while initialActive is still in OCR;
			// it must park before OCR without claiming the OCR gate ahead of active pages.
			env.coordinator.onPagePreloaded(preloadedPage)
			delay(50)
			assertEquals(listOf(initialActive.id), env.ocrEngine.startedPages.toList())

			// Now schedule high-priority visible active page and ensure it queues on OCR permit
			env.coordinator.updateReadingWindow(
				activePages = listOf(initialActive.toReaderPage(), urgentActivePage.toReaderPage()),
				prefetchPages = emptyList(),
			)
			while (env.coordinator.pendingOcrWaitersCount() < 1) {
				delay(5)
			}

			initialOcrRelease.complete(Unit)

			awaitPageDone(env.coordinator, initialActive.id)
			awaitPageDone(env.coordinator, urgentActivePage.id)
			awaitPageDone(env.coordinator, preloadedPage.id)

			assertEquals(
				listOf(initialActive.id, urgentActivePage.id, preloadedPage.id),
				env.ocrEngine.startedPages.toList(),
			)
		}
	}

	@Test
	fun simultaneousWindowSchedule_activePageEntersOcrBeforePrefetchEvenIfActiveLoadsSlower(): Unit = runBlocking {
		withTimeout(10_000L) {
			val env = createCoordinator(
				autoTranslate = true,
				prefetchNextPage = true,
				bitmapLoadDelayMs = { page -> if (page.id == 501L) 50L else 0L },
			)
			val activePage = createPage(501L, index = 0)
			val prefetchPage = createPage(502L, index = 1)

			env.coordinator.updateReadingWindow(
				activePages = listOf(activePage.toReaderPage()),
				prefetchPages = listOf(prefetchPage.toReaderPage()),
			)

			awaitPageDone(env.coordinator, activePage.id)
			awaitPageDone(env.coordinator, prefetchPage.id)

			assertEquals(listOf(activePage.id, prefetchPage.id), env.ocrEngine.startedPages.toList())
			Unit
		}
	}

	@Test
	fun disjointWindowJump_cancelsStalePrefetchAndPreloadedJobs() = runBlocking {
		withTimeout(10_000L) {
			val env = createCoordinator(autoTranslate = true, prefetchNextPage = true)
			val active1 = createPage(401L, index = 0)
			val stalePrefetch = createPage(402L, index = 1)
			val stalePreloaded = createPage(403L, index = 2)
			val newActive = createPage(420L, index = 19)
			val newPrefetch = createPage(421L, index = 20)

			val stalePrefetchStarted = CompletableDeferred<Unit>()
			val stalePrefetchRelease = CompletableDeferred<Unit>()
			val stalePrefetchCancelled = CompletableDeferred<Boolean>()
			val stalePreloadedStarted = CompletableDeferred<Unit>()
			val stalePreloadedRelease = CompletableDeferred<Unit>()
			val stalePreloadedCancelled = CompletableDeferred<Boolean>()

			env.translator.enteredCallbacks[stalePrefetch.id] = stalePrefetchStarted
			env.translator.releaseGates[stalePrefetch.id] = stalePrefetchRelease
			env.translator.cancelledSignals[stalePrefetch.id] = stalePrefetchCancelled

			env.translator.enteredCallbacks[stalePreloaded.id] = stalePreloadedStarted
			env.translator.releaseGates[stalePreloaded.id] = stalePreloadedRelease
			env.translator.cancelledSignals[stalePreloaded.id] = stalePreloadedCancelled

			env.coordinator.updateReadingWindow(
				activePages = listOf(active1.toReaderPage()),
				prefetchPages = listOf(stalePrefetch.toReaderPage()),
			)
			awaitPageDone(env.coordinator, active1.id)
			stalePrefetchStarted.await()

			env.coordinator.onPagePreloaded(stalePreloaded)
			stalePreloadedStarted.await()

			// Jump to a disjoint window further ahead
			env.coordinator.updateReadingWindow(
				activePages = listOf(newActive.toReaderPage()),
				prefetchPages = listOf(newPrefetch.toReaderPage()),
			)

			assertTrue(stalePrefetchCancelled.await())
			assertTrue(stalePreloadedCancelled.await())

			awaitPageDone(env.coordinator, newActive.id)
			awaitPageDone(env.coordinator, newPrefetch.id)

			// Completed page stays DONE; cancelled prefetch/preload states are cleared
			assertEquals(TranslationStage.DONE, env.coordinator.pageStates.value[active1.id]?.stage)
			assertNull(env.coordinator.pageStates.value[stalePrefetch.id])
			assertNull(env.coordinator.pageStates.value[stalePreloaded.id])
		}
	}

	private fun createCoordinator(
		autoTranslate: Boolean,
		prefetchNextPage: Boolean,
		bitmapLoadDelayMs: (MangaPage) -> Long = { 0L },
	): TestEnv {
		val settings = TranslationSettingsSnapshot(
			provider = TranslationProvider.GOOGLE,
			sourceLang = OcrSourceLanguage.JAPANESE,
			targetLang = "tr",
			apiKeys = emptyList(),
			autoTranslate = autoTranslate,
			prefetchNextPage = prefetchNextPage,
			skipPureSfx = true,
			filterPromoAndCredits = true,
		)
		val settingsFlow = MutableStateFlow(settings)
		val bitmapRegistry = Collections.synchronizedMap(IdentityHashMap<Bitmap, Long>())
		val ocrEngine = FakeOcrEngine(bitmapRegistry)
		val translator = FakeComicTextTranslator()
		val renderer = FakeTranslatedPageRenderer()
		val cacheStore = FakeTranslationCacheStore(tempFolder.newFolder())
		val useCase = TranslatePageUseCase(
			ocrEngine = ocrEngine,
			textTranslator = translator,
			pageRenderer = renderer,
			cacheStore = cacheStore,
		)
		val coordinator = ReaderTranslationCoordinator(
			lifecycle = lifecycle,
			settingsState = settingsFlow,
			translatePageUseCase = useCase,
			ocrEngine = ocrEngine,
			cacheStore = cacheStore,
		)
		coordinator.attachPageBitmapProvider { page ->
			val delayMs = bitmapLoadDelayMs(page)
			if (delayMs > 0L) delay(delayMs)
			val bitmap = allocateDummyBitmap()
			bitmapRegistry[bitmap] = page.id
			bitmap
		}
		return TestEnv(coordinator, ocrEngine, translator)
	}

	private fun allocateDummyBitmap(): Bitmap {
		val (unsafeInstance, allocateMethod) = unsafeAllocate
		return allocateMethod.invoke(unsafeInstance, Bitmap::class.java) as Bitmap
	}

	private fun createPage(id: Long, index: Int): MangaPage = MangaPage(
		id = id,
		url = "https://example.com/manga/page-$index.jpg",
		preview = null,
		source = TestMangaSource,
	)

	private fun MangaPage.toReaderPage(): ReaderPage = ReaderPage(
		page = this,
		index = id.toInt(),
		chapterId = 1L,
	)

	private suspend fun awaitPageDone(coordinator: ReaderTranslationCoordinator, pageId: Long) {
		coordinator.pageStates.first { states ->
			states[pageId]?.stage == TranslationStage.DONE
		}
	}

	private class TestEnv(
		val coordinator: ReaderTranslationCoordinator,
		val ocrEngine: FakeOcrEngine,
		val translator: FakeComicTextTranslator,
	)

	private class FakeActivityRetainedLifecycle : ActivityRetainedLifecycle {
		private val listeners = CopyOnWriteArrayList<RetainedLifecycle.OnClearedListener>()

		override fun addOnClearedListener(listener: RetainedLifecycle.OnClearedListener) {
			listeners.addIfAbsent(listener)
		}

		override fun removeOnClearedListener(listener: RetainedLifecycle.OnClearedListener) {
			listeners.remove(listener)
		}

		fun clear() {
			for (listener in listeners) {
				listener.onCleared()
			}
			listeners.clear()
		}
	}

	private class FakeOcrEngine(
		private val bitmapRegistry: MutableMap<Bitmap, Long>,
	) : LocalOcrEngine {
		val enteredCallbacks = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
		val finishedCallbacks = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
		val releaseGates = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
		val startedPages = CopyOnWriteArrayList<Long>()
		val completedPages = CopyOnWriteArrayList<Long>()
		val currentConcurrentOcr = AtomicInteger(0)
		val maxConcurrentOcr = AtomicInteger(0)

		override suspend fun isModelReady(sourceLang: OcrSourceLanguage): Boolean = true

		override suspend fun ensureModels(
			sourceLang: OcrSourceLanguage,
			onProgress: (suspend (percent: Int) -> Unit)?,
		) = Unit

		override suspend fun recognizePage(
			bitmap: Bitmap,
			sourceLang: OcrSourceLanguage,
			skipPureSfx: Boolean,
			filterPromoAndCredits: Boolean,
			onModelDownloadProgress: (suspend (percent: Int) -> Unit)?,
		): List<OcrBubbleGroup> {
			val pageId = checkNotNull(bitmapRegistry[bitmap]) { "Unknown test bitmap" }
			val concurrent = currentConcurrentOcr.incrementAndGet()
			maxConcurrentOcr.updateAndGet { max -> maxOf(max, concurrent) }
			try {
				startedPages.add(pageId)
				enteredCallbacks[pageId]?.complete(Unit)
				releaseGates[pageId]?.await()
				completedPages.add(pageId)
				finishedCallbacks[pageId]?.complete(Unit)
				return listOf(
					OcrBubbleGroup(
						id = "bubble-$pageId",
						box = NormalizedBox(0f, 0f, 100f, 100f),
						text = "こんにちは",
						lines = emptyList(),
					),
				)
			} finally {
				currentConcurrentOcr.decrementAndGet()
			}
		}
	}

	private class FakeComicTextTranslator : ComicTextTranslator {
		val enteredCallbacks = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
		val releaseGates = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
		val cancelledSignals = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
		val onEnterHook = ConcurrentHashMap<Long, () -> Unit>()
		val onExitHook = ConcurrentHashMap<Long, () -> Unit>()
		val currentConcurrentTranslate = AtomicInteger(0)
		val maxConcurrentTranslate = AtomicInteger(0)

		override suspend fun translateBubbles(
			bubbles: List<OcrBubbleGroup>,
			settings: TranslationSettingsSnapshot,
		): List<TranslatedTextItem> {
			val pageId = bubbles.first().id.removePrefix("bubble-").toLong()
			val concurrent = currentConcurrentTranslate.incrementAndGet()
			maxConcurrentTranslate.updateAndGet { max -> maxOf(max, concurrent) }
			try {
				onEnterHook[pageId]?.invoke()
				enteredCallbacks[pageId]?.complete(Unit)
				try {
					releaseGates[pageId]?.await()
				} catch (ce: CancellationException) {
					cancelledSignals[pageId]?.complete(true)
					throw ce
				}
				return bubbles.map { bubble ->
					TranslatedTextItem(
						id = bubble.id,
						text = "Merhaba-${bubble.id}",
					)
				}
			} finally {
				onExitHook[pageId]?.invoke()
				currentConcurrentTranslate.decrementAndGet()
			}
		}
	}

	private class FakeTranslatedPageRenderer : TranslatedPageRenderer {
		override suspend fun renderTranslatedPage(
			sourceBitmap: Bitmap,
			bubbles: List<OcrBubbleGroup>,
			translations: List<TranslatedTextItem>,
			outputFile: File,
			targetLang: String,
		): File {
			outputFile.parentFile?.mkdirs()
			outputFile.writeText("rendered:$targetLang:${translations.firstOrNull()?.text.orEmpty()}")
			return outputFile
		}
	}

	private class FakeTranslationCacheStore(
		private val rootDir: File,
	) : TranslationCacheStore {
		private val files = ConcurrentHashMap<String, File>()

		override fun buildCacheKey(
			pageIdentity: String,
			settings: TranslationSettingsSnapshot,
		): String {
			val sanitized = pageIdentity.replace(Regex("[^a-zA-Z0-9._-]"), "_")
			return "${sanitized}_${settings.effectiveCacheSignature().replace(':', '_')}"
		}

		override suspend fun getCachedFile(cacheKey: String): File? {
			return files[cacheKey]?.takeIf { it.isFile && it.length() > 0L }
		}

		override suspend fun getOrCreateTargetFile(cacheKey: String): File {
			return File(rootDir, "$cacheKey.png").also { files[cacheKey] = it }
		}

		override suspend fun clearAll(): Long {
			files.clear()
			rootDir.listFiles()?.forEach { it.delete() }
			return 0L
		}
	}
}
