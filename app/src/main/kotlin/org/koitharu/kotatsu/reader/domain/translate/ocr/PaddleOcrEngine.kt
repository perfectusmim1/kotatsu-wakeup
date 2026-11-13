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
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
					"recognizePage($sourceLang): det regions=${detRegions.size} on ${width}x${height}",
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
					val bubbles = groupLinesIntoBubbles(rawLines, width, height)
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
	): String {
		val probeRegions = selectAutoProbeRegions(detRegions)
		if (probeRegions.isEmpty()) {
			return lastAutoGroup.get() ?: OcrModelManager.DEFAULT_AUTO_GROUP
		}

		val readyGroups = withContext(Dispatchers.IO) {
			modelManager.readyGroupsInPriority(preferredFirst = lastAutoGroup.get())
		}
		var bestResult: AutoProbeResult? = null
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
			if (bestResult == null || result.combinedScore > bestResult.combinedScore) {
				bestResult = result
			}
			if (isStrongAutoMatch(result, probeRegions.size)) {
				lastAutoGroup.set(group)
				Log.i(LOG_TAG, "AUTO fast-accepted '$group' (no extra downloads)")
				return group
			}
		}

		val currentBest = bestResult
		if (currentBest != null && isAcceptableAutoMatch(currentBest)) {
			lastAutoGroup.set(currentBest.group)
			Log.i(LOG_TAG, "AUTO selected already-ready '${currentBest.group}' (score=${"%.1f".format(currentBest.combinedScore)})")
			return currentBest.group
		}

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
			if (isStrongAutoMatch(result, probeRegions.size) || isAcceptableAutoMatch(result)) {
				break
			}
		}

		val winner = bestResult?.group ?: OcrModelManager.DEFAULT_AUTO_GROUP
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

	private fun selectAutoProbeRegions(detRegions: List<DetRegion>): List<DetRegion> {
		val candidates = detRegions.filter { r ->
			val w = r.width
			val h = r.height
			w >= 12f && h >= 10f && h <= w * 20f
		}.ifEmpty { detRegions }
		val ranked = candidates.sortedByDescending { r ->
			val minDim = min(r.width, r.height)
			minDim * sqrt((r.width * r.height).coerceAtLeast(1f))
		}
		val horizontal = ranked.filter { it.height < it.width * 1.55f }
		val vertical = ranked.filter { it.height >= it.width * 1.55f }
		val selected = ArrayList<DetRegion>(AUTO_PROBE_MAX_BOXES)
		for (r in horizontal.take(4)) selected.add(r)
		for (r in vertical.take(2)) {
			if (selected.size < AUTO_PROBE_MAX_BOXES && r !in selected) selected.add(r)
		}
		for (r in ranked) {
			if (selected.size >= AUTO_PROBE_MAX_BOXES) break
			if (r !in selected) selected.add(r)
		}
		return selected
	}

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
		result.usableLines >= 1 &&
			result.avgConfidence >= 68f &&
			result.validCharRatio >= 0.65f &&
			result.scriptPurity >= 0.50f &&
			result.totalScriptLetters >= 2

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

	private data class DetRegion(
		val x0: Float,
		val y0: Float,
		val x1: Float,
		val y1: Float,
	) {
		val width: Float get() = x1 - x0
		val height: Float get() = y1 - y0
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
				if (!isFirst && b.y0 <= 2f) continue
				if (!isLast && b.y1 >= mapH - 2f) continue

				val pageX0 = (b.x0 * fx).coerceIn(0f, width.toFloat())
				val pageY0 = (b.y0 * fy + sy).coerceIn(0f, height.toFloat())
				val pageX1 = (b.x1 * fx).coerceIn(0f, width.toFloat())
				val pageY1 = (b.y1 * fy + sy).coerceIn(0f, height.toFloat())
				val cw = pageX1 - pageX0
				val ch = pageY1 - pageY0
				if (cw < 10f || ch < 8f || ch > cw * 20f) continue
				val cand = DetRegion(pageX0, pageY0, pageX1, pageY1)
				if (detected.any { existing -> iouRect(existing, cand) > 0.55f }) continue
				detected.add(cand)
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
			if (!allowVerticalRotation && ch > cw * 6f) {
				aspectSkipped++
				continue
			}

			val rotateVertical = allowVerticalRotation && ch >= cw * 1.6f
			val rawTw = if (rotateVertical) {
				((REC_H * ch) / cw).roundToInt()
			} else {
				((REC_H * cw) / ch).roundToInt()
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

			val cropX0 = floor(region.x0).toInt().coerceIn(0, width - 1)
			val cropY0 = floor(region.y0).toInt().coerceIn(0, height - 1)
			val cropX1 = ceil(region.x1).toInt().coerceIn(cropX0 + 1, width)
			val cropY1 = ceil(region.y1).toInt().coerceIn(cropY0 + 1, height)
			val cropW = cropX1 - cropX0
			val cropH = cropY1 - cropY0
			val neededPixels = cropW * cropH
			if (cropPixels.size < neededPixels) {
				cropPixels = IntArray(neededPixels)
			}
			bitmap.getPixels(cropPixels, 0, cropW, cropX0, cropY0, cropW, cropH)

			if (rotateVertical) {
				sampleRotated90CcwToBgrTensor(
					pixels = cropPixels,
					imgW = cropW,
					imgH = cropH,
					sx0 = region.x0 - cropX0,
					sy0 = region.y0 - cropY0,
					sw = cw,
					sh = ch,
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
					sx0 = region.x0 - cropX0,
					sy0 = region.y0 - cropY0,
					sw = cw,
					sh = ch,
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

		// Dilim örtüşmelerinden gelen çift kutuları IoU ile tekilleştir
		val sorted = collectedLines.sortedByDescending { it.confidence }
		val kept = ArrayList<RawOcrLine>(sorted.size)
		for (line in sorted) {
			if (kept.any { existing -> iou(existing, line) > 0.45f }) continue
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

	private data class DetBox(
		val x0: Float,
		val y0: Float,
		val x1: Float,
		val y1: Float,
	)

	private fun extractDetBoxes(
		prob: FloatArray,
		w: Int,
		h: Int,
		labels: IntArray,
		queue: IntArray,
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

			while (head < tail) {
				val p = queue[head++]
				val py = p / w
				val px = p - py * w
				sum += prob[p]
				count += 1
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
			val dx = max(d * 1.18f, bh * 0.28f)
			val dy = max(d * 0.95f, bh * 0.18f)
			boxes.add(
				DetBox(
					x0 = (minX - dx).coerceIn(0f, w.toFloat()),
					y0 = (minY - dy).coerceIn(0f, h.toFloat()),
					x1 = (maxX + 1f + dx).coerceIn(0f, w.toFloat()),
					y1 = (maxY + 1f + dy).coerceIn(0f, h.toFloat()),
				),
			)
		}
		return boxes
	}

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

	private fun groupLinesIntoBubbles(
		rawLines: List<RawOcrLine>,
		imgWidth: Int,
		imgHeight: Int,
	): List<OcrBubbleGroup> {
		val cleanedAll = rawLines.mapNotNull { raw ->
			val cleaned = TextBlockFilters.cleanOcrText(raw.text)
			if (cleaned.isEmpty()) null else raw.copy(text = cleaned)
		}
		if (cleanedAll.isEmpty()) return emptyList()

		val strongLines = cleanedAll.filter { TextBlockFilters.isUsableOcrLine(it, TextBlockFilters.DEFAULT_MIN_LINE_CONFIDENCE) }

		val rescuedLines = cleanedAll.filter { cand ->
			if (TextBlockFilters.isUsableOcrLine(cand, TextBlockFilters.DEFAULT_MIN_LINE_CONFIDENCE)) return@filter false
			if (cand.confidence < 34f) return@filter false
			val letters = TextBlockFilters.countLetters(cand.text)
			val junk = TextBlockFilters.countJunk(cand.text)
			if (letters < 1) return@filter false
			val ch = cand.height
			val cw = cand.width
			val centerXc = cand.centerX
			val centerYc = cand.centerY

			strongLines.any { s ->
				val sh = s.height
				val sw = s.width
				val minH = min(ch, sh)
				val maxH = max(ch, sh)
				if (maxH / max(1f, minH) > 2.85f) return@any false

				val vOverlap = min(cand.y1, s.y1) - max(cand.y0, s.y0)
				val hGap = max(cand.x0, s.x0) - min(cand.x1, s.x1)
				if (maxH / max(1f, minH) <= 1.85f && vOverlap >= minH * 0.5f && hGap >= -minH * 0.4f && hGap <= maxH * 1.65f) {
					return@any true
				}

				if (letters >= 2 && junk <= 1 && cand.confidence >= 36f) {
					val vGap = max(cand.y0, s.y0) - min(cand.y1, s.y1)
					val hOverlap = min(cand.x1, s.x1) - max(cand.x0, s.x0)
					val narrow = min(cw, sw)
					val centerDist = abs(centerXc - s.centerX)
					val centerYDist = abs(centerYc - s.centerY)
					if (
						vGap >= -minH * 0.62f &&
						centerYDist >= minH * 0.45f &&
						vGap <= maxH * 1.55f &&
						hOverlap > narrow * 0.32f &&
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
			val isStatA = KEY_VALUE_STAT_LINE_REGEX.containsMatchIn(a.text)
			val wordsA = a.text.trim().split(WHITESPACE_REGEX).size

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
				val vertA = ha > wa * 1.4f
				val vertB = hb > wb * 1.4f
				if (vertA && vertB) {
					val minW = min(wa, wb)
					val maxW = max(wa, wb)
					if (
						vOverlap >= minH * 0.25f &&
						hGap >= -minW * 0.45f &&
						hGap <= maxW * 1.25f &&
						maxW / max(1f, minW) <= 1.75f
					) {
						parent[find(j)] = find(i)
						continue
					}
				} else if (
					!vertA && !vertB &&
					vOverlap >= minH * 0.52f &&
					hGap >= -minH * 0.35f &&
					hGap <= maxH * 0.62f &&
					maxH / max(1f, minH) <= 1.65f
				) {
					parent[find(j)] = find(i)
					continue
				}

				if (b.y0 - a.y1 > maxH * 1.55f) {
					if (b.y0 - a.y1 > maxH * 4f) break
					continue
				}

				val isStatB = KEY_VALUE_STAT_LINE_REGEX.containsMatchIn(b.text)
				if (isStatA || isStatB) continue

				val wordsB = b.text.trim().split(WHITESPACE_REGEX).size
				if (ha >= hb * 1.16f && wordsA <= 3 && (wordsB >= 5 || wb >= wa * 1.42f)) {
					continue
				}

				val vGap = max(a.y0, b.y0) - min(a.y1, b.y1)
				val centerYDist = abs(centerYb - centerYa)
				if (vGap > maxH * 1.5f || vGap < -minH * 0.62f || centerYDist < minH * 0.45f) continue

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

				if (spansSeparateSideBySideColumns(i, j) || spansSeparateSideBySideColumns(j, i)) {
					continue
				}

				val overlap = min(a.x1, b.x1) - max(a.x0, b.x0)
				val narrow = min(wa, wb)
				val centerDist = abs(centerXa - centerXb)
				val heightRatio = maxH / max(1f, minH)

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
			group.sortWith { a, b ->
				if (isVerticalCjkGroup) {
					// Dikey Japonca/Çince balon sütunları sağdan sola okunur
					b.x0.compareTo(a.x0)
				} else {
					val ha = a.height
					val hb = b.height
					val vOv = min(a.y1, b.y1) - max(a.y0, b.y0)
					if (vOv >= min(ha, hb) * 0.5f) {
						a.x0.compareTo(b.x0)
					} else {
						val yCmp = a.y0.compareTo(b.y0)
						if (yCmp != 0) yCmp else a.x0.compareTo(b.x0)
					}
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
					if (vOv >= minH * 0.52f && abs(prev.centerY - item.centerY) < minH * 0.45f && hGap >= -minH * 0.4f) {
						val joiner = if (
							hGap < minH * 0.28f &&
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
				),
			)
		}

		sortedGroups.sortBy { it.first }
		return sortedGroups.mapIndexed { index, pair ->
			pair.second.copy(id = "b$index")
		}
	}

	private fun toNormCoord(value: Float, total: Int): Float =
		((value / max(1, total)) * 1000f).coerceIn(0f, 1000f)

	private companion object {
		const val LOG_TAG = OcrModelManager.LOG_TAG
		const val AUTO_PROBE_MAX_BOXES = 6
		const val DET_MAX_WIDTH = 960
		const val DET_SLICE_H = 1536
		const val DET_SLICE_OVERLAP = 192
		const val DET_BIN_THRESH = 0.3f
		const val DET_BOX_THRESH = 0.58f
		const val DET_UNCLIP_RATIO = 1.78f
		const val REC_H = 48
		const val REC_MAX_W = 1600
		const val INV_255 = 1f / 255f

		val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
		val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
		val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
		val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)

		val KEY_VALUE_STAT_LINE_REGEX = Regex("""^[A-Za-zÇĞİÖŞÜçğıöşü0-9\s/()+-]{2,26}:\s*\S""")
		val WHITESPACE_REGEX = Regex("""\s+""")
		val ENDS_WITH_ASCII_ALPHA_REGEX = Regex("""[a-zA-Z]$""")
		val STARTS_WITH_ASCII_ALPHA_REGEX = Regex("""^[a-zA-Z]""")
	}
}
