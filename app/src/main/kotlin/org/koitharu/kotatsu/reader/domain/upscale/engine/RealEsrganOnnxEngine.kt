package org.koitharu.kotatsu.reader.domain.upscale.engine

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
import kotlinx.coroutines.yield
import org.koitharu.kotatsu.reader.domain.upscale.StripPlanner
import org.koitharu.kotatsu.reader.domain.upscale.UpscaleModelManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Yapay Zekâ Modu (Real-ESRGAN SRVGGNetCompact x2 — ONNX Runtime Android):
 * Şeridi sabit boyutlu (128 + 2*8 = 144x144) örtüşmeli karolara böler.
 * Her karoda birebir aynı sabit tensör şeklini (`[1, 3, 144, 144]`) kullanarak ONNX Runtime bellek
 * planını tek seferde (~50 MB) sabitler, bellek sızıntısı ve yerel çökme riskini ortadan kaldırır
 * ve çıkış şeridini yarı RAM tüketimli `RGB_565` olarak üretir.
 */
@Singleton
class RealEsrganOnnxEngine @Inject constructor(
	private val modelManager: UpscaleModelManager,
) {

	private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
	private val sessionMutex = Mutex()
	private val inferenceMutex = Mutex()

	private var session: OrtSession? = null
	private var activeModelPath: String? = null

	private var reusedInputFloatArray: FloatArray? = null
	private var reusedInputDirectBuf: FloatBuffer? = null
	private var reusedOutputFloatArray: FloatArray? = null
	private var reusedSrcPixels: IntArray? = null
	private var reusedFullOutPixels: IntArray? = null

	fun isModelReady(): Boolean = modelManager.isModelReady()

	suspend fun ensureModel(
		onProgress: (suspend (percent: Int) -> Unit)? = null,
	): File = modelManager.ensureModel(onProgress)

	suspend fun warmUpSessionIfModelReady() {
		if (!modelManager.isModelReady()) return
		runCatching {
			val modelFile = modelManager.ensureModel(null)
			loadOrReuseSession(modelFile, onInitializing = null)
		}
	}

	suspend fun trimMemory(closeSession: Boolean = false) {
		inferenceMutex.withLock {
			reusedInputFloatArray = null
			reusedInputDirectBuf = null
			reusedOutputFloatArray = null
			reusedSrcPixels = null
			reusedFullOutPixels = null
		}
		if (closeSession) {
			sessionMutex.withLock {
				runCatching { session?.close() }
				session = null
				activeModelPath = null
			}
		}
	}

	suspend fun upscale(
		source: Bitmap,
		outWidth: Int,
		outHeight: Int,
		cropTop: Int,
		cropBottom: Int,
		onDownloadProgress: (suspend (percent: Int) -> Unit)? = null,
		onInitializing: (suspend () -> Unit)? = null,
		onSessionReady: (suspend () -> Unit)? = null,
		shouldPreempt: (() -> Boolean)? = null,
	): Bitmap {
		val srcW = source.width
		val srcH = source.height
		require(srcW > 0 && srcH > 0) { "Invalid source bitmap dimensions ${srcW}x$srcH" }

		val modelFile = modelManager.ensureModel(onDownloadProgress)
		val ortSession = loadOrReuseSession(modelFile, onInitializing)
		onSessionReady?.invoke()

		return inferenceMutex.withLock {
			withContext(Dispatchers.Default) {
				try {
					runUpscaleLocked(
						ortSession = ortSession,
						source = source,
						srcW = srcW,
						srcH = srcH,
						outWidth = outWidth,
						outHeight = outHeight,
						cropTop = cropTop,
						cropBottom = cropBottom,
						shouldPreempt = shouldPreempt,
					)
				} catch (oom: OutOfMemoryError) {
					reusedInputFloatArray = null
					reusedInputDirectBuf = null
					reusedOutputFloatArray = null
					reusedSrcPixels = null
					reusedFullOutPixels = null
					Log.e(LOG_TAG, "OutOfMemoryError during AI upscale (${srcW}x$srcH -> ${outWidth}x$outHeight)", oom)
					throw oom
				}
			}
		}
	}

	private suspend fun runUpscaleLocked(
		ortSession: OrtSession,
		source: Bitmap,
		srcW: Int,
		srcH: Int,
		outWidth: Int,
		outHeight: Int,
		cropTop: Int,
		cropBottom: Int,
		shouldPreempt: (() -> Boolean)?,
	): Bitmap {
		currentCoroutineContext().ensureActive()
		if (shouldPreempt?.invoke() == true) {
			throw kotlinx.coroutines.CancellationException("Preempted by visible strip before inference")
		}

		val srcPixelCount = srcW * srcH
		var srcPixels = reusedSrcPixels
		if (srcPixels == null || srcPixels.size < srcPixelCount) {
			srcPixels = IntArray(srcPixelCount)
			reusedSrcPixels = srcPixels
		}
		val readable = if (source.config == Bitmap.Config.ARGB_8888) {
			source
		} else {
			source.copy(Bitmap.Config.ARGB_8888, false)
				?: throw IllegalStateException("Bitmap.copy(ARGB_8888) returned null")
		}
		try {
			readable.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
		} finally {
			if (readable !== source) {
				readable.recycle()
			}
		}

		val fullOutW = srcW * 2
		val fullOutH = srcH * 2
		val fullOutCount = fullOutW * fullOutH
		var fullOutPixels = reusedFullOutPixels
		if (fullOutPixels == null || fullOutPixels.size < fullOutCount) {
			fullOutPixels = IntArray(fullOutCount)
			reusedFullOutPixels = fullOutPixels
		}

		val tileSize = StripPlanner.AI_TILE_SIZE
		val tileOverlap = StripPlanner.AI_TILE_OVERLAP
		val tiles = StripPlanner.planTiles(srcW, srcH, tileSize, tileOverlap)
		val margin = tileOverlap * 2

		// Sabit kare karo giriş boyutu: her çalıştırmada birebir aynı tensör şekli [1, 3, fixedInH, fixedInW]
		// kullanılarak ONNX Runtime bellek planı tek tamponda sabitlenir (sızıntı ve dinamik-şekil çökmesi olmaz).
		val fixedInW = tileSize + tileOverlap * 2
		val fixedInH = tileSize + tileOverlap * 2
		val fixedPlaneSize = fixedInW * fixedInH
		val fixedRequiredFloatLen = 3 * fixedPlaneSize
		val fixedInputShape = longArrayOf(1L, 3L, fixedInH.toLong(), fixedInW.toLong())

		var inputFloat = reusedInputFloatArray
		var inputDirectBuf = reusedInputDirectBuf
		if (inputFloat == null || inputFloat.size < fixedRequiredFloatLen || inputDirectBuf == null || inputDirectBuf.capacity() < fixedRequiredFloatLen) {
			inputFloat = FloatArray(fixedRequiredFloatLen)
			inputDirectBuf = ByteBuffer.allocateDirect(fixedRequiredFloatLen * 4)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer()
			reusedInputFloatArray = inputFloat
			reusedInputDirectBuf = inputDirectBuf
		}

		val expectedOutFloatLen = 3 * (fixedInW * 2) * (fixedInH * 2)
		var outFloat: FloatArray = reusedOutputFloatArray?.takeIf { it.size >= expectedOutFloatLen }
			?: FloatArray(expectedOutFloatLen).also { reusedOutputFloatArray = it }

		val inputName = ortSession.inputNames.firstOrNull() ?: "input"
		val inv255 = 1f / 255f

		for (tIdx in tiles.indices) {
			currentCoroutineContext().ensureActive()
			if (tIdx > 0) {
				yield()
				currentCoroutineContext().ensureActive()
				if (shouldPreempt?.invoke() == true) {
					throw kotlinx.coroutines.CancellationException("Preempted by visible strip between tiles")
				}
			}

			val tile = tiles[tIdx]
			val coreW = tile.coreWidth
			val coreH = tile.coreHeight
			val inX0 = tile.tx0 - tile.pad
			val inY0 = tile.ty0 - tile.pad

			for (py in 0 until fixedInH) {
				val sy = (inY0 + py).coerceIn(0, srcH - 1)
				val srcRowOffset = sy * srcW
				val dstRowOffset = py * fixedInW
				for (px in 0 until fixedInW) {
					val sx = (inX0 + px).coerceIn(0, srcW - 1)
					val c = srcPixels[srcRowOffset + sx]
					val dIdx = dstRowOffset + px
					inputFloat[dIdx] = ((c ushr 16) and 0xFF) * inv255
					inputFloat[fixedPlaneSize + dIdx] = ((c ushr 8) and 0xFF) * inv255
					inputFloat[fixedPlaneSize * 2 + dIdx] = (c and 0xFF) * inv255
				}
			}

			inputDirectBuf.clear()
			inputDirectBuf.put(inputFloat, 0, fixedRequiredFloatLen)
			inputDirectBuf.rewind()
			inputDirectBuf.limit(fixedRequiredFloatLen)

			OnnxTensor.createTensor(env, inputDirectBuf, fixedInputShape).use { inTensor ->
				ortSession.run(Collections.singletonMap(inputName, inTensor)).use { result ->
					currentCoroutineContext().ensureActive()
					val outTensor = result[0] as OnnxTensor
					val shape = (outTensor.info as TensorInfo).shape
					val outTileH = if (shape.size >= 4) shape[2].toInt() else fixedInH * 2
					val outTileW = if (shape.size >= 4) shape[3].toInt() else fixedInW * 2
					val outPlane = outTileW * outTileH
					val totalOutFloats = 3 * outPlane

					var targetOutArr = outFloat
					if (targetOutArr.size < totalOutFloats) {
						targetOutArr = FloatArray(totalOutFloats)
						reusedOutputFloatArray = targetOutArr
						outFloat = targetOutArr
					}
					outTensor.floatBuffer.apply {
						rewind()
						get(targetOutArr, 0, totalOutFloats)
					}

					val coreOutW = coreW * 2
					val coreOutH = coreH * 2
					val dstX0 = tile.tx0 * 2
					val dstY0 = tile.ty0 * 2

					for (y in 0 until coreOutH) {
						val oy = margin + y
						val outTileRow = oy * outTileW + margin
						val fullDstRow = (dstY0 + y) * fullOutW + dstX0
						for (x in 0 until coreOutW) {
							val oIdx = outTileRow + x
							val r = (targetOutArr[oIdx] * 255f + 0.5f).toInt().coerceIn(0, 255)
							val g = (targetOutArr[outPlane + oIdx] * 255f + 0.5f).toInt().coerceIn(0, 255)
							val b = (targetOutArr[outPlane * 2 + oIdx] * 255f + 0.5f).toInt().coerceIn(0, 255)
							fullOutPixels[fullDstRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
						}
					}
				}
			}
		}

		currentCoroutineContext().ensureActive()
		val cropY0 = (cropTop * 2).coerceIn(0, (fullOutH - 1).coerceAtLeast(0))
		val cropH = (fullOutH - cropY0 - (cropBottom * 2).coerceAtLeast(0)).coerceAtLeast(1)

		// Opak manga/webtoon şeritlerinde RGB_565 %50 daha az Bitmap RAM tüketir.
		val croppedBitmap = Bitmap.createBitmap(fullOutW, cropH, Bitmap.Config.RGB_565)
		croppedBitmap.setPixels(fullOutPixels, cropY0 * fullOutW, fullOutW, 0, 0, fullOutW, cropH)

		// Olağandışı büyük bir şerit geldiyse devasa yardımcı diziyi kalıcı tutma.
		if (fullOutPixels.size > MAX_RETAINED_OUT_PIXELS) {
			reusedFullOutPixels = null
		}
		if (srcPixels.size > MAX_RETAINED_SRC_PIXELS) {
			reusedSrcPixels = null
		}

		val safeOutW = outWidth.coerceAtLeast(1)
		val safeOutH = outHeight.coerceAtLeast(1)
		return if (croppedBitmap.width == safeOutW && croppedBitmap.height == safeOutH) {
			croppedBitmap
		} else {
			val resized = Bitmap.createScaledBitmap(croppedBitmap, safeOutW, safeOutH, true)
			if (resized !== croppedBitmap) {
				croppedBitmap.recycle()
			}
			resized
		}
	}

	private suspend fun loadOrReuseSession(
		modelFile: File,
		onInitializing: (suspend () -> Unit)?,
	): OrtSession = sessionMutex.withLock {
		val current = session
		if (current != null && activeModelPath == modelFile.absolutePath) {
			return@withLock current
		}
		onInitializing?.invoke()
		return@withLock withContext(Dispatchers.IO) {
			val options = OrtSession.SessionOptions().apply {
				val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
				// 2-4 çekirdek: mobil SoC'lerde bellek/ısınma sıçraması ve arayüz takılması olmadan optimum CPU verimi.
				setIntraOpNumThreads(min(4, max(2, cores / 2)))
				setInterOpNumThreads(1)
				setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
				setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
				// Sabit 144x144 tensör şekliyle bellek planı tek seferde yeniden kullanılır (~50 MB).
				setMemoryPatternOptimization(true)
				setCPUArenaAllocator(true)
			}
			try {
				current?.close()
				session = null
				env.createSession(modelFile.absolutePath, options).also { created ->
					session = created
					activeModelPath = modelFile.absolutePath
					Log.i(LOG_TAG, "Loaded Real-ESRGAN ONNX session from ${modelFile.name} (${modelFile.length()} bytes)")
				}
			} finally {
				options.close()
			}
		}
	}

	private companion object {
		const val LOG_TAG = "KotatsuUpscale"
		const val MAX_RETAINED_SRC_PIXELS = 1_000_000
		const val MAX_RETAINED_OUT_PIXELS = 4_000_000
	}
}
