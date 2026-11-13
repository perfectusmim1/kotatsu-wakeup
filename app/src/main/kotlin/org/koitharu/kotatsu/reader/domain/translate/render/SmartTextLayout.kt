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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.reader.domain.translate.model.FontStyleMode
import org.koitharu.kotatsu.reader.domain.translate.model.ScanlationFontKey
import org.koitharu.kotatsu.reader.domain.translate.model.TextBlockKind
import org.koitharu.kotatsu.reader.domain.translate.model.TextStyleFingerprint

internal object RenderTextHelpers {

	val REPEATED_ONLY_SFX_STEMS = hashSetOf(
		"tap", "step", "drip", "drop", "clap", "pat", "knock", "tick", "tock", "click",
		"pop", "snap", "ring", "beep", "honk", "cough", "pant", "sob", "gasp", "gulp",
		"chew", "chop", "slash", "slice", "poke", "prod", "shake", "tremble", "shiver",
		"shudder", "flinch", "twitch", "sparkle", "glint", "gleam", "flash", "glow",
	)

	private val WHITESPACE_REGEX = Regex("\\s+")
	private val NON_LETTER_DIGIT_REGEX = Regex("[^\\p{L}\\p{N}]+")
	private val STAT_KEY_VAL_REGEX =
		Regex("^([A-Za-zÇĞİÖŞÜçğıöşü0-9\\s/()+-]{2,26}):\\s*(.+)$")
	private val DIALOGUE_OPENING_WORD_REGEX =
		Regex("^(?:oh|ah|uh|um|hey|well|wait|what|why|how|damn|shit|fuck)$", RegexOption.IGNORE_CASE)
	private val YES_NO_OPENING_REGEX =
		Regex("^(?:no|yes)$", RegexOption.IGNORE_CASE)
	private val UPPER_STAT_VAL_REGEX = Regex("^[A-Z0-9+/().\\s-]+$")
	private val SIGNED_NUMBER_START_REGEX = Regex("^[+-]?\\d")
	private val KNOWN_RPG_STAT_KEY_REGEX = Regex(
		"^(?:name|isim|ad|level|lvl|lv|seviye|race|type|t[üu]r|class|s[ıi]n[ıi]f|rank|r[üu]tbe|" +
			"title|unvan|hp|mp|sp|xp|exp|health|can|mana|b[üu]y[üu]|stamina|dayan[ıi]kl[ıi]l[ıi]k|" +
			"atk|attack|sald[ıi]r[ıi]|def|defense|savunma|str|strength|g[üu][çc]|agi|agility|" +
			"[çc]eviklik|dex|int|intelligence|zeka|vit|vitality|luk|luck|[şs]ans|speed|h[ıi]z|" +
			"weapon|silah|armor|z[ıi]rh|skill|skills|yetenek|yetenekler|ability|abilities|owner|" +
			"sahip|master|stat|stats|status|durum|power|g[üu][çc]\\s*puan[ıi]|" +
			"evolution(?:\\s*points?)?|evrim(?:\\s*puan[ıi])?|points?|tier|grade|element|" +
			"attribute|effect|passive|active|reward|[öo]d[üu]l|quest|g[öo]rev|mission|condition|" +
			"ko[şs]ul|age|ya[şs]|gender|cinsiyet|affiliation|clan|klan|sect|tarikat|realm|diyar|" +
			"stage|a[şs]ama|cultivation|trait|traits|buff|debuff|equipment|ekipman|item|e[şs]ya|" +
			"description|a[çc][ıi]klama|durability|host|system)$",
		RegexOption.IGNORE_CASE,
	)

	fun normalizeForCompare(text: String): String =
		text.lowercase(Locale.US).replace(NON_LETTER_DIGIT_REGEX, "")

	fun isRpgStatLine(rawText: String): Boolean {
		val cleaned = rawText.trim().replace(WHITESPACE_REGEX, " ")
		val match = STAT_KEY_VAL_REGEX.matchEntire(cleaned) ?: return false
		val key = match.groupValues[1].trim()
		val value = match.groupValues[2].trim()
		if (value.isEmpty()) return false
		if (value.endsWith('?') || value.endsWith(',')) return false

		val valWords = value.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
		val firstWord = valWords.firstOrNull().orEmpty()
		if (DIALOGUE_OPENING_WORD_REGEX.matches(firstWord) ||
			(YES_NO_OPENING_REGEX.matches(firstWord) && valWords.size >= 2)
		) {
			return false
		}
		if (KNOWN_RPG_STAT_KEY_REGEX.matches(key)) return true
		if (SIGNED_NUMBER_START_REGEX.containsMatchIn(value)) return true
		return valWords.size <= 3 &&
			!value.contains('!') &&
			!value.contains('?') &&
			UPPER_STAT_VAL_REGEX.matches(value)
	}
}

data class SmartWrappedLine(
	val text: String,
	val widthPx: Float,
	val scale: Float = 1f,
)

data class SmartWrapResult(
	val lines: List<SmartWrappedLine>,
	val maxWordWidth: Float,
	val totalHeightPx: Float,
	val maxLineWidthPx: Float = maxWordWidth,
	val hasOverflow: Boolean = false,
)

@Singleton
class SmartTextLayout @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private data class FontCacheKey(
		@FontRes val resId: Int,
		val weight: Int,
		val isItalic: Boolean,
	)

	private val baseFontCache = ConcurrentHashMap<Int, Typeface>()
	private val styledFontCache = ConcurrentHashMap<FontCacheKey, Typeface>()

	fun formatScanlationText(text: String, targetLang: String = "tr"): String =
		Companion.formatScanlationText(text, targetLang)

	fun resolveTypefaceForText(
		fontKey: ScanlationFontKey,
		fontWeight: Int,
		fontStyle: FontStyleMode,
		sampleText: String,
	): Typeface {
		val candidates = buildFontFallbackChain(fontKey)
		val probePaint = Paint()
		val isItalic = fontStyle == FontStyleMode.ITALIC
		for (resId in candidates) {
			val styled = getOrCreateStyledTypeface(resId, fontWeight, isItalic)
			if (canRenderAllGlyphs(probePaint, styled, sampleText)) {
				return styled
			}
		}
		return getOrCreateStyledTypeface(candidates.last(), fontWeight, isItalic)
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
		paint.isFakeBoldText = Build.VERSION.SDK_INT < Build.VERSION_CODES.P && style.fontWeight >= 650
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
			Build.VERSION.SDK_INT < Build.VERSION_CODES.P
		) {
			paint.fontVariationSettings = "'wght' ${style.fontWeight.coerceIn(300, 800)}"
		}
		paint.textSkewX = if (style.skewDeg != 0f) {
			-tan(Math.toRadians(style.skewDeg.toDouble())).toFloat()
		} else {
			0f
		}
	}

	fun measureStyledWordWidth(
		word: String,
		paint: Paint,
		fontFamily: ScanlationFontKey = ScanlationFontKey.SHANTELL,
	): Float {
		val w = paint.measureText(word)
		if (w > 0f) {
			val spacingBonus =
				if (fontFamily == ScanlationFontKey.ANTON || fontFamily == ScanlationFontKey.BANGERS) {
					1.04f
				} else {
					1.01f
				}
			return w * spacingBonus
		}
		return estimateTextWidthFallback(word, paint.textSize, fontFamily)
	}

	fun computeSmartWrappedLines(
		text: String,
		maxWidthPx: Float,
		paint: TextPaint,
		lineWidthsPct: List<Float> = emptyList(),
		emphasisLastLineScale: Float = 1f,
		fontFamily: ScanlationFontKey = ScanlationFontKey.SHANTELL,
		lineHeightRatio: Float = getLineHeightRatioForBlock(fontFamily),
	): SmartWrapResult {
		val baseSize = paint.textSize.coerceAtLeast(1f)
		return Companion.computeSmartWrappedLines(
			text = text,
			maxWidthPx = maxWidthPx,
			fontPx = baseSize,
			lineWidthsPct = lineWidthsPct,
			emphasisLastLineScale = emphasisLastLineScale,
			fontFamily = fontFamily,
			lineHeightRatio = lineHeightRatio,
		) { s, reqFontPx ->
			if (abs(reqFontPx - baseSize) < 0.05f) {
				measureStyledWordWidth(s, paint, fontFamily)
			} else {
				val prev = paint.textSize
				paint.textSize = reqFontPx
				val measured = measureStyledWordWidth(s, paint, fontFamily)
				paint.textSize = prev
				measured
			}
		}
	}

	fun getLineHeightRatioForBlock(
		kind: TextBlockKind,
		style: TextStyleFingerprint,
	): Float {
		// Consistent ratio matching textLayout.ts getLineHeightRatioForBlock; keep signature compatible.
		if (kind == TextBlockKind.SFX &&
			(style.fontFamily == ScanlationFontKey.ANTON || style.fontFamily == ScanlationFontKey.BANGERS)
		) {
			return 1.12f
		}
		return Companion.getLineHeightRatioForBlock(style.fontFamily)
	}

	private fun buildFontFallbackChain(requested: ScanlationFontKey): List<Int> {
		val list = ArrayList<Int>(4)
		list.add(fontResForKey(requested))
		if (requested != ScanlationFontKey.SHANTELL) {
			list.add(R.font.shantell_sans)
		}
		list.add(R.font.google_sans)
		list.add(SYS_SANS_SENTINEL)
		return list
	}

	private fun getOrCreateStyledTypeface(
		@FontRes resId: Int,
		fontWeight: Int,
		isItalic: Boolean,
	): Typeface {
		val key = FontCacheKey(resId, fontWeight, isItalic)
		return styledFontCache.getOrPut(key) {
			val base = if (resId == SYS_SANS_SENTINEL) {
				Typeface.SANS_SERIF
			} else {
				baseFontCache.getOrPut(resId) {
					runCatching { ResourcesCompat.getFont(context, resId) }.getOrNull()
						?: Typeface.SANS_SERIF
				}
			}
			applyStyleToTypeface(base, fontWeight, isItalic)
		}
	}

	private fun applyStyleToTypeface(
		base: Typeface,
		fontWeight: Int,
		isItalic: Boolean,
	): Typeface {
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

	companion object {
		private const val SYS_SANS_SENTINEL = -1
		private val HORIZONTAL_SPACE_REGEX = Regex("[ \\t]+")
		private val MULTI_SPACE_REGEX = Regex(" {2,}")
		private val WHITESPACE_REGEX = Regex("\\s+")
		private val NON_LETTER_REGEX = Regex("[^\\p{L}]")
		private val TR_VOWELS = hashSetOf(
			'a', 'e', 'ı', 'i', 'o', 'ö', 'u', 'ü',
			'A', 'E', 'I', 'İ', 'O', 'Ö', 'U', 'Ü',
		)
		private val BULLET_ROW_REGEX = Regex("^\\s*[•\\-*+]\\s+")

		fun estimateTextWidthFallback(
			text: String,
			fontPx: Float,
			fontFamily: ScanlationFontKey = ScanlationFontKey.SHANTELL,
		): Float {
			val charFactor =
				if (fontFamily == ScanlationFontKey.ANTON || fontFamily == ScanlationFontKey.BANGERS) {
					0.56f
				} else {
					0.54f
				}
			return text.length * fontPx * charFactor
		}

		fun getLineHeightRatioForBlock(fontFamily: ScanlationFontKey = ScanlationFontKey.SHANTELL): Float =
			if (fontFamily == ScanlationFontKey.ANTON || fontFamily == ScanlationFontKey.BANGERS) {
				1.12f
			} else {
				1.13f
			}

		fun formatScanlationText(text: String, targetLang: String = "tr"): String {
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
				RenderTextHelpers.isRpgStatLine(line) || BULLET_ROW_REGEX.containsMatchIn(line)
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

		/**
		 * ToonParrot `textLayout.ts:43-116` parity: splits oversized words (>= 10 alpha chars)
		 * at Turkish syllable boundaries (C+V or V+C+C) or existing hyphens so lines balance naturally.
		 */
		fun splitOversizedWordBySyllable(
			word: String,
			maxWidthPx: Float,
			measureWidth: (String) -> Float,
		): List<String> {
			if (word.isEmpty()) return emptyList()
			if (measureWidth(word) <= maxWidthPx * 0.96f) return listOf(word)

			val dashIdx = word.indexOf('-')
			if (dashIdx >= 3 && word.length - dashIdx >= 4) {
				val first = word.substring(0, dashIdx + 1)
				val second = word.substring(dashIdx + 1)
				if (measureWidth(first) <= maxWidthPx * 0.96f) {
					return listOf(first) + splitOversizedWordBySyllable(second, maxWidthPx, measureWidth)
				}
			}

			val alphaOnly = word.replace(NON_LETTER_REGEX, "")
			if (alphaOnly.length < 10) return listOf(word)

			val chars = word.toCharArray()
			if (chars.size < 10) return listOf(word)
			val candidateSplits = ArrayList<Int>()
			for (i in 4..(chars.size - 4)) {
				if (!Character.isLetter(chars[i - 1]) || !Character.isLetter(chars[i])) continue
				val prevIsVowel = chars[i - 1] in TR_VOWELS
				val currIsVowel = chars[i] in TR_VOWELS
				val nextIsVowel = i + 1 < chars.size && chars[i + 1] in TR_VOWELS
				if (!prevIsVowel && currIsVowel) {
					if (i - 1 >= 4 && chars.size - (i - 1) >= 4) {
						candidateSplits.add(i - 1)
					}
				} else if (prevIsVowel && !currIsVowel && !nextIsVowel) {
					candidateSplits.add(i + 1)
				}
			}

			if (candidateSplits.isEmpty()) return listOf(word)

			val mid = chars.size * 0.52
			val uniqueSorted = candidateSplits.distinct()
				.filter { it in 3 until chars.size - 2 }
				.sortedBy { abs(it - mid) }
			if (uniqueSorted.isEmpty()) return listOf(word)

			var chosen = -1
			for (idx in uniqueSorted) {
				val leftPart = "${String(chars, 0, idx)}-"
				if (measureWidth(leftPart) <= maxWidthPx * 0.96f) {
					chosen = idx
					break
				}
			}
			if (chosen < 0) {
				chosen = uniqueSorted.min()
			}

			val head = "${String(chars, 0, chosen)}-"
			val tail = String(chars, chosen, chars.size - chosen)
			if (tail.length >= word.length) return listOf(word)
			return listOf(head) + splitOversizedWordBySyllable(tail, maxWidthPx, measureWidth)
		}

		/**
		 * Pure-Kotlin port of `textLayout.ts:135-314` supporting oval/contour DP wrapping,
		 * orphan-word penalty, explicit `lineWidthsPct`, Turkish hyphenation, and `emphasisLastLineScale`.
		 */
		fun computeSmartWrappedLines(
			text: String,
			maxWidthPx: Float,
			fontPx: Float,
			lineWidthsPct: List<Float> = emptyList(),
			emphasisLastLineScale: Float = 1f,
			fontFamily: ScanlationFontKey = ScanlationFontKey.SHANTELL,
			lineHeightRatio: Float = getLineHeightRatioForBlock(fontFamily),
			measureScaledWidth: (String, Float) -> Float,
		): SmartWrapResult {
			val safeMaxW = maxWidthPx.coerceAtLeast(8f)
			val safeFontPx = fontPx.coerceAtLeast(1f)
			val trimmed = text.trim()
			if (trimmed.isEmpty()) {
				return SmartWrapResult(emptyList(), 0f, 0f, 0f, false)
			}

			if (trimmed.contains('\n')) {
				val explicitParas = trimmed.split('\n')
					.map { it.trim() }
					.filter { it.isNotEmpty() }
				if (explicitParas.size > 1) {
					val allLines = ArrayList<SmartWrappedLine>(explicitParas.size * 2)
					var maxWordW = 0f
					var maxLineW = 0f
					var totalH = 0f
					var anyOverflow = false
					for (para in explicitParas) {
						val res = wrapSingleParagraph(
							paragraph = para,
							maxWidthPx = safeMaxW,
							fontPx = safeFontPx,
							lineWidthsPct = emptyList(),
							emphasisLastLineScale = 1f,
							lineHeightRatio = lineHeightRatio,
							measureScaledWidth = measureScaledWidth,
						)
						allLines.addAll(res.lines)
						if (res.maxWordWidth > maxWordW) maxWordW = res.maxWordWidth
						if (res.maxLineWidthPx > maxLineW) maxLineW = res.maxLineWidthPx
						totalH += res.totalHeightPx
						if (res.hasOverflow) anyOverflow = true
					}
					return SmartWrapResult(allLines, maxWordW, totalH, maxLineW, anyOverflow)
				}
			}

			return wrapSingleParagraph(
				paragraph = trimmed,
				maxWidthPx = safeMaxW,
				fontPx = safeFontPx,
				lineWidthsPct = lineWidthsPct,
				emphasisLastLineScale = emphasisLastLineScale,
				lineHeightRatio = lineHeightRatio,
				measureScaledWidth = measureScaledWidth,
			)
		}

		private fun wrapSingleParagraph(
			paragraph: String,
			maxWidthPx: Float,
			fontPx: Float,
			lineWidthsPct: List<Float>,
			emphasisLastLineScale: Float,
			lineHeightRatio: Float,
			measureScaledWidth: (String, Float) -> Float,
		): SmartWrapResult {
			val words = paragraph.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
			if (words.isEmpty()) {
				return SmartWrapResult(emptyList(), 0f, 0f, 0f, false)
			}

			var bodyWords: List<String> = words
			var emphasisText = ""
			if (emphasisLastLineScale >= 1.2f && words.size >= 3) {
				val lastWord = words.last()
				val lastAlphaLen = lastWord.replace(NON_LETTER_REGEX, "").length
				val takeTwo = words.size >= 5 && lastAlphaLen <= 4
				val splitIdx = if (takeTwo) words.size - 2 else words.size - 1
				bodyWords = words.subList(0, splitIdx)
				emphasisText = words.subList(splitIdx, words.size).joinToString(" ")
			}

			bodyWords = bodyWords.flatMap { word ->
				splitOversizedWordBySyllable(word, maxWidthPx) { s ->
					measureScaledWidth(s, fontPx)
				}
			}

			val spaceRaw = measureScaledWidth(" ", fontPx)
			val spaceW = if (spaceRaw > 0f) spaceRaw else fontPx * 0.28f
			val wordWidths = FloatArray(bodyWords.size) { i ->
				measureScaledWidth(bodyWords[i], fontPx)
			}
			var maxWordWidth = wordWidths.maxOrNull() ?: 0f

			val greedyLines = ArrayList<String>()
			var curLine = ""
			var curW = 0f
			for (i in bodyWords.indices) {
				val w = bodyWords[i]
				val ww = wordWidths[i]
				if (curLine.isEmpty()) {
					curLine = w
					curW = ww
				} else if (curW + spaceW + ww <= maxWidthPx) {
					curLine = "$curLine $w"
					curW += spaceW + ww
				} else {
					greedyLines.add(curLine)
					curLine = w
					curW = ww
				}
			}
			if (curLine.isNotEmpty()) greedyLines.add(curLine)

			val targetLineCount = greedyLines.size
			val finalBodyLines = ArrayList<SmartWrappedLine>()
			val n = bodyWords.size

			if (targetLineCount in 2..7 && n in 2..36) {
				val cols = targetLineCount + 1
				val dp = DoubleArray((n + 1) * cols) { Double.POSITIVE_INFINITY }
				val nextBreak = IntArray((n + 1) * cols)
				dp[n * cols] = 0.0

				val totalDisplayLines = targetLineCount + if (emphasisText.isNotEmpty()) 1 else 0
				val hasExplicitWidths = lineWidthsPct.size == totalDisplayLines && totalDisplayLines > 0

				for (remLines in 1..targetLineCount) {
					val lineIdx = targetLineCount - remLines
					val lineRatio = getLineTargetRatio(lineIdx, totalDisplayLines, lineWidthsPct, hasExplicitWidths)
					val allowedMaxW = if (hasExplicitWidths) {
						maxWidthPx * lineRatio * 1.01
					} else {
						maxWidthPx * min(1.0, lineRatio + 0.08)
					}
					val idealW = maxWidthPx * lineRatio * 0.85
					val overflowLimit = if (hasExplicitWidths) maxWidthPx * lineRatio else maxWidthPx.toDouble()

					for (i in 0 until n) {
						var lineW = 0.0
						for (j in i until n) {
							lineW += (if (j == i) 0.0 else spaceW.toDouble()) + wordWidths[j].toDouble()
							if (lineW > allowedMaxW && j > i) break

							val nextCost = dp[(j + 1) * cols + (remLines - 1)]
							if (nextCost.isInfinite()) continue

							val overflowPenalty = if (lineW > overflowLimit) (lineW - overflowLimit) * 50.0 else 0.0
							val diff = idealW - lineW
							val orphanPenalty = if (remLines == 1 && j == i && n >= 4) {
								val p = maxWidthPx * 0.45
								p * p
							} else {
								0.0
							}
							val cost = diff * diff + overflowPenalty * overflowPenalty + orphanPenalty + nextCost

							val stateIdx = i * cols + remLines
							if (cost < dp[stateIdx]) {
								dp[stateIdx] = cost
								nextBreak[stateIdx] = j + 1
							}
						}
					}
				}

				if (dp[targetLineCount].isFinite()) {
					var curr = 0
					for (rem in targetLineCount downTo 1) {
						if (curr >= n) break
						val nxt = nextBreak[curr * cols + rem]
						if (nxt <= curr) break
						var lw = 0f
						for (k in curr until nxt) {
							lw += (if (k == curr) 0f else spaceW) + wordWidths[k]
						}
						finalBodyLines.add(
							SmartWrappedLine(
								text = bodyWords.subList(curr, nxt).joinToString(" "),
								widthPx = lw,
								scale = 1f,
							),
						)
						curr = nxt
					}
					if (curr < n) {
						finalBodyLines.clear()
					}
				}
			}

			if (finalBodyLines.isEmpty()) {
				for (lineText in greedyLines) {
					finalBodyLines.add(
						SmartWrappedLine(
							text = lineText,
							widthPx = measureScaledWidth(lineText, fontPx),
							scale = 1f,
						),
					)
				}
			}

			val combined = ArrayList<SmartWrappedLine>(finalBodyLines.size + 1)
			combined.addAll(finalBodyLines)

			if (emphasisText.isNotEmpty() && emphasisLastLineScale >= 1.2f) {
				val empFontPx = fontPx * emphasisLastLineScale
				val empW = measureScaledWidth(emphasisText, empFontPx)
				if (empW > maxWordWidth) maxWordWidth = empW
				combined.add(
					SmartWrappedLine(
						text = emphasisText,
						widthPx = empW,
						scale = emphasisLastLineScale,
					),
				)
			}

			var totalHeightPx = 0f
			var maxLineWidthPx = 0f
			var anyOverflow = false
			val scaledLines = combined.map { line ->
				val newScale = if (line.widthPx > maxWidthPx && line.widthPx > 0f) {
					val rawScaled = (maxWidthPx / line.widthPx) * line.scale
					val rounded = (max(0.88f, rawScaled) * 1000f).roundToInt() / 1000f
					if (rawScaled < 0.88f) anyOverflow = true
					rounded
				} else {
					line.scale
				}
				val effW = if (line.scale > 0f) line.widthPx * (newScale / line.scale) else line.widthPx
				if (effW > maxLineWidthPx) maxLineWidthPx = effW
				if (effW > maxWidthPx * 1.01f) anyOverflow = true
				totalHeightPx += fontPx * newScale * lineHeightRatio
				if (newScale != line.scale) line.copy(scale = newScale) else line
			}

			return SmartWrapResult(
				lines = scaledLines,
				maxWordWidth = maxWordWidth,
				totalHeightPx = totalHeightPx,
				maxLineWidthPx = maxLineWidthPx,
				hasOverflow = anyOverflow,
			)
		}

		private fun getLineTargetRatio(
			lineIdx: Int,
			totalLines: Int,
			lineWidthsPct: List<Float>,
			hasExplicitWidths: Boolean,
		): Double {
			if (hasExplicitWidths && lineIdx in lineWidthsPct.indices) {
				val raw = lineWidthsPct[lineIdx]
				val normalized = if (raw > 1.5f) raw / 100.0 else raw.toDouble()
				return normalized.coerceIn(0.45, 1.0)
			}
			if (totalLines <= 2) return 0.92
			val normPos = (lineIdx + 0.5) / totalLines - 0.5
			return max(0.76, 1.0 - normPos * normPos * 0.65)
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
	}
}
