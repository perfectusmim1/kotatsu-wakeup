package org.koitharu.kotatsu.reader.domain.translate

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.net.toFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.ensureRamAtLeast
import org.koitharu.kotatsu.core.util.ext.isAnimatedImage
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.ramAvailable
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.domain.translate.api.MissingGeminiApiKeyException
import org.koitharu.kotatsu.reader.domain.translate.model.ComicTextTranslator
import org.koitharu.kotatsu.reader.domain.translate.model.LocalOcrEngine
import org.koitharu.kotatsu.reader.domain.translate.model.TranslatedPageRenderer
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationCacheStore
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationProvider
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationSettingsSnapshot
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationStage
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface PageTranslationResult {
	data class Translated(val file: File) : PageTranslationResult
	data object NoText : PageTranslationResult
}

interface PipelinePermit {
	suspend fun <T> withPermit(block: suspend () -> T): T
}

internal fun Semaphore.asPipelinePermit(): PipelinePermit = object : PipelinePermit {
	override suspend fun <T> withPermit(block: suspend () -> T): T =
		this@asPipelinePermit.withPermit { block() }
}

internal object TranslatePipelineLog {
	private val isAndroidLogAvailable: Boolean = try {
		Log.isLoggable("KotatsuTranslate", Log.INFO)
		true
	} catch (_: LinkageError) {
		false
	} catch (_: RuntimeException) {
		false
	}

	fun d(tag: String, msg: String) {
		if (isAndroidLogAvailable) {
			Log.d(tag, msg)
		}
	}

	fun i(tag: String, msg: String) {
		if (isAndroidLogAvailable) {
			Log.i(tag, msg)
		}
	}

	fun w(tag: String, msg: String) {
		if (isAndroidLogAvailable) {
			Log.w(tag, msg)
		}
	}

	fun e(tag: String, msg: String, throwable: Throwable? = null) {
		if (isAndroidLogAvailable) {
			if (throwable != null) {
				Log.e(tag, msg, throwable)
			} else {
				Log.e(tag, msg)
			}
		}
	}
}

@Singleton
class TranslatePageUseCase private constructor(
	private val context: Context?,
	private val ocrEngine: LocalOcrEngine,
	private val textTranslator: ComicTextTranslator,
	private val pageRenderer: TranslatedPageRenderer,
	private val cacheStore: TranslationCacheStore,
	private val isTestBitmapMode: Boolean,
) {

	@Inject
	constructor(
		@ApplicationContext context: Context,
		ocrEngine: LocalOcrEngine,
		textTranslator: ComicTextTranslator,
		pageRenderer: TranslatedPageRenderer,
		cacheStore: TranslationCacheStore,
	) : this(
		context = context,
		ocrEngine = ocrEngine,
		textTranslator = textTranslator,
		pageRenderer = pageRenderer,
		cacheStore = cacheStore,
		isTestBitmapMode = false,
	)

	internal constructor(
		ocrEngine: LocalOcrEngine,
		textTranslator: ComicTextTranslator,
		pageRenderer: TranslatedPageRenderer,
		cacheStore: TranslationCacheStore,
	) : this(
		context = null,
		ocrEngine = ocrEngine,
		textTranslator = textTranslator,
		pageRenderer = pageRenderer,
		cacheStore = cacheStore,
		isTestBitmapMode = true,
	)

	suspend operator fun invoke(
		page: MangaPage,
		settings: TranslationSettingsSnapshot,
		loadSourceUri: suspend () -> Uri,
		onProgress: suspend (stage: TranslationStage, progressPercent: Int) -> Unit,
		ocrPermit: PipelinePermit? = null,
		translatePermit: PipelinePermit? = null,
	): PageTranslationResult = executePipeline(
		page = page,
		settings = settings,
		prepareSource = loadSourceUri,
		decodeBitmap = { uri -> decodeSoftwareBitmap(uri) },
		sourceLabel = { uri -> uri.toString() },
		onProgress = onProgress,
		ocrPermit = ocrPermit,
		translatePermit = translatePermit,
	)

	internal suspend fun invokeWithBitmap(
		page: MangaPage,
		settings: TranslationSettingsSnapshot,
		loadSourceBitmap: suspend () -> Bitmap,
		onProgress: suspend (stage: TranslationStage, progressPercent: Int) -> Unit,
		ocrPermit: PipelinePermit? = null,
		translatePermit: PipelinePermit? = null,
	): PageTranslationResult = executePipeline(
		page = page,
		settings = settings,
		prepareSource = {},
		decodeBitmap = { loadSourceBitmap() },
		sourceLabel = { "bitmap://test/${page.id}" },
		onProgress = onProgress,
		ocrPermit = ocrPermit,
		translatePermit = translatePermit,
	)

	private suspend fun <S> executePipeline(
		page: MangaPage,
		settings: TranslationSettingsSnapshot,
		prepareSource: suspend () -> S,
		decodeBitmap: suspend (S) -> Bitmap,
		sourceLabel: (S) -> String,
		onProgress: suspend (stage: TranslationStage, progressPercent: Int) -> Unit,
		ocrPermit: PipelinePermit?,
		translatePermit: PipelinePermit?,
	): PageTranslationResult {
		val totalStartNs = System.nanoTime()
		if (page.url.isAnimatedImage()) {
			TranslatePipelineLog.d(LOG_TAG, "Skipping animated page id=${page.id} url=${page.url}")
			return PageTranslationResult.NoText
		}

		val pageIdentity = "${page.source.name}:${page.id}:${page.url}"
		val cacheKey = cacheStore.buildCacheKey(pageIdentity, settings)
		cacheStore.getCachedFile(cacheKey)?.takeIf { it.isFile && it.length() > 0L }?.let { cached ->
			TranslatePipelineLog.d(LOG_TAG, "Cache hit page=${page.id} file=${cached.name} (${cached.length()} bytes)")
			onProgress(TranslationStage.DONE, 100)
			return PageTranslationResult.Translated(cached)
		}

		if (settings.provider == TranslationProvider.GEMINI && settings.apiKeys.isEmpty()) {
			TranslatePipelineLog.w(LOG_TAG, "Missing Gemini API key for page=${page.id}")
			throw MissingGeminiApiKeyException()
		}

		val source = prepareSource()
		TranslatePipelineLog.d(
			LOG_TAG,
			"Translating page=${page.id} source=${settings.sourceLang} -> ${settings.targetLang} " +
				"provider=${settings.provider} uri=${sourceLabel(source)}",
		)

		var sourceBitmap: Bitmap? = null
		try {
			var ocrMs = 0L
			val bubbles = ocrPermit.runWithPermit {
				currentCoroutineContext().ensureActive()
				if (!ocrEngine.isModelReady(settings.sourceLang)) {
					TranslatePipelineLog.i(LOG_TAG, "OCR models missing for ${settings.sourceLang}, downloading...")
					onProgress(TranslationStage.DOWNLOADING_MODELS, 0)
					ocrEngine.ensureModels(settings.sourceLang) { percent ->
						onProgress(TranslationStage.DOWNLOADING_MODELS, percent)
					}
				}

				onProgress(TranslationStage.RUNNING_OCR, -1)
				val ocrStartNs = System.nanoTime()
				val decoded = decodeBitmap(source)
				sourceBitmap = decoded
				val decodeDoneNs = System.nanoTime()
				val decodeMs = (decodeDoneNs - ocrStartNs) / 1_000_000L
				if (!isTestBitmapMode) {
					TranslatePipelineLog.d(
						LOG_TAG,
						"Decoded page=${page.id} bitmap=${decoded.width}x${decoded.height} config=${decoded.config} (${decodeMs}ms)",
					)
				}

				val recognized = ocrEngine.recognizePage(
					bitmap = decoded,
					sourceLang = settings.sourceLang,
					skipPureSfx = settings.skipPureSfx,
					filterPromoAndCredits = settings.filterPromoAndCredits,
					onModelDownloadProgress = { percent ->
						if (percent >= 100) {
							onProgress(TranslationStage.RUNNING_OCR, -1)
						} else {
							onProgress(TranslationStage.DOWNLOADING_MODELS, percent)
						}
					},
				)
				val ocrEndNs = System.nanoTime()
				ocrMs = (ocrEndNs - ocrStartNs) / 1_000_000L
				val recMs = (ocrEndNs - decodeDoneNs) / 1_000_000L
				TranslatePipelineLog.d(
					LOG_TAG,
					"OCR finished page=${page.id}: bubbles=${recognized.size} ocrMs=${ocrMs}ms " +
						"(decode=${decodeMs}ms, rec=${recMs}ms)",
				)
				recognized
			}

			if (bubbles.isEmpty()) {
				val totalMs = (System.nanoTime() - totalStartNs) / 1_000_000L
				TranslatePipelineLog.i(
					LOG_TAG,
					"No translatable bubbles detected on page=${page.id} (ocrMs=${ocrMs}ms, totalMs=${totalMs}ms)",
				)
				recycleSourceBitmap(sourceBitmap)
				sourceBitmap = null
				onProgress(TranslationStage.DONE, 100)
				return PageTranslationResult.NoText
			}

			onProgress(TranslationStage.TRANSLATING_TEXT, -1)
			val transStartNs = System.nanoTime()
			val translations = translatePermit.runWithPermit {
				currentCoroutineContext().ensureActive()
				textTranslator.translateBubbles(bubbles, settings)
			}
			val transMs = (System.nanoTime() - transStartNs) / 1_000_000L
			val validTranslations = translations.count { !it.isTrash && it.text.isNotBlank() }
			TranslatePipelineLog.d(
				LOG_TAG,
				"Translation finished page=${page.id}: valid=$validTranslations/${translations.size} " +
					"provider=${settings.provider} transMs=${transMs}ms",
			)
			if (validTranslations == 0) {
				val totalMs = (System.nanoTime() - totalStartNs) / 1_000_000L
				TranslatePipelineLog.i(
					LOG_TAG,
					"All bubbles filtered/empty after translation on page=${page.id} " +
						"(ocrMs=${ocrMs}ms, transMs=${transMs}ms, totalMs=${totalMs}ms)",
				)
				recycleSourceBitmap(sourceBitmap)
				sourceBitmap = null
				onProgress(TranslationStage.DONE, 100)
				return PageTranslationResult.NoText
			}

			onProgress(TranslationStage.RENDERING_PAGE, -1)
			val targetFile = cacheStore.getOrCreateTargetFile(cacheKey)
			val renderStartNs = System.nanoTime()
			return try {
				val bitmapToRender = checkNotNull(sourceBitmap)
				val rendered = withContext(Dispatchers.Default) {
					pageRenderer.renderTranslatedPage(
						sourceBitmap = bitmapToRender,
						bubbles = bubbles,
						translations = translations,
						outputFile = targetFile,
						targetLang = settings.targetLang,
					)
				}
				val renderMs = (System.nanoTime() - renderStartNs) / 1_000_000L
				val totalMs = (System.nanoTime() - totalStartNs) / 1_000_000L
				if (!rendered.isFile || rendered.length() <= 0L) {
					TranslatePipelineLog.w(
						LOG_TAG,
						"Rendered output missing/empty for page=${page.id}: ${rendered.absolutePath} " +
							"(ocrMs=${ocrMs}ms, transMs=${transMs}ms, renderMs=${renderMs}ms, totalMs=${totalMs}ms)",
					)
					if (targetFile.exists()) {
						targetFile.delete()
					}
					recycleSourceBitmap(sourceBitmap)
					sourceBitmap = null
					onProgress(TranslationStage.DONE, 100)
					PageTranslationResult.NoText
				} else {
					TranslatePipelineLog.i(
						LOG_TAG,
						"Page=${page.id} translated & baked: ${rendered.name} (${rendered.length()} bytes) " +
							"ocrMs=${ocrMs}ms transMs=${transMs}ms renderMs=${renderMs}ms totalMs=${totalMs}ms",
					)
					recycleSourceBitmap(sourceBitmap)
					sourceBitmap = null
					onProgress(TranslationStage.DONE, 100)
					PageTranslationResult.Translated(rendered)
				}
			} catch (t: Throwable) {
				TranslatePipelineLog.e(LOG_TAG, "Rendering failed for page=${page.id}", t)
				if (targetFile.exists()) {
					targetFile.delete()
				}
				throw t
			}
		} finally {
			recycleSourceBitmap(sourceBitmap)
		}
	}

	private suspend inline fun <T> PipelinePermit?.runWithPermit(
		crossinline block: suspend () -> T,
	): T = if (this != null) {
		withPermit { block() }
	} else {
		block()
	}

	private fun recycleSourceBitmap(bitmap: Bitmap?) {
		if (bitmap == null || isTestBitmapMode) return
		if (!bitmap.isRecycled) {
			bitmap.recycle()
		}
	}

	private suspend fun decodeSoftwareBitmap(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
		runInterruptible {
			val decoded = when {
				uri.isFileUri() -> decodeFileSoftwareBitmap(uri.toFile())
				uri.isZipUri() -> decodeZipSoftwareBitmap(uri)
				uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) -> decodeContentSoftwareBitmap(uri)
				else -> error("Unsupported page URI scheme for translation: ${uri.scheme}")
			}
			ensureSoftwareArgb8888(decoded)
		}
	}

	@Blocking
	private fun decodeContentSoftwareBitmap(uri: Uri): Bitmap {
		val appContext = checkNotNull(context) { "Context is required to decode content URIs" }
		val maxBytes = effectiveMaxBitmapBytes()
		val mime = appContext.contentResolver.getType(uri)?.toMimeTypeOrNull()
		val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
			?: error("Unable to open content URI: $uri")
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
			mime?.subtype != FORMAT_AVIF &&
			mime?.subtype != FORMAT_JXL
		) {
			val source = ImageDecoder.createSource(bytes)
			return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
				decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
				applyScaledTargetIfNeeded(decoder, info.size.width, info.size.height, maxBytes)
			}
		}
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
		val opts = BitmapFactory.Options().apply {
			inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxBytes)
			inPreferredConfig = Bitmap.Config.ARGB_8888
		}
		return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)) {
			"Unable to decode content URI bitmap: $uri"
		}
	}

	@Blocking
	private fun decodeFileSoftwareBitmap(file: File): Bitmap {
		val appContext = checkNotNull(context) { "Context is required to decode image files" }
		val maxBytes = effectiveMaxBitmapBytes()
		if (BitmapDecoderCompat.isJxl(file) || BitmapDecoderCompat.isAvif(file)) {
			appContext.ensureRamAtLeast(BitmapDecoderCompat.estimateDecodedSize(file))
			return BitmapDecoderCompat.decode(file)
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
				decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
				applyScaledTargetIfNeeded(decoder, info.size.width, info.size.height, maxBytes)
			}
		}
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeFile(file.absolutePath, bounds)
		val opts = BitmapFactory.Options().apply {
			inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxBytes)
			inPreferredConfig = Bitmap.Config.ARGB_8888
		}
		return checkNotNull(BitmapFactory.decodeFile(file.absolutePath, opts)) {
			"Unable to decode image file: ${file.name}"
		}
	}

	@Blocking
	private fun decodeZipSoftwareBitmap(uri: Uri): Bitmap {
		val appContext = checkNotNull(context) { "Context is required to decode zip entries" }
		val maxBytes = effectiveMaxBitmapBytes()
		val archivePath = requireNotNull(uri.schemeSpecificPart)
		val entryName = requireNotNull(uri.fragment)
		return ZipFile(archivePath).use { zip ->
			val entry = checkNotNull(zip.getEntry(entryName)) {
				"Zip entry not found: $entryName"
			}
			appContext.ensureRamAtLeast((entry.size * 2L).coerceAtMost(maxBytes))
			val mime = MimeTypes.getMimeTypeFromExtension(entry.name)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
				mime?.subtype != FORMAT_AVIF &&
				mime?.subtype != FORMAT_JXL
			) {
				zip.getInputStream(entry).use { stream ->
					val bytes = stream.readBytes()
					val source = ImageDecoder.createSource(bytes)
					ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
						decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
						applyScaledTargetIfNeeded(decoder, info.size.width, info.size.height, maxBytes)
					}
				}
			} else {
				zip.getInputStream(entry).use { stream ->
					BitmapDecoderCompat.decode(stream, mime, isMutable = true)
				}
			}
		}
	}

	private fun effectiveMaxBitmapBytes(): Long {
		val appContext = checkNotNull(context) { "Context is required to compute RAM budget" }
		val ramBudget = (appContext.ramAvailable / 4L).coerceAtLeast(MIN_RAM_BUDGET_BYTES)
		return min(MAX_BITMAP_BYTES, ramBudget)
	}

	private fun applyScaledTargetIfNeeded(
		decoder: ImageDecoder,
		width: Int,
		height: Int,
		maxBytes: Long,
	) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || width <= 0 || height <= 0) return
		val scale = computeLinearScale(width, height, maxBytes)
		if (scale < 0.999f) {
			decoder.setTargetSize(
				(width * scale).roundToInt().coerceAtLeast(1),
				(height * scale).roundToInt().coerceAtLeast(1),
			)
		}
	}

	private fun computeLinearScale(width: Int, height: Int, maxBytes: Long): Float {
		var scale = 1f
		if (width > MAX_BITMAP_WIDTH) {
			scale = min(scale, MAX_BITMAP_WIDTH.toFloat() / width.toFloat())
		}
		val rawBytes = width.toLong() * height.toLong() * 4L
		if (rawBytes > maxBytes && rawBytes > 0L) {
			val byteScale = kotlin.math.sqrt(maxBytes.toDouble() / rawBytes.toDouble()).toFloat()
			scale = min(scale, byteScale)
		}
		return scale
	}

	private fun computeSampleSize(width: Int, height: Int, maxBytes: Long): Int {
		var sampleSize = 1
		if (width <= 0 || height <= 0) return sampleSize
		while (
			(width / sampleSize) > MAX_BITMAP_WIDTH ||
			((width / sampleSize).toLong() * (height / sampleSize).toLong() * 4L) > maxBytes
		) {
			sampleSize *= 2
		}
		return sampleSize
	}

	private fun ensureSoftwareArgb8888(bitmap: Bitmap): Bitmap {
		val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
			bitmap.config == Bitmap.Config.HARDWARE
		if (!isHardware && bitmap.config == Bitmap.Config.ARGB_8888) {
			return bitmap
		}
		val copy = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) {
			"Failed to convert bitmap to software ARGB_8888"
		}
		bitmap.recycle()
		return copy
	}

	private companion object {
		const val LOG_TAG = "KotatsuTranslate"
		const val FORMAT_AVIF = "avif"
		const val FORMAT_JXL = "jxl"
		const val MAX_BITMAP_WIDTH = 2400
		const val MAX_BITMAP_BYTES = 64L * 1024L * 1024L
		const val MIN_RAM_BUDGET_BYTES = 16L * 1024L * 1024L
	}
}
