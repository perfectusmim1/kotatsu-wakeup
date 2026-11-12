package org.koitharu.kotatsu.reader.domain.translate.render

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.reader.domain.translate.model.FontStyleMode
import org.koitharu.kotatsu.reader.domain.translate.model.ScanlationFontKey
import org.koitharu.kotatsu.reader.domain.translate.model.TextBlockKind
import org.koitharu.kotatsu.reader.domain.translate.model.TextStyleFingerprint

data class SmartWrappedLine(
	val text: String,
	val widthPx: Float,
	val scale: Float = 1f,
)

data class SmartWrapResult(
	val lines: List<SmartWrappedLine>,
	val maxLineWidthPx: Float,
	val hasOverflow: Boolean,
)

@Singleton
class SmartTextLayout @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val baseFontCache = ConcurrentHashMap<Int, Typeface>()

	fun formatScanlationText(text: String, targetLang: String): String {
		val normalized = text
			.replace("\r\n", "\n")
			.replace('\r', '\n')
			.replace(HORIZONTAL_SPACE_REGEX, " ")
			.trim()
		if (normalized.isEmpty()) return ""

		val locale = resolveLocale(targetLang)
		val rawLines = normalized.split('\n')
			.map { it.trim() }
			.filter { it.isNotEmpty() }

		val preserveExplicitBreaks = rawLines.size >= 2 && rawLines.all { line ->
			STAT_ROW_REGEX.containsMatchIn(line) || line.length <= 24
		}

		val collapsed = if (preserveExplicitBreaks) {
			rawLines.joinToString("\n")
		} else {
			rawLines.joinToString(" ")
		}

		return collapsed
			.replace(MULTI_SPACE_REGEX, " ")
			.uppercase(locale)
	}

	fun resolveTypefaceForText(
		fontKey: ScanlationFontKey,
		fontWeight: Int,
		fontStyle: FontStyleMode,
		sampleText: String,
	): Typeface {
		val candidates = buildFontFallbackChain(fontKey)
		val probePaint = Paint()
		for (base in candidates) {
			val styled = applyStyleToTypeface(base, fontWeight, fontStyle)
			if (canRenderAllGlyphs(probePaint, styled, sampleText)) {
				return styled
			}
		}
		return applyStyleToTypeface(candidates.last(), fontWeight, fontStyle)
	}

	fun configureTextPaint(
		paint: TextPaint,
		style: TextStyleFingerprint,
		fontPx: Float,
		text: String,
	) {
		paint.isAntiAlias = true
		paint.isSubpixelText = true
		paint.typeface = resolveTypefaceForText(
			fontKey = style.fontFamily,
			fontWeight = style.fontWeight,
			fontStyle = style.fontStyle,
			sampleText = text,
		)
		paint.textSize = fontPx.coerceAtLeast(1f)
		paint.letterSpacing = style.letterSpacingEm
		paint.textSkewX = if (style.skewDeg != 0f) {
			-tan(Math.toRadians(style.skewDeg.toDouble())).toFloat()
		} else {
			0f
		}
	}

	fun measureStyledWordWidth(word: String, paint: Paint): Float = paint.measureText(word)

	fun computeSmartWrappedLines(
		text: String,
		maxWidthPx: Float,
		paint: TextPaint,
		lineWidthsPct: List<Float> = emptyList(),
		emphasisLastLineScale: Float = 1f,
	): SmartWrapResult {
		val safeMaxW = maxWidthPx.coerceAtLeast(8f)
		val trimmed = text.trim()
		if (trimmed.isEmpty()) {
			return SmartWrapResult(emptyList(), 0f, false)
		}

		val explicitParagraphs = trimmed.split('\n')
			.map { it.trim() }
			.filter { it.isNotEmpty() }

		if (explicitParagraphs.size > 1) {
			val allLines = ArrayList<SmartWrappedLine>(explicitParagraphs.size * 2)
			var maxW = 0f
			var anyOverflow = false
			for (para in explicitParagraphs) {
				val res = wrapSingleParagraph(
					paragraph = para,
					maxWidthPx = safeMaxW,
					paint = paint,
					lineWidthsPct = emptyList(),
					emphasisLastLineScale = 1f,
				)
				allLines.addAll(res.lines)
				if (res.maxLineWidthPx > maxW) maxW = res.maxLineWidthPx
				if (res.hasOverflow) anyOverflow = true
			}
			return SmartWrapResult(allLines, maxW, anyOverflow)
		}

		return wrapSingleParagraph(
			paragraph = explicitParagraphs.first(),
			maxWidthPx = safeMaxW,
			paint = paint,
			lineWidthsPct = lineWidthsPct,
			emphasisLastLineScale = emphasisLastLineScale,
		)
	}

	fun getLineHeightRatioForBlock(
		kind: TextBlockKind,
		style: TextStyleFingerprint,
	): Float = when (kind) {
		TextBlockKind.SFX -> 0.98f
		TextBlockKind.EMPHASIS -> if (style.hasTextStroke) 1.08f else 1.04f
		TextBlockKind.NARRATION -> if (style.hasTextStroke) 1.18f else 1.14f
		TextBlockKind.DIALOGUE -> if (style.hasTextStroke && style.strokeWidthEm >= 0.08f) 1.16f else 1.12f
	}

	private fun wrapSingleParagraph(
		paragraph: String,
		maxWidthPx: Float,
		paint: TextPaint,
		lineWidthsPct: List<Float>,
		emphasisLastLineScale: Float,
	): SmartWrapResult {
		val words = paragraph.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
		val n = words.size
		if (n == 0) return SmartWrapResult(emptyList(), 0f, false)
		if (n == 1) {
			val w = measureStyledWordWidth(words[0], paint)
			val scale = if (w > maxWidthPx) (maxWidthPx / w).coerceAtLeast(0.35f) else 1f
			return SmartWrapResult(
				lines = listOf(SmartWrappedLine(words[0], w, scale)),
				maxLineWidthPx = w * scale,
				hasOverflow = w > maxWidthPx * 1.02f && scale <= 0.36f,
			)
		}

		val singleLineW = measureStyledWordWidth(words.joinToString(" "), paint)
		if (lineWidthsPct.size <= 1 &&
			emphasisLastLineScale < 1.2f &&
			singleLineW <= maxWidthPx * 0.96f &&
			(n <= 3 || singleLineW <= maxWidthPx * 0.72f)
		) {
			return SmartWrapResult(
				lines = listOf(SmartWrappedLine(words.joinToString(" "), singleLineW, 1f)),
				maxLineWidthPx = singleLineW,
				hasOverflow = false,
			)
		}

		// Emphasized punchline mode: keep the final emphatic token enlarged on its own bottom row.
		if (emphasisLastLineScale >= 1.2f && n >= 3) {
			val lastWord = words.last()
			val headWords = words.subList(0, n - 1)
			val lastW = measureStyledWordWidth(lastWord, paint)
			val lastScale = min(emphasisLastLineScale, maxWidthPx / max(1f, lastW)).coerceAtLeast(0.5f)
			val headResult = runDpWrap(headWords, maxWidthPx, paint, emptyList())
			if (headResult != null) {
				val combined = headResult + SmartWrappedLine(lastWord, lastW, lastScale)
				val maxW = combined.maxOf { it.widthPx * it.scale }
				return SmartWrapResult(
					lines = combined,
					maxLineWidthPx = maxW,
					hasOverflow = maxW > maxWidthPx * 1.02f,
				)
			}
		}

		val dpLines = runDpWrap(words, maxWidthPx, paint, lineWidthsPct)
		val lines = dpLines ?: greedyWrap(words, maxWidthPx, paint)
		val finalLines = lines.map { line ->
			val effW = line.widthPx * line.scale
			if (effW > maxWidthPx) {
				line.copy(scale = (maxWidthPx / max(1f, line.widthPx)).coerceAtLeast(0.35f))
			} else {
				line
			}
		}
		val maxW = finalLines.maxOfOrNull { it.widthPx * it.scale } ?: 0f
		val overflow = finalLines.any { it.widthPx > maxWidthPx * 1.35f }
		return SmartWrapResult(
			lines = finalLines,
			maxLineWidthPx = maxW,
			hasOverflow = overflow,
		)
	}

	private fun runDpWrap(
		words: List<String>,
		maxWidthPx: Float,
		paint: TextPaint,
		lineWidthsPct: List<Float>,
	): List<SmartWrappedLine>? {
		val n = words.size
		if (n == 0) return emptyList()
		if (n > 40) return null

		val wordWidths = FloatArray(n) { measureStyledWordWidth(words[it], paint) }
		val spaceW = paint.measureText(" ")
		val prefixSum = FloatArray(n + 1)
		for (i in 0 until n) {
			prefixSum[i + 1] = prefixSum[i] + wordWidths[i]
		}

		fun segWidth(start: Int, endExclusive: Int): Float =
			(prefixSum[endExclusive] - prefixSum[start]) + (endExclusive - start - 1) * spaceW

		val totalAllW = segWidth(0, n)
		val estMinK = max(1, ceil(totalAllW / (maxWidthPx * 0.94f)).toInt()).coerceAtMost(min(7, n))
		val maxK = min(7, n)
		if (estMinK > maxK) return null

		var bestLines: List<SmartWrappedLine>? = null
		var bestTotalCost = Double.POSITIVE_INFINITY
		val lineCountBiasUnit = maxWidthPx.toDouble() * maxWidthPx.toDouble() * 0.035

		val startK = if (totalAllW <= maxWidthPx * 0.98f) 1 else estMinK
		for (k in startK..maxK) {
			val dp = Array(k + 1) { DoubleArray(n + 1) { Double.POSITIVE_INFINITY } }
			val prevBreak = Array(k + 1) { IntArray(n + 1) { -1 } }
			dp[0][0] = 0.0

			for (lineIdx in 0 until k) {
				val ratio = getLineTargetRatio(lineIdx, k, lineWidthsPct)
				val targetW = (maxWidthPx * ratio).toDouble()
				val isLastLine = lineIdx == k - 1
				val minStart = lineIdx
				val maxStart = n - (k - lineIdx)
				if (minStart > maxStart) break

				for (i in minStart..maxStart) {
					val baseCost = dp[lineIdx][i]
					if (baseCost.isInfinite()) continue
					val jStart = if (isLastLine) n else i + 1
					val jEnd = n - (k - lineIdx - 1)
					for (j in jStart..jEnd) {
						val lineW = segWidth(i, j).toDouble()
						var segCost = 0.0
						if (lineW > maxWidthPx) {
							val over = lineW - maxWidthPx
							segCost += if (j == i + 1) {
								over * over * 60.0 + 1e5
							} else {
								over * over * 1200.0 + 1e8
							}
						} else if (lineW > targetW * 1.03) {
							val softOver = lineW - targetW * 1.03
							segCost += softOver * softOver * 18.0
						} else {
							val slack = targetW - lineW
							segCost += slack * slack * 0.65
						}

						if (isLastLine && j == i + 1 && n >= 3 && lineW < targetW * 0.36) {
							segCost += targetW * targetW * 1.85
						}

						val total = baseCost + segCost
						if (total < dp[lineIdx + 1][j]) {
							dp[lineIdx + 1][j] = total
							prevBreak[lineIdx + 1][j] = i
						}
					}
				}
			}

			val finalCost = dp[k][n] + (k - 1) * lineCountBiasUnit
			if (finalCost < bestTotalCost) {
				bestTotalCost = finalCost
				val rebuilt = ArrayList<SmartWrappedLine>(k)
				var curEnd = n
				for (lineIdx in k downTo 1) {
					val start = prevBreak[lineIdx][curEnd]
					if (start < 0) break
					val joined = words.subList(start, curEnd).joinToString(" ")
					val w = segWidth(start, curEnd)
					rebuilt.add(SmartWrappedLine(joined, w, 1f))
					curEnd = start
				}
				if (rebuilt.size == k && curEnd == 0) {
					bestLines = rebuilt.asReversed()
				}
			}
		}

		// Avoid returning a severe multi-word overflow plan when greedy wrap packs better.
		if (bestTotalCost >= 1e8) return null
		return bestLines
	}

	private fun greedyWrap(
		words: List<String>,
		maxWidthPx: Float,
		paint: TextPaint,
	): List<SmartWrappedLine> {
		val result = ArrayList<SmartWrappedLine>()
		val current = StringBuilder()
		for (word in words) {
			if (current.isEmpty()) {
				current.append(word)
			} else {
				val candidate = "$current $word"
				if (paint.measureText(candidate) <= maxWidthPx) {
					current.append(' ').append(word)
				} else {
					val str = current.toString()
					result.add(SmartWrappedLine(str, paint.measureText(str), 1f))
					current.clear()
					current.append(word)
				}
			}
		}
		if (current.isNotEmpty()) {
			val str = current.toString()
			result.add(SmartWrappedLine(str, paint.measureText(str), 1f))
		}
		return result
	}

	private fun getLineTargetRatio(
		lineIdx: Int,
		totalLines: Int,
		lineWidthsPct: List<Float>,
	): Float {
		if (lineWidthsPct.size == totalLines && lineWidthsPct.isNotEmpty()) {
			return (lineWidthsPct[lineIdx] / 100f).coerceIn(0.45f, 1.0f)
		}
		if (totalLines >= 3) {
			val mid = (totalLines - 1) * 0.5f
			val normPos = if (mid > 0f) (lineIdx - mid) / mid else 0f
			return max(0.76f, 1.0f - normPos * normPos * 0.65f)
		}
		return 1.0f
	}

	private fun buildFontFallbackChain(requested: ScanlationFontKey): List<Typeface> {
		val list = ArrayList<Typeface>(4)
		list.add(loadBaseFont(fontResForKey(requested)))
		if (requested != ScanlationFontKey.SHANTELL) {
			list.add(loadBaseFont(R.font.shantell_sans))
		}
		list.add(loadBaseFont(R.font.google_sans))
		if (list.last() !== Typeface.SANS_SERIF) {
			list.add(Typeface.SANS_SERIF)
		}
		return list
	}

	private fun loadBaseFont(@FontRes resId: Int): Typeface {
		return baseFontCache.getOrPut(resId) {
			runCatching { ResourcesCompat.getFont(context, resId) }.getOrNull()
				?: Typeface.SANS_SERIF
		}
	}

	private fun applyStyleToTypeface(
		base: Typeface,
		fontWeight: Int,
		fontStyle: FontStyleMode,
	): Typeface {
		val isItalic = fontStyle == FontStyleMode.ITALIC
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			Typeface.create(base, fontWeight.coerceIn(100, 1000), isItalic)
		} else {
			val styleInt = when {
				fontWeight >= 600 && isItalic -> Typeface.BOLD_ITALIC
				fontWeight >= 600 -> Typeface.BOLD
				isItalic -> Typeface.ITALIC
				else -> Typeface.NORMAL
			}
			Typeface.create(base, styleInt)
		}
	}

	private fun canRenderAllGlyphs(
		probePaint: Paint,
		typeface: Typeface,
		text: String,
	): Boolean {
		if (text.isEmpty()) return true
		probePaint.typeface = typeface
		var i = 0
		while (i < text.length) {
			val cp = text.codePointAt(i)
			val count = Character.charCount(cp)
			if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) {
				val type = Character.getType(cp)
				if (type != Character.FORMAT.toInt() && type != Character.NON_SPACING_MARK.toInt()) {
					val glyph = String(Character.toChars(cp))
					if (!probePaint.hasGlyph(glyph)) {
						return false
					}
				}
			}
			i += count
		}
		return true
	}

	private fun fontResForKey(key: ScanlationFontKey): Int = when (key) {
		ScanlationFontKey.SHANTELL -> R.font.shantell_sans
		ScanlationFontKey.ANTON -> R.font.anton_regular
		ScanlationFontKey.BANGERS -> R.font.bangers_regular
		ScanlationFontKey.MARKER -> R.font.permanent_marker
		ScanlationFontKey.CINZEL -> R.font.cinzel_bold
	}

	private fun resolveLocale(targetLang: String): Locale {
		val clean = targetLang.trim().lowercase(Locale.ROOT)
		return when {
			clean.startsWith("tr") -> Locale.forLanguageTag("tr-TR")
			clean.startsWith("az") -> Locale.forLanguageTag("az-AZ")
			clean.isEmpty() -> Locale.forLanguageTag("tr-TR")
			else -> Locale.forLanguageTag(targetLang)
		}
	}

	private companion object {
		val HORIZONTAL_SPACE_REGEX = Regex("[ \\t]+")
		val MULTI_SPACE_REGEX = Regex(" {2,}")
		val WHITESPACE_REGEX = Regex("\\s+")
		val STAT_ROW_REGEX = Regex("[:：]|^\\s*[•\\-*+]\\s+")
	}
}
