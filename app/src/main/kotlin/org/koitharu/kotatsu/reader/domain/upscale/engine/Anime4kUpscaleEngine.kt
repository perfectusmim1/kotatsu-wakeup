package org.koitharu.kotatsu.reader.domain.upscale.engine

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hızlı Mod (Anime4K 2x):
 * Birincil olarak Android OpenGL ES 2.0 (EGL14 Pbuffer + GLES20 FBO) üzerinde
 * 6 aşamalı Anime4K (Catmull-Rom 2x -> Luma -> Line Push -> Luma -> Sobel Gradient -> Directional Refine)
 * shader hattını çalıştırır (~4-8 ms/şerit).
 * EGL bağlamı kurulamayan ortamlarda (ör. JVM testleri veya kısıtlı cihazlar)
 * otomatik olarak optimize edilmiş CPU Anime4K kenar keskinleştirme hattına düşer.
 */
@Singleton
class Anime4kUpscaleEngine @Inject constructor() {

	private val mutex = Mutex()

	private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
	private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
	private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
	private var glFailedPermanently = false

	private var fboId = 0
	private var quadVboId = 0
	private var inputTexId = 0
	private var workTexA = 0
	private var workTexB = 0
	private var workTexC = 0
	private var outTexId = 0
	private var allocInternalW = 0
	private var allocInternalH = 0
	private var allocOutW = 0
	private var allocOutH = 0

	private var scaleProg: GlProgramBundle? = null
	private var lumProg: GlProgramBundle? = null
	private var pushProg: GlProgramBundle? = null
	private var gradProg: GlProgramBundle? = null
	private var finalProg: GlProgramBundle? = null
	private var drawProg: GlProgramBundle? = null

	private var readByteBuffer: ByteBuffer? = null

	private class GlProgramBundle(
		val program: Int,
		val aPos: Int,
		val uniforms: Map<String, Int>,
	)

	suspend fun upscale(
		source: Bitmap,
		outWidth: Int,
		outHeight: Int,
		cropTop: Int,
		cropBottom: Int,
	): Bitmap = withContext(Dispatchers.Default) {
		currentCoroutineContext().ensureActive()
		val safeOutW = outWidth.coerceAtLeast(1)
		val safeOutH = outHeight.coerceAtLeast(1)

		mutex.withLock {
			currentCoroutineContext().ensureActive()
			if (!glFailedPermanently) {
				try {
					return@withLock upscaleGlLocked(
						source = source,
						outWidth = safeOutW,
						outHeight = safeOutH,
						cropTop = cropTop,
						cropBottom = cropBottom,
					)
				} catch (t: Throwable) {
					Log.w(LOG_TAG, "GLES20 Anime4K pipeline unavailable, falling back to CPU: ${t.message}")
					releaseGlLocked()
					glFailedPermanently = true
				}
			}
		}

		upscaleCpu(
			source = source,
			outWidth = safeOutW,
			outHeight = safeOutH,
			cropTop = cropTop,
			cropBottom = cropBottom,
		)
	}

	private fun upscaleGlLocked(
		source: Bitmap,
		outWidth: Int,
		outHeight: Int,
		cropTop: Int,
		cropBottom: Int,
	): Bitmap {
		ensureEglContextLocked()
		if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
			throw IllegalStateException("eglMakeCurrent failed: ${EGL14.eglGetError()}")
		}
		try {
			ensureProgramsAndBuffersLocked()
			val readable = if (source.config == Bitmap.Config.ARGB_8888) {
				source
			} else {
				source.copy(Bitmap.Config.ARGB_8888, false)
					?: throw IllegalStateException("Failed to copy bitmap to ARGB_8888")
			}
			try {
				val srcW = readable.width
				val srcH = readable.height
				val maxTexArr = IntArray(1)
				GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexArr, 0)
				val maxTex = maxTexArr[0].takeIf { it >= 1024 } ?: 4096
				val internalW = min(srcW * 2, maxTex)
				val internalH = min(srcH * 2, maxTex)

				ensureWorkTexturesLocked(internalW, internalH, outWidth, outHeight)

				GLES20.glDisable(GLES20.GL_DEPTH_TEST)
				GLES20.glDisable(GLES20.GL_STENCIL_TEST)
				GLES20.glDisable(GLES20.GL_BLEND)

				GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
				GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
				GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, readable, 0)

				// 1. 2x Catmull-Rom başlangıç ölçekleme -> workTexA
				val sProg = checkNotNull(scaleProg)
				GLES20.glUseProgram(sProg.program)
				GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
				GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
				GLES20.glUniform1i(sProg.uniforms.getValue("u_tex"), 0)
				GLES20.glUniform2f(sProg.uniforms.getValue("u_srcSize"), srcW.toFloat(), srcH.toFloat())
				drawQuadToFboLocked(sProg, workTexA, internalW, internalH)

				val ptX = 1f / internalW.toFloat()
				val ptY = 1f / internalH.toFloat()
				val lProg = checkNotNull(lumProg)
				val pProg = checkNotNull(pushProg)
				val gProg = checkNotNull(gradProg)
				val fProg = checkNotNull(finalProg)

				repeat(2) {
					// 2. Parlaklık haritası (workTexA -> workTexB)
					GLES20.glUseProgram(lProg.program)
					GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
					GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, workTexA)
					GLES20.glUniform1i(lProg.uniforms.getValue("u_tex"), 0)
					drawQuadToFboLocked(lProg, workTexB, internalW, internalH)

					// 3. Kenar itme ve çizgi inceltme (workTexA + workTexB -> workTexC)
					GLES20.glUseProgram(pProg.program)
					GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
					GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, workTexA)
					GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
					GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, workTexB)
					GLES20.glUniform1i(pProg.uniforms.getValue("u_tex"), 0)
					GLES20.glUniform1i(pProg.uniforms.getValue("u_lumTex"), 1)
					GLES20.glUniform2f(pProg.uniforms.getValue("u_pt"), ptX, ptY)
					GLES20.glUniform1f(pProg.uniforms.getValue("u_strength"), PUSH_STRENGTH)
					drawQuadToFboLocked(pProg, workTexC, internalW, internalH)

					// 4. Güncellenmiş parlaklık (workTexC -> workTexB)
					GLES20.glUseProgram(lProg.program)
					GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
					GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, workTexC)
					GLES20.glUniform1i(lProg.uniforms.getValue("u_tex"), 0)
					drawQuadToFboLocked(lProg, workTexB, internalW, internalH)

					// 5. Sobel gradyan hesabı (workTexB -> workTexA)
					GLES20.glUseProgram(gProg.program)
					GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
					GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, workTexB)
					GLES20.glUniform1i(gProg.uniforms.getValue("u_lumTex"), 0)
					GLES20.glUniform2f(gProg.uniforms.getValue("u_pt"), ptX, ptY)
					drawQuadToFboLocked(gProg, workTexA, internalW, internalH)

					// 6. Gradyan yönlü keskinleştirme (workTexC + workTexA -> workTexB, ardından workTexA ile takas)
					GLES20.glUseProgram(fProg.program)
					GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
					GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, workTexC)
					GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
					GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, workTexA)
					GLES20.glUniform1i(fProg.uniforms.getValue("u_tex"), 0)
					GLES20.glUniform1i(fProg.uniforms.getValue("u_gradTex"), 1)
					GLES20.glUniform2f(fProg.uniforms.getValue("u_pt"), ptX, ptY)
					GLES20.glUniform1f(fProg.uniforms.getValue("u_strength"), REFINE_STRENGTH)
					drawQuadToFboLocked(fProg, workTexB, internalW, internalH)

					val tmp = workTexA
					workTexA = workTexB
					workTexB = tmp
				}

				// 7. Bindirme kırpma ve hedef boyuta çıkış (workTexA -> outTexId)
				val vMin = (cropTop.toFloat() / srcH.toFloat()).coerceIn(0f, 1f)
				val vMax = (1f - cropBottom.toFloat() / srcH.toFloat()).coerceIn(vMin, 1f)
				val dProg = checkNotNull(drawProg)
				GLES20.glUseProgram(dProg.program)
				GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
				GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, workTexA)
				GLES20.glUniform1i(dProg.uniforms.getValue("u_tex"), 0)
				GLES20.glUniform2f(dProg.uniforms.getValue("u_vRange"), vMin, vMax)
				drawQuadToFboLocked(dProg, outTexId, outWidth, outHeight)

				val byteCount = outWidth * outHeight * 4
				var buf = readByteBuffer
				if (buf == null || buf.capacity() < byteCount) {
					buf = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
					readByteBuffer = buf
				}
				buf.clear()
				buf.limit(byteCount)
				GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 4)
				GLES20.glReadPixels(0, 0, outWidth, outHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
				buf.rewind()

				val outBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
				outBitmap.copyPixelsFromBuffer(buf)
				return outBitmap
			} finally {
				if (readable !== source) {
					readable.recycle()
				}
			}
		} finally {
			EGL14.eglMakeCurrent(
				eglDisplay,
				EGL14.EGL_NO_SURFACE,
				EGL14.EGL_NO_SURFACE,
				EGL14.EGL_NO_CONTEXT,
			)
		}
	}

	private fun ensureEglContextLocked() {
		if (eglDisplay !== EGL14.EGL_NO_DISPLAY && eglContext !== EGL14.EGL_NO_CONTEXT) {
			return
		}
		val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
		if (display === EGL14.EGL_NO_DISPLAY) {
			throw IllegalStateException("Unable to get EGL14 display")
		}
		val version = IntArray(2)
		if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
			throw IllegalStateException("Unable to initialize EGL14")
		}
		val attribList = intArrayOf(
			EGL14.EGL_RED_SIZE, 8,
			EGL14.EGL_GREEN_SIZE, 8,
			EGL14.EGL_BLUE_SIZE, 8,
			EGL14.EGL_ALPHA_SIZE, 8,
			EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
			EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
			EGL14.EGL_NONE,
		)
		val configs = arrayOfNulls<EGLConfig>(1)
		val numConfigs = IntArray(1)
		if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
			throw IllegalStateException("Unable to find suitable EGLConfig")
		}
		val config = checkNotNull(configs[0])
		val ctxAttribs = intArrayOf(
			EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
			EGL14.EGL_NONE,
		)
		val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
		if (context === EGL14.EGL_NO_CONTEXT) {
			throw IllegalStateException("Unable to create EGLContext")
		}
		val surfaceAttribs = intArrayOf(
			EGL14.EGL_WIDTH, 4,
			EGL14.EGL_HEIGHT, 4,
			EGL14.EGL_NONE,
		)
		val surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
		if (surface === EGL14.EGL_NO_SURFACE) {
			EGL14.eglDestroyContext(display, context)
			throw IllegalStateException("Unable to create Pbuffer EGLSurface")
		}
		eglDisplay = display
		eglContext = context
		eglSurface = surface
	}

	private fun ensureProgramsAndBuffersLocked() {
		if (scaleProg != null && fboId != 0 && quadVboId != 0 && inputTexId != 0) {
			return
		}
		val fbos = IntArray(1)
		GLES20.glGenFramebuffers(1, fbos, 0)
		fboId = fbos[0]

		val vbos = IntArray(1)
		GLES20.glGenBuffers(1, vbos, 0)
		quadVboId = vbos[0]
		val vertexBuf: FloatBuffer = ByteBuffer.allocateDirect(QUAD_VERTS.size * 4)
			.order(ByteOrder.nativeOrder())
			.asFloatBuffer()
			.apply {
				put(QUAD_VERTS)
				position(0)
			}
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVboId)
		GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, QUAD_VERTS.size * 4, vertexBuf, GLES20.GL_STATIC_DRAW)

		inputTexId = createLinearTextureLocked()
		scaleProg = compileProgramLocked(QUAD_VERT, SCALE_FRAG, listOf("u_tex", "u_srcSize"))
		lumProg = compileProgramLocked(QUAD_VERT, LUM_FRAG, listOf("u_tex"))
		pushProg = compileProgramLocked(QUAD_VERT, PUSH_FRAG, listOf("u_tex", "u_lumTex", "u_pt", "u_strength"))
		gradProg = compileProgramLocked(QUAD_VERT, GRAD_FRAG, listOf("u_lumTex", "u_pt"))
		finalProg = compileProgramLocked(QUAD_VERT, FINAL_FRAG, listOf("u_tex", "u_gradTex", "u_pt", "u_strength"))
		drawProg = compileProgramLocked(DRAW_VERT, DRAW_FRAG, listOf("u_tex", "u_vRange"))
	}

	private fun createLinearTextureLocked(): Int {
		val tex = IntArray(1)
		GLES20.glGenTextures(1, tex, 0)
		val id = tex[0]
		if (id == 0) throw IllegalStateException("glGenTextures returned 0")
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
		return id
	}

	private fun ensureWorkTexturesLocked(internalW: Int, internalH: Int, outW: Int, outH: Int) {
		if (allocInternalW != internalW || allocInternalH != internalH || workTexA == 0 || workTexB == 0 || workTexC == 0) {
			fun alloc(existing: Int): Int {
				val id = if (existing != 0) existing else createLinearTextureLocked()
				GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
				GLES20.glTexImage2D(
					GLES20.GL_TEXTURE_2D,
					0,
					GLES20.GL_RGBA,
					internalW,
					internalH,
					0,
					GLES20.GL_RGBA,
					GLES20.GL_UNSIGNED_BYTE,
					null,
				)
				return id
			}
			workTexA = alloc(workTexA)
			workTexB = alloc(workTexB)
			workTexC = alloc(workTexC)
			allocInternalW = internalW
			allocInternalH = internalH
		}

		if (allocOutW != outW || allocOutH != outH || outTexId == 0) {
			val id = if (outTexId != 0) outTexId else createLinearTextureLocked()
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
			GLES20.glTexImage2D(
				GLES20.GL_TEXTURE_2D,
				0,
				GLES20.GL_RGBA,
				outW,
				outH,
				0,
				GLES20.GL_RGBA,
				GLES20.GL_UNSIGNED_BYTE,
				null,
			)
			outTexId = id
			allocOutW = outW
			allocOutH = outH
		}
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
	}

	private fun drawQuadToFboLocked(
		bundle: GlProgramBundle,
		targetTex: Int,
		w: Int,
		h: Int,
	) {
		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
		GLES20.glFramebufferTexture2D(
			GLES20.GL_FRAMEBUFFER,
			GLES20.GL_COLOR_ATTACHMENT0,
			GLES20.GL_TEXTURE_2D,
			targetTex,
			0,
		)
		GLES20.glViewport(0, 0, w, h)
		GLES20.glUseProgram(bundle.program)
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVboId)
		GLES20.glEnableVertexAttribArray(bundle.aPos)
		GLES20.glVertexAttribPointer(bundle.aPos, 2, GLES20.GL_FLOAT, false, 0, 0)
		GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
	}

	private fun compileProgramLocked(
		vertSrc: String,
		fragSrc: String,
		uniformNames: List<String>,
	): GlProgramBundle {
		fun compile(type: Int, src: String): Int {
			val sh = GLES20.glCreateShader(type)
			if (sh == 0) throw IllegalStateException("glCreateShader failed")
			GLES20.glShaderSource(sh, src)
			GLES20.glCompileShader(sh)
			val status = IntArray(1)
			GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
			if (status[0] == 0) {
				val log = GLES20.glGetShaderInfoLog(sh)
				GLES20.glDeleteShader(sh)
				throw IllegalStateException("Shader compile error: $log")
			}
			return sh
		}

		val vs = compile(GLES20.GL_VERTEX_SHADER, vertSrc)
		val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragSrc)
		val prog = GLES20.glCreateProgram()
		if (prog == 0) {
			GLES20.glDeleteShader(vs)
			GLES20.glDeleteShader(fs)
			throw IllegalStateException("glCreateProgram failed")
		}
		GLES20.glAttachShader(prog, vs)
		GLES20.glAttachShader(prog, fs)
		GLES20.glLinkProgram(prog)
		GLES20.glDeleteShader(vs)
		GLES20.glDeleteShader(fs)

		val linkStatus = IntArray(1)
		GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
		if (linkStatus[0] == 0) {
			val log = GLES20.glGetProgramInfoLog(prog)
			GLES20.glDeleteProgram(prog)
			throw IllegalStateException("Program link error: $log")
		}

		val uniforms = HashMap<String, Int>(uniformNames.size)
		for (name in uniformNames) {
			uniforms[name] = GLES20.glGetUniformLocation(prog, name)
		}
		return GlProgramBundle(
			program = prog,
			aPos = GLES20.glGetAttribLocation(prog, "a_pos"),
			uniforms = uniforms,
		)
	}

	private fun releaseGlLocked() {
		runCatching {
			if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
				EGL14.eglMakeCurrent(
					eglDisplay,
					EGL14.EGL_NO_SURFACE,
					EGL14.EGL_NO_SURFACE,
					EGL14.EGL_NO_CONTEXT,
				)
				if (eglSurface !== EGL14.EGL_NO_SURFACE) {
					EGL14.eglDestroySurface(eglDisplay, eglSurface)
				}
				if (eglContext !== EGL14.EGL_NO_CONTEXT) {
					EGL14.eglDestroyContext(eglDisplay, eglContext)
				}
				EGL14.eglTerminate(eglDisplay)
			}
		}
		eglDisplay = EGL14.EGL_NO_DISPLAY
		eglContext = EGL14.EGL_NO_CONTEXT
		eglSurface = EGL14.EGL_NO_SURFACE
		fboId = 0
		quadVboId = 0
		inputTexId = 0
		workTexA = 0
		workTexB = 0
		workTexC = 0
		outTexId = 0
		allocInternalW = 0
		allocInternalH = 0
		allocOutW = 0
		allocOutH = 0
		scaleProg = null
		lumProg = null
		pushProg = null
		gradProg = null
		finalProg = null
		drawProg = null
		readByteBuffer = null
	}

	/**
	 * EGL/GLES20 kullanılamayan durumlar için CPU üzerinde hızlı Anime4K kenar keskinleştirme.
	 */
	internal fun upscaleCpu(
		source: Bitmap,
		outWidth: Int,
		outHeight: Int,
		cropTop: Int,
		cropBottom: Int,
	): Bitmap {
		val srcW = source.width
		val srcH = source.height
		val y0 = cropTop.coerceIn(0, (srcH - 1).coerceAtLeast(0))
		val cropH = (srcH - y0 - cropBottom.coerceAtLeast(0)).coerceAtLeast(1)

		val cropped = if (y0 == 0 && cropH == srcH) {
			source
		} else {
			Bitmap.createBitmap(source, 0, y0, srcW, cropH)
		}
		val scaled = try {
			val s = Bitmap.createScaledBitmap(cropped, outWidth, outHeight, true)
			if (s.config != Bitmap.Config.ARGB_8888) {
				val copy = s.copy(Bitmap.Config.ARGB_8888, true)
				if (s !== cropped && s !== source) s.recycle()
				copy
			} else if (!s.isMutable) {
				val copy = s.copy(Bitmap.Config.ARGB_8888, true)
				if (s !== cropped && s !== source) s.recycle()
				copy
			} else {
				s
			}
		} finally {
			if (cropped !== source) {
				cropped.recycle()
			}
		}

		val w = scaled.width
		val h = scaled.height
		if (w < 3 || h < 3) {
			return scaled
		}

		val total = w * h
		val pixels = IntArray(total)
		scaled.getPixels(pixels, 0, w, 0, 0, w, h)
		val luma = IntArray(total)
		for (i in 0 until total) {
			val c = pixels[i]
			val r = (c ushr 16) and 0xFF
			val g = (c ushr 8) and 0xFF
			val b = c and 0xFF
			luma[i] = (r * 2 + g * 3 + b) / 6
		}

		val outPixels = pixels.copyOf()
		for (y in 1 until h - 1) {
			val row = y * w
			val topRow = (y - 1) * w
			val botRow = (y + 1) * w
			for (x in 1 until w - 1) {
				val idx = row + x
				val cLum = luma[idx]
				val tLum = luma[topRow + x]
				val bLum = luma[botRow + x]
				val lLum = luma[row + x - 1]
				val rLum = luma[row + x + 1]

				val gx = abs(rLum - lLum)
				val gy = abs(bLum - tLum)
				if (gx + gy < 12) continue

				val c = pixels[idx]
				val cr = (c ushr 16) and 0xFF
				val cg = (c ushr 8) and 0xFF
				val cb = c and 0xFF

				val avgLum = (tLum + bLum + lLum + rLum) shr 2
				val delta = ((cLum - avgLum) * 58) shr 7
				val nr = (cr + delta).coerceIn(0, 255)
				val ng = (cg + delta).coerceIn(0, 255)
				val nb = (cb + delta).coerceIn(0, 255)
				outPixels[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
			}
		}
		scaled.setPixels(outPixels, 0, w, 0, 0, w, h)
		return scaled
	}

	companion object {
		private const val LOG_TAG = "KotatsuUpscale"
		private const val PUSH_STRENGTH = 0.56f
		private const val REFINE_STRENGTH = 0.42f

		private val QUAD_VERTS = floatArrayOf(
			0f, 0f,
			1f, 0f,
			0f, 1f,
			0f, 1f,
			1f, 0f,
			1f, 1f,
		)

		private const val QUAD_VERT = """
			precision highp float;
			attribute vec2 a_pos;
			varying vec2 v_uv;
			void main() {
				v_uv = a_pos;
				gl_Position = vec4(a_pos * 2.0 - 1.0, 0.0, 1.0);
			}
		"""

		// FBO çıkışında glReadPixels satır 0'ı gl_Position.y = -1.0'dan (a_pos.y = 0.0) okur.
		private const val DRAW_VERT = """
			precision highp float;
			attribute vec2 a_pos;
			uniform vec2 u_vRange;
			varying vec2 v_uv;
			void main() {
				v_uv = vec2(a_pos.x, mix(u_vRange.x, u_vRange.y, a_pos.y));
				gl_Position = vec4(a_pos * 2.0 - 1.0, 0.0, 1.0);
			}
		"""

		private const val SCALE_FRAG = """
			precision highp float;
			uniform sampler2D u_tex;
			uniform vec2 u_srcSize;
			varying vec2 v_uv;

			vec4 catmullRom(vec2 uv) {
				vec2 px = 1.0 / u_srcSize;
				vec2 coord = uv * u_srcSize - 0.5;
				vec2 i = floor(coord);
				vec2 f = coord - i;
				vec2 f2 = f * f;
				vec2 f3 = f2 * f;

				vec2 w0 = -0.5 * f3 + f2 - 0.5 * f;
				vec2 w1 = 1.5 * f3 - 2.5 * f2 + 1.0;
				vec2 w2 = -1.5 * f3 + 2.0 * f2 + 0.5 * f;
				vec2 w3 = 0.5 * f3 - 0.5 * f2;

				vec2 p0 = (i - 0.5) * px;
				vec2 p1 = (i + 0.5) * px;
				vec2 p2 = (i + 1.5) * px;
				vec2 p3 = (i + 2.5) * px;

				vec4 r0 = texture2D(u_tex, vec2(p0.x, p0.y)) * w0.x +
					texture2D(u_tex, vec2(p1.x, p0.y)) * w1.x +
					texture2D(u_tex, vec2(p2.x, p0.y)) * w2.x +
					texture2D(u_tex, vec2(p3.x, p0.y)) * w3.x;
				vec4 r1 = texture2D(u_tex, vec2(p0.x, p1.y)) * w0.x +
					texture2D(u_tex, vec2(p1.x, p1.y)) * w1.x +
					texture2D(u_tex, vec2(p2.x, p1.y)) * w2.x +
					texture2D(u_tex, vec2(p3.x, p1.y)) * w3.x;
				vec4 r2 = texture2D(u_tex, vec2(p0.x, p2.y)) * w0.x +
					texture2D(u_tex, vec2(p1.x, p2.y)) * w1.x +
					texture2D(u_tex, vec2(p2.x, p2.y)) * w2.x +
					texture2D(u_tex, vec2(p3.x, p2.y)) * w3.x;
				vec4 r3 = texture2D(u_tex, vec2(p0.x, p3.y)) * w0.x +
					texture2D(u_tex, vec2(p1.x, p3.y)) * w1.x +
					texture2D(u_tex, vec2(p2.x, p3.y)) * w2.x +
					texture2D(u_tex, vec2(p3.x, p3.y)) * w3.x;

				return clamp(r0 * w0.y + r1 * w1.y + r2 * w2.y + r3 * w3.y, 0.0, 1.0);
			}

			void main() {
				gl_FragColor = catmullRom(v_uv);
			}
		"""

		private const val LUM_FRAG = """
			precision highp float;
			uniform sampler2D u_tex;
			varying vec2 v_uv;
			void main() {
				vec4 rgb = texture2D(u_tex, v_uv);
				float lum = (rgb.r + rgb.r + rgb.g + rgb.g + rgb.g + rgb.b) / 6.0;
				gl_FragColor = vec4(lum, lum, lum, 1.0);
			}
		"""

		private const val PUSH_FRAG = """
			precision highp float;
			uniform sampler2D u_tex;
			uniform sampler2D u_lumTex;
			uniform vec2 u_pt;
			uniform float u_strength;
			varying vec2 v_uv;

			vec4 getRGBL(vec2 pos) {
				return vec4(texture2D(u_tex, pos).rgb, texture2D(u_lumTex, pos).r);
			}

			float min3v(vec4 a, vec4 b, vec4 c) {
				return min(min(a.a, b.a), c.a);
			}

			float max3v(vec4 a, vec4 b, vec4 c) {
				return max(max(a.a, b.a), c.a);
			}

			vec4 getLargest(vec4 cc, vec4 lightestColor, vec4 a, vec4 b, vec4 c) {
				vec4 nextColor = cc * (1.0 - u_strength) + ((a + b + c) / 3.0) * u_strength;
				return nextColor.a > lightestColor.a ? nextColor : lightestColor;
			}

			void main() {
				vec2 d = u_pt;
				vec4 cc = getRGBL(v_uv);
				vec4 t  = getRGBL(v_uv + vec2(0.0, -d.y));
				vec4 tl = getRGBL(v_uv + vec2(-d.x, -d.y));
				vec4 tr = getRGBL(v_uv + vec2(d.x, -d.y));
				vec4 l  = getRGBL(v_uv + vec2(-d.x, 0.0));
				vec4 r  = getRGBL(v_uv + vec2(d.x, 0.0));
				vec4 b  = getRGBL(v_uv + vec2(0.0, d.y));
				vec4 bl = getRGBL(v_uv + vec2(-d.x, d.y));
				vec4 br = getRGBL(v_uv + vec2(d.x, d.y));

				vec4 lightestColor = cc;

				float maxDark = max3v(br, b, bl);
				float minLight = min3v(tl, t, tr);
				if (minLight > cc.a && minLight > maxDark) {
					lightestColor = getLargest(cc, lightestColor, tl, t, tr);
				} else {
					maxDark = max3v(tl, t, tr);
					minLight = min3v(br, b, bl);
					if (minLight > cc.a && minLight > maxDark) {
						lightestColor = getLargest(cc, lightestColor, br, b, bl);
					}
				}

				maxDark = max3v(cc, l, b);
				minLight = min3v(r, t, tr);
				if (minLight > maxDark) {
					lightestColor = getLargest(cc, lightestColor, r, t, tr);
				} else {
					maxDark = max3v(cc, r, t);
					minLight = min3v(bl, l, b);
					if (minLight > maxDark) {
						lightestColor = getLargest(cc, lightestColor, bl, l, b);
					}
				}

				maxDark = max3v(l, tl, bl);
				minLight = min3v(r, br, tr);
				if (minLight > cc.a && minLight > maxDark) {
					lightestColor = getLargest(cc, lightestColor, r, br, tr);
				} else {
					maxDark = max3v(r, br, tr);
					minLight = min3v(l, tl, bl);
					if (minLight > cc.a && minLight > maxDark) {
						lightestColor = getLargest(cc, lightestColor, l, tl, bl);
					}
				}

				maxDark = max3v(cc, l, t);
				minLight = min3v(r, br, b);
				if (minLight > maxDark) {
					lightestColor = getLargest(cc, lightestColor, r, br, b);
				} else {
					maxDark = max3v(cc, r, b);
					minLight = min3v(t, l, tl);
					if (minLight > maxDark) {
						lightestColor = getLargest(cc, lightestColor, t, l, tl);
					}
				}

				gl_FragColor = vec4(lightestColor.rgb, 1.0);
			}
		"""

		private const val GRAD_FRAG = """
			precision highp float;
			uniform sampler2D u_lumTex;
			uniform vec2 u_pt;
			varying vec2 v_uv;

			float lumAt(vec2 pos) {
				return texture2D(u_lumTex, pos).r;
			}

			void main() {
				vec2 d = u_pt;
				float t  = lumAt(v_uv + vec2(0.0, -d.y));
				float tl = lumAt(v_uv + vec2(-d.x, -d.y));
				float tr = lumAt(v_uv + vec2(d.x, -d.y));
				float l  = lumAt(v_uv + vec2(-d.x, 0.0));
				float r  = lumAt(v_uv + vec2(d.x, 0.0));
				float b  = lumAt(v_uv + vec2(0.0, d.y));
				float bl = lumAt(v_uv + vec2(-d.x, d.y));
				float br = lumAt(v_uv + vec2(d.x, d.y));

				float xgrad = (-tl + tr - l - l + r + r - bl + br);
				float ygrad = (-tl - t - t - tr + bl + b + b + br);
				float val = 1.0 - clamp(sqrt(xgrad * xgrad + ygrad * ygrad), 0.0, 1.0);
				gl_FragColor = vec4(val, val, val, 1.0);
			}
		"""

		private const val FINAL_FRAG = """
			precision highp float;
			uniform sampler2D u_tex;
			uniform sampler2D u_gradTex;
			uniform vec2 u_pt;
			uniform float u_strength;
			varying vec2 v_uv;

			vec4 getRGBG(vec2 pos) {
				return vec4(texture2D(u_tex, pos).rgb, texture2D(u_gradTex, pos).r);
			}

			float min3v(vec4 a, vec4 b, vec4 c) {
				return min(min(a.a, b.a), c.a);
			}

			float max3v(vec4 a, vec4 b, vec4 c) {
				return max(max(a.a, b.a), c.a);
			}

			vec4 getAverage(vec4 cc, vec4 a, vec4 b, vec4 c) {
				vec4 mixed = cc * (1.0 - u_strength) + ((a + b + c) / 3.0) * u_strength;
				return vec4(mixed.rgb, 1.0);
			}

			void main() {
				vec2 d = u_pt;
				vec4 cc = getRGBG(v_uv);
				vec4 t  = getRGBG(v_uv + vec2(0.0, -d.y));
				vec4 tl = getRGBG(v_uv + vec2(-d.x, -d.y));
				vec4 tr = getRGBG(v_uv + vec2(d.x, -d.y));
				vec4 l  = getRGBG(v_uv + vec2(-d.x, 0.0));
				vec4 r  = getRGBG(v_uv + vec2(d.x, 0.0));
				vec4 b  = getRGBG(v_uv + vec2(0.0, d.y));
				vec4 bl = getRGBG(v_uv + vec2(-d.x, d.y));
				vec4 br = getRGBG(v_uv + vec2(d.x, d.y));

				float maxDark = max3v(br, b, bl);
				float minLight = min3v(tl, t, tr);
				if (minLight > cc.a && minLight > maxDark) {
					gl_FragColor = getAverage(cc, tl, t, tr);
					return;
				} else {
					maxDark = max3v(tl, t, tr);
					minLight = min3v(br, b, bl);
					if (minLight > cc.a && minLight > maxDark) {
						gl_FragColor = getAverage(cc, br, b, bl);
						return;
					}
				}

				maxDark = max3v(cc, l, b);
				minLight = min3v(r, t, tr);
				if (minLight > maxDark) {
					gl_FragColor = getAverage(cc, r, t, tr);
					return;
				} else {
					maxDark = max3v(cc, r, t);
					minLight = min3v(bl, l, b);
					if (minLight > maxDark) {
						gl_FragColor = getAverage(cc, bl, l, b);
						return;
					}
				}

				maxDark = max3v(l, tl, bl);
				minLight = min3v(r, br, tr);
				if (minLight > cc.a && minLight > maxDark) {
					gl_FragColor = getAverage(cc, r, br, tr);
					return;
				} else {
					maxDark = max3v(r, br, tr);
					minLight = min3v(l, tl, bl);
					if (minLight > cc.a && minLight > maxDark) {
						gl_FragColor = getAverage(cc, l, tl, bl);
						return;
					}
				}

				maxDark = max3v(cc, l, t);
				minLight = min3v(r, br, b);
				if (minLight > maxDark) {
					gl_FragColor = getAverage(cc, r, br, b);
					return;
				} else {
					maxDark = max3v(cc, r, b);
					minLight = min3v(t, l, tl);
					if (minLight > maxDark) {
						gl_FragColor = getAverage(cc, t, l, tl);
						return;
					}
				}

				gl_FragColor = vec4(cc.rgb, 1.0);
			}
		"""

		private const val DRAW_FRAG = """
			precision highp float;
			uniform sampler2D u_tex;
			varying vec2 v_uv;
			void main() {
				vec4 c = texture2D(u_tex, v_uv);
				gl_FragColor = vec4(c.rgb, 1.0);
			}
		"""
	}
}
