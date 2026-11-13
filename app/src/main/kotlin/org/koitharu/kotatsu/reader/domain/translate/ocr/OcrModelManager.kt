package org.koitharu.kotatsu.reader.domain.translate.ocr

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.reader.domain.translate.model.OcrSourceLanguage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class OcrModelFiles(
	val detModel: File,
	val recModel: File,
	val dictFile: File,
	val modelGroup: String,
)

private data class ModelFileSpec(
	val targetFile: File,
	val urls: List<String>,
	val minBytes: Long,
)

private data class GroupModelUrls(
	val recUrls: List<String>,
	val dictUrls: List<String>,
)

/**
 * PaddleOCR ONNX model ve sözlük dosyalarını [Context.getFilesDir]/ocr_models altına
 * atomik olarak indirir, doğrular ve yönetir.
 */
@Singleton
class OcrModelManager @Inject constructor(
	@ApplicationContext context: Context,
	@BaseHttpClient private val baseHttpClientProvider: Provider<OkHttpClient>,
) {

	private val modelsRootDir = File(context.filesDir, MODELS_DIR_NAME)
	private val downloadMutex = Mutex()
	private val downloadClient: OkHttpClient by lazy {
		baseHttpClientProvider.get().newBuilder()
			.connectTimeout(30, TimeUnit.SECONDS)
			.readTimeout(120, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			.followRedirects(true)
			.followSslRedirects(true)
			.build()
	}

	internal val detModelFile: File
		get() = File(modelsRootDir, DET_FILE_NAME)

	fun isModelReady(sourceLang: OcrSourceLanguage): Boolean {
		if (sourceLang == OcrSourceLanguage.AUTO) {
			return isValidOnnxFile(detModelFile) && AUTO_CANDIDATE_GROUPS.any(::isGroupModelOnlyReady)
		}
		return isGroupReady(sourceLang.modelGroup)
	}

	internal fun isGroupReady(modelGroup: String): Boolean {
		val files = resolveGroupFiles(modelGroup)
		return isValidOnnxFile(files.detModel) &&
			isValidOnnxFile(files.recModel) &&
			isValidDictFile(files.dictFile)
	}

	private fun isGroupModelOnlyReady(modelGroup: String): Boolean {
		val files = resolveGroupFiles(modelGroup)
		return isValidOnnxFile(files.recModel) && isValidDictFile(files.dictFile)
	}

	internal fun readyGroupsInPriority(preferredFirst: String? = null): List<String> {
		val ordered = buildList {
			if (!preferredFirst.isNullOrBlank() && preferredFirst in AUTO_CANDIDATE_GROUPS) {
				add(preferredFirst)
			}
			for (group in AUTO_CANDIDATE_GROUPS) {
				if (group != preferredFirst) {
					add(group)
				}
			}
		}
		return ordered.filter(::isGroupReady)
	}

	internal fun getModelFiles(sourceLang: OcrSourceLanguage): OcrModelFiles {
		val group = if (sourceLang == OcrSourceLanguage.AUTO) DEFAULT_AUTO_GROUP else sourceLang.modelGroup
		return resolveGroupFiles(group)
	}

	internal fun getModelFilesForGroup(modelGroup: String): OcrModelFiles = resolveGroupFiles(modelGroup)

	suspend fun ensureModels(
		sourceLang: OcrSourceLanguage,
		onProgress: (suspend (percent: Int) -> Unit)? = null,
	) {
		if (sourceLang == OcrSourceLanguage.AUTO) {
			val autoReady = withContext(Dispatchers.IO) { isModelReady(OcrSourceLanguage.AUTO) }
			if (autoReady) {
				Log.d(LOG_TAG, "ensureModels(AUTO): ready on disk (groups=${readyGroupsInPriority()})")
				return
			}
			Log.i(LOG_TAG, "ensureModels(AUTO): downloading base detector + '$DEFAULT_AUTO_GROUP' recognizer")
			ensureGroupModels(DEFAULT_AUTO_GROUP, onProgress)
			return
		}
		ensureGroupModels(sourceLang.modelGroup, onProgress)
	}

	internal suspend fun ensureGroupModels(
		modelGroup: String,
		onProgress: (suspend (percent: Int) -> Unit)? = null,
	) {
		val alreadyReady = withContext(Dispatchers.IO) { isGroupReady(modelGroup) }
		if (alreadyReady) {
			return
		}
		downloadMutex.withLock {
			val readyLocked = withContext(Dispatchers.IO) { isGroupReady(modelGroup) }
			if (readyLocked) {
				return
			}
			val files = resolveGroupFiles(modelGroup)
			val groupUrls = modelUrlsForGroup(files.modelGroup)
			val requiredSpecs = listOf(
				ModelFileSpec(files.detModel, DET_MODEL_URLS, MIN_ONNX_BYTES),
				ModelFileSpec(files.recModel, groupUrls.recUrls, MIN_ONNX_BYTES),
				ModelFileSpec(files.dictFile, groupUrls.dictUrls, MIN_DICT_BYTES),
			)
			val missingSpecs = withContext(Dispatchers.IO) {
				requiredSpecs.filter { spec ->
					!spec.targetFile.isFile || spec.targetFile.length() < spec.minBytes
				}
			}
			if (missingSpecs.isEmpty()) {
				return
			}
			Log.i(
				LOG_TAG,
				"ensureGroupModels($modelGroup): downloading ${missingSpecs.size} file(s): " +
					missingSpecs.joinToString { it.targetFile.name },
			)

			withContext(Dispatchers.IO) {
				modelsRootDir.mkdirs()
				files.recModel.parentFile?.mkdirs()
			}

			var lastReportedPercent = -1
			suspend fun emitProgress(percent: Int) {
				val clamped = percent.coerceIn(0, 100)
				if (clamped > lastReportedPercent) {
					lastReportedPercent = clamped
					onProgress?.invoke(clamped)
				}
			}

			emitProgress(0)
			val totalFiles = missingSpecs.size
			missingSpecs.forEachIndexed { index, spec ->
				currentCoroutineContext().ensureActive()
				val sliceStart = (index * 100f) / totalFiles
				val sliceSpan = 100f / totalFiles
				downloadWithFallback(spec) { fileFraction ->
					val overall = (sliceStart + sliceSpan * fileFraction).toInt().coerceIn(0, 99)
					emitProgress(overall)
				}
			}
			emitProgress(100)
			Log.i(LOG_TAG, "ensureGroupModels($modelGroup): completed")
		}
	}

	internal suspend fun loadDictionary(sourceLang: OcrSourceLanguage): List<String> {
		val group = if (sourceLang == OcrSourceLanguage.AUTO) DEFAULT_AUTO_GROUP else sourceLang.modelGroup
		return loadDictionaryForGroup(group)
	}

	internal suspend fun loadDictionaryForGroup(modelGroup: String): List<String> {
		val dictFile = resolveGroupFiles(modelGroup).dictFile
		return withContext(Dispatchers.IO) {
			val raw = dictFile.readText(Charsets.UTF_8)
			val lines = raw.replace("\r", "").split('\n').toMutableList()
			if (lines.lastOrNull() == "") {
				lines.removeAt(lines.lastIndex)
			}
			lines.add(" ")
			lines
		}
	}

	private fun resolveGroupFiles(modelGroup: String): OcrModelFiles {
		val groupDir = File(modelsRootDir, modelGroup)
		return OcrModelFiles(
			detModel = detModelFile,
			recModel = File(groupDir, REC_FILE_NAME),
			dictFile = File(groupDir, DICT_FILE_NAME),
			modelGroup = modelGroup,
		)
	}

	private fun isValidOnnxFile(file: File): Boolean = file.isFile && file.length() >= MIN_ONNX_BYTES

	private fun isValidDictFile(file: File): Boolean = file.isFile && file.length() >= MIN_DICT_BYTES

	private suspend fun downloadWithFallback(
		spec: ModelFileSpec,
		onFileFraction: suspend (Float) -> Unit,
	) {
		var lastError: Throwable? = null
		for (url in spec.urls) {
			currentCoroutineContext().ensureActive()
			try {
				downloadAtomically(
					url = url,
					targetFile = spec.targetFile,
					minBytes = spec.minBytes,
					onFraction = onFileFraction,
				)
				return
			} catch (e: CancellationException) {
				throw e
			} catch (t: Throwable) {
				Log.w(LOG_TAG, "downloadWithFallback failed for url=$url: ${t.message}")
				lastError = t
			}
		}
		throw IOException(
			"Failed to download OCR model file ${spec.targetFile.name}: ${lastError?.message ?: "unknown error"}",
			lastError,
		)
	}

	private suspend fun downloadAtomically(
		url: String,
		targetFile: File,
		minBytes: Long,
		onFraction: suspend (Float) -> Unit,
	) {
		val parent = targetFile.parentFile
			?: throw IOException("Missing parent directory for ${targetFile.absolutePath}")
		val tempFile = File(parent, "${targetFile.name}.tmp")
		withContext(Dispatchers.IO) {
			parent.mkdirs()
			if (tempFile.exists()) {
				tempFile.delete()
			}
		}

		val request = Request.Builder()
			.url(url)
			.cacheControl(NO_STORE_CACHE)
			.get()
			.build()

		val client = withContext(Dispatchers.IO) { downloadClient }
		val response = client.newCall(request).awaitExecute()
		try {
			withContext(Dispatchers.IO) {
				response.use { res ->
					if (!res.isSuccessful) {
						throw IOException("HTTP ${res.code} while downloading $url")
					}
					val body = res.body
					val contentLength = body.contentLength()
					val buffer = ByteArray(STREAM_BUFFER_BYTES)
					var totalRead = 0L
					var lastStep = -1

					body.byteStream().use { input ->
						FileOutputStream(tempFile).use { output ->
							while (true) {
								currentCoroutineContext().ensureActive()
								val read = input.read(buffer)
								if (read == -1) break
								output.write(buffer, 0, read)
								totalRead += read
								if (contentLength > 0L) {
									val fraction = (totalRead.toFloat() / contentLength).coerceIn(0f, 1f)
									val step = (fraction * 50).toInt()
									if (step > lastStep) {
										lastStep = step
										onFraction(fraction)
									}
								}
							}
							output.fd.sync()
						}
					}

					if (contentLength > 0L && totalRead < contentLength) {
						throw IOException(
							"Incomplete download for ${targetFile.name}: got $totalRead of $contentLength bytes",
						)
					}
					if (totalRead < minBytes) {
						throw IOException(
							"Downloaded file ${targetFile.name} is unexpectedly small ($totalRead bytes)",
						)
					}
					Log.d(LOG_TAG, "downloadAtomically saved ${targetFile.absolutePath} ($totalRead bytes)")
				}

				if (targetFile.exists() && !targetFile.delete()) {
					throw IOException("Could not replace existing model file ${targetFile.name}")
				}
				if (!tempFile.renameTo(targetFile)) {
					tempFile.copyTo(targetFile, overwrite = true)
					tempFile.delete()
				}
			}
			onFraction(1f)
		} catch (t: Throwable) {
			withContext(Dispatchers.IO) {
				tempFile.delete()
			}
			throw t
		}
	}

	private suspend fun Call.awaitExecute(): Response = suspendCancellableCoroutine { continuation ->
		continuation.invokeOnCancellation { cancel() }
		enqueue(
			object : Callback {
				override fun onFailure(call: Call, e: IOException) {
					if (continuation.isActive) {
						continuation.resumeWithException(e)
					}
				}

				override fun onResponse(call: Call, response: Response) {
					if (continuation.isActive) {
						continuation.resume(response)
					} else {
						response.close()
					}
				}
			},
		)
	}

	private fun modelUrlsForGroup(modelGroup: String): GroupModelUrls = when (modelGroup) {
		"en" -> GroupModelUrls(
			recUrls = listOf("https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/english/rec.onnx"),
			dictUrls = listOf("https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/english/dict.txt"),
		)

		"korean" -> GroupModelUrls(
			recUrls = listOf("https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/korean/rec.onnx"),
			dictUrls = listOf("https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/korean/dict.txt"),
		)

		"cjk" -> GroupModelUrls(
			recUrls = listOf("https://huggingface.co/bluecopa/rapidocr-onnx/resolve/main/ch_PP-OCRv5_rec_mobile_infer.onnx"),
			dictUrls = listOf("https://huggingface.co/bluecopa/rapidocr-onnx/resolve/main/ppocrv5_dict.txt"),
		)

		"latin" -> GroupModelUrls(
			recUrls = listOf("https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/latin/rec.onnx"),
			dictUrls = listOf("https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/latin/dict.txt"),
		)

		"eslav" -> GroupModelUrls(
			recUrls = listOf("https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/eslav/rec.onnx"),
			dictUrls = listOf("https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/eslav/dict.txt"),
		)

		"arabic" -> GroupModelUrls(
			recUrls = listOf("https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/arabic/rec.onnx"),
			dictUrls = listOf("https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/arabic/dict.txt"),
		)

		else -> GroupModelUrls(
			recUrls = listOf("https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/english/rec.onnx"),
			dictUrls = listOf("https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/english/dict.txt"),
		)
	}

	internal companion object {
		const val LOG_TAG = "KotatsuTranslate"
		const val DEFAULT_AUTO_GROUP = "en"
		val AUTO_CANDIDATE_GROUPS = listOf("en", "cjk", "korean", "latin", "eslav", "arabic")
		val AUTO_DOWNLOAD_FALLBACK_GROUPS = listOf("cjk", "korean")

		private const val MODELS_DIR_NAME = "ocr_models"
		private const val DET_FILE_NAME = "det_v4.onnx"
		private const val REC_FILE_NAME = "rec.onnx"
		private const val DICT_FILE_NAME = "dict.txt"
		private const val STREAM_BUFFER_BYTES = 16 * 1024
		private const val MIN_ONNX_BYTES = 100_000L
		private const val MIN_DICT_BYTES = 32L

		private val DET_MODEL_URLS = listOf(
			"https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx",
			"https://huggingface.co/monkt/paddleocr-onnx/resolve/main/detection/v3/det.onnx",
		)

		private val NO_STORE_CACHE = CacheControl.Builder().noStore().build()
	}
}
