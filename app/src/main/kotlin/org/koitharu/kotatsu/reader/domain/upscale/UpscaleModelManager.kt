package org.koitharu.kotatsu.reader.domain.upscale

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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real-ESRGAN (SRVGGNetCompact x2) ONNX model dosyasını [Context.getFilesDir]/upscale_models
 * altına atomik olarak indirir, doğrular ve yönetir.
 */
@Singleton
class UpscaleModelManager @Inject constructor(
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

	val modelFile: File
		get() = File(modelsRootDir, REALESRGAN_MODEL_FILE_NAME)

	fun isModelReady(): Boolean {
		val file = modelFile
		return file.isFile && file.length() >= MIN_ONNX_BYTES
	}

	suspend fun ensureModel(
		onProgress: (suspend (percent: Int) -> Unit)? = null,
	): File {
		val alreadyReady = withContext(Dispatchers.IO) { isModelReady() }
		if (alreadyReady) {
			return modelFile
		}
		return downloadMutex.withLock {
			val readyLocked = withContext(Dispatchers.IO) { isModelReady() }
			if (readyLocked) {
				return@withLock modelFile
			}
			withContext(Dispatchers.IO) {
				modelsRootDir.mkdirs()
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
			val target = modelFile
			var lastError: Throwable? = null
			var downloaded = false
			for (url in REALESRGAN_MODEL_URLS) {
				currentCoroutineContext().ensureActive()
				try {
					downloadAtomically(
						url = url,
						targetFile = target,
						minBytes = MIN_ONNX_BYTES,
						onFraction = { fraction ->
							emitProgress((fraction * 99f).toInt().coerceIn(0, 99))
						},
					)
					downloaded = true
					break
				} catch (ce: CancellationException) {
					throw ce
				} catch (t: Throwable) {
					Log.w(LOG_TAG, "Model download failed for url=$url: ${t.message}")
					lastError = t
				}
			}
			if (!downloaded) {
				throw IOException(
					"Failed to download AI upscale model: ${lastError?.message ?: "unknown error"}",
					lastError,
				)
			}
			emitProgress(100)
			target
		}
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

	companion object {
		const val LOG_TAG = "KotatsuUpscale"
		private const val MODELS_DIR_NAME = "upscale_models"
		private const val REALESRGAN_MODEL_FILE_NAME = "realesr-animevideo-x2.onnx"
		private const val STREAM_BUFFER_BYTES = 16 * 1024
		private const val MIN_ONNX_BYTES = 2_000_000L

		private val REALESRGAN_MODEL_URLS = listOf(
			"https://raw.githubusercontent.com/perfectusmim1/dark-manga-realm/main/public/models/realesr-animevideo-x2.onnx",
			"https://cdn.jsdelivr.net/gh/perfectusmim1/dark-manga-realm@main/public/models/realesr-animevideo-x2.onnx",
		)

		private val NO_STORE_CACHE = CacheControl.Builder().noStore().build()
	}
}
