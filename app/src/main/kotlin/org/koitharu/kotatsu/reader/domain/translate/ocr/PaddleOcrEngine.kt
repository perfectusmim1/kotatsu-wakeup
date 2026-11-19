package org.koitharu.kotatsu.reader.domain.translate.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.reader.domain.translate.model.LocalOcrEngine
import org.koitharu.kotatsu.reader.domain.translate.model.NormalizedBox
import org.koitharu.kotatsu.reader.domain.translate.model.OcrBubbleGroup
import org.koitharu.kotatsu.reader.domain.translate.model.OcrLine
import org.koitharu.kotatsu.reader.domain.translate.model.OcrSourceLanguage
import org.koitharu.kotatsu.reader.domain.translate.model.TextBlockKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Yerel PaddleOCR (ONNX Runtime Android) motoru.
 * Uzun webtoon şeritlerini örtüşmeli dilimlerle tarar, split-word kurtarma ve
 * balon gruplama uygular; görseller hiçbir uzak sunucuya gönderilmez.
 */
@Singleton
class PaddleOcrEngine @Inject constructor(
	private val modelManager: OcrModelManager,
) : LocalOcrEngine {

	private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
	private val sessionMutex = Mutex()
	private val inferenceMutex = Mutex()

	private var detSession: OrtSession? = null
	private var recSession: OrtSession? = null
	private var activeRecGroup: String? = null
	private var activeDictionary: List<String> = emptyList()
	private val lastAutoGroup = AtomicReference<String?>(null)

	override suspend fun isModelReady(sourceLang: OcrSourceLanguage): Boolean = withContext(Dispatchers.IO) {
		modelManager.isModelReady(sourceLang)
	}

	override suspend fun ensureModels(
		sourceLang: OcrSourceLanguage,
		onProgress: (suspend (percent: Int) -> Unit)?,
	) {
		modelManager.ensureModels(sourceLang, onProgress)
	}

	override suspend fun recognizePage(
		bitmap: Bitmap,
		sourceLang: OcrSourceLanguage,
		skipPureSfx: Boolean,
		filterPromoAndCredits: Boolean,
		onModelDownloadProgress: (suspend (percent: Int) -> Unit)?,
	): List<OcrBubbleGroup> = recognizePage(
		bitmap = bitmap,
		sourceLang = sourceLang,
		skipPureSfx = skipPureSfx,
		filterPromoAndCredits = filterPromoAndCredits,
		onModelDownloadProgress = onModelDownloadProgress,
		sourceHintLocale = null,
	)

	override suspend fun recognizePage(
		bitmap: Bitmap,
		sourceLang: OcrSourceLanguage,
		skipPureSfx: Boolean,
		filterPromoAndCredits: Boolean,
		onModelDownloadProgress: (suspend (percent: Int) -> Unit)?,
		sourceHintLocale: String?,
	): List<OcrBubbleGroup> {
		val width = bitmap.width
		val height = bitmap.height
		if (width <= 0 || height <= 0) {
			Log.w(LOG_TAG, "recognizePage: invalid bitmap dimensions ${width}x${height}")
			return emptyList()
		}
		modelManager.ensureModels(sourceLang, onModelDownloadProgress)

		return inferenceMutex.withLock {
			val det = loadOrReuseDetSession()
			var copiedBitmap: Bitmap? = null
			val readableBitmap = try {
				if (bitmap.config != Bitmap.Config.ARGB_8888) {
					val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
						?: throw IllegalStateException("Bitmap.copy(ARGB_8888) returned null")
					copiedBitmap = copy
					copy
				} else {
					bitmap
				}
			} catch (t: Throwable) {
				throw IllegalStateException("Failed to read page bitmap for OCR", t)
			}

			try {
				val detRegions = withContext(Dispatchers.Default) {
					detectAllRegions(readableBitmap, width, height, det)
				}
				Log.d(
					LOG_TAG,
					"recognizePage($sourceLang, hint=$sourceHintLocale): det regions=${detRegions.size} on ${width}x${height}",
				)
				if (detRegions.isEmpty()) {
					return@withLock emptyList()
				}

				val resolvedGroup = if (sourceLang == OcrSourceLanguage.AUTO) {
					resolveAutoScriptGroup(
						bitmap = readableBitmap,
						width = width,
						height = height,
						detRegions = detRegions,
						onModelDownloadProgress = onModelDownloadProgress,
						sourceHintLocale = sourceHintLocale,
					)
				} else {
					sourceLang.modelGroup
				}

				val (rec, dictionary) = loadOrReuseRecSession(resolvedGroup)
				withContext(Dispatchers.Default) {
					val rawLines = recognizeRegions(
						bitmap = readableBitmap,
						width = width,
						height = height,
						regions = detRegions,
						recSession = rec,
						dictionary = dictionary,
						modelGroup = resolvedGroup,
					)
					val totalPixels = width.toLong() * height.toLong()
					val argbPixels = if (rawLines.isNotEmpty() && totalPixels in 1L..MAX_GROUPING_PIXELS) {
						IntArray(totalPixels.toInt()).also { pixels ->
							readableBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
						}
					} else {
						null
					}
					val bubbles = groupLinesIntoBubbles(rawLines, width, height, argbPixels)
					val filtered = TextBlockFilters.filterBubbles(
						bubbles = bubbles,
						skipPureSfx = skipPureSfx,
						filterPromoAndCredits = filterPromoAndCredits,
					)
					Log.d(
						LOG_TAG,
						"recognizePage(group=$resolvedGroup): rawLines=${rawLines.size} -> " +
							"bubbles=${bubbles.size} -> filtered=${filtered.size}" +
							(rawLines.take(3).takeIf { it.isNotEmpty() }?.let { sample ->
								" sample=${sample.joinToString(" | ") { "'${it.text.take(24)}'(${it.confidence.roundToInt()}%)" }}"
							} ?: ""),
					)
					filtered
				}
			} finally {
				copiedBitmap?.recycle()
			}
		}
	}

	private data class AutoProbeResult(
		val group: String,
		val avgConfidence: Float,
		val validCharRatio: Float,
		val scriptPurity: Float,
		val totalScriptLetters: Int,
		val usableLines: Int,
		val combinedScore: Float,
		val sampleText: String,
	)

	private suspend fun resolveAutoScriptGroup(
		bitmap: Bitmap,
		width: Int,
		height: Int,
		detRegions: List<DetRegion>,
		onModelDownloadProgress: (suspend (percent: Int) -> Unit)?,
		sourceHintLocale: String? = null,
	): String {
		val hintGroup = resolveHintGroup(sourceHintLocale)
		val probeRegions = selectAutoProbeRegions(detRegions)
		if (probeRegions.isEmpty()) {
			return hintGroup ?: lastAutoGroup.get() ?: OcrModelManager.DEFAULT_AUTO_GROUP
		}

		if (hintGroup != null) {
			try {
				modelManager.ensureGroupModels(hintGroup, onModelDownloadProgress)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (t: Throwable) {
				Log.w(LOG_TAG, "AUTO hint group '$hintGroup' download failed: ${t.message}")
			}
		}

		val readyGroups = withContext(Dispatchers.IO) {
			modelManager.readyGroupsInPriority(preferredFirst = hintGroup ?: lastAutoGroup.get())
		}
		var bestResult: AutoProbeResult? = null
		var hintResult: AutoProbeResult? = null
		val probedGroups = HashSet<String>()

		for (group in readyGroups) {
			currentCoroutineContext().ensureActive()
			probedGroups.add(group)
			val result = probeGroup(bitmap, width, height, probeRegions, group)
			Log.d(
				LOG_TAG,
				"AUTO probe ready '$group': usable=${result.usableLines}/${probeRegions.size}, " +
					"avgConf=${"%.1f".format(result.avgConfidence)}, valid=${"%.2f".format(result.validCharRatio)}, " +
					"purity=${"%.2f".format(result.scriptPurity)}, scriptLetters=${result.totalScriptLetters}, " +
					"score=${"%.1f".format(result.combinedScore)}, sample='${result.sampleText}'",
			)
			if (group == hintGroup) {
				hintResult = result
			}
			if (bestResult == null || result.combinedScore > bestResult.combinedScore) {
				bestResult = result
			}
			if (isStrongAutoMatch(result, probeRegions.size)) {
				lastAutoGroup.set(group)
				Log.i(LOG_TAG, "AUTO fast-accepted '$group' (no extra downloads)")
				return group
			}
			// Source-locale hint match: don't trigger cross-script probes on SFX-heavy or number-heavy pages when hint is clearly legible
			if (
				group == hintGroup &&
				result.usableLines >= 1 &&
				(
					(result.avgConfidence >= 56f &&
						result.validCharRatio >= 0.72f &&
						result.scriptPurity >= 0.70f &&
						result.totalScriptLetters >= 4) ||
						(result.avgConfidence >= 80f &&
							result.validCharRatio >= 0.45f &&
							result.scriptPurity >= 0.90f &&
							result.totalScriptLetters >= 16)
					)
			) {
				lastAutoGroup.set(group)
				Log.i(LOG_TAG, "AUTO accepted source-hint '$group' (score=${"%.1f".format(result.combinedScore)})")
				return group
			}
		}

		val currentBest = bestResult
		if (currentBest != null && isAcceptableAutoMatch(currentBest)) {
			lastAutoGroup.set(currentBest.group)
			Log.i(LOG_TAG, "AUTO selected already-ready '${currentBest.group}' (score=${"%.1f".format(currentBest.combinedScore)})")
			return currentBest.group
		}

		// If source hint is Latin/English and we haven't found strong evidence of another script yet,
		// avoid letting 1-2 stylized SFX detections hijack the whole page into korean/cjk unless strong match occurs.
		val isLatinHint = hintGroup == "en" || hintGroup == "latin"

		for (candidateGroup in OcrModelManager.AUTO_DOWNLOAD_FALLBACK_GROUPS) {
			if (candidateGroup in probedGroups) continue
			currentCoroutineContext().ensureActive()
			Log.i(
				LOG_TAG,
				"AUTO downloading fallback group '$candidateGroup' " +
					"(best so far=${bestResult?.group} conf=${"%.1f".format(bestResult?.avgConfidence ?: 0f)})",
			)
			try {
				modelManager.ensureGroupModels(candidateGroup, onModelDownloadProgress)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (t: Throwable) {
				Log.w(LOG_TAG, "AUTO fallback download failed for '$candidateGroup': ${t.message}")
				continue
			}
			probedGroups.add(candidateGroup)
			val result = probeGroup(bitmap, width, height, probeRegions, candidateGroup)
			Log.d(
				LOG_TAG,
				"AUTO probe downloaded '$candidateGroup': usable=${result.usableLines}/${probeRegions.size}, " +
					"avgConf=${"%.1f".format(result.avgConfidence)}, valid=${"%.2f".format(result.validCharRatio)}, " +
					"purity=${"%.2f".format(result.scriptPurity)}, scriptLetters=${result.totalScriptLetters}, " +
					"score=${"%.1f".format(result.combinedScore)}, sample='${result.sampleText}'",
			)
			if (bestResult == null || result.combinedScore > bestResult.combinedScore) {
				bestResult = result
			}
			if (isStrongAutoMatch(result, probeRegions.size) || (!isLatinHint && isAcceptableAutoMatch(result))) {
				break
			}
		}

		val winner = when {
			isLatinHint && bestResult != null && bestResult.group != hintGroup -> {
				val strongCrossScript = bestResult.usableLines >= 2 &&
					bestResult.avgConfidence >= 72f &&
					bestResult.scriptPurity >= 0.85f &&
					bestResult.totalScriptLetters >= 8
				if (strongCrossScript || (hintResult?.usableLines ?: 0) == 0 && isAcceptableAutoMatch(bestResult)) {
					bestResult.group
				} else {
					hintGroup ?: bestResult.group
				}
			}

			bestResult != null -> bestResult.group
			else -> hintGroup ?: OcrModelManager.DEFAULT_AUTO_GROUP
		}
		lastAutoGroup.set(winner)
		Log.i(
			LOG_TAG,
			"AUTO winner='$winner' (score=${"%.1f".format(bestResult?.combinedScore ?: 0f)}, sample='${bestResult?.sampleText.orEmpty()}')",
		)
		return winner
	}

	private suspend fun probeGroup(
		bitmap: Bitmap,
		width: Int,
		height: Int,
		probeRegions: List<DetRegion>,
		modelGroup: String,
	): AutoProbeResult {
		val (rec, dictionary) = loadOrReuseRecSession(modelGroup)
		return withContext(Dispatchers.Default) {
			val lines = recognizeRegions(
				bitmap = bitmap,
				width = width,
				height = height,
				regions = probeRegions,
				recSession = rec,
				dictionary = dictionary,
				modelGroup = modelGroup,
				minConfidence = 28f,
			)
			scoreProbeLines(modelGroup, lines)
		}
	}

	private fun selectAutoProbeRegions(detRegions: List<DetRegion>): List<DetRegion> =
		selectAutoProbeRegionsStatic(detRegions)

	private fun scoreProbeLines(modelGroup: String, lines: List<RawOcrLine>): AutoProbeResult {
		var confSum = 0f
		var usableCount = 0
		var totalLetters = 0
		var totalNonSpace = 0
		var totalScriptLetters = 0
		var bestSample = ""
		var bestSampleLen = -1

		for (line in lines) {
			val cleaned = TextBlockFilters.cleanOcrText(line.text)
			if (cleaned.isEmpty()) continue
			val nonSpace = cleaned.count { !it.isWhitespace() }
			val letters = TextBlockFilters.countLetters(cleaned)
			val scriptLetters = countScriptLetters(cleaned, modelGroup)
			if (letters == 0) continue

			totalLetters += letters
			totalNonSpace += nonSpace
			totalScriptLetters += scriptLetters
			confSum += line.confidence
			usableCount++
			if (scriptLetters > bestSampleLen) {
				bestSampleLen = scriptLetters
				bestSample = cleaned.take(32)
			}
		}

		val avgConf = if (usableCount > 0) confSum / usableCount else 0f
		val validRatio = if (totalNonSpace > 0) totalLetters.toFloat() / totalNonSpace else 0f
		val scriptPurity = if (totalLetters > 0) totalScriptLetters.toFloat() / totalLetters else 0f
		val lengthFactor = when {
			totalScriptLetters <= 0 -> 0.05f
			totalScriptLetters == 1 -> 0.35f
			else -> min(1.25f, 0.55f + 0.14f * min(usableCount, 5))
		}
		val combinedScore = avgConf * (0.40f + 0.60f * validRatio) * (0.35f + 0.65f * scriptPurity) * lengthFactor
		return AutoProbeResult(
			group = modelGroup,
			avgConfidence = avgConf,
			validCharRatio = validRatio,
			scriptPurity = scriptPurity,
			totalScriptLetters = totalScriptLetters,
			usableLines = usableCount,
			combinedScore = combinedScore,
			sampleText = bestSample,
		)
	}

	private fun isStrongAutoMatch(result: AutoProbeResult, probeCount: Int): Boolean {
		val minLines = if (probeCount <= 1) 1 else 2
		val singleClearBubble = result.usableLines == 1 &&
			result.avgConfidence >= 82f &&
			result.validCharRatio >= 0.78f &&
			result.scriptPurity >= 0.80f &&
			result.totalScriptLetters >= 4
		val multiLineStrong = result.usableLines >= minLines &&
			result.avgConfidence >= 72f &&
			result.validCharRatio >= 0.72f &&
			result.scriptPurity >= 0.55f &&
			result.totalScriptLetters >= max(3, minLines * 2)
		return multiLineStrong || singleClearBubble
	}

	private fun isAcceptableAutoMatch(result: AutoProbeResult): Boolean =
		isAcceptableAutoMatch(
			usableLines = result.usableLines,
			avgConfidence = result.avgConfidence,
			validCharRatio = result.validCharRatio,
			scriptPurity = result.scriptPurity,
			totalScriptLetters = result.totalScriptLetters,
		)

	private fun countScriptLetters(text: String, modelGroup: String): Int {
		var count = 0
		for (ch in text) {
			if (!ch.isLetter()) continue
			val matches = when (modelGroup) {
				"en" -> ch in 'A'..'Z' || ch in 'a'..'z'
				"latin" -> {
					val block = Character.UnicodeBlock.of(ch)
					block == Character.UnicodeBlock.BASIC_LATIN ||
						block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT ||
						block == Character.UnicodeBlock.LATIN_EXTENDED_A ||
						block == Character.UnicodeBlock.LATIN_EXTENDED_B
				}
				"cjk" -> {
					val block = Character.UnicodeBlock.of(ch)
					block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
						block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
						block == Character.UnicodeBlock.HIRAGANA ||
						block == Character.UnicodeBlock.KATAKANA
				}
				"korean" -> {
					val block = Character.UnicodeBlock.of(ch)
					block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
						block == Character.UnicodeBlock.HANGUL_JAMO ||
						block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
				}
				"eslav" -> Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CYRILLIC
				"arabic" -> {
					val block = Character.UnicodeBlock.of(ch)
					block == Character.UnicodeBlock.ARABIC ||
						block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
						block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
				}
				else -> ch.isLetter()
			}
			if (matches) count++
		}
		return count
	}

	private suspend fun loadOrReuseDetSession(): OrtSession = sessionMutex.withLock {
		val currentDet = detSession
		if (currentDet != null) return@withLock currentDet
		val detFile = modelManager.detModelFile
		return@withLock withContext(Dispatchers.IO) {
			val options = createSessionOptions()
			try {
				env.createSession(detFile.absolutePath, options).also {
					detSession = it
					Log.d(LOG_TAG, "Loaded det session from ${detFile.name} (${detFile.length()} bytes)")
				}
			} finally {
				options.close()
			}
		}
	}

	private suspend fun loadOrReuseRecSession(modelGroup: String): Pair<OrtSession, List<String>> = sessionMutex.withLock {
		val currentRec = recSession
		if (currentRec != null && activeRecGroup == modelGroup && activeDictionary.isNotEmpty()) {
			return@withLock currentRec to activeDictionary
		}
		val files = modelManager.getModelFilesForGroup(modelGroup)
		return@withLock withContext(Dispatchers.IO) {
			val options = createSessionOptions()
			try {
				currentRec?.close()
				recSession = null
				val rec = env.createSession(files.recModel.absolutePath, options).also {
					recSession = it
					activeRecGroup = files.modelGroup
				}
				val dict = modelManager.loadDictionaryForGroup(files.modelGroup).also {
					activeDictionary = it
				}
				Log.d(
					LOG_TAG,
					"Loaded rec session group='${files.modelGroup}' (${files.recModel.length()} bytes, dictSize=${dict.size})",
				)
				rec to dict
			} finally {
				options.close()
			}
		}
	}

	private fun createSessionOptions(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
		val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
		setIntraOpNumThreads(min(4, max(2, cores / 2)))
		setInterOpNumThreads(1)
		setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
		setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
		setMemoryPatternOptimization(true)
		setCPUArenaAllocator(true)
	}

	internal data class DetRegion(
		val x0: Float,
		val y0: Float,
		val x1: Float,
		val y1: Float,
		val cx: Float = (x0 + x1) * 0.5f,
		val cy: Float = (y0 + y1) * 0.5f,
		val obbW: Float = 0f,
		val obbH: Float = 0f,
		val angleDeg: Float = 0f,
		val edgeClipped: Boolean = false,
	) {
		val width: Float get() = x1 - x0
		val height: Float get() = y1 - y0
		val area: Float get() = max(0f, width) * max(0f, height)
	}

	private enum class CropOrientation {
		HORIZONTAL,
		ROTATE_90_CCW,
		ROTATE_90_CW,
		TILTED_OBB,
	}

	private suspend fun detectAllRegions(
		bitmap: Bitmap,
		width: Int,
		height: Int,
		detSession: OrtSession,
	): List<DetRegion> {
		val scale = if (width > DET_MAX_WIDTH) DET_MAX_WIDTH.toFloat() / width else 1f
		val detW = max(32, ((width * scale) / 32f).roundToInt() * 32)
		val sliceSrcH = (DET_SLICE_H / scale).roundToInt().coerceAtLeast(1)
		val stepSrc = ((DET_SLICE_H - DET_SLICE_OVERLAP) / scale).roundToInt().coerceAtLeast(1)

		val detInputName = detSession.inputNames.first()
		val slicePixels = IntArray(width * min(sliceSrcH, height))

		var detFloatArray = FloatArray(0)
		var detDirectBuf: FloatBuffer? = null
		var detProbArray = FloatArray(0)
		var bfsLabels = IntArray(0)
		var bfsQueue = IntArray(0)

		val detected = ArrayList<DetRegion>()
		var sy = 0

		while (sy < height) {
			currentCoroutineContext().ensureActive()
			val srcH = min(sliceSrcH, height - sy)
			val isFirst = sy == 0
			val isLast = sy + srcH >= height
			val detH = max(32, ((srcH * scale) / 32f).roundToInt() * 32)

			bitmap.getPixels(slicePixels, 0, width, 0, sy, width, srcH)

			val detTotal = 3 * detW * detH
			if (detFloatArray.size < detTotal) {
				detFloatArray = FloatArray(detTotal)
				detDirectBuf = allocateDirectFloatBuffer(detTotal)
			}
			val detBuf = checkNotNull(detDirectBuf)
			sampleToBgrTensor(
				pixels = slicePixels,
				imgW = width,
				imgH = srcH,
				sx0 = 0f,
				sy0 = 0f,
				sw = width.toFloat(),
				sh = srcH.toFloat(),
				tw = detW,
				th = detH,
				outW = detW,
				mean = DET_MEAN,
				std = DET_STD,
				out = detFloatArray,
			)
			detBuf.clear()
			detBuf.put(detFloatArray, 0, detTotal)
			detBuf.rewind()
			detBuf.limit(detTotal)

			val detBoxes: List<DetBox>
			val mapW: Int
			val mapH: Int
			OnnxTensor.createTensor(env, detBuf, longArrayOf(1L, 3L, detH.toLong(), detW.toLong())).use { detTensor ->
				detSession.run(Collections.singletonMap(detInputName, detTensor)).use { result ->
					currentCoroutineContext().ensureActive()
					val outTensor = result[0] as OnnxTensor
					val shape = (outTensor.info as TensorInfo).shape
					if (shape.size >= 4) {
						if (shape[3] == 1L && shape[1] > 1L) {
							mapH = shape[1].toInt()
							mapW = shape[2].toInt()
						} else {
							mapH = shape[2].toInt()
							mapW = shape[3].toInt()
						}
					} else {
						mapH = shape[1].toInt()
						mapW = shape[2].toInt()
					}
					val probCount = mapW * mapH
					if (detProbArray.size < probCount) {
						detProbArray = FloatArray(probCount)
					}
					outTensor.floatBuffer.apply {
						rewind()
						get(detProbArray, 0, probCount)
					}
					if (bfsLabels.size < probCount) {
						bfsLabels = IntArray(probCount)
						bfsQueue = IntArray(probCount)
					} else {
						bfsLabels.fill(0, 0, probCount)
					}
					detBoxes = extractDetBoxes(detProbArray, mapW, mapH, bfsLabels, bfsQueue)
				}
			}

			val fx = width.toFloat() / mapW
			val fy = srcH.toFloat() / mapH

			for (b in detBoxes) {
				val clippedTop = !isFirst && b.y0 <= 4f && b.y1 < mapH * 0.72f
				val clippedBottom = !isLast && b.y1 >= mapH - 4f && b.y0 > mapH * 0.28f
				val isEdgeClipped = clippedTop || clippedBottom

				val pageX0 = (b.x0 * fx).coerceIn(0f, width.toFloat())
				val pageY0 = (b.y0 * fy + sy).coerceIn(0f, height.toFloat())
				val pageX1 = (b.x1 * fx).coerceIn(0f, width.toFloat())
				val pageY1 = (b.y1 * fy + sy).coerceIn(0f, height.toFloat())
				val cw = pageX1 - pageX0
				val ch = pageY1 - pageY0
				if (cw < 10f || ch < 8f || ch > cw * 24f) continue

				val hasTiltedObb = abs(b.angleDeg) >= 4f && b.obbW > 1f && b.obbH > 1f
				val cand = if (hasTiltedObb) {
					val rad = Math.toRadians(b.angleDeg.toDouble())
					val ux = cos(rad).toFloat() * fx
					val uy = sin(rad).toFloat() * fy
					val uScale = sqrt(ux * ux + uy * uy).coerceAtLeast(1e-4f)
					val vx = -sin(rad).toFloat() * fx
					val vy = cos(rad).toFloat() * fy
					val vScale = sqrt(vx * vx + vy * vy).coerceAtLeast(1e-4f)
					DetRegion(
						x0 = pageX0,
						y0 = pageY0,
						x1 = pageX1,
						y1 = pageY1,
						cx = (b.cx * fx).coerceIn(0f, width.toFloat()),
						cy = (b.cy * fy + sy).coerceIn(0f, height.toFloat()),
						obbW = b.obbW * uScale,
						obbH = b.obbH * vScale,
						angleDeg = Math.toDegrees(atan2((uy / uScale).toDouble(), (ux / uScale).toDouble())).toFloat(),
						edgeClipped = isEdgeClipped,
					)
				} else {
					DetRegion(pageX0, pageY0, pageX1, pageY1, edgeClipped = isEdgeClipped)
				}

				var absorbedByExisting = false
				var replacedIdx = -1
				for (idx in detected.indices.reversed()) {
					val existing = detected[idx]
					if (shouldSuppressDetRegionOverlap(existing, cand)) {
						val existingWins = when {
							existing.edgeClipped && !cand.edgeClipped -> false
							!existing.edgeClipped && cand.edgeClipped -> true
							else -> cand.area <= existing.area * 1.10f
						}
						if (existingWins) {
							absorbedByExisting = true
						} else if (replacedIdx == -1) {
							detected[idx] = cand
							replacedIdx = idx
						} else {
							detected.removeAt(idx)
						}
					}
				}
				if (!absorbedByExisting && replacedIdx == -1) {
					detected.add(cand)
				}
			}

			if (isLast) break
			sy += stepSrc
		}
		return detected
	}

	private suspend fun recognizeRegions(
		bitmap: Bitmap,
		width: Int,
		height: Int,
		regions: List<DetRegion>,
		recSession: OrtSession,
		dictionary: List<String>,
		modelGroup: String,
		minConfidence: Float = 34f,
	): List<RawOcrLine> {
		val recInputName = recSession.inputNames.first()
		val allowVerticalRotation = modelGroup == "cjk" || modelGroup == "korean"
		var cropPixels = IntArray(0)
		var enhancedPixels = IntArray(0)
		val lumHist = IntArray(256)
		val borderHist = IntArray(256)
		var recFloatArray = FloatArray(0)
		var recDirectBuf: FloatBuffer? = null
		var recLogitsArray = FloatArray(0)
		val collectedLines = ArrayList<RawOcrLine>(regions.size)
		var aspectSkipped = 0
		var lowConfSkipped = 0

		fun scoreCandidate(text: String, conf: Float): Float {
			if (text.isEmpty()) return -1f
			val clean = TextBlockFilters.cleanOcrText(text)
			if (clean.isEmpty()) return -1f
			val letters = TextBlockFilters.countLetters(clean)
			if (letters == 0) return -1f
			val junk = TextBlockFilters.countJunk(clean)
			return (conf - junk * 18f).coerceAtLeast(0f) * sqrt(letters.toFloat() + 0.5f)
		}

		for (region in regions) {
			currentCoroutineContext().ensureActive()
			val cw = region.width
			val ch = region.height
			if (cw < 10f || ch < 8f) continue
			val useTiltedUnwarp = abs(region.angleDeg) >= 4f && region.obbW >= 12f && region.obbH >= 8f
			val effW = if (useTiltedUnwarp) region.obbW else cw
			val effH = if (useTiltedUnwarp) region.obbH else ch
			if (!useTiltedUnwarp && ch > cw * 24f) {
				aspectSkipped++
				continue
			}

			val isVerticalShape = !useTiltedUnwarp && ch >= cw * 1.55f
			val fetchPadW = max((effH * 0.14f).coerceIn(2f, 5f), (cw * 0.08f).coerceIn(1.5f, 4f))
			val fetchPadH = max((effH * 0.08f).coerceIn(1.5f, 4f), (ch * 0.05f).coerceIn(2f, 5f))

			val cropX0 = floor((region.x0 - fetchPadW).coerceIn(0f, (width - 1).toFloat())).toInt().coerceIn(0, width - 1)
			val cropY0 = floor((region.y0 - fetchPadH).coerceIn(0f, (height - 1).toFloat())).toInt().coerceIn(0, height - 1)
			val cropX1 = ceil((region.x1 + fetchPadW).coerceIn(cropX0 + 1f, width.toFloat())).toInt().coerceIn(cropX0 + 1, width)
			val cropY1 = ceil((region.y1 + fetchPadH).coerceIn(cropY0 + 1f, height.toFloat())).toInt().coerceIn(cropY0 + 1, height)
			val cropW = cropX1 - cropX0
			val cropH = cropY1 - cropY0
			val neededPixels = cropW * cropH
			if (cropPixels.size < neededPixels) {
				cropPixels = IntArray(neededPixels)
			}
			bitmap.getPixels(cropPixels, 0, cropW, cropX0, cropY0, cropW, cropH)

			suspend fun runOrientation(srcPixels: IntArray, mode: CropOrientation): Pair<String, Float> {
				val isVertMode = mode == CropOrientation.ROTATE_90_CCW || mode == CropOrientation.ROTATE_90_CW
				val baseW = if (mode == CropOrientation.TILTED_OBB) region.obbW else cw
				val baseH = if (mode == CropOrientation.TILTED_OBB) region.obbH else ch
				val padAlongW = if (isVertMode) (cw * 0.08f).coerceIn(1.5f, 4f) else (baseH * 0.14f).coerceIn(2f, 5f)
				val padAlongH = if (isVertMode) (ch * 0.05f).coerceIn(2f, 5f) else (baseH * 0.08f).coerceIn(1.5f, 4f)
				val sampleW = baseW + padAlongW * 2f
				val sampleH = baseH + padAlongH * 2f
				val rawTw = if (isVertMode) {
					((REC_H * sampleH) / sampleW).roundToInt()
				} else {
					((REC_H * sampleW) / sampleH).roundToInt()
				}
				val tw = rawTw.coerceIn(16, REC_MAX_W)
				val outW = ((tw + 7) / 8) * 8
				val recTotal = 3 * REC_H * outW
				if (recFloatArray.size < recTotal) {
					recFloatArray = FloatArray(recTotal)
					recDirectBuf = allocateDirectFloatBuffer(recTotal)
				}
				recFloatArray.fill(0f, 0, recTotal)
				val recBuf = checkNotNull(recDirectBuf)

				val sampleX0 = (region.x0 - padAlongW).coerceIn(cropX0.toFloat(), (cropX1 - 1).toFloat())
				val sampleY0 = (region.y0 - padAlongH).coerceIn(cropY0.toFloat(), (cropY1 - 1).toFloat())
				val sampleX1 = (region.x1 + padAlongW).coerceIn(sampleX0 + 1f, cropX1.toFloat())
				val sampleY1 = (region.y1 + padAlongH).coerceIn(sampleY0 + 1f, cropY1.toFloat())

				when (mode) {
					CropOrientation.TILTED_OBB -> sampleRotatedAngleToBgrTensor(
						pixels = srcPixels,
						imgW = cropW,
						imgH = cropH,
						cx = region.cx - cropX0,
						cy = region.cy - cropY0,
						boxW = sampleW,
						boxH = sampleH,
						angleDeg = region.angleDeg,
						tw = tw,
						th = REC_H,
						outW = outW,
						mean = REC_MEAN,
						std = REC_STD,
						out = recFloatArray,
					)
					CropOrientation.ROTATE_90_CCW -> sampleRotated90CcwToBgrTensor(
						pixels = srcPixels,
						imgW = cropW,
						imgH = cropH,
						sx0 = sampleX0 - cropX0,
						sy0 = sampleY0 - cropY0,
						sw = sampleX1 - sampleX0,
						sh = sampleY1 - sampleY0,
						tw = tw,
						th = REC_H,
						outW = outW,
						mean = REC_MEAN,
						std = REC_STD,
						out = recFloatArray,
					)
					CropOrientation.ROTATE_90_CW -> sampleRotated90CwToBgrTensor(
						pixels = srcPixels,
						imgW = cropW,
						imgH = cropH,
						sx0 = sampleX0 - cropX0,
						sy0 = sampleY0 - cropY0,
						sw = sampleX1 - sampleX0,
						sh = sampleY1 - sampleY0,
						tw = tw,
						th = REC_H,
						outW = outW,
						mean = REC_MEAN,
						std = REC_STD,
						out = recFloatArray,
					)
					CropOrientation.HORIZONTAL -> sampleToBgrTensor(
						pixels = srcPixels,
						imgW = cropW,
						imgH = cropH,
						sx0 = sampleX0 - cropX0,
						sy0 = sampleY0 - cropY0,
						sw = sampleX1 - sampleX0,
						sh = sampleY1 - sampleY0,
						tw = tw,
						th = REC_H,
						outW = outW,
						mean = REC_MEAN,
						std = REC_STD,
						out = recFloatArray,
					)
				}
				recBuf.clear()
				recBuf.put(recFloatArray, 0, recTotal)
				recBuf.rewind()
				recBuf.limit(recTotal)

				return OnnxTensor.createTensor(
					env,
					recBuf,
					longArrayOf(1L, 3L, REC_H.toLong(), outW.toLong()),
				).use { recTensor ->
					recSession.run(Collections.singletonMap(recInputName, recTensor)).use { recResult ->
						val outTensor = recResult[0] as OnnxTensor
						val outShape = (outTensor.info as TensorInfo).shape
						if (outShape.size < 3) {
							"" to 0f
						} else {
							val steps = outShape[1].toInt()
							val classes = outShape[2].toInt()
							val logitsCount = steps * classes
							if (recLogitsArray.size < logitsCount) {
								recLogitsArray = FloatArray(logitsCount)
							}
							outTensor.floatBuffer.apply {
								rewind()
								get(recLogitsArray, 0, logitsCount)
							}
							ctcDecode(recLogitsArray, steps, classes, dictionary)
						}
					}
				}
			}

			suspend fun runRotatedRectRec(
				cxOffset: Float,
				cyOffset: Float,
				boxW: Float,
				boxH: Float,
				angleDeg: Float,
			): Pair<String, Float> {
				val rawTw = ((REC_H * boxW) / boxH.coerceAtLeast(1f)).roundToInt()
				val tw = rawTw.coerceIn(16, REC_MAX_W)
				val outW = ((tw + 7) / 8) * 8
				val recTotal = 3 * REC_H * outW
				if (recFloatArray.size < recTotal) {
					recFloatArray = FloatArray(recTotal)
					recDirectBuf = allocateDirectFloatBuffer(recTotal)
				}
				recFloatArray.fill(0f, 0, recTotal)
				val recBuf = checkNotNull(recDirectBuf)
				sampleRotatedAngleToBgrTensor(
					pixels = cropPixels,
					imgW = cropW,
					imgH = cropH,
					cx = cxOffset,
					cy = cyOffset,
					boxW = boxW,
					boxH = boxH,
					angleDeg = angleDeg,
					tw = tw,
					th = REC_H,
					outW = outW,
					mean = REC_MEAN,
					std = REC_STD,
					out = recFloatArray,
				)
				recBuf.clear()
				recBuf.put(recFloatArray, 0, recTotal)
				recBuf.rewind()
				recBuf.limit(recTotal)
				return OnnxTensor.createTensor(
					env,
					recBuf,
					longArrayOf(1L, 3L, REC_H.toLong(), outW.toLong()),
				).use { recTensor ->
					recSession.run(Collections.singletonMap(recInputName, recTensor)).use { recResult ->
						val outTensor = recResult[0] as OnnxTensor
						val outShape = (outTensor.info as TensorInfo).shape
						if (outShape.size < 3) {
							"" to 0f
						} else {
							val steps = outShape[1].toInt()
							val classes = outShape[2].toInt()
							val logitsCount = steps * classes
							if (recLogitsArray.size < logitsCount) {
								recLogitsArray = FloatArray(logitsCount)
							}
							outTensor.floatBuffer.apply {
								rewind()
								get(recLogitsArray, 0, logitsCount)
							}
							ctcDecode(recLogitsArray, steps, classes, dictionary)
						}
					}
				}
			}

			suspend fun runTightTiltedProbe(tiltDeg: Float): Pair<String, Float> {
				val boxW = cw + 6f
				val boxH = (if (useTiltedUnwarp) min(region.obbH + 4f, ch * 0.72f) else ch * 0.70f).coerceAtLeast(24f)
				val cxPage = (region.x0 + region.x1) * 0.5f - cropX0
				val cyPage = (region.y0 + region.y1) * 0.5f - cropY0
				return runRotatedRectRec(cxPage, cyPage, boxW, boxH, tiltDeg)
			}

			val primaryMode = when {
				useTiltedUnwarp -> CropOrientation.TILTED_OBB
				isVerticalShape && (allowVerticalRotation || ch >= cw * 2.3f) -> CropOrientation.ROTATE_90_CCW
				else -> CropOrientation.HORIZONTAL
			}
			var (bestText, bestConf) = runOrientation(cropPixels, primaryMode).let { it.first.trim() to it.second }
			var bestMode = primaryMode
			var bestScore = scoreCandidate(bestText, bestConf)

			fun acceptFallback(candPair: Pair<String, Float>, mode: CropOrientation) {
				val candText = candPair.first.trim()
				val candConf = candPair.second
				val candScore = scoreCandidate(candText, candConf)
				val candLetters = TextBlockFilters.countLetters(candText)
				val bestLetters = TextBlockFilters.countLetters(bestText)
				val thresholdMult = if (candLetters > bestLetters && candConf >= 88f) 1.01f else 1.05f
				if (candScore > bestScore * thresholdMult || (bestScore < 0f && candScore > 0f)) {
					bestText = candText
					bestConf = candConf
					bestMode = mode
					bestScore = candScore
				}
			}

			if (useTiltedUnwarp && abs(region.angleDeg) <= 22f && (bestConf < 97.5f || bestScore < 0f)) {
				val horizCand = runOrientation(cropPixels, CropOrientation.HORIZONTAL)
				val hText = horizCand.first.trim()
				val hConf = horizCand.second
				val hScore = scoreCandidate(hText, hConf)
				if (hText.isNotEmpty() && hScore > bestScore && (bestConf < minConfidence || hConf >= 75f)) {
					bestText = hText
					bestConf = hConf
					bestMode = CropOrientation.HORIZONTAL
					bestScore = hScore
				}
			} else if (useTiltedUnwarp && (bestConf < 68f || bestScore < 0f)) {
				val axisFallback = if (ch >= cw * 1.55f) CropOrientation.ROTATE_90_CCW else CropOrientation.HORIZONTAL
				acceptFallback(runOrientation(cropPixels, axisFallback), axisFallback)
			}
			if (!isVerticalShape && cw >= ch * 2.8f && ch >= 36f && bestConf < 96.5f) {
				acceptFallback(runTightTiltedProbe(-5f), CropOrientation.TILTED_OBB)
				if (bestConf < 97.5f) {
					acceptFallback(runTightTiltedProbe(5f), CropOrientation.TILTED_OBB)
				}
			}
			if (isVerticalShape && (bestConf < 82f || bestScore < 0f)) {
				if (bestMode != CropOrientation.ROTATE_90_CCW) {
					acceptFallback(runOrientation(cropPixels, CropOrientation.ROTATE_90_CCW), CropOrientation.ROTATE_90_CCW)
				}
				if (bestConf < 82f || bestScore < 0f) {
					acceptFallback(runOrientation(cropPixels, CropOrientation.ROTATE_90_CW), CropOrientation.ROTATE_90_CW)
				}
				if (bestMode != CropOrientation.HORIZONTAL && ch < cw * 2.4f && (bestConf < 72f || bestScore < 0f)) {
					acceptFallback(runOrientation(cropPixels, CropOrientation.HORIZONTAL), CropOrientation.HORIZONTAL)
				}
			}

			val needsContrastRetry = bestText.isEmpty() ||
				bestConf < 68f ||
				TextBlockFilters.countLetters(bestText) < 2
			if (needsContrastRetry) {
				if (enhancedPixels.size < neededPixels) {
					enhancedPixels = IntArray(neededPixels)
				}
				enhanceCropForRecRetry(
					src = cropPixels,
					w = cropW,
					h = cropH,
					out = enhancedPixels,
					lumHist = lumHist,
					borderHist = borderHist,
				)
				val enhancedRes = runOrientation(enhancedPixels, bestMode)
				val eText = enhancedRes.first.trim()
				val eConf = enhancedRes.second
				val eScore = scoreCandidate(eText, eConf)
				if (eText.isNotEmpty() && (eScore > bestScore * 1.05f || (bestConf < minConfidence && eConf >= minConfidence && eScore > 0f))) {
					bestText = eText
					bestConf = eConf
				}
			}

			// If a wide tilted OBB merges two closely stacked lines (e.g. tilted phone screen UI text),
			// probe upper and lower halves along the rotated perpendicular axis so neither line gets squished.
			if (useTiltedUnwarp &&
				region.obbH >= 46f &&
				region.obbW >= region.obbH * 1.8f &&
				(bestConf < 85f || bestScore < 0f)
			) {
				val rad = Math.toRadians(region.angleDeg.toDouble())
				val vx = -sin(rad).toFloat()
				val vy = cos(rad).toFloat()
				val shift = region.obbH * 0.22f
				val subH = (region.obbH * 0.54f).coerceAtLeast(24f)
				val subW = region.obbW + (region.obbH * 0.14f).coerceIn(2f, 5f) * 2f
				val baseCx = region.cx - cropX0
				val baseCy = region.cy - cropY0
				val (rawTopText, topConf) = runRotatedRectRec(baseCx - shift * vx, baseCy - shift * vy, subW, subH, region.angleDeg)
				val (rawBotText, botConf) = runRotatedRectRec(baseCx + shift * vx, baseCy + shift * vy, subW, subH, region.angleDeg)
				val topText = rawTopText.trim()
				val botText = rawBotText.trim()
				val avgSplitConf = (topConf + botConf) * 0.5f
				val splitScore = scoreCandidate(topText, topConf) + scoreCandidate(botText, botConf)
				if (topConf >= minConfidence &&
					botConf >= minConfidence &&
					TextBlockFilters.countLetters(topText) >= 3 &&
					TextBlockFilters.countLetters(botText) >= 3 &&
					!textsOverlapNearDuplicate(topText, botText) &&
					avgSplitConf >= max(78f, bestConf + 10f) &&
					splitScore > bestScore
				) {
					val rx0 = region.x0.roundToInt().toFloat()
					val ry0 = region.y0.roundToInt().toFloat()
					val rx1 = region.x1.roundToInt().toFloat()
					val ry1 = region.y1.roundToInt().toFloat()
					val splitTopY1 = (region.y0 + ch * 0.62f).roundToInt().toFloat()
					val splitBotY0 = (region.y0 + ch * 0.38f).roundToInt().toFloat()
					collectedLines.add(RawOcrLine(topText, topConf, rx0, ry0, rx1, splitTopY1))
					collectedLines.add(RawOcrLine(botText, botConf, rx0, splitBotY0, rx1, ry1))
					continue
				}
			}

			if (bestText.isEmpty() || bestConf < minConfidence) {
				lowConfSkipped++
				continue
			}
			collectedLines.add(
				RawOcrLine(
					text = bestText,
					confidence = bestConf,
					x0 = region.x0.roundToInt().toFloat(),
					y0 = region.y0.roundToInt().toFloat(),
					x1 = region.x1.roundToInt().toFloat(),
					y1 = region.y1.roundToInt().toFloat(),
				),
			)
		}

		if (aspectSkipped > 0 || lowConfSkipped > 0) {
			Log.d(
				LOG_TAG,
				"recognizeRegions(group=$modelGroup): input=${regions.size}, kept=${collectedLines.size}, " +
					"aspectSkipped=$aspectSkipped, emptyOrLowConf=$lowConfSkipped",
			)
		}

		// Dilim örtüşmelerinden gelen çift kutuları IoU, içerme (IoMin) ve metin benzerliği ile tekilleştir
		val sorted = collectedLines.sortedByDescending { it.confidence * sqrt(it.text.length.coerceAtLeast(1).toFloat()) }
		val kept = ArrayList<RawOcrLine>(sorted.size)
		for (line in sorted) {
			val lineArea = line.width * line.height
			val duplicate = kept.any { existing ->
				val lineIoMin = ioMin(existing, line)
				val lineIou = iou(existing, line)
				val minH = min(existing.height, line.height).coerceAtLeast(1f)
				val maxH = max(existing.height, line.height).coerceAtLeast(1f)
				val existArea = existing.width * existing.height
				val comparableScale = min(existArea, lineArea) >= max(existArea, lineArea) * 0.22f
				val sameRowBand = abs(existing.centerY - line.centerY) < minH * 0.48f && maxH <= minH * 1.85f
				val isTextOverlap = textsOverlapNearDuplicate(existing.text, line.text)
				val isVertCol = (existing.height >= existing.width * 1.45f || line.height >= line.width * 1.45f) && comparableScale
				lineIou > 0.50f ||
					(lineIoMin > 0.60f && ((sameRowBand && comparableScale) || isTextOverlap || isVertCol)) ||
					(lineIoMin > 0.35f && isTextOverlap)
			}
			if (duplicate) continue
			kept.add(line)
		}
		return kept
	}

	private fun sampleRotatedAngleToBgrTensor(
		pixels: IntArray,
		imgW: Int,
		imgH: Int,
		cx: Float,
		cy: Float,
		boxW: Float,
		boxH: Float,
		angleDeg: Float,
		tw: Int,
		th: Int,
		outW: Int,
		mean: FloatArray,
		std: FloatArray,
		out: FloatArray,
	) {
		val plane = outW * th
		val rad = Math.toRadians(angleDeg.toDouble())
		val ux = cos(rad).toFloat()
		val uy = sin(rad).toFloat()
		val vx = -uy
		val vy = ux
		val maxImgX = (imgW - 1).toFloat()
		val maxImgY = (imgH - 1).toFloat()

		for (y in 0 until th) {
			val v = ((y + 0.5f) / th - 0.5f) * boxH
			val outRow = y * outW
			for (x in 0 until tw) {
				val u = ((x + 0.5f) / tw - 0.5f) * boxW
				val fx = (cx + u * ux + v * vx).coerceIn(0f, maxImgX)
				val fy = (cy + u * uy + v * vy).coerceIn(0f, maxImgY)
				val x0 = floor(fx).toInt()
				val x1 = min(imgW - 1, x0 + 1)
				val y0 = floor(fy).toInt()
				val y1 = min(imgH - 1, y0 + 1)
				val wy = fy - y0
				writeBilinearBgrPixel(
					p00 = pixels[y0 * imgW + x0],
					p01 = pixels[y0 * imgW + x1],
					p10 = pixels[y1 * imgW + x0],
					p11 = pixels[y1 * imgW + x1],
					wx = fx - x0,
					invWy = 1f - wy,
					wy = wy,
					outOffset = outRow + x,
					plane = plane,
					mean = mean,
					std = std,
					out = out,
				)
			}
		}
	}

	private fun allocateDirectFloatBuffer(floatCount: Int): FloatBuffer =
		ByteBuffer.allocateDirect(floatCount * Float.SIZE_BYTES)
			.order(ByteOrder.nativeOrder())
			.asFloatBuffer()

	private fun sampleToBgrTensor(
		pixels: IntArray,
		imgW: Int,
		imgH: Int,
		sx0: Float,
		sy0: Float,
		sw: Float,
		sh: Float,
		tw: Int,
		th: Int,
		outW: Int,
		mean: FloatArray,
		std: FloatArray,
		out: FloatArray,
	) {
		val plane = outW * th
		val scaleX = sw / tw
		val scaleY = sh / th
		val maxImgX = (imgW - 1).toFloat()
		val maxImgY = (imgH - 1).toFloat()

		for (y in 0 until th) {
			val fy = (sy0 + (y + 0.5f) * scaleY - 0.5f).coerceIn(0f, maxImgY)
			val y0 = floor(fy).toInt()
			val y1 = min(imgH - 1, y0 + 1)
			val wy = fy - y0
			val invWy = 1f - wy
			val row0 = y0 * imgW
			val row1 = y1 * imgW
			val outRow = y * outW

			for (x in 0 until tw) {
				val fx = (sx0 + (x + 0.5f) * scaleX - 0.5f).coerceIn(0f, maxImgX)
				val x0 = floor(fx).toInt()
				val x1 = min(imgW - 1, x0 + 1)
				writeBilinearBgrPixel(
					p00 = pixels[row0 + x0],
					p01 = pixels[row0 + x1],
					p10 = pixels[row1 + x0],
					p11 = pixels[row1 + x1],
					wx = fx - x0,
					invWy = invWy,
					wy = wy,
					outOffset = outRow + x,
					plane = plane,
					mean = mean,
					std = std,
					out = out,
				)
			}
		}
	}

	private fun sampleRotated90CcwToBgrTensor(
		pixels: IntArray,
		imgW: Int,
		imgH: Int,
		sx0: Float,
		sy0: Float,
		sw: Float,
		sh: Float,
		tw: Int,
		th: Int,
		outW: Int,
		mean: FloatArray,
		std: FloatArray,
		out: FloatArray,
	) {
		val plane = outW * th
		val scaleY = sh / tw
		val scaleX = sw / th
		val maxImgX = (imgW - 1).toFloat()
		val maxImgY = (imgH - 1).toFloat()

		for (y in 0 until th) {
			val fx = (sx0 + sw - (y + 0.5f) * scaleX - 0.5f).coerceIn(0f, maxImgX)
			val x0 = floor(fx).toInt()
			val x1 = min(imgW - 1, x0 + 1)
			val wx = fx - x0
			val outRow = y * outW

			for (x in 0 until tw) {
				val fy = (sy0 + (x + 0.5f) * scaleY - 0.5f).coerceIn(0f, maxImgY)
				val y0 = floor(fy).toInt()
				val y1 = min(imgH - 1, y0 + 1)
				val wy = fy - y0
				val row0 = y0 * imgW
				val row1 = y1 * imgW
				writeBilinearBgrPixel(
					p00 = pixels[row0 + x0],
					p01 = pixels[row0 + x1],
					p10 = pixels[row1 + x0],
					p11 = pixels[row1 + x1],
					wx = wx,
					invWy = 1f - wy,
					wy = wy,
					outOffset = outRow + x,
					plane = plane,
					mean = mean,
					std = std,
					out = out,
				)
			}
		}
	}

	private fun sampleRotated90CwToBgrTensor(
		pixels: IntArray,
		imgW: Int,
		imgH: Int,
		sx0: Float,
		sy0: Float,
		sw: Float,
		sh: Float,
		tw: Int,
		th: Int,
		outW: Int,
		mean: FloatArray,
		std: FloatArray,
		out: FloatArray,
	) {
		val plane = outW * th
		val scaleY = sh / tw
		val scaleX = sw / th
		val maxImgX = (imgW - 1).toFloat()
		val maxImgY = (imgH - 1).toFloat()

		for (y in 0 until th) {
			val fx = (sx0 + (y + 0.5f) * scaleX - 0.5f).coerceIn(0f, maxImgX)
			val x0 = floor(fx).toInt()
			val x1 = min(imgW - 1, x0 + 1)
			val wx = fx - x0
			val outRow = y * outW

			for (x in 0 until tw) {
				val fy = (sy0 + sh - (x + 0.5f) * scaleY - 0.5f).coerceIn(0f, maxImgY)
				val y0 = floor(fy).toInt()
				val y1 = min(imgH - 1, y0 + 1)
				val wy = fy - y0
				val row0 = y0 * imgW
				val row1 = y1 * imgW
				writeBilinearBgrPixel(
					p00 = pixels[row0 + x0],
					p01 = pixels[row0 + x1],
					p10 = pixels[row1 + x0],
					p11 = pixels[row1 + x1],
					wx = wx,
					invWy = 1f - wy,
					wy = wy,
					outOffset = outRow + x,
					plane = plane,
					mean = mean,
					std = std,
					out = out,
				)
			}
		}
	}

	private fun writeBilinearBgrPixel(
		p00: Int,
		p01: Int,
		p10: Int,
		p11: Int,
		wx: Float,
		invWy: Float,
		wy: Float,
		outOffset: Int,
		plane: Int,
		mean: FloatArray,
		std: FloatArray,
		out: FloatArray,
	) {
		val invWx = 1f - wx
		val rTop = ((p00 ushr 16) and 0xFF) * invWx + ((p01 ushr 16) and 0xFF) * wx
		val rBot = ((p10 ushr 16) and 0xFF) * invWx + ((p11 ushr 16) and 0xFF) * wx
		val r = (rTop * invWy + rBot * wy) * INV_255

		val gTop = ((p00 ushr 8) and 0xFF) * invWx + ((p01 ushr 8) and 0xFF) * wx
		val gBot = ((p10 ushr 8) and 0xFF) * invWx + ((p11 ushr 8) and 0xFF) * wx
		val g = (gTop * invWy + gBot * wy) * INV_255

		val bTop = (p00 and 0xFF) * invWx + (p01 and 0xFF) * wx
		val bBot = (p10 and 0xFF) * invWx + (p11 and 0xFF) * wx
		val b = (bTop * invWy + bBot * wy) * INV_255

		// PaddleOCR BGR sırası bekler: 0->B, 1->G, 2->R
		out[outOffset] = (b - mean[0]) / std[0]
		out[plane + outOffset] = (g - mean[1]) / std[1]
		out[2 * plane + outOffset] = (r - mean[2]) / std[2]
	}

	internal data class DetBox(
		val x0: Float,
		val y0: Float,
		val x1: Float,
		val y1: Float,
		val cx: Float = (x0 + x1) * 0.5f,
		val cy: Float = (y0 + y1) * 0.5f,
		val obbW: Float = 0f,
		val obbH: Float = 0f,
		val angleDeg: Float = 0f,
	)

	private fun ctcDecode(
		data: FloatArray,
		steps: Int,
		classes: Int,
		dict: List<String>,
	): Pair<String, Float> {
		val sb = StringBuilder()
		var probSum = 0f
		var probCount = 0
		var prevIdx = 0

		for (t in 0 until steps) {
			val off = t * classes
			var maxIdx = 0
			var maxVal = Float.NEGATIVE_INFINITY
			var hasOutOfUnitRange = false
			for (k in 0 until classes) {
				val v = data[off + k]
				if (v < -0.001f || v > 1.001f) {
					hasOutOfUnitRange = true
				}
				if (v > maxVal) {
					maxVal = v
					maxIdx = k
				}
			}
			if (maxIdx > 0 && maxIdx != prevIdx) {
				val ch = dict.getOrNull(maxIdx - 1).orEmpty()
				if (ch.isNotEmpty()) {
					sb.append(ch)
					val prob = if (hasOutOfUnitRange) {
						// Ham logit üreten modellerde sayısal olarak kararlı softmax olasılığı hesapla
						var expSum = 0.0
						for (k in 0 until classes) {
							expSum += exp((data[off + k] - maxVal).toDouble())
						}
						if (expSum > 0.0) (1.0 / expSum).toFloat() else 0f
					} else {
						maxVal.coerceIn(0f, 1f)
					}
					probSum += prob
					probCount += 1
				}
			}
			prevIdx = maxIdx
		}
		val confidence = if (probCount > 0) (probSum / probCount) * 100f else 0f
		return sb.toString() to confidence
	}

	private fun iou(a: RawOcrLine, b: RawOcrLine): Float {
		val ix = min(a.x1, b.x1) - max(a.x0, b.x0)
		val iy = min(a.y1, b.y1) - max(a.y0, b.y0)
		if (ix <= 0f || iy <= 0f) return 0f
		val inter = ix * iy
		val areaA = (a.x1 - a.x0) * (a.y1 - a.y0)
		val areaB = (b.x1 - b.x0) * (b.y1 - b.y0)
		return inter / max(1f, areaA + areaB - inter)
	}

	private fun ioMin(a: RawOcrLine, b: RawOcrLine): Float {
		val ix = min(a.x1, b.x1) - max(a.x0, b.x0)
		val iy = min(a.y1, b.y1) - max(a.y0, b.y0)
		if (ix <= 0f || iy <= 0f) return 0f
		val inter = ix * iy
		val areaA = (a.x1 - a.x0) * (a.y1 - a.y0)
		val areaB = (b.x1 - b.x0) * (b.y1 - b.y0)
		return inter / max(1f, min(areaA, areaB))
	}

	internal companion object {
		const val LOG_TAG = OcrModelManager.LOG_TAG
		private const val AUTO_PROBE_MAX_BOXES = 12
		private const val DET_MAX_WIDTH = 1280
		private const val DET_SLICE_H = 1536
		private const val DET_SLICE_OVERLAP = 256
		private const val DET_BIN_THRESH = 0.28f
		internal const val DET_BOX_THRESH = 0.48f
		private const val DET_UNCLIP_RATIO = 1.80f
		private const val REC_H = 48
		private const val REC_MAX_W = 1600
		private const val INV_255 = 1f / 255f
		private const val MAX_GROUPING_PIXELS = 32_000_000L

		internal fun textsOverlapNearDuplicate(a: String, b: String): Boolean {
			val normA = TextBlockFilters.cleanOcrText(a).lowercase().filter { it.isLetterOrDigit() }
			val normB = TextBlockFilters.cleanOcrText(b).lowercase().filter { it.isLetterOrDigit() }
			if (normA.isEmpty() || normB.isEmpty()) return false
			if (normA == normB) return true
			val shorter = if (normA.length <= normB.length) normA else normB
			val longer = if (normA.length <= normB.length) normB else normA
			if (longer.contains(shorter) && shorter.length >= max(3, (longer.length * 0.65f).toInt())) {
				return true
			}
			var match = 0
			val used = BooleanArray(longer.length)
			for (ch in shorter) {
				val idx = longer.indexOf(ch)
				if (idx >= 0) {
					var searchFrom = idx
					while (searchFrom >= 0 && used[searchFrom]) {
						searchFrom = longer.indexOf(ch, searchFrom + 1)
					}
					if (searchFrom >= 0) {
						used[searchFrom] = true
						match++
					}
				}
			}
			return match.toFloat() / shorter.length >= 0.80f &&
				shorter.length.toFloat() / longer.length >= 0.65f
		}

		internal fun enhanceCropForRecRetry(
			src: IntArray,
			w: Int,
			h: Int,
			out: IntArray,
			lumHist: IntArray = IntArray(256),
			borderHist: IntArray = IntArray(256),
		) {
			val total = w * h
			if (total <= 0) return
			lumHist.fill(0)
			borderHist.fill(0)
			var borderR = 0L
			var borderG = 0L
			var borderB = 0L
			var borderCount = 0

			for (y in 0 until h) {
				val rowOff = y * w
				val yBorder = y == 0 || y == h - 1
				for (x in 0 until w) {
					val p = src[rowOff + x]
					val r = (p ushr 16) and 0xFF
					val g = (p ushr 8) and 0xFF
					val b = p and 0xFF
					val lum = ((r * 77 + g * 150 + b * 29) ushr 8).coerceIn(0, 255)
					lumHist[lum]++
					if (yBorder || x == 0 || x == w - 1) {
						borderHist[lum]++
						borderR += r
						borderG += g
						borderB += b
						borderCount++
					}
				}
			}

			fun percentile(hist: IntArray, count: Int, q: Float): Int {
				if (count <= 0) return 128
				val target = (count * q).roundToInt().coerceIn(0, count - 1)
				var acc = 0
				for (i in 0..255) {
					acc += hist[i]
					if (acc > target) return i
				}
				return 255
			}

			val p2 = percentile(lumHist, total, 0.02f)
			val p98 = percentile(lumHist, total, 0.98f)
			val borderMedLum = percentile(borderHist, borderCount, 0.50f)
			val lumRange = (p98 - p2).coerceAtLeast(1)

			var bgR = if (borderCount > 0) (borderR / borderCount).toInt() else 255
			var bgG = if (borderCount > 0) (borderG / borderCount).toInt() else 255
			var bgB = if (borderCount > 0) (borderB / borderCount).toInt() else 255
			if (borderCount > 0) {
				var nearR = 0L
				var nearG = 0L
				var nearB = 0L
				var nearCnt = 0
				for (y in 0 until h) {
					val rowOff = y * w
					val yBorder = y == 0 || y == h - 1
					for (x in 0 until w) {
						if (!yBorder && x != 0 && x != w - 1) continue
						val p = src[rowOff + x]
						val r = (p ushr 16) and 0xFF
						val g = (p ushr 8) and 0xFF
						val b = p and 0xFF
						val lum = (r * 77 + g * 150 + b * 29) ushr 8
						if (abs(lum - borderMedLum) <= 28) {
							nearR += r
							nearG += g
							nearB += b
							nearCnt++
						}
					}
				}
				if (nearCnt > 0) {
					bgR = (nearR / nearCnt).toInt()
					bgG = (nearG / nearCnt).toInt()
					bgB = (nearB / nearCnt).toInt()
				}
			}

			var maxBgDiff = 0
			for (i in 0 until total) {
				val p = src[i]
				val r = (p ushr 16) and 0xFF
				val g = (p ushr 8) and 0xFF
				val b = p and 0xFF
				val d = max(abs(r - bgR), max(abs(g - bgG), abs(b - bgB)))
				if (d > maxBgDiff) maxBgDiff = d
			}

			val useChromaProjection = lumRange < 72 && maxBgDiff >= 50
			val invertPolarity = borderMedLum < 120
			val invLumRange = 255f / lumRange
			val chromaSpan = (maxBgDiff - 12).coerceAtLeast(24).toFloat()

			for (i in 0 until total) {
				val p = src[i]
				val r = (p ushr 16) and 0xFF
				val g = (p ushr 8) and 0xFF
				val b = p and 0xFF
				val outGray = if (useChromaProjection) {
					val dBg = max(abs(r - bgR), max(abs(g - bgG), abs(b - bgB)))
					val norm = ((dBg - 12).coerceAtLeast(0) / chromaSpan).coerceIn(0f, 1f)
					(255f - norm * 255f).roundToInt().coerceIn(0, 255)
				} else {
					val lum = (r * 77 + g * 150 + b * 29) ushr 8
					val stretched = ((lum - p2) * invLumRange).roundToInt().coerceIn(0, 255)
					if (invertPolarity) 255 - stretched else stretched
				}
				out[i] = (0xFF shl 24) or (outGray shl 16) or (outGray shl 8) or outGray
			}
		}

		internal fun isAcceptableAutoMatch(
			usableLines: Int,
			avgConfidence: Float,
			validCharRatio: Float,
			scriptPurity: Float,
			totalScriptLetters: Int,
		): Boolean {
			val standardMatch = usableLines >= 1 &&
				avgConfidence >= 68f &&
				validCharRatio >= 0.65f &&
				scriptPurity >= 0.50f &&
				totalScriptLetters >= 2
			// Accept clear multi-line text with strong valid-char ratio and script purity even if confidence sits under 68.
			val highPurityTextRichMatch = usableLines >= 2 &&
				validCharRatio >= 0.85f &&
				((avgConfidence >= 55f && scriptPurity >= 0.85f && totalScriptLetters >= 6) ||
					(avgConfidence >= 38f && scriptPurity >= 0.90f && totalScriptLetters >= 10))
			return standardMatch || highPurityTextRichMatch
		}

		internal val DET_MEAN = floatArrayOf(0.406f, 0.456f, 0.485f)
		internal val DET_STD = floatArrayOf(0.225f, 0.224f, 0.229f)
		private val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
		private val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)

		internal fun resolveHintGroup(sourceHintLocale: String?): String? {
			val lang = sourceHintLocale?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
			return when (lang) {
				"en" -> "en"
				"ko" -> "korean"
				"ja", "zh" -> "cjk"
				"ru", "uk", "be", "bg", "sr" -> "eslav"
				"ar", "fa", "ur" -> "arabic"
				"tr", "es", "pt", "fr", "de", "it", "id", "vi", "pl" -> "latin"
				else -> null
			}
		}

		internal fun selectAutoProbeRegionsStatic(detRegions: List<DetRegion>): List<DetRegion> {
			val candidates = detRegions.filter { r ->
				val w = r.width
				val h = r.height
				w >= 12f && h >= 10f && h <= w * 20f
			}.ifEmpty { detRegions }
			if (candidates.size <= AUTO_PROBE_MAX_BOXES) return candidates

			val horizHeights = candidates
				.filter { it.width >= it.height * 1.15f && it.width >= 24f && it.height >= 10f }
				.map { it.height }
				.sorted()
			val medianHorizH = if (horizHeights.isNotEmpty()) {
				horizHeights[horizHeights.size / 2]
			} else {
				40f
			}
			val giantHorizCeiling = max(90f, medianHorizH * 2.5f)

			val scored = candidates.sortedByDescending { r ->
				val w = r.width
				val h = r.height
				if (h < w * 1.55f) {
					val aspectBonus = min(w / max(1f, h), 14f)
					val sfxPenalty = if (h > giantHorizCeiling) 0.12f else 1f
					w * aspectBonus * sfxPenalty
				} else {
					val minDim = min(w, h)
					minDim * sqrt((w * h).coerceAtLeast(1f)) * 0.25f
				}
			}

			val horizontal = scored.filter { it.height < it.width * 1.55f }
			val vertical = scored.filter { it.height >= it.width * 1.55f }
			val selected = ArrayList<DetRegion>(AUTO_PROBE_MAX_BOXES)
			val maxHoriz = if (vertical.isEmpty()) AUTO_PROBE_MAX_BOXES else AUTO_PROBE_MAX_BOXES - 2
			for (r in horizontal.take(maxHoriz)) selected.add(r)
			for (r in vertical.take(2)) {
				if (selected.size < AUTO_PROBE_MAX_BOXES && r !in selected) selected.add(r)
			}
			for (r in scored) {
				if (selected.size >= AUTO_PROBE_MAX_BOXES) break
				if (r !in selected) selected.add(r)
			}
			return selected
		}

		internal fun iouRect(a: DetRegion, b: DetRegion): Float {
			val ix = min(a.x1, b.x1) - max(a.x0, b.x0)
			val iy = min(a.y1, b.y1) - max(a.y0, b.y0)
			if (ix <= 0f || iy <= 0f) return 0f
			val inter = ix * iy
			val areaA = a.width * a.height
			val areaB = b.width * b.height
			return inter / max(1f, areaA + areaB - inter)
		}

		internal fun ioMinRect(a: DetRegion, b: DetRegion): Float {
			val ix = min(a.x1, b.x1) - max(a.x0, b.x0)
			val iy = min(a.y1, b.y1) - max(a.y0, b.y0)
			if (ix <= 0f || iy <= 0f) return 0f
			val inter = ix * iy
			return inter / max(1f, min(a.area, b.area))
		}

		internal fun shouldSuppressDetRegionOverlap(existing: DetRegion, cand: DetRegion): Boolean {
			val minH = min(existing.height, cand.height).coerceAtLeast(1f)
			val maxH = max(existing.height, cand.height).coerceAtLeast(1f)
			val vOv = min(existing.y1, cand.y1) - max(existing.y0, cand.y0)
			val sameRowBand = abs(existing.cy - cand.cy) < minH * 0.48f ||
				(maxH <= minH * 1.85f && vOv > minH * 0.68f)
			val hasVert = (existing.height >= existing.width * 1.45f || cand.height >= cand.width * 1.45f) &&
				maxH <= minH * 2.2f
			val edgeMismatch = existing.edgeClipped != cand.edgeClipped &&
				abs(existing.cy - cand.cy) <= maxH * 0.65f &&
				maxH <= minH * 2.8f
			val comparableScale = min(existing.area, cand.area) >= max(existing.area, cand.area) * 0.22f
			val rectIou = iouRect(existing, cand)
			val rectIoMin = ioMinRect(existing, cand)
			return rectIou > 0.52f ||
				((sameRowBand || hasVert || edgeMismatch) &&
					(rectIou > 0.42f || (rectIoMin > 0.60f && (comparableScale || edgeMismatch))))
		}

		private val WHITESPACE_REGEX = Regex("""\s+""")
		private val ENDS_WITH_ASCII_ALPHA_REGEX = Regex("""[a-zA-Z]$""")
		private val STARTS_WITH_ASCII_ALPHA_REGEX = Regex("""^[a-zA-Z]""")

		internal fun extractDetBoxes(
			prob: FloatArray,
			w: Int,
			h: Int,
			labels: IntArray = IntArray(w * h),
			queue: IntArray = IntArray(w * h),
		): List<DetBox> {
			val boxes = ArrayList<DetBox>()
			var label = 0
			val total = w * h

			for (i in 0 until total) {
				if (prob[i] <= DET_BIN_THRESH || labels[i] != 0) continue
				label += 1
				var head = 0
				var tail = 0
				queue[tail++] = i
				labels[i] = label
				var minX = i % w
				var maxX = minX
				var minY = i / w
				var maxY = minY
				var sum = 0f
				var count = 0
				var sumX = 0f
				var sumY = 0f
				var sumXX = 0f
				var sumYY = 0f
				var sumXY = 0f

				while (head < tail) {
					val p = queue[head++]
					val py = p / w
					val px = p - py * w
					sum += prob[p]
					count += 1
					val fxP = px.toFloat()
					val fyP = py.toFloat()
					sumX += fxP
					sumY += fyP
					sumXX += fxP * fxP
					sumYY += fyP * fyP
					sumXY += fxP * fyP
					if (px < minX) minX = px
					if (px > maxX) maxX = px
					if (py < minY) minY = py
					if (py > maxY) maxY = py

					for (dy in -1..1) {
						val ny = py + dy
						if (ny < 0 || ny >= h) continue
						val rowOff = ny * w
						for (dx in -1..1) {
							val nx = px + dx
							if (nx < 0 || nx >= w) continue
							val n = rowOff + nx
							if (labels[n] == 0 && prob[n] > DET_BIN_THRESH) {
								labels[n] = label
								queue[tail++] = n
							}
						}
					}
				}

				val bw = maxX - minX + 1
				val bh = maxY - minY + 1
				if (count < 6 || bw < 2 || bh < 2) continue
				val score = sum / count
				if (score < DET_BOX_THRESH) continue

				val d = (bw * bh * DET_UNCLIP_RATIO) / (2f * (bw + bh))
				val dx = max(d * 1.42f, bh * 0.54f)
				val dy = max(d * 1.05f, bh * 0.22f)
				val x0 = (minX - dx).coerceIn(0f, w.toFloat())
				val y0 = (minY - dy).coerceIn(0f, h.toFloat())
				val x1 = (maxX + 1f + dx).coerceIn(0f, w.toFloat())
				val y1 = (maxY + 1f + dy).coerceIn(0f, h.toFloat())

				var obbCx = (x0 + x1) * 0.5f
				var obbCy = (y0 + y1) * 0.5f
				var obbW = 0f
				var obbH = 0f
				var angleDeg = 0f

				val fillRatio = count.toFloat() / max(1, bw * bh)
				if (count >= 20 && fillRatio < 0.68f && bw >= 8 && bh >= 7) {
					val mx = sumX / count
					val my = sumY / count
					val cxx = sumXX / count - mx * mx
					val cyy = sumYY / count - my * my
					val cxy = sumXY / count - mx * my
					val theta = 0.5f * atan2(2f * cxy, cxx - cyy)
					val deg = Math.toDegrees(theta.toDouble()).toFloat()
					if (abs(deg) in 4f..74f) {
						val ux = cos(theta.toDouble()).toFloat()
						val uy = sin(theta.toDouble()).toFloat()
						val vx = -uy
						val vy = ux
						var minU = Float.POSITIVE_INFINITY
						var maxU = Float.NEGATIVE_INFINITY
						var minV = Float.POSITIVE_INFINITY
						var maxV = Float.NEGATIVE_INFINITY
						for (qIdx in 0 until tail) {
							val pt = queue[qIdx]
							val py = (pt / w).toFloat()
							val px = (pt - (pt / w) * w).toFloat()
							val u = (px - mx) * ux + (py - my) * uy
							val v = (px - mx) * vx + (py - my) * vy
							if (u < minU) minU = u
							if (u > maxU) maxU = u
							if (v < minV) minV = v
							if (v > maxV) maxV = v
						}
						val uSpan = (maxU - minU + 1f).coerceAtLeast(1f)
						val vSpan = (maxV - minV + 1f).coerceAtLeast(1f)
						if (uSpan / vSpan >= 1.6f && (uSpan * vSpan) < (bw * bh) * 0.85f) {
							val midU = (minU + maxU + 1f) * 0.5f
							val midV = (minV + maxV + 1f) * 0.5f
							val obbD = (uSpan * vSpan * DET_UNCLIP_RATIO) / (2f * (uSpan + vSpan))
							val du = max(obbD * 1.42f, vSpan * 0.54f)
							val dv = max(obbD * 1.05f, vSpan * 0.22f)
							obbCx = mx + midU * ux + midV * vx
							obbCy = my + midU * uy + midV * vy
							obbW = uSpan + 2f * du
							obbH = vSpan + 2f * dv
							angleDeg = deg
						}
					}
				}

				boxes.add(
					DetBox(
						x0 = x0,
						y0 = y0,
						x1 = x1,
						y1 = y1,
						cx = obbCx,
						cy = obbCy,
						obbW = obbW,
						obbH = obbH,
						angleDeg = angleDeg,
					),
				)
			}
			return boxes
		}

		private fun argbLum(pixel: Int): Float {
			val r = (pixel ushr 16) and 0xFF
			val g = (pixel ushr 8) and 0xFF
			val b = pixel and 0xFF
			return 0.299f * r + 0.587f * g + 0.114f * b
		}

		internal fun sampleLineCollarLum(
			pixels: IntArray,
			width: Int,
			height: Int,
			line: RawOcrLine,
			allLines: List<RawOcrLine>? = null,
		): Float {
			val xStart = max(0, floor(line.x0).toInt())
			val xEnd = min(width - 1, ceil(line.x1).toInt())
			val yStart = max(0, floor(line.y0).toInt())
			val yEnd = min(height - 1, ceil(line.y1).toInt())
			if (xEnd < xStart || yEnd < yStart) return 255f

			fun medianOf(list: ArrayList<Float>): Float? {
				if (list.isEmpty()) return null
				list.sort()
				return list[list.size / 2]
			}

			fun isInsideOtherLine(px: Int, py: Int): Boolean {
				val list = allLines ?: return false
				for (other in list) {
					if (other === line) continue
					if (px >= other.x0 - 1f && px <= other.x1 + 1f && py >= other.y0 - 1f && py <= other.y1 + 1f) {
						return true
					}
				}
				return false
			}

			val stepX = max(1, (xEnd - xStart) / 14)
			val stepY = max(1, (yEnd - yStart) / 6)
			val topLums = ArrayList<Float>(32)
			val botLums = ArrayList<Float>(32)
			val leftLums = ArrayList<Float>(16)
			val rightLums = ArrayList<Float>(16)

			val topYs = intArrayOf(max(0, yStart - 3), max(0, yStart - 1))
			for (y in topYs) {
				val rowOff = y * width
				var x = xStart
				while (x <= xEnd) {
					if (!isInsideOtherLine(x, y)) topLums.add(argbLum(pixels[rowOff + x]))
					x += stepX
				}
			}
			val botYs = intArrayOf(min(height - 1, yEnd + 1), min(height - 1, yEnd + 3))
			for (y in botYs) {
				val rowOff = y * width
				var x = xStart
				while (x <= xEnd) {
					if (!isInsideOtherLine(x, y)) botLums.add(argbLum(pixels[rowOff + x]))
					x += stepX
				}
			}
			val leftXs = intArrayOf(max(0, xStart - 3), max(0, xStart - 1))
			for (x in leftXs) {
				var y = yStart
				while (y <= yEnd) {
					if (!isInsideOtherLine(x, y)) leftLums.add(argbLum(pixels[y * width + x]))
					y += stepY
				}
			}
			val rightXs = intArrayOf(min(width - 1, xEnd + 1), min(width - 1, xEnd + 3))
			for (x in rightXs) {
				var y = yStart
				while (y <= yEnd) {
					if (!isInsideOtherLine(x, y)) rightLums.add(argbLum(pixels[y * width + x]))
					y += stepY
				}
			}

			// If all 4 sides were occluded by overlapping neighbor boxes, fall back to unmasked sampling.
			if (allLines != null && topLums.isEmpty() && botLums.isEmpty() && leftLums.isEmpty() && rightLums.isEmpty()) {
				return sampleLineCollarLum(pixels, width, height, line, null)
			}

			// If most sides agree tightly (e.g. a top word near the dark curved top border of a white oval bubble,
			// or a dark-card line touching a bright frame edge), ignore the single outlier border side.
			val sideMedians = ArrayList<Float>(4).apply {
				medianOf(topLums)?.let { add(it) }
				medianOf(botLums)?.let { add(it) }
				medianOf(leftLums)?.let { add(it) }
				medianOf(rightLums)?.let { add(it) }
			}
			if (sideMedians.size == 4) {
				sideMedians.sort()
				val lowSpread = sideMedians[2] - sideMedians[0]
				val highSpread = sideMedians[3] - sideMedians[1]
				if (highSpread <= 30f && lowSpread > highSpread + 20f) {
					return sideMedians[2]
				}
				if (lowSpread <= 30f && highSpread > lowSpread + 20f) {
					return sideMedians[1]
				}
			} else if (sideMedians.size == 3) {
				sideMedians.sort()
				val lowSpread = sideMedians[1] - sideMedians[0]
				val highSpread = sideMedians[2] - sideMedians[1]
				if ((highSpread <= 30f && lowSpread > highSpread + 20f) ||
					(lowSpread <= 30f && highSpread > lowSpread + 20f)
				) {
					return sideMedians[1]
				}
			}

			val allLums = ArrayList<Float>(topLums.size + botLums.size + leftLums.size + rightLums.size).apply {
				addAll(topLums)
				addAll(botLums)
				addAll(leftLums)
				addAll(rightLums)
			}
			if (allLums.isEmpty()) return 255f
			allLums.sort()
			val idx = (allLums.size * 0.55f).toInt().coerceIn(0, allLums.lastIndex)
			return allLums[idx]
		}

		private fun isFaintGrayWatermarkOnWhite(
			pixels: IntArray,
			width: Int,
			height: Int,
			line: RawOcrLine,
		): Boolean {
			val bgCollar = sampleLineCollarLum(pixels, width, height, line)
			if (bgCollar < 238f) return false
			val x0 = floor(line.x0).toInt().coerceIn(0, width - 1)
			val x1 = ceil(line.x1).toInt().coerceIn(0, width - 1)
			val y0 = floor(line.y0).toInt().coerceIn(0, height - 1)
			val y1 = ceil(line.y1).toInt().coerceIn(0, height - 1)
			val w = x1 - x0
			val h = y1 - y0
			if (w < 6 || h < 6) return false
			val stepX = max(1, w / 24)
			val stepY = max(1, h / 12)
			var minLum = 255f
			var y = y0
			while (y <= y1) {
				val rowOff = y * width
				var x = x0
				while (x <= x1) {
					val lum = argbLum(pixels[rowOff + x])
					if (lum < minLum) minLum = lum
					x += stepX
				}
				y += stepY
			}
			return minLum >= 175f
		}

		internal fun hasHorizontalBubbleBarrier(
			pixels: IntArray,
			width: Int,
			height: Int,
			lineA: RawOcrLine,
			lineB: RawOcrLine,
			collarLumA: Float? = null,
			collarLumB: Float? = null,
			allLines: List<RawOcrLine>? = null,
		): Boolean {
			val leftFirst = lineA.x0 <= lineB.x0
			val left = if (leftFirst) lineA else lineB
			val right = if (leftFirst) lineB else lineA
			val bgL = (if (leftFirst) collarLumA else collarLumB) ?: sampleLineCollarLum(pixels, width, height, left, allLines)
			val bgR = (if (leftFirst) collarLumB else collarLumA) ?: sampleLineCollarLum(pixels, width, height, right, allLines)
			if (abs(bgL - bgR) >= 75f) return true

			val hGapPx = right.x0 - left.x1
			if (hGapPx <= 1.5f) return false
			val gapX0 = max(0, floor(left.x1).toInt() + 1)
			val gapX1 = min(width - 1, ceil(right.x0).toInt() - 1)
			if (gapX1 < gapX0) return false

			val ovY0 = max(0, floor(max(left.y0, right.y0)).toInt())
			val ovY1 = min(height - 1, ceil(min(left.y1, right.y1)).toInt())
			val spanY = ovY1 - ovY0 + 1
			if (spanY < 4) return false

			fun isInsideOtherOcrLine(px: Int, py: Int): Boolean {
				val list = allLines ?: return false
				for (other in list) {
					if (other === lineA || other === lineB) continue
					if (px >= other.x0 - 1f && px <= other.x1 + 1f && py >= other.y0 - 1f && py <= other.y1 + 1f) {
						return true
					}
				}
				return false
			}

			val bothLight = bgL >= 165f && bgR >= 165f
			val bothDark = bgL <= 105f && bgR <= 105f
			val padY = max(4, (spanY * 0.55f).roundToInt())
			val cuspTopY = max(0, ovY0 - padY)
			val cuspBotY = min(height - 1, ovY1 + padY)

			for (x in gapX0..gapX1) {
				var darkCount = 0
				var brightCount = 0
				for (y in ovY0..ovY1) {
					val lum = argbLum(pixels[y * width + x])
					if (lum <= 145f) darkCount += 1
					if (lum >= 180f) brightCount += 1
				}
				if (bothLight && darkCount.toFloat() / spanY >= 0.4f) return true
				if (bothDark && brightCount.toFloat() / spanY >= 0.4f) return true

				// İkiz / fıstık (peanut) beyaz balonlar arasındaki üst-alt boğum/sınır daralması (cusp)
				if (bothLight) {
					var topDark = false
					var botDark = false
					val topEnd = min(height - 1, ovY0 + 2)
					for (y in cuspTopY..topEnd) {
						if (!isInsideOtherOcrLine(x, y) && argbLum(pixels[y * width + x]) <= 135f) {
							topDark = true
							break
						}
					}
					if (topDark) {
						val botStart = max(0, ovY1 - 2)
						for (y in botStart..cuspBotY) {
							if (!isInsideOtherOcrLine(x, y) && argbLum(pixels[y * width + x]) <= 135f) {
								botDark = true
								break
							}
						}
					}
					if (topDark && botDark) return true
				}
			}

			// Konkav içbükey boğum kontrolü: iki teğet beyaz balonun birleşim noktasında boğum duvarı içeri çöker
			if (bothLight && hGapPx >= 4f) {
				val scanMax = min(110, (max(left.height, right.height) * 2.4f).roundToInt())
				fun findWallAbove(px: Float, startY: Int): Int? {
					val cx = px.roundToInt().coerceIn(0, width - 1)
					val clampedStart = startY.coerceIn(0, height - 1)
					val stopY = max(0, clampedStart - scanMax)
					for (y in clampedStart downTo stopY) {
						if (!isInsideOtherOcrLine(cx, y) && argbLum(pixels[y * width + cx]) <= 120f) return y
					}
					return null
				}

				fun findWallBelow(px: Float, startY: Int): Int? {
					val cx = px.roundToInt().coerceIn(0, width - 1)
					val clampedStart = startY.coerceIn(0, height - 1)
					val stopY = min(height - 1, clampedStart + scanMax)
					for (y in clampedStart..stopY) {
						if (!isInsideOtherOcrLine(cx, y) && argbLum(pixels[y * width + cx]) <= 120f) return y
					}
					return null
				}

				val gapMidX = (gapX0 + gapX1) * 0.5f
				val wTopGap = findWallAbove(gapMidX, ovY0)
				val wTopL = findWallAbove(left.centerX, floor(left.y0).toInt())
				val wTopR = findWallAbove(right.centerX, floor(right.y0).toInt())
				if (wTopGap != null && ((wTopL != null && wTopGap > wTopL + 6) || (wTopR != null && wTopGap > wTopR + 6))) {
					return true
				}
				val wBotGap = findWallBelow(gapMidX, ovY1)
				val wBotL = findWallBelow(left.centerX, ceil(left.y1).toInt())
				val wBotR = findWallBelow(right.centerX, ceil(right.y1).toInt())
				if (wBotGap != null && ((wBotL != null && wBotGap < wBotL - 6) || (wBotR != null && wBotGap < wBotR - 6))) {
					return true
				}
			}
			return false
		}

		internal fun hasVerticalBubbleBarrier(
			pixels: IntArray,
			width: Int,
			height: Int,
			lineA: RawOcrLine,
			lineB: RawOcrLine,
			collarLumA: Float? = null,
			collarLumB: Float? = null,
			allLines: List<RawOcrLine>? = null,
		): Boolean {
			val topFirst = lineA.y0 <= lineB.y0
			val top = if (topFirst) lineA else lineB
			val bot = if (topFirst) lineB else lineA
			val vGapPx = bot.y0 - top.y1
			val bgT = (if (topFirst) collarLumA else collarLumB) ?: sampleLineCollarLum(pixels, width, height, top, allLines)
			val bgB = (if (topFirst) collarLumB else collarLumA) ?: sampleLineCollarLum(pixels, width, height, bot, allLines)
			// Kalın siyah konturlu orta ton sahne/anlatım yazılarında sıkı dikey yaka pikselinin biri siyah kontura denk gelebilir
			val fakeOutlineCollar = vGapPx <= 6f &&
				min(bgT, bgB) <= 28f &&
				max(bgT, bgB) >= 75f &&
				max(bgT, bgB) < 220f
			if (abs(bgT - bgB) >= 75f && !fakeOutlineCollar) return true
			// Distinct bright speech-bubble interior (>=242) vs scene/outdoor art (<=212) must not merge
			if (max(bgT, bgB) >= 242f && min(bgT, bgB) <= 212f && abs(bgT - bgB) >= 34f && !fakeOutlineCollar) {
				return true
			}

			val ovX0 = max(0, floor(max(top.x0, bot.x0)).toInt())
			val ovX1 = min(width - 1, ceil(min(top.x1, bot.x1)).toInt())
			val spanX = ovX1 - ovX0 + 1
			if (spanX < 4) return false

			val bothLight = bgT >= 165f && bgB >= 165f
			val bothDark = bgT <= 105f && bgB <= 105f
			val bothNearWhite = bgT >= 232f && bgB >= 232f
			if (vGapPx <= 1.5f) {
				// Only probe for a tilted UI/forum divider when both boxes are genuinely tall tilted regions
				// (not normal unclipped horizontal speech-bubble lines whose own glyph strokes lie between centerY's).
				val looksLikeTiltedRows = top.height >= 55f &&
					bot.height >= 55f &&
					abs(bot.centerY - top.centerY) >= 42f &&
					spanX >= 90
				if (bothNearWhite && looksLikeTiltedRows) {
					val scanY0 = max(0, floor(min(top.centerY, bot.centerY)).toInt())
					val scanY1 = min(height - 1, ceil(max(top.centerY, bot.centerY)).toInt())
					if (scanY1 > scanY0 && hasContinuousTiltedDivider(pixels, width, ovX0, ovX1, scanY0, scanY1, max(64, (spanX * 0.62f).toInt()))) {
						return true
					}
				}
				return false
			}
			val gapY0 = max(0, floor(top.y1).toInt() + 1)
			val gapY1 = min(height - 1, ceil(bot.y0).toInt() - 1)
			if (gapY1 < gapY0) return false
			// For narrow short top words (e.g. "I", "OH", "WAIT"), avoid treating 1px descender/ascender anti-aliasing
			// right at gapY0 or gapY1 as a solid horizontal bubble wall.
			val scanGapY0 = if (gapY1 - gapY0 >= 3 && spanX < 44) gapY0 + 1 else gapY0
			val scanGapY1 = if (gapY1 - gapY0 >= 3 && spanX < 44) gapY1 - 1 else gapY1
			val darkWallThresh = if (gapY1 - gapY0 <= 2 && spanX < 28) 115f else 145f
			for (y in scanGapY0..scanGapY1) {
				var darkCount = 0
				var brightCount = 0
				var contrastWallCount = 0
				val rowOff = y * width
				for (x in ovX0..ovX1) {
					val lum = argbLum(pixels[rowOff + x])
					if (lum <= darkWallThresh) darkCount += 1
					if (lum >= 180f) brightCount += 1
					if (abs(lum - bgT) >= 42f && abs(lum - bgB) >= 42f) contrastWallCount += 1
				}
				if (bothLight && darkCount.toFloat() / spanX >= 0.45f) return true
				if (bothDark && brightCount.toFloat() / spanX >= 0.45f) return true
				if (!fakeOutlineCollar && contrastWallCount.toFloat() / spanX >= 0.55f) return true
			}
			// Faint continuous gray divider (e.g. forum/table rule): require a continuous stroke across wide rows
			// rather than disconnected glyph anti-aliasing touching top/bottom edges of a narrow top word.
			if (bothNearWhite && gapY1 > gapY0 && spanX >= 36) {
				val innerY0 = if (gapY1 - gapY0 >= 2) gapY0 + 1 else gapY0
				val innerY1 = if (gapY1 - gapY0 >= 2) gapY1 - 1 else gapY1
				if (innerY1 >= innerY0 &&
					hasContinuousTiltedDivider(pixels, width, ovX0, ovX1, innerY0, innerY1, max(32, (spanX * 0.65f).toInt()))
				) {
					return true
				}
			}
			return false
		}

		private fun hasContinuousTiltedDivider(
			pixels: IntArray,
			width: Int,
			x0: Int,
			x1: Int,
			y0: Int,
			y1: Int,
			minRun: Int,
		): Boolean {
			val scanH = y1 - y0 + 1
			if (scanH <= 0) return false
			var prev = IntArray(scanH)
			var curr = IntArray(scanH)
			for (x in x0..x1) {
				curr.fill(0)
				for (i in 0 until scanH) {
					val y = y0 + i
					if (argbLum(pixels[y * width + x]) <= 215f) {
						var best = prev[i]
						if (i > 0 && prev[i - 1] > best) best = prev[i - 1]
						if (i + 1 < scanH && prev[i + 1] > best) best = prev[i + 1]
						val nextVal = best + 1
						if (nextVal >= minRun) return true
						curr[i] = nextVal
					}
				}
				val tmp = prev
				prev = curr
				curr = tmp
			}
			return false
		}

		private fun toNormCoord(value: Float, total: Int): Float =
			((value / max(1, total)) * 1000f).coerceIn(0f, 1000f)

		internal fun groupLinesIntoBubbles(
			rawLines: List<RawOcrLine>,
			imgWidth: Int,
			imgHeight: Int,
			argbPixels: IntArray? = null,
		): List<OcrBubbleGroup> {
			val validPixels = argbPixels?.takeIf {
				imgWidth > 0 && imgHeight > 0 && it.size.toLong() >= imgWidth.toLong() * imgHeight.toLong()
			}
			val cleanedAll = rawLines.mapNotNull { raw ->
				val cleaned = TextBlockFilters.cleanOcrText(raw.text)
				if (cleaned.isEmpty()) null else raw.copy(text = cleaned)
			}
			if (cleanedAll.isEmpty()) return emptyList()

			val strongLines = cleanedAll.filter { line ->
				TextBlockFilters.isUsableOcrLine(line, TextBlockFilters.DEFAULT_MIN_LINE_CONFIDENCE) &&
					(validPixels == null || !isFaintGrayWatermarkOnWhite(validPixels, imgWidth, imgHeight, line))
			}

			val rescuedLines = cleanedAll.filter { cand ->
				if (TextBlockFilters.isUsableOcrLine(cand, TextBlockFilters.DEFAULT_MIN_LINE_CONFIDENCE)) return@filter false
				if (cand.confidence < 34f) return@filter false
				if (validPixels != null && isFaintGrayWatermarkOnWhite(validPixels, imgWidth, imgHeight, cand)) return@filter false
				val letters = TextBlockFilters.countLetters(cand.text)
				val digits = cand.text.count { it.isDigit() }
				val junk = TextBlockFilters.countJunk(cand.text)
				val isNumericContinuation = letters == 0 && digits >= 1 && junk <= 1 && cand.confidence >= 60f
				if (letters < 1 && !isNumericContinuation) return@filter false
				val ch = cand.height
				val cw = cand.width
				val centerXc = cand.centerX
				val centerYc = cand.centerY
				val isEdgeTruncated = cand.y0 <= 4f || cand.y1 >= imgHeight - 4f

				strongLines.any { s ->
					val sh = s.height
					val sw = s.width
					val minH = min(ch, sh)
					val maxH = max(ch, sh)
					if (!isEdgeTruncated && maxH / max(1f, minH) > 2.85f) return@any false

					val vOverlap = min(cand.y1, s.y1) - max(cand.y0, s.y0)
					val hGap = max(cand.x0, s.x0) - min(cand.x1, s.x1)
					if (
						maxH / max(1f, minH) <= 1.85f &&
						vOverlap >= minH * 0.5f &&
						hGap >= -minH * 0.4f &&
						hGap <= maxH * 0.55f &&
						(validPixels == null || !hasHorizontalBubbleBarrier(validPixels, imgWidth, imgHeight, cand, s))
					) {
						return@any true
					}

					val reqConf = if (isEdgeTruncated) 34f else 36f
					val maxJunk = if (isEdgeTruncated) 2 else 1
					val minOverlapRatio = if (isEdgeTruncated) 0.25f else 0.32f
					if ((letters >= 2 || isNumericContinuation) && junk <= maxJunk && cand.confidence >= reqConf) {
						val vGap = max(cand.y0, s.y0) - min(cand.y1, s.y1)
						val hOverlap = min(cand.x1, s.x1) - max(cand.x0, s.x0)
						val narrow = min(cw, sw)
						val centerDist = abs(centerXc - s.centerX)
						val centerYDist = abs(centerYc - s.centerY)
						if (
							vGap >= -minH * 0.62f &&
							centerYDist >= minH * 0.45f &&
							vGap <= maxH * 1.55f &&
							hOverlap > narrow * minOverlapRatio &&
							centerDist < maxH * 2.1f &&
							(!isNumericContinuation || validPixels == null || !hasVerticalBubbleBarrier(validPixels, imgWidth, imgHeight, cand, s, allLines = cleanedAll))
						) {
							return@any true
						}
					}
					false
				}
			}

			val lines = (strongLines + rescuedLines).sortedWith(
				compareBy<RawOcrLine> { it.y0 }.thenBy { it.x0 },
			)
			if (lines.isEmpty()) return emptyList()

			val collarLums = validPixels?.let { px ->
				FloatArray(lines.size) { idx -> sampleLineCollarLum(px, imgWidth, imgHeight, lines[idx], lines) }
			}

			fun hasIndependentVerticalPartner(targetIdx: Int, otherIdx: Int): Boolean {
				val tBox = lines[targetIdx]
				val oBox = lines[otherIdx]
				val th = tBox.height
				val tw = tBox.width
				for (k in lines.indices) {
					if (k == targetIdx || k == otherIdx) continue
					val kBox = lines[k]
					val kh = kBox.height
					val vGap = max(tBox.y0, kBox.y0) - min(tBox.y1, kBox.y1)
					if (vGap < -min(th, kh) * 0.4f || vGap > max(th, kh) * 1.35f) continue
					val ovT = min(tBox.x1, kBox.x1) - max(tBox.x0, kBox.x0)
					val ovO = min(oBox.x1, kBox.x1) - max(oBox.x0, kBox.x0)
					if (ovT > min(tw, kBox.width) * 0.42f && ovO <= 0f) {
						return true
					}
				}
				return false
			}

			val rowParent = IntArray(lines.size) { it }
			fun findRow(index: Int): Int {
				var cur = index
				while (rowParent[cur] != cur) {
					rowParent[cur] = rowParent[rowParent[cur]]
					cur = rowParent[cur]
				}
				return cur
			}
			val rowX0 = FloatArray(lines.size) { lines[it].x0 }
			val rowX1 = FloatArray(lines.size) { lines[it].x1 }
			for (i in lines.indices) {
				val a = lines[i]
				for (j in i + 1 until lines.size) {
					val b = lines[j]
					val minH = min(a.height, b.height)
					val maxH = max(a.height, b.height)
					val vOv = min(a.y1, b.y1) - max(a.y0, b.y0)
					val hGap = max(a.x0, b.x0) - min(a.x1, b.x1)
					val sameRowOverlapOk = hGap >= -minH * 0.35f ||
						(vOv >= minH * 0.62f && hGap >= -min(a.width, b.width) * 0.55f)
					if (
						vOv >= minH * 0.52f &&
						sameRowOverlapOk &&
						(hGap <= maxH * 0.45f ||
							(validPixels != null && collarLums != null && hGap <= maxH * 0.95f && abs(collarLums[i] - collarLums[j]) < 22f)) &&
						maxH / max(1f, minH) <= 1.65f &&
						(hGap <= 0f || !(hasIndependentVerticalPartner(i, j) && hasIndependentVerticalPartner(j, i))) &&
						(validPixels == null || !hasHorizontalBubbleBarrier(validPixels, imgWidth, imgHeight, a, b, collarLums!![i], collarLums[j], lines))
					) {
						val ri = findRow(i)
						val rj = findRow(j)
						if (ri != rj) {
							rowParent[rj] = ri
							rowX0[ri] = min(rowX0[ri], rowX0[rj])
							rowX1[ri] = max(rowX1[ri], rowX1[rj])
						}
					}
				}
			}
			for (i in lines.indices) {
				val root = findRow(i)
				rowX0[i] = rowX0[root]
				rowX1[i] = rowX1[root]
			}

			fun hasCentralDarkOcclusionBetween(left: RawOcrLine, right: RawOcrLine, colL: Float, colR: Float): Boolean {
				val px = validPixels ?: return false
				val collarMax = max(colL, colR)
				if (collarMax < 165f) return false
				val gx0 = ceil(left.x1).toInt().coerceIn(0, imgWidth - 1)
				val gx1 = floor(right.x0).toInt().coerceIn(0, imgWidth - 1)
				val spanX = gx1 - gx0
				if (spanX < 28) return false
				val midX0 = gx0 + spanX / 5
				val midX1 = gx1 - spanX / 5
				val oy0 = floor(max(left.y0, right.y0)).toInt().coerceIn(0, imgHeight - 1)
				val oy1 = ceil(min(left.y1, right.y1)).toInt().coerceIn(0, imgHeight - 1)
				if (oy1 <= oy0 || midX1 <= midX0) return false
				var samples = 0
				var occluded = 0
				val stepX = max(1, (midX1 - midX0) / 14)
				val stepY = max(1, (oy1 - oy0) / 8)
				var y = oy0
				while (y <= oy1) {
					val rOff = y * imgWidth
					var x = midX0
					while (x <= midX1) {
						samples++
						val pLum = argbLum(px[rOff + x])
						if (pLum <= 125f || collarMax - pLum >= 35f) occluded++
						x += stepX
					}
					y += stepY
				}
				return samples >= 12 && occluded.toFloat() / samples >= 0.32f
			}

			fun spansSeparateSideBySideColumns(wideIdx: Int, colIdx: Int): Boolean {
				val cBox = lines[colIdx]
				val ch = cBox.height
				val wX0 = rowX0[wideIdx]
				val wX1 = rowX1[wideIdx]
				val colRowRoot = findRow(colIdx)
				for (k in lines.indices) {
					if (k == wideIdx || k == colIdx) continue
					// Words already proven to belong to the same horizontal line inside one bubble are NOT separate columns.
					if (findRow(k) == colRowRoot) continue
					val oBox = lines[k]
					val oh = oBox.height
					val vOv = min(cBox.y1, oBox.y1) - max(cBox.y0, oBox.y0)
					val hGap = max(cBox.x0, oBox.x0) - min(cBox.x1, oBox.x1)
					if (vOv >= min(ch, oh) * 0.4f && hGap > max(ch, oh) * 0.55f) {
						val ov1 = min(wX1, cBox.x1) - max(wX0, cBox.x0)
						val ov2 = min(wX1, oBox.x1) - max(wX0, oBox.x0)
						if (ov1 > 0f && ov2 > 0f) return true
					}
				}
				return false
			}

			fun hasVerticalBubblePinch(top: RawOcrLine, bot: RawOcrLine, bgLum: Float): Boolean {
				val px = validPixels ?: return false
				if (bgLum < 215f) return false
				val gapY = (((top.y1 + bot.y0) * 0.5f).roundToInt()).coerceIn(0, imgHeight - 1)
				val topY = top.centerY.roundToInt().coerceIn(0, imgHeight - 1)
				val botY = bot.centerY.roundToInt().coerceIn(0, imgHeight - 1)
				val ovLeft = max(top.x0, bot.x0)
				val ovRight = min(top.x1, bot.x1)
				val midX = if (ovRight > ovLeft) {
					((ovLeft + ovRight) * 0.5f).roundToInt()
				} else {
					((top.centerX + bot.centerX) * 0.5f).roundToInt()
				}.coerceIn(0, imgWidth - 1)
				val maxScan = max(140, (max(top.width, bot.width) * 1.35f).roundToInt())

				fun findWallSpan(y: Int, skipMinX: Int, skipMaxX: Int): Pair<Int, Int>? {
					val rowOff = y * imgWidth
					var leftW = -1
					val minX = max(0, midX - maxScan)
					for (x in min(midX, skipMinX) downTo minX) {
						if (argbLum(px[rowOff + x]) <= 135f) {
							leftW = x
							break
						}
					}
					var rightW = -1
					val maxX = min(imgWidth - 1, midX + maxScan)
					for (x in max(midX, skipMaxX) until maxX + 1) {
						if (argbLum(px[rowOff + x]) <= 135f) {
							rightW = x
							break
						}
					}
					return if (leftW >= 0 && rightW >= 0 && rightW > leftW) leftW to rightW else null
				}

				val gapSpan = findWallSpan(gapY, midX, midX) ?: return false
				val topSpan = findWallSpan(topY, floor(top.x0).toInt().coerceIn(0, imgWidth - 1), ceil(top.x1).toInt().coerceIn(0, imgWidth - 1)) ?: return false
				val botSpan = findWallSpan(botY, floor(bot.x0).toInt().coerceIn(0, imgWidth - 1), ceil(bot.x1).toInt().coerceIn(0, imgWidth - 1)) ?: return false
				val wGap = gapSpan.second - gapSpan.first
				val wTop = topSpan.second - topSpan.first
				val wBot = botSpan.second - botSpan.first
				val pinchedNarrow = wGap < min(wTop, wBot) * 0.72f
				val pinchedBothSides = gapSpan.first > max(topSpan.first, botSpan.first) + 8 &&
					gapSpan.second < min(topSpan.second, botSpan.second) - 8
				return pinchedNarrow || pinchedBothSides
			}

			val occludedSideIndices = HashSet<Int>()
			if (validPixels != null && collarLums != null) {
				for (i in lines.indices) {
					val a = lines[i]
					for (j in i + 1 until lines.size) {
						val b = lines[j]
						val vOv = min(a.y1, b.y1) - max(a.y0, b.y0)
						val minH = min(a.height, b.height)
						val maxH = max(a.height, b.height)
						if (vOv < minH * 0.40f || maxH / max(1f, minH) > 1.55f) continue
						val left = if (a.x0 <= b.x0) a else b
						val right = if (a.x0 <= b.x0) b else a
						val lIdx = if (a.x0 <= b.x0) i else j
						val rIdx = if (a.x0 <= b.x0) j else i
						val hGap = right.x0 - left.x1
						if (hGap > maxH * 0.55f &&
							hasCentralDarkOcclusionBetween(left, right, collarLums[lIdx], collarLums[rIdx])
						) {
							val rowTopY = min(left.y0, right.y0)
							val hasUpperBridgedRow = lines.indices.any { u ->
								if (u == lIdx || u == rIdx) return@any false
								val uBox = lines[u]
								val vDist = rowTopY - uBox.y1
								vDist in (-maxH * 0.2f)..(maxH * 4.5f) &&
									max(uBox.height, maxH) / max(1f, min(uBox.height, minH)) <= 1.65f &&
									rowX0[u] < left.x1 &&
									rowX1[u] > right.x0 &&
									(rowX1[u] - rowX0[u]) > (right.x0 - left.x1)
							}
							if (hasUpperBridgedRow) {
								occludedSideIndices.add(lIdx)
								occludedSideIndices.add(rIdx)
							}
						}
					}
				}
			}

			val parent = IntArray(lines.size) { it }
			fun find(index: Int): Int {
				var i = index
				while (parent[i] != i) {
					parent[i] = parent[parent[i]]
					i = parent[i]
				}
				return i
			}

			for (i in lines.indices) {
				val a = lines[i]
				val ha = a.height
				val wa = a.width
				val centerXa = a.centerX
				val centerYa = a.centerY
				val isStatA = TextBlockFilters.isRpgStatLine(a.text)
				val textA = a.text.trim()
				val wordsA = textA.split(WHITESPACE_REGEX).size

				for (j in i + 1 until lines.size) {
					val b = lines[j]
					val hb = b.height
					val wb = b.width
					val centerXb = b.centerX
					val centerYb = b.centerY
					val minH = min(ha, hb)
					val maxH = max(ha, hb)

					val vOverlap = min(a.y1, b.y1) - max(a.y0, b.y0)
					val hGap = max(a.x0, b.x0) - min(a.x1, b.x1)
					val vertA = ha > wa * 1.4f && (TextBlockFilters.hasCjkOrHangulOrArabic(textA) || textA.length >= 2)
					val textB = b.text.trim()
					val vertB = hb > wb * 1.4f && (TextBlockFilters.hasCjkOrHangulOrArabic(textB) || textB.length >= 2)
					if (vertA && vertB) {
						val minW = min(wa, wb)
						val maxW = max(wa, wb)
						if (
							vOverlap >= minH * 0.25f &&
							hGap >= -minW * 0.45f &&
							hGap <= maxW * 1.25f &&
							maxW / max(1f, minW) <= 1.75f &&
							(validPixels == null || !hasHorizontalBubbleBarrier(validPixels, imgWidth, imgHeight, a, b, collarLums!![i], collarLums[j], lines))
						) {
							parent[find(j)] = find(i)
							continue
						}
					} else if (
						!vertA && !vertB &&
						vOverlap >= minH * 0.52f &&
						(hGap >= -minH * 0.35f ||
							(vOverlap >= minH * 0.62f && hGap >= -min(wa, wb) * 0.55f)) &&
						(hGap <= maxH * 0.45f ||
							(validPixels != null &&
								collarLums != null &&
								hGap <= maxH * 0.95f &&
								abs(collarLums[i] - collarLums[j]) < 22f)) &&
						maxH / max(1f, minH) <= 1.65f &&
						!(hasIndependentVerticalPartner(i, j) && hasIndependentVerticalPartner(j, i)) &&
						(validPixels == null || !hasHorizontalBubbleBarrier(validPixels, imgWidth, imgHeight, a, b, collarLums!![i], collarLums[j], lines))
					) {
						parent[find(j)] = find(i)
						continue
					}

					if (b.y0 - a.y1 > maxH * 2.15f) {
						if (b.y0 - a.y1 > maxH * 4f) break
						continue
					}

					val isStatB = TextBlockFilters.isRpgStatLine(b.text)
					if (isStatA || isStatB) continue

					val wordsB = b.text.trim().split(WHITESPACE_REGEX).size
					if (wordsA == 1 && wordsB == 1 && textA.length <= 12 && textA.equals(textB, ignoreCase = true)) {
						continue
					}
					if (
						ha >= hb * 1.16f &&
						((wordsA <= 3 &&
							(wordsB >= 5 || wb >= wa * 1.42f) &&
							TextBlockFilters.isSystemBoxHeader(textA)) ||
							(textA.startsWith('[') && textA.endsWith(']') && !textB.startsWith('[')))
					) {
						continue
					}

					val vGap = max(a.y0, b.y0) - min(a.y1, b.y1)
					val centerYDist = abs(centerYb - centerYa)
					if (vGap > maxH * 2.15f || vGap < -minH * 0.62f || centerYDist < minH * 0.45f) continue

					var hasIntermediateRow = false
					for (m in i + 1 until j) {
						val mb = lines[m]
						val mCenterY = mb.centerY
						if (mCenterY > a.y1 - minH * 0.2f && mCenterY < b.y0 + minH * 0.2f) {
							val ovA = min(a.x1, mb.x1) - max(a.x0, mb.x0)
							val ovB = min(b.x1, mb.x1) - max(b.x0, mb.x0)
							if (ovA > 0f || ovB > 0f) {
								hasIntermediateRow = true
								break
							}
						}
					}
					if (hasIntermediateRow) continue

					if (
						spansSeparateSideBySideColumns(i, j) ||
						spansSeparateSideBySideColumns(j, i) ||
						(validPixels != null && hasVerticalBubbleBarrier(validPixels, imgWidth, imgHeight, a, b, collarLums!![i], collarLums[j]))
					) {
						continue
					}

					val effAx0 = rowX0[i]
					val effAx1 = rowX1[i]
					val effBx0 = rowX0[j]
					val effBx1 = rowX1[j]
					val effWa = (effAx1 - effAx0).coerceAtLeast(1f)
					val effWb = (effBx1 - effBx0).coerceAtLeast(1f)
					val effCenterXa = (effAx0 + effAx1) * 0.5f
					val effCenterXb = (effBx0 + effBx1) * 0.5f
					val overlap = min(effAx1, effBx1) - max(effAx0, effBx0)
					val narrow = min(effWa, effWb)
					val wide = max(effWa, effWb)
					val centerDist = abs(effCenterXa - effCenterXb)
					val heightRatio = maxH / max(1f, minH)

					val uniformCollarForWideNarration = collarLums == null || abs(collarLums[i] - collarLums[j]) < 28f
					val sameUniformBubble = validPixels != null &&
						collarLums != null &&
						abs(collarLums[i] - collarLums[j]) < 24f &&
						(min(collarLums[i], collarLums[j]) >= 220f || max(collarLums[i], collarLums[j]) <= 55f) &&
						!hasVerticalBubblePinch(a, b, min(collarLums[i], collarLums[j]))

					if (heightRatio <= 1.35f && vGap > maxH * 1.5f) {
						val wideGapAligned =
							(overlap > narrow * 0.60f && centerDist < max(maxH * 1.8f, wide * 0.30f)) ||
								(sameUniformBubble &&
									overlap >= narrow * 0.50f &&
									(centerDist < max(maxH * 2.4f, wide * 0.38f) ||
										abs(effAx0 - effBx0) <= maxH * 0.90f ||
										abs(effAx1 - effBx1) <= maxH * 0.90f))
						if (uniformCollarForWideNarration && wideGapAligned) {
							parent[find(j)] = find(i)
						}
						continue
					}

					if (heightRatio <= 1.65f) {
						val aligned =
							(overlap > narrow * 0.35f && centerDist < max(maxH * 2.2f, wide * 0.32f)) ||
								(overlap > 0f && centerDist < maxH * 1.8f) ||
								(sameUniformBubble &&
									overlap >= narrow * 0.65f &&
									(centerDist < max(maxH * 2.6f, wide * 0.42f) ||
										abs(effAx0 - effBx0) <= maxH * 0.90f ||
										abs(effAx1 - effBx1) <= maxH * 0.90f))
						val maxAllowedGap = if (sameUniformBubble) {
							maxH * 1.75f
						} else {
							max(minH * 1.45f, maxH * 1.25f)
						}
						if (aligned && vGap <= maxAllowedGap) {
							parent[find(j)] = find(i)
							continue
						}
					}

					if (heightRatio <= 2.85f) {
						val narrowUnderWideOffset =
							min(effWa, effWb) < wide * 0.55f &&
								centerDist > wide * 0.20f
						val containedInSameBubble = sameUniformBubble &&
							overlap >= narrow * 0.72f &&
							(centerDist < max(maxH * 2.4f, wide * 0.42f) ||
								abs(effAx0 - effBx0) <= maxH * 0.90f ||
								abs(effAx1 - effBx1) <= maxH * 0.90f)
						val stronglyAligned =
							containedInSameBubble ||
								(!narrowUnderWideOffset && (
									(overlap > narrow * 0.36f && centerDist < maxH * 1.55f) ||
										(overlap > narrow * 0.6f && centerDist < wide * 0.45f)
									))
						val maxGapTall = if (sameUniformBubble) maxH * 1.75f else maxH * 1.45f
						if (stronglyAligned && vGap >= -minH * 0.55f && vGap <= maxGapTall) {
							parent[find(j)] = find(i)
						}
					}
				}
			}

			val groups = LinkedHashMap<Int, ArrayList<RawOcrLine>>()
			val groupOrigIndices = LinkedHashMap<Int, ArrayList<Int>>()
			for (i in lines.indices) {
				val root = find(i)
				groups.getOrPut(root) { ArrayList() }.add(lines[i])
				groupOrigIndices.getOrPut(root) { ArrayList() }.add(i)
			}

			val sortedGroups = ArrayList<Pair<Float, OcrBubbleGroup>>(groups.size)
			for ((root, group) in groups) {
				val origIdxList = groupOrigIndices[root].orEmpty()
				val isVerticalCjkGroup = group.all { it.height > it.width * 1.4f } &&
					group.any { TextBlockFilters.hasCjkOrHangulOrArabic(it.text) }
				if (isVerticalCjkGroup) {
					group.sortWith(compareByDescending<RawOcrLine> { it.x0 }.thenBy { it.y0 })
				} else if (group.size > 1) {
					val byY = group.sortedWith(compareBy<RawOcrLine> { it.y0 }.thenBy { it.x0 })
					val orderedRows = ArrayList<MutableList<RawOcrLine>>()
					var bandCenterY = byY[0].centerY
					var bandHeight = byY[0].height
					var curRow = ArrayList<RawOcrLine>().apply { add(byY[0]) }
					for (idx in 1 until byY.size) {
						val item = byY[idx]
						val minH = min(item.height, bandHeight)
						if (abs(item.centerY - bandCenterY) <= minH * 0.55f) {
							curRow.add(item)
							bandCenterY = (bandCenterY * (curRow.size - 1) + item.centerY) / curRow.size
							bandHeight = (bandHeight * (curRow.size - 1) + item.height) / curRow.size
						} else {
							orderedRows.add(curRow)
							curRow = ArrayList<RawOcrLine>().apply { add(item) }
							bandCenterY = item.centerY
							bandHeight = item.height
						}
					}
					orderedRows.add(curRow)
					group.clear()
					for (row in orderedRows) {
						row.sortBy { it.x0 }
						group.addAll(row)
					}
				}

				val mergedRows = ArrayList<RawOcrLine>(group.size)
				for (item in group) {
					val prev = mergedRows.lastOrNull()
					if (prev != null && !isVerticalCjkGroup) {
						val hp = prev.height
						val hi = item.height
						val minH = min(hp, hi)
						val vOv = min(prev.y1, item.y1) - max(prev.y0, item.y0)
						val hGap = item.x0 - prev.x1
						val sameRowOverlapOk = hGap >= -minH * 0.4f ||
							(vOv >= minH * 0.62f && hGap >= -min(prev.width, item.width) * 0.55f)
						if (
							vOv >= minH * 0.52f &&
							abs(prev.centerY - item.centerY) < minH * 0.45f &&
							sameRowOverlapOk &&
							hGap <= max(hp, hi) * 0.95f &&
							(validPixels == null || !hasHorizontalBubbleBarrier(validPixels, imgWidth, imgHeight, prev, item, allLines = lines))
						) {
							val prevTrim = prev.text.trim()
							var itemTrim = item.text.trim()
							var trimmedOverlapPrefix = false
							if (hGap < -4f) {
								val firstTok = itemTrim.substringBefore(' ', "")
								if (firstTok.length == 1 && firstTok[0].isLetter() && itemTrim.length > 2 && prevTrim.endsWith(firstTok, ignoreCase = true)) {
									itemTrim = itemTrim.substring(1).trimStart()
									trimmedOverlapPrefix = true
								}
							}
							val joiner = if (
								!trimmedOverlapPrefix &&
								hGap <= max(1.5f, minH * 0.12f) &&
								ENDS_WITH_ASCII_ALPHA_REGEX.containsMatchIn(prevTrim) &&
								STARTS_WITH_ASCII_ALPHA_REGEX.containsMatchIn(itemTrim)
							) {
								""
							} else {
								" "
							}
							mergedRows[mergedRows.lastIndex] = RawOcrLine(
								text = "$prevTrim$joiner$itemTrim".trim(),
								confidence = max(prev.confidence, item.confidence),
								x0 = min(prev.x0, item.x0),
								y0 = min(prev.y0, item.y0),
								x1 = max(prev.x1, item.x1),
								y1 = max(prev.y1, item.y1),
							)
							continue
						}
					}
					mergedRows.add(item)
				}

				var x0 = Float.POSITIVE_INFINITY
				var y0 = Float.POSITIVE_INFINITY
				var x1 = Float.NEGATIVE_INFINITY
				var y1 = Float.NEGATIVE_INFINITY
				for (l in mergedRows) {
					x0 = min(x0, l.x0)
					y0 = min(y0, l.y0)
					x1 = max(x1, l.x1)
					y1 = max(y1, l.y1)
				}

				val joinedText = if (isVerticalCjkGroup) {
					mergedRows.joinToString("") { it.text }
				} else {
					mergedRows.joinToString(" ") { it.text }
				}
				val text = TextBlockFilters.joinHyphenatedWords(joinedText)
				if (text.isEmpty()) continue

				val groupBox = NormalizedBox(
					yMin = toNormCoord(y0, imgHeight),
					xMin = toNormCoord(x0, imgWidth),
					yMax = toNormCoord(y1, imgHeight),
					xMax = toNormCoord(x1, imgWidth),
				)
				if (!groupBox.isValid()) continue

				var emphasisLastLineScale = 1f
				if (!isVerticalCjkGroup && mergedRows.size >= 2) {
					val lastH = mergedRows.last().height
					var sumOtherH = 0f
					for (idx in 0 until mergedRows.lastIndex) {
						sumOtherH += mergedRows[idx].height
					}
					val avgOtherH = sumOtherH / mergedRows.lastIndex
					val ratio = if (avgOtherH > 0f) lastH / avgOtherH else 1f
					val minRatio = if (mergedRows.size >= 3) 1.32f else 1.48f
					if (avgOtherH > 0f && ratio >= minRatio && (lastH - avgOtherH) >= 7f) {
						val clamped = ratio.coerceIn(1.25f, 1.65f)
						emphasisLastLineScale = (clamped * 100f).roundToInt() / 100f
					}
				}

				val lineModels = mergedRows.map { r ->
					OcrLine(
						box = NormalizedBox(
							yMin = toNormCoord(r.y0, imgHeight),
							xMin = toNormCoord(r.x0, imgWidth),
							yMax = toNormCoord(r.y1, imgHeight),
							xMax = toNormCoord(r.x1, imgWidth),
						),
						text = r.text,
						confidence = (r.confidence / 100f).coerceIn(0f, 1f),
					)
				}

				val bw = x1 - x0
				val bh = y1 - y0
				val avgConf = if (lineModels.isEmpty()) 0.5f else lineModels.sumOf { it.confidence.toDouble() }.toFloat() / lineModels.size
				if (validPixels != null &&
					occludedSideIndices.isNotEmpty() &&
					origIdxList.isNotEmpty() &&
					origIdxList.any { it in occludedSideIndices } &&
					origIdxList.all { idx ->
						val t = lines[idx].text.trim().trimEnd('.', ',')
						val oneToken = t.isNotEmpty() && !t.contains(' ') && t.none { ch -> ch == '?' || ch == '!' || ch == '.' }
						val nearOccluded = idx in occludedSideIndices || origIdxList.any { oi ->
							oi in occludedSideIndices && min(lines[idx].x1, lines[oi].x1) > max(lines[idx].x0, lines[oi].x0)
						}
						oneToken && nearOccluded
					}
				) {
					continue
				}
				if (validPixels != null &&
					!isVerticalCjkGroup &&
					(isLikelyNonBubblePseudoWord(
						pixels = validPixels,
						imgW = imgWidth,
						imgH = imgHeight,
						x0 = x0,
						y0 = y0,
						x1 = x1,
						y1 = y1,
						mergedLineCount = mergedRows.size,
						text = text,
						avgConf = avgConf,
					) || isLikelyQrOrPatternNoise(
						pixels = validPixels,
						imgW = imgWidth,
						imgH = imgHeight,
						x0 = x0,
						y0 = y0,
						x1 = x1,
						y1 = y1,
						lines = mergedRows,
						text = text,
						avgConf = avgConf,
					))
				) {
					continue
				}
				val isVertical = bh > bw * 1.4f && mergedRows.size == 1 && TextBlockFilters.hasCjkOrHangulOrArabic(text)
				val kindHint = if (TextBlockFilters.isPureSoundEffect(text)) TextBlockKind.SFX else TextBlockKind.DIALOGUE

				sortedGroups.add(
					y0 to OcrBubbleGroup(
						id = "",
						box = groupBox,
						text = text,
						lines = lineModels,
						isVertical = isVertical,
						kindHint = kindHint,
						emphasisLastLineScale = emphasisLastLineScale,
					),
				)
			}

			fun normIoMin(a: NormalizedBox, b: NormalizedBox): Float {
				val ix = min(a.xMax, b.xMax) - max(a.xMin, b.xMin)
				val iy = min(a.yMax, b.yMax) - max(a.yMin, b.yMin)
				if (ix <= 0f || iy <= 0f) return 0f
				val inter = ix * iy
				val areaA = (a.xMax - a.xMin).coerceAtLeast(1f) * (a.yMax - a.yMin).coerceAtLeast(1f)
				val areaB = (b.xMax - b.xMin).coerceAtLeast(1f) * (b.yMax - b.yMin).coerceAtLeast(1f)
				return inter / min(areaA, areaB)
			}

			fun groupScore(g: OcrBubbleGroup): Float {
				val avgConf = if (g.lines.isEmpty()) 0.5f else g.lines.sumOf { it.confidence.toDouble() }.toFloat() / g.lines.size
				val letters = TextBlockFilters.countLetters(g.text).coerceAtLeast(1)
				return avgConf * sqrt(letters.toFloat())
			}

			val dedupedGroups = ArrayList<Pair<Float, OcrBubbleGroup>>(sortedGroups.size)
			for (candPair in sortedGroups.sortedByDescending { groupScore(it.second) }) {
				val cand = candPair.second
				val collides = dedupedGroups.any { existingPair ->
					val existing = existingPair.second
					val hasOverlappingLine = existing.lines.any { el ->
						cand.lines.any { cl ->
							val lineOverlap = normIoMin(el.box, cl.box)
							lineOverlap >= 0.78f ||
								(lineOverlap >= 0.48f && textsOverlapNearDuplicate(el.text, cl.text))
						}
					}
					if (hasOverlappingLine) {
						true
					} else {
						normIoMin(existing.box, cand.box) >= 0.72f &&
							textsOverlapNearDuplicate(existing.text, cand.text)
					}
				}
				if (!collides) {
					dedupedGroups.add(candPair)
				}
			}

			dedupedGroups.sortWith(compareBy<Pair<Float, OcrBubbleGroup>> { it.first }.thenBy { it.second.box.xMin })
			return dedupedGroups.mapIndexed { index, pair ->
				pair.second.copy(id = "b$index")
			}
		}

		private fun isUniformCollarBubble(
			pixels: IntArray,
			imgW: Int,
			imgH: Int,
			x0: Float,
			y0: Float,
			x1: Float,
			y1: Float,
		): Boolean {
			val bx0 = (floor(x0).toInt() - 4).coerceIn(0, imgW - 1)
			val bx1 = (ceil(x1).toInt() + 4).coerceIn(0, imgW - 1)
			val by0 = (floor(y0).toInt() - 4).coerceIn(0, imgH - 1)
			val by1 = (ceil(y1).toInt() + 4).coerceIn(0, imgH - 1)
			if (bx1 - bx0 < 8 || by1 - by0 < 8) return false

			val borderLums = ArrayList<Float>(48)
			val stepX = max(1, (bx1 - bx0) / 14)
			var x = bx0
			while (x <= bx1) {
				borderLums.add(argbLum(pixels[by0 * imgW + x]))
				borderLums.add(argbLum(pixels[by1 * imgW + x]))
				x += stepX
			}
			val stepY = max(1, (by1 - by0) / 6)
			var y = by0 + stepY
			while (y < by1) {
				borderLums.add(argbLum(pixels[y * imgW + bx0]))
				borderLums.add(argbLum(pixels[y * imgW + bx1]))
				y += stepY
			}
			if (borderLums.size < 8) return false
			borderLums.sort()
			val median = borderLums[borderLums.size / 2]
			val spread = borderLums.last() - borderLums.first()
			val uniformRatio = borderLums.count { abs(it - median) <= 18f }.toFloat() / borderLums.size
			return spread <= 48f && uniformRatio >= 0.72f
		}

		internal fun isLikelyQrOrPatternNoise(
			pixels: IntArray,
			imgW: Int,
			imgH: Int,
			x0: Float,
			y0: Float,
			x1: Float,
			y1: Float,
			lines: List<RawOcrLine>,
			text: String,
			avgConf: Float,
		): Boolean {
			if (lines.isEmpty() || TextBlockFilters.hasCjkOrHangulOrArabic(text)) return false
			val trimmed = text.trim()
			if (trimmed.none { it.isLetter() }) return false
			val hasPunctuation = trimmed.any { it == '?' || it == '!' || it == '.' || it == ',' }
			if (isUniformCollarBubble(pixels, imgW, imgH, x0, y0, x1, y1)) return false

			val bx0 = floor(x0).toInt().coerceIn(0, imgW - 1)
			val bx1 = ceil(x1).toInt().coerceIn(bx0, imgW - 1)
			val by0 = floor(y0).toInt().coerceIn(0, imgH - 1)
			val by1 = ceil(y1).toInt().coerceIn(by0, imgH - 1)
			val bw = bx1 - bx0 + 1
			val bh = by1 - by0 + 1

			// High-contrast QR/finder square grid detection (checkerboard transitions without real sentences)
			if (!hasPunctuation && bw >= 28 && bh >= 28 && (avgConf < 0.76f || lines.none { it.confidence >= 80f })) {
				var totalSamples = 0
				var binarySamples = 0
				var horizTransitions = 0
				var rowCount = 0
				val stepY = max(1, bh / 12)
				val stepX = max(1, bw / 24)
				var py = by0
				while (py <= by1) {
					val rOff = py * imgW
					var prevDark: Boolean? = null
					var px = bx0
					while (px <= bx1) {
						val lum = argbLum(pixels[rOff + px])
						totalSamples++
						if (lum < 110f || lum > 215f) binarySamples++
						val isDark = lum < 140f
						if (prevDark != null && isDark != prevDark) horizTransitions++
						prevDark = isDark
						px += stepX
					}
					rowCount++
					py += stepY
				}
				val bwRatio = binarySamples.toFloat() / max(1, totalSamples)
				val avgTrans = horizTransitions.toFloat() / max(1, rowCount)
				if (bwRatio >= 0.62f && avgTrans >= 4.2f) return true
			}

			// Artwork / stylised Korean title-logo gibberish on non-bubble art (e.g. "Lt T8o gaSOFLAEF")
			if (!hasPunctuation && avgConf < 0.80f && lines.none { it.confidence >= 92f }) {
				val tokens = trimmed.split(WHITESPACE_REGEX)
				val hasGibberishToken = tokens.any { tok ->
					val hasDigitInsideLetters = tok.length >= 3 && tok.any { it.isDigit() } && tok.any { it.isLetter() }
					val hasLowerBeforeCaps = tok.length >= 4 &&
						tok.indexOfFirst { it.isLowerCase() } in 0..1 &&
						tok.count { it.isUpperCase() } >= 3
					hasDigitInsideLetters || hasLowerBeforeCaps
				}
				if (hasGibberishToken) return true
			}

			// Multi-token / signboard low-confidence texture pareidolia without punctuation
			return lines.size <= 2 &&
				!hasPunctuation &&
				avgConf < 0.62f &&
				lines.none { it.confidence >= 68f }
		}

		internal fun isLikelyNonBubblePseudoWord(
			pixels: IntArray,
			imgW: Int,
			imgH: Int,
			x0: Float,
			y0: Float,
			x1: Float,
			y1: Float,
			mergedLineCount: Int,
			text: String,
			avgConf: Float,
		): Boolean {
			if (mergedLineCount != 1) return false
			val trimmed = text.trim()
			if (trimmed.isEmpty() || ' ' in trimmed) return false
			if ('?' in trimmed || '!' in trimmed || '[' in trimmed || ']' in trimmed) return false
			if (TextBlockFilters.hasCjkOrHangulOrArabic(trimmed)) return false
			if (trimmed.any { it.isDigit() }) return false
			val alpha = buildString {
				for (ch in trimmed) {
					if (ch.isLetter()) append(ch.lowercaseChar())
				}
			}
			if (alpha.length !in 2..11) return false
			if (alpha in KNOWN_ISOLATED_ENGLISH_TOKENS) return false
			if (alpha.length >= 5 && (alpha.endsWith("ing") || alpha.endsWith("tion") || alpha.endsWith("ment") || alpha.endsWith("ness") || alpha.endsWith("able"))) {
				return false
			}

			if (isUniformCollarBubble(pixels, imgW, imgH, x0, y0, x1, y1)) return false

			return avgConf < 0.94f || alpha.length <= 4
		}

		private val KNOWN_ISOLATED_ENGLISH_TOKENS = hashSetOf(
			"no", "yes", "ok", "okay", "oh", "ah", "eh", "uh", "um", "hm", "hmm", "hmmm", "hnnh", "hnh",
			"huh", "he", "she", "it", "we", "they", "you", "me", "my", "hi", "hey", "bye", "yo",
			"wait", "stop", "run", "help", "why", "who", "what", "how", "when", "where", "well",
			"sure", "fine", "still", "really", "sorry", "please", "thanks", "thank", "hello",
			"miracle", "sigh", "hoho", "hehe", "haha", "ahem", "aahem", "ding", "beep", "excuse",
			"master", "chief", "doctor", "nurse", "right", "next", "later", "more", "less", "none",
			"done", "gone", "back", "down", "here", "there", "look", "listen", "stay", "come", "go",
			"dead", "alive", "alone", "scared", "crazy", "insane", "weird", "strange", "quiet",
			"junk", "code", "tip", "note", "mail", "site", "info", "stats", "status", "name", "open",
		)
	}
}
