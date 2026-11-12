package org.koitharu.kotatsu.reader.domain.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.net.toFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.ensureRamAtLeast
import org.koitharu.kotatsu.core.util.ext.isAnimatedImage
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.ramAvailable
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

@Singleton
class TranslatePageUseCase @Inject constructor(
	@ApplicationContext private val context: Context,
	private val ocrEngine: LocalOcrEngine,
	private val textTranslator: ComicTextTranslator,
	private val pageRenderer: TranslatedPageRenderer,
	private val cacheStore: TranslationCacheStore,
) {

	suspend operator fun invoke(
		page: MangaPage,
		settings: TranslationSettingsSnapshot,
		loadSourceUri: suspend () -> Uri,
		onProgress: suspend (stage: TranslationStage, progressPercent: Int) -> Unit,
	): PageTranslationResult {
		if (page.url.isAnimatedImage()) {
			return PageTranslationResult.NoText
		}

		val pageIdentity = "${page.source.name}:${page.id}:${page.url}"
		val cacheKey = cacheStore.buildCacheKey(pageIdentity, settings)
		cacheStore.getCachedFile(cacheKey)?.takeIf { it.isFile && it.length() > 0L }?.let { cached ->
			onProgress(TranslationStage.DONE, 100)
			return PageTranslationResult.Translated(cached)
		}

		if (settings.provider == TranslationProvider.GEMINI && settings.apiKeys.isEmpty()) {
			throw MissingGeminiApiKeyException()
		}

		val sourceUri = loadSourceUri()
		val sourceBitmap = decodeSoftwareBitmap(sourceUri)
		try {
			if (!ocrEngine.isModelReady(settings.sourceLang)) {
				onProgress(TranslationStage.DOWNLOADING_MODELS, 0)
				ocrEngine.ensureModels(settings.sourceLang) { percent ->
					onProgress(TranslationStage.DOWNLOADING_MODELS, percent)
				}
			}

			onProgress(TranslationStage.RUNNING_OCR, -1)
			val bubbles = ocrEngine.recognizePage(
				bitmap = sourceBitmap,
				sourceLang = settings.sourceLang,
				skipPureSfx = settings.skipPureSfx,
				filterPromoAndCredits = settings.filterPromoAndCredits,
				onModelDownloadProgress = { percent ->
					onProgress(TranslationStage.DOWNLOADING_MODELS, percent)
				},
			)
			if (bubbles.isEmpty()) {
				onProgress(TranslationStage.DONE, 100)
				return PageTranslationResult.NoText
			}

			onProgress(TranslationStage.TRANSLATING_TEXT, -1)
			val translations = textTranslator.translateBubbles(bubbles, settings)
			if (translations.none { !it.isTrash && it.text.isNotBlank() }) {
				onProgress(TranslationStage.DONE, 100)
				return PageTranslationResult.NoText
			}

			onProgress(TranslationStage.RENDERING_PAGE, -1)
			val targetFile = cacheStore.getOrCreateTargetFile(cacheKey)
			return try {
				val rendered = pageRenderer.renderTranslatedPage(
					sourceBitmap = sourceBitmap,
					bubbles = bubbles,
					translations = translations,
					outputFile = targetFile,
					targetLang = settings.targetLang,
				)
				if (!rendered.isFile || rendered.length() <= 0L) {
					if (targetFile.exists()) {
						targetFile.delete()
					}
					onProgress(TranslationStage.DONE, 100)
					PageTranslationResult.NoText
				} else {
					onProgress(TranslationStage.DONE, 100)
					PageTranslationResult.Translated(rendered)
				}
			} catch (t: Throwable) {
				if (targetFile.exists()) {
					targetFile.delete()
				}
				throw t
			}
		} finally {
			if (!sourceBitmap.isRecycled) {
				sourceBitmap.recycle()
			}
		}
	}

	private suspend fun decodeSoftwareBitmap(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
		runInterruptible {
			val decoded = when {
				uri.isFileUri() -> decodeFileSoftwareBitmap(uri.toFile())
				uri.isZipUri() -> decodeZipSoftwareBitmap(uri)
				else -> error("Unsupported page URI scheme for translation: ${uri.scheme}")
			}
			ensureSoftwareArgb8888(decoded)
		}
	}

	@Blocking
	private fun decodeFileSoftwareBitmap(file: File): Bitmap {
		val maxBytes = effectiveMaxBitmapBytes()
		if (BitmapDecoderCompat.isJxl(file) || BitmapDecoderCompat.isAvif(file)) {
			context.ensureRamAtLeast(BitmapDecoderCompat.estimateDecodedSize(file))
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
		val maxBytes = effectiveMaxBitmapBytes()
		val archivePath = requireNotNull(uri.schemeSpecificPart)
		val entryName = requireNotNull(uri.fragment)
		return ZipFile(archivePath).use { zip ->
			val entry = checkNotNull(zip.getEntry(entryName)) {
				"Zip entry not found: $entryName"
			}
			context.ensureRamAtLeast((entry.size * 2L).coerceAtMost(maxBytes))
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
		val ramBudget = (context.ramAvailable / 3L).coerceAtLeast(MIN_RAM_BUDGET_BYTES)
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
		const val FORMAT_AVIF = "avif"
		const val FORMAT_JXL = "jxl"
		const val MAX_BITMAP_WIDTH = 2400
		const val MAX_BITMAP_BYTES = 64L * 1024L * 1024L
		const val MIN_RAM_BUDGET_BYTES = 16L * 1024L * 1024L
	}
}
