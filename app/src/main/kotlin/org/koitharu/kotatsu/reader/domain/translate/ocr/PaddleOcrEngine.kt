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
			// Source-locale hint match: don't trigger cross-script probes on SFX-heavy pages when hint is clearly legible
			if (
				group == hintGroup &&
				result.usableLines >= 1 &&
				result.avgConfidence >= 56f &&
				result.validCharRatio >= 0.72f &&
				result.scriptPurity >= 0.70f &&
				result.totalScriptLetters >= 4
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
	) {
		val width: Float get() = x1 - x0
		val height: Float get() = y1 - y0
		val area: Float get() = max(0f, width) * max(0f, height)
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
				if (!isFirst && b.y0 <= 6f && b.y1 < mapH * 0.55f) continue
				if (!isLast && b.y1 >= mapH - 6f && b.y0 > mapH * 0.45f) continue

				val pageX0 = (b.x0 * fx).coerceIn(0f, width.toFloat())
				val pageY0 = (b.y0 * fy + sy).coerceIn(0f, height.toFloat())
				val pageX1 = (b.x1 * fx).coerceIn(0f, width.toFloat())
				val pageY1 = (b.y1 * fy + sy).coerceIn(0f, height.toFloat())
				val cw = pageX1 - pageX0
				val ch = pageY1 - pageY0
				if (cw < 10f || ch < 8f || ch > cw * 20f) continue

				val hasTiltedObb = abs(b.angleDeg) >= 6f && b.obbW > 1f && b.obbH > 1f
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
					)
				} else {
					DetRegion(pageX0, pageY0, pageX1, pageY1)
				}

				var replacedOrDuplicate = false
				for (idx in detected.indices) {
					val existing = detected[idx]
					if (iouRect(existing, cand) > 0.50f || ioMinRect(existing, cand) > 0.65f) {
						if (cand.area > existing.area * 1.15f) {
							detected[idx] = cand
						}
						replacedOrDuplicate = true
						break
					}
				}
				if (!replacedOrDuplicate) {
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
		var recFloatArray = FloatArray(0)
		var recDirectBuf: FloatBuffer? = null
		var recLogitsArray = FloatArray(0)
		val collectedLines = ArrayList<RawOcrLine>(regions.size)
		var aspectSkipped = 0
		var lowConfSkipped = 0

		for (region in regions) {
			currentCoroutineContext().ensureActive()
			val cw = region.width
			val ch = region.height
			if (cw < 10f || ch < 8f) continue
			val useTiltedUnwarp = abs(region.angleDeg) >= 6f && region.obbW >= 12f && region.obbH >= 8f
			val effW = if (useTiltedUnwarp) region.obbW else cw
			val effH = if (useTiltedUnwarp) region.obbH else ch
			if (!useTiltedUnwarp && !allowVerticalRotation && ch > cw * 6f) {
				aspectSkipped++
				continue
			}

			val rotateVertical = !useTiltedUnwarp && allowVerticalRotation && ch >= cw * 1.6f
			val padAlongW = if (rotateVertical) (cw * 0.08f).coerceIn(1.5f, 4f) else (effH * 0.14f).coerceIn(2f, 5f)
			val padAlongH = if (rotateVertical) (ch * 0.05f).coerceIn(2f, 5f) else (effH * 0.08f).coerceIn(1.5f, 4f)
			val sampleW = effW + padAlongW * 2f
			val sampleH = effH + padAlongH * 2f

			val rawTw = if (rotateVertical) {
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

			val sampleX0 = (region.x0 - padAlongW).coerceIn(0f, (width - 1).toFloat())
			val sampleY0 = (region.y0 - padAlongH).coerceIn(0f, (height - 1).toFloat())
			val sampleX1 = (region.x1 + padAlongW).coerceIn(sampleX0 + 1f, width.toFloat())
			val sampleY1 = (region.y1 + padAlongH).coerceIn(sampleY0 + 1f, height.toFloat())

			val cropX0 = floor(sampleX0).toInt().coerceIn(0, width - 1)
			val cropY0 = floor(sampleY0).toInt().coerceIn(0, height - 1)
			val cropX1 = ceil(sampleX1).toInt().coerceIn(cropX0 + 1, width)
			val cropY1 = ceil(sampleY1).toInt().coerceIn(cropY0 + 1, height)
			val cropW = cropX1 - cropX0
			val cropH = cropY1 - cropY0
			val neededPixels = cropW * cropH
			if (cropPixels.size < neededPixels) {
				cropPixels = IntArray(neededPixels)
			}
			bitmap.getPixels(cropPixels, 0, cropW, cropX0, cropY0, cropW, cropH)

			if (useTiltedUnwarp) {
				sampleRotatedAngleToBgrTensor(
					pixels = cropPixels,
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
			} else if (rotateVertical) {
				sampleRotated90CcwToBgrTensor(
					pixels = cropPixels,
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
			} else {
				sampleToBgrTensor(
					pixels = cropPixels,
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

			val (text, confidence) = OnnxTensor.createTensor(
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

			val trimmed = text.trim()
			if (trimmed.isEmpty() || confidence < minConfidence) {
				lowConfSkipped++
				continue
			}
			collectedLines.add(
				RawOcrLine(
					text = trimmed,
					confidence = confidence,
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

		// Dilim örtüşmelerinden gelen çift kutuları IoU ve içerme (IoMin) ile tekilleştir
		val sorted = collectedLines.sortedByDescending { it.confidence * sqrt(it.text.length.coerceAtLeast(1).toFloat()) }
		val kept = ArrayList<RawOcrLine>(sorted.size)
		for (line in sorted) {
			if (kept.any { existing -> iou(existing, line) > 0.45f || ioMin(existing, line) > 0.68f }) continue
			kept.add(line)
		}
		return kept
	}

	private fun iouRect(a: DetRegion, b: DetRegion): Float {
		val ix = min(a.x1, b.x1) - max(a.x0, b.x0)
		val iy = min(a.y1, b.y1) - max(a.y0, b.y0)
		if (ix <= 0f || iy <= 0f) return 0f
		val inter = ix * iy
		val areaA = a.width * a.height
		val areaB = b.width * b.height
		return inter / max(1f, areaA + areaB - inter)
	}

	private fun ioMinRect(a: DetRegion, b: DetRegion): Float {
		val ix = min(a.x1, b.x1) - max(a.x0, b.x0)
		val iy = min(a.y1, b.y1) - max(a.y0, b.y0)
		if (ix <= 0f || iy <= 0f) return 0f
		val inter = ix * iy
		return inter / max(1f, min(a.area, b.area))
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
		private const val DET_MAX_WIDTH = 960
		private const val DET_SLICE_H = 1536
		private const val DET_SLICE_OVERLAP = 192
		private const val DET_BIN_THRESH = 0.3f
		internal const val DET_BOX_THRESH = 0.50f
		private const val DET_UNCLIP_RATIO = 1.78f
		private const val REC_H = 48
		private const val REC_MAX_W = 1600
		private const val INV_255 = 1f / 255f
		private const val MAX_GROUPING_PIXELS = 32_000_000L

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
				if (count < 8 || bw < 3 || bh < 3) continue
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
				if (count >= 24 && fillRatio < 0.65f && bw >= 10 && bh >= 8) {
					val mx = sumX / count
					val my = sumY / count
					val cxx = sumXX / count - mx * mx
					val cyy = sumYY / count - my * my
					val cxy = sumXY / count - mx * my
					val theta = 0.5f * atan2(2f * cxy, cxx - cyy)
					val deg = Math.toDegrees(theta.toDouble()).toFloat()
					if (abs(deg) in 6f..68f) {
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
						if (uSpan / vSpan >= 2.0f && (uSpan * vSpan) < (bw * bh) * 0.80f) {
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
		): Float {
			val xStart = max(0, floor(line.x0).toInt())
			val xEnd = min(width - 1, ceil(line.x1).toInt())
			if (xEnd < xStart) return 255f
			val stepX = max(1, (xEnd - xStart) / 14)
			val lums = ArrayList<Float>(64)
			val sampleYs = intArrayOf(
				max(0, floor(line.y0).toInt() - 3),
				max(0, floor(line.y0).toInt() - 1),
				min(height - 1, ceil(line.y1).toInt() + 1),
				min(height - 1, ceil(line.y1).toInt() + 3),
			)
			for (y in sampleYs) {
				val rowOff = y * width
				var x = xStart
				while (x <= xEnd) {
					lums.add(argbLum(pixels[rowOff + x]))
					x += stepX
				}
			}
			if (lums.isEmpty()) return 255f
			lums.sort()
			val idx = (lums.size * 0.55f).toInt().coerceIn(0, lums.lastIndex)
			return lums[idx]
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
		): Boolean {
			val leftFirst = lineA.x0 <= lineB.x0
			val left = if (leftFirst) lineA else lineB
			val right = if (leftFirst) lineB else lineA
			val bgL = (if (leftFirst) collarLumA else collarLumB) ?: sampleLineCollarLum(pixels, width, height, left)
			val bgR = (if (leftFirst) collarLumB else collarLumA) ?: sampleLineCollarLum(pixels, width, height, right)
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
						if (argbLum(pixels[y * width + x]) <= 135f) {
							topDark = true
							break
						}
					}
					if (topDark) {
						val botStart = max(0, ovY1 - 2)
						for (y in botStart..cuspBotY) {
							if (argbLum(pixels[y * width + x]) <= 135f) {
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
						if (argbLum(pixels[y * width + cx]) <= 120f) return y
					}
					return null
				}

				fun findWallBelow(px: Float, startY: Int): Int? {
					val cx = px.roundToInt().coerceIn(0, width - 1)
					val clampedStart = startY.coerceIn(0, height - 1)
					val stopY = min(height - 1, clampedStart + scanMax)
					for (y in clampedStart..stopY) {
						if (argbLum(pixels[y * width + cx]) <= 120f) return y
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
		): Boolean {
			val topFirst = lineA.y0 <= lineB.y0
			val top = if (topFirst) lineA else lineB
			val bot = if (topFirst) lineB else lineA
			val vGapPx = bot.y0 - top.y1
			val bgT = (if (topFirst) collarLumA else collarLumB) ?: sampleLineCollarLum(pixels, width, height, top)
			val bgB = (if (topFirst) collarLumB else collarLumA) ?: sampleLineCollarLum(pixels, width, height, bot)
			// Kalın siyah konturlu orta ton sahne/anlatım yazılarında sıkı dikey yaka pikselinin biri siyah kontura denk gelebilir
			val fakeOutlineCollar = vGapPx <= 6f &&
				min(bgT, bgB) <= 28f &&
				max(bgT, bgB) >= 75f &&
				max(bgT, bgB) < 220f
			if (abs(bgT - bgB) >= 75f && !fakeOutlineCollar) return true

			if (vGapPx <= 1.5f) return false
			val gapY0 = max(0, floor(top.y1).toInt() + 1)
			val gapY1 = min(height - 1, ceil(bot.y0).toInt() - 1)
			if (gapY1 < gapY0) return false

			val ovX0 = max(0, floor(max(top.x0, bot.x0)).toInt())
			val ovX1 = min(width - 1, ceil(min(top.x1, bot.x1)).toInt())
			val spanX = ovX1 - ovX0 + 1
			if (spanX < 4) return false

			val bothLight = bgT >= 165f && bgB >= 165f
			val bothDark = bgT <= 105f && bgB <= 105f
			for (y in gapY0..gapY1) {
				var darkCount = 0
				var brightCount = 0
				var contrastWallCount = 0
				val rowOff = y * width
				for (x in ovX0..ovX1) {
					val lum = argbLum(pixels[rowOff + x])
					if (lum <= 145f) darkCount += 1
					if (lum >= 180f) brightCount += 1
					if (abs(lum - bgT) >= 42f && abs(lum - bgB) >= 42f) contrastWallCount += 1
				}
				if (bothLight && darkCount.toFloat() / spanX >= 0.45f) return true
				if (bothDark && brightCount.toFloat() / spanX >= 0.45f) return true
				if (!fakeOutlineCollar && contrastWallCount.toFloat() / spanX >= 0.55f) return true
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
				val junk = TextBlockFilters.countJunk(cand.text)
				if (letters < 1) return@filter false
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
					if (letters >= 2 && junk <= maxJunk && cand.confidence >= reqConf) {
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
							centerDist < maxH * 2.1f
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
				FloatArray(lines.size) { idx -> sampleLineCollarLum(px, imgWidth, imgHeight, lines[idx]) }
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

			fun spansSeparateSideBySideColumns(wideIdx: Int, colIdx: Int): Boolean {
				val wBox = lines[wideIdx]
				val cBox = lines[colIdx]
				val ch = cBox.height
				for (k in lines.indices) {
					if (k == wideIdx || k == colIdx) continue
					val oBox = lines[k]
					val oh = oBox.height
					val vOv = min(cBox.y1, oBox.y1) - max(cBox.y0, oBox.y0)
					val hGap = max(cBox.x0, oBox.x0) - min(cBox.x1, oBox.x1)
					if (vOv >= min(ch, oh) * 0.4f && hGap > max(ch, oh) * 0.55f) {
						val ov1 = min(wBox.x1, cBox.x1) - max(wBox.x0, cBox.x0)
						val ov2 = min(wBox.x1, oBox.x1) - max(wBox.x0, oBox.x0)
						if (ov1 > 0f && ov2 > 0f) return true
					}
				}
				return false
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
							(validPixels == null || !hasHorizontalBubbleBarrier(validPixels, imgWidth, imgHeight, a, b, collarLums!![i], collarLums[j]))
						) {
							parent[find(j)] = find(i)
							continue
						}
					} else if (
						!vertA && !vertB &&
						vOverlap >= minH * 0.52f &&
						hGap >= -minH * 0.35f &&
						hGap <= maxH * 0.45f &&
						maxH / max(1f, minH) <= 1.65f &&
						!(hasIndependentVerticalPartner(i, j) && hasIndependentVerticalPartner(j, i)) &&
						(validPixels == null || !hasHorizontalBubbleBarrier(validPixels, imgWidth, imgHeight, a, b, collarLums!![i], collarLums[j]))
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
					if (
						ha >= hb * 1.16f &&
						wordsA <= 3 &&
						(wordsB >= 5 || wb >= wa * 1.42f) &&
						TextBlockFilters.isSystemBoxHeader(textA)
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
						(validPixels != null && vGap >= -2f && hasVerticalBubbleBarrier(validPixels, imgWidth, imgHeight, a, b, collarLums!![i], collarLums[j]))
					) {
						continue
					}

					val overlap = min(a.x1, b.x1) - max(a.x0, b.x0)
					val narrow = min(wa, wb)
					val centerDist = abs(centerXa - centerXb)
					val heightRatio = maxH / max(1f, minH)

					val uniformCollarForWideNarration = collarLums == null || abs(collarLums[i] - collarLums[j]) < 28f
					if (heightRatio <= 1.35f && vGap > maxH * 1.5f) {
						if (
							uniformCollarForWideNarration &&
							overlap > narrow * 0.60f &&
							centerDist < max(maxH * 1.8f, max(wa, wb) * 0.30f)
						) {
							parent[find(j)] = find(i)
						}
						continue
					}

					if (heightRatio <= 1.65f) {
						val aligned =
							(overlap > narrow * 0.35f && centerDist < max(maxH * 2.2f, max(wa, wb) * 0.32f)) ||
								(overlap > 0f && centerDist < maxH * 1.8f)
						if (aligned && vGap <= minH * 1.35f) {
							parent[find(j)] = find(i)
							continue
						}
					}

					if (heightRatio <= 2.85f) {
						val stronglyAligned =
							(overlap > narrow * 0.36f && centerDist < maxH * 1.55f) ||
								(overlap > narrow * 0.6f && centerDist < max(wa, wb) * 0.45f)
						if (stronglyAligned && vGap >= -minH * 0.55f && vGap <= maxH * 1.45f) {
							parent[find(j)] = find(i)
						}
					}
				}
			}

			val groups = LinkedHashMap<Int, ArrayList<RawOcrLine>>()
			for (i in lines.indices) {
				val root = find(i)
				groups.getOrPut(root) { ArrayList() }.add(lines[i])
			}

			val sortedGroups = ArrayList<Pair<Float, OcrBubbleGroup>>(groups.size)
			for (group in groups.values) {
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
						if (
							vOv >= minH * 0.52f &&
							abs(prev.centerY - item.centerY) < minH * 0.45f &&
							hGap >= -minH * 0.4f &&
							hGap <= minH * 0.65f &&
							(validPixels == null || !hasHorizontalBubbleBarrier(validPixels, imgWidth, imgHeight, prev, item))
						) {
							val joiner = if (
								hGap <= max(1.5f, minH * 0.12f) &&
								ENDS_WITH_ASCII_ALPHA_REGEX.containsMatchIn(prev.text) &&
								STARTS_WITH_ASCII_ALPHA_REGEX.containsMatchIn(item.text)
							) {
								""
							} else {
								" "
							}
							mergedRows[mergedRows.lastIndex] = RawOcrLine(
								text = "${prev.text}$joiner${item.text}".trim(),
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
					if (avgOtherH > 0f && lastH / avgOtherH >= 1.32f) {
						val clamped = (lastH / avgOtherH).coerceIn(1.25f, 1.65f)
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

			sortedGroups.sortBy { it.first }
			return sortedGroups.mapIndexed { index, pair ->
				pair.second.copy(id = "b$index")
			}
		}
	}
}
