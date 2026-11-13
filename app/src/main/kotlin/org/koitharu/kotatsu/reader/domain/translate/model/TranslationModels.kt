package org.koitharu.kotatsu.reader.domain.translate.model

import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 0..1000 normalized bounding box matching ToonParrot [ymin, xmin, ymax, xmax] coordinates.
 */
@Serializable
data class NormalizedBox(
	val yMin: Float,
	val xMin: Float,
	val yMax: Float,
	val xMax: Float,
) {
	val width: Float get() = (xMax - xMin).coerceAtLeast(0f)
	val height: Float get() = (yMax - yMin).coerceAtLeast(0f)
	val centerX: Float get() = (xMin + xMax) * 0.5f
	val centerY: Float get() = (yMin + yMax) * 0.5f
	val area: Float get() = width * height

	fun toRectF(imageWidth: Int, imageHeight: Int): RectF = RectF(
		(xMin / 1000f) * imageWidth,
		(yMin / 1000f) * imageHeight,
		(xMax / 1000f) * imageWidth,
		(yMax / 1000f) * imageHeight,
	)

	fun isValid(): Boolean = width > 0.5f && height > 0.5f
}

enum class TranslationProvider {
	GEMINI,
	GOOGLE,
}

enum class OcrSourceLanguage(
	val code: String,
	val modelGroup: String,
) {
	AUTO("auto", "auto"),
	ENGLISH("en", "en"),
	JAPANESE("ja", "cjk"),
	KOREAN("ko", "korean"),
	CHINESE("zh", "cjk"),
	LATIN("latin", "latin"),
	CYRILLIC("ru", "eslav"),
	ARABIC("ar", "arabic");

	companion object {
		fun fromCode(code: String?): OcrSourceLanguage {
			if (code.isNullOrBlank()) return AUTO
			return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO
		}
	}
}

@Serializable
enum class TextBlockKind {
	DIALOGUE,
	NARRATION,
	SFX,
	EMPHASIS,
}

@Serializable
enum class ScanlationFontKey {
	SHANTELL,
	ANTON,
	BANGERS,
	MARKER,
	CINZEL,
}

@Serializable
enum class FontStyleMode {
	NORMAL,
	ITALIC,
}

@Serializable
data class OcrLine(
	val box: NormalizedBox,
	val text: String,
	val confidence: Float = 1f,
	val polygon: List<Float> = emptyList(),
)

@Serializable
data class OcrBubbleGroup(
	val id: String,
	val box: NormalizedBox,
	val text: String,
	val lines: List<OcrLine>,
	val isVertical: Boolean = false,
	val kindHint: TextBlockKind = TextBlockKind.DIALOGUE,
)

@Serializable
data class TextStyleFingerprint(
	val fontFamily: ScanlationFontKey = ScanlationFontKey.SHANTELL,
	val fontWeight: Int = 700,
	val fontStyle: FontStyleMode = FontStyleMode.NORMAL,
	val bgColor: Int = 0xFFFFFFFF.toInt(),
	val textColor: Int = 0xFF111111.toInt(),
	val gradientTopColor: Int? = null,
	val gradientBottomColor: Int? = null,
	val strokeColor: Int = 0xFFFFFFFF.toInt(),
	val strokeWidthEm: Float = 0f,
	val shadowColor: Int? = null,
	val shadowBlurEm: Float = 0f,
	val isDark: Boolean = false,
	val hasTextStroke: Boolean = false,
	val fontSizeCqw: Float = 2.6f,
	val rotationDeg: Float = 0f,
	val skewDeg: Float = 0f,
	val letterSpacingEm: Float = 0f,
	val lineWidthsPct: List<Float> = emptyList(),
	val emphasisLastLineScale: Float = 1f,
)

@Serializable
data class TranslatedTextItem(
	val id: String,
	val text: String,
	val kind: TextBlockKind? = null,
	val isTrash: Boolean = false,
)

@Serializable
data class TranslatedBlock(
	val box: NormalizedBox,
	val text: String,
	val originalText: String = "",
	val lines: List<OcrLine> = emptyList(),
	val kind: TextBlockKind = TextBlockKind.DIALOGUE,
	val style: TextStyleFingerprint = TextStyleFingerprint(),
)

data class TranslationSettingsSnapshot(
	val provider: TranslationProvider = TranslationProvider.GEMINI,
	val sourceLang: OcrSourceLanguage = OcrSourceLanguage.AUTO,
	val targetLang: String = "tr",
	val apiKeys: List<String> = emptyList(),
	val autoTranslate: Boolean = false,
	val prefetchNextPage: Boolean = true,
	val skipPureSfx: Boolean = true,
	val filterPromoAndCredits: Boolean = true,
) {
	fun effectiveCacheSignature(): String = "${sourceLang.code}:${targetLang.lowercase()}:${provider.name}:$skipPureSfx:$filterPromoAndCredits"
}

enum class TranslationStage {
	IDLE,
	DOWNLOADING_MODELS,
	RUNNING_OCR,
	TRANSLATING_TEXT,
	RENDERING_PAGE,
	DONE,
	ERROR,
}

data class PageTranslationState(
	val pageId: Long,
	val stage: TranslationStage,
	val progressPercent: Int = -1,
	val errorMessage: String? = null,
	val translatedFilePath: String? = null,
)

interface LocalOcrEngine {
	suspend fun isModelReady(sourceLang: OcrSourceLanguage): Boolean
	suspend fun ensureModels(
		sourceLang: OcrSourceLanguage,
		onProgress: (suspend (percent: Int) -> Unit)? = null,
	)

	suspend fun recognizePage(
		bitmap: Bitmap,
		sourceLang: OcrSourceLanguage,
		skipPureSfx: Boolean = true,
		filterPromoAndCredits: Boolean = true,
		onModelDownloadProgress: (suspend (percent: Int) -> Unit)? = null,
	): List<OcrBubbleGroup>
}

interface ComicTextTranslator {
	suspend fun translateBubbles(
		bubbles: List<OcrBubbleGroup>,
		settings: TranslationSettingsSnapshot,
	): List<TranslatedTextItem>
}

interface TranslatedPageRenderer {
	suspend fun renderTranslatedPage(
		sourceBitmap: Bitmap,
		bubbles: List<OcrBubbleGroup>,
		translations: List<TranslatedTextItem>,
		outputFile: File,
		targetLang: String,
	): File
}

interface TranslationCacheStore {
	fun buildCacheKey(pageIdentity: String, settings: TranslationSettingsSnapshot): String
	suspend fun getCachedFile(cacheKey: String): File?
	suspend fun getOrCreateTargetFile(cacheKey: String): File
	suspend fun clearAll(): Long
}
