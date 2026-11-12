package org.koitharu.kotatsu.reader.domain.translate.ocr

import org.koitharu.kotatsu.reader.domain.translate.model.OcrBubbleGroup
import java.util.Locale

internal data class RawOcrLine(
	val text: String,
	val confidence: Float,
	val x0: Float,
	val y0: Float,
	val x1: Float,
	val y1: Float,
) {
	val width: Float get() = x1 - x0
	val height: Float get() = y1 - y0
	val centerX: Float get() = (x0 + x1) * 0.5f
	val centerY: Float get() = (y0 + y1) * 0.5f
}

/**
 * OCR gürültü temizleme, reklam/jenerik sayfa tespiti ve saf ses efekti (SFX) filtreleme yardımcıları.
 */
object TextBlockFilters {

	const val DEFAULT_MIN_LINE_CONFIDENCE = 78f

	private val WHITESPACE_REGEX = Regex("""\s+""")
	private val EDGE_SYMBOLS_LEADING_REGEX = Regex("""^[|\\/_=~^`<>]+""")
	private val EDGE_SYMBOLS_TRAILING_REGEX = Regex("""[|\\/_=~^`<>]+$""")
	private val ANGLE_TAG_REGEX = Regex("""<[^>]{1,28}>""")
	private val ANGLE_BRACKET_REGEX = Regex("""[<>]+""")
	private val HYPHEN_JOIN_REGEX = Regex("""(\p{L})- (\p{L})""")
	private val ZERO_AFTER_ALPHA_REGEX = Regex("""(?<=[A-Za-z])0(?=[A-Za-z.!?,;:'"\s]|$)""")
	private val ZERO_BEFORE_ALPHA_REGEX = Regex("""(?:^|(?<=[\s"'(\[¡¿]))0(?=[A-Za-z]+)""")
	private val FIVE_ZERO_TO_SO_REGEX = Regex("""^5[0O]([!?.…]*)$""", RegexOption.IGNORE_CASE)
	private val SPANISH_STRAY_C_REGEX = Regex(
		"""\b[cC](?=(?:eh|ah|oh|que|quien|qlien|qle|como|cuando|cuanto|donde|por|para|podria|podrias|verdad|estaria|esta|eso|ese|ahora|acaso|sera|puede|puedo|tiene|hay)\b)""",
		RegexOption.IGNORE_CASE,
	)
	private val SPANISH_QLE_REGEX = Regex("""\b[qQ][lL][eE]\b""")
	private val SPANISH_QLIEN_REGEX = Regex("""\b[qQ][lL][iI][eE][nN]\b""")
	private val SPANISH_MLCH_REGEX = Regex("""\b[mM][lL][cC][hH]([aA][sS]?|[oO][sS]?)\b""")

	private val LETTER_REGEX = Regex("""\p{L}""")
	private val DIGIT_REGEX = Regex("""\p{N}""")
	private val JUNK_CHAR_REGEX = Regex(
		"""[^\p{L}\p{N}\s.,!?'"“”‘’…:;()\-–—~&%+*「」『』【】〈〉《》（）？！。、，・ー〜～،؟؛«»]""",
	)
	private val PUNCT_ONLY_ALLOW_REGEX = Regex("""^[.…!?]+$""")
	private val SINGLE_LATIN_WORD_REGEX = Regex("""^(?:I|A|O)[!?.…,]*$""", RegexOption.IGNORE_CASE)
	private val NON_LETTER_DIGIT_EDGE_REGEX = Regex("""^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$""")
	private val CLEAN_MULTI_TOKEN_REGEX = Regex(
		"""^[\p{L}\p{N}]+(?:[\s.,!?'"“”‘’…:;()\-–—~「」『』【】〈〉《》（）？！。、，・ー〜～،؟؛]+[\p{L}\p{N}]+)*[\s.,!?'"“”‘’…:;()\-–—~「」『』【】〈〉《》（）？！。、，・ー〜～،؟؛]*$""",
	)

	private val SHORT_MEANINGFUL_WORD_REGEX = Regex(
		"""^(?:no|yes|go|so|up|in|on|ok|hi|me|us|we|he|do|to|or|if|my|by|am|is|it|at|be|oh|ah|eh|uh|hm|yo|ha|ho|aw|ow|oi|why|who|how|now|run|die|out|off|get|let|see|say|can|not|all|one|two|too|bad|new|old|big|end|lie|cry|try|fly|sky|son|mom|dad|bro|sis|sir|god|war|sin|red|hit|cut|weak|what|wait|stop|help|kill|dead|damn|shit|hell|sure|fine|good|nice|cool|real|true|fast|slow|hard|easy|high|low|full|none|zero|more|less|most|last|next|back|down|away|here|look|take|give|make|come|gone|done)[!?.…,:;'"~-]*$""",
		RegexOption.IGNORE_CASE,
	)

	private val CONSONANT_STRETCH_SFX_REGEX = Regex(
		"""^(?:sh+|z+|g+r+|k+r+|b+z+|f+s+h+|s+w+i+s+h+|w+h+o+s+h+|t+h+u+d+|b+o+m+|b+a+m+|p+o+w+)$""",
		RegexOption.IGNORE_CASE,
	)

	// Anlamsız seslenme/inleme uzatmaları: "aghhh", "ughhh", "aaaahhh", "ooohhh", "aaargh" (anlamlı "nooo", "stooop", "heelp" kelimelerine dokunmaz).
	private val VOWEL_GRUNT_STRETCH_REGEX = Regex(
		"""^(?:[aeou]+h+|[auo]+g+h+|[aeou]*r+g+h+|m+p+h+|h+m+p+h+|p+f+t*|t+s+k+|t+c+h+)$""",
		RegexOption.IGNORE_CASE,
	)

	private val COLLAPSE_TRIPLE_REGEX = Regex("""([a-z])\1{2,}""")
	private val COLLAPSE_DOUBLE_REGEX = Regex("""([a-z])\1+""")
	private val NON_ASCII_LOWER_ALPHA_REGEX = Regex("""[^a-z]""")
	private val SFX_CLEAN_EDGE_REGEX = Regex(
		"""^[¡¿.,!?:;…\-_~*^'"/\\()\[\]{}<>◁▷◀▶★☆•\s]+|[¡¿.,!?:;…\-_~*^'"/\\()\[\]{}<>◁▷◀▶★☆•\s]+$""",
	)
	private val SFX_SPLIT_REGEX = Regex("""[\s¡¿.,!?:;…\-_~*^'"/\\()\[\]{}<>]+""")

	private val PURE_SFX_STEMS = hashSetOf(
		// Darbe / Patlama / Çarpma
		"boom", "kaboom", "bam", "baam", "bang", "clang", "clank", "clash", "clack",
		"clatter", "crash", "crack", "crackle", "cruck", "crach", "grack", "craek", "krack",
		"crunch", "thud", "thump", "thwack", "wham", "whack", "slam", "smack", "smash",
		"pow", "poof", "bonk", "boing", "bong", "ding", "dong", "kring", "ting", "plink",
		"plop", "splat", "sploosh", "squelch", "squish", "thwip", "twang", "zing", "zap",
		"zzt", "zzzt", "bzz", "bzzt", "vroom", "screech", "skrrt",
		// Rüzgar / Kesme / Hareket / İrkilme efektleri
		"swoosh", "whoosh", "swish", "shing", "shwing", "schwing", "fwip", "fwoosh",
		"whir", "whirr", "rustle", "patter", "stomp", "startle", "startles", "flinch",
		"flinches", "shudder", "shudders", "tremble", "trembles", "twitch", "twitches",
		"jolt", "jolts",
		// Titreme / Çevre / Hayvan / Kalp atışı
		"rumble", "grumble", "rattle", "creak", "sizzle", "fizz", "glug", "gulp",
		"slurp", "munch", "chomp", "woof", "meow", "purr", "grr", "grrr", "roar", "growl", "howl",
		"badump", "badum", "lubdub", "doki", "dokidoki",
		// Nefes / Gülme / Seslenme / Anlamsız homurtu efektleri
		"wheeze", "sniffle", "achoo", "hic", "zzz", "bwahaha", "muahaha", "mwahaha",
		"kekeke", "kukuku", "agh", "aghh", "aghhh", "ugh", "ughh", "ughhh", "argh", "urgh",
		"ergh", "mmph", "mmmph", "mph", "hmph", "hmmph", "huff", "pant", "phew", "tsk",
		"tch", "pff", "pfft", "bleh", "gack", "guh", "blub", "glub", "aaah", "aaaah",
		// Korece / Japonca romanize saf ses efektleri
		"kwang", "kwaang", "kung", "kuung", "dugeun", "dukgeun", "jjeoreong", "puk",
		"peok", "ssuk", "geuk", "kkeuk", "heok", "heuk", "deolkeok", "chirik", "sarak",
		"ttuk", "jjak", "gogo", "gogogo", "menacing",
		// Türkçe yansıma / ses efekti karşılıkları
		"çat", "pat", "küt", "güm", "şak", "tak", "tık", "çatır", "çatırt", "çatırtı",
		"şırrak", "şangır", "gıcırt", "gıcırtı", "hışırt", "hışırtı", "vın", "vınn",
		"vız", "vızz", "hart", "kırt", "fış", "fışş", "cız", "cızz", "şlap", "irülür", "irkilir",
	)

	private val REPEATED_ONLY_SFX_STEMS = hashSetOf(
		"tap", "step", "drip", "drop", "clap", "pat", "knock", "tick", "tock", "click",
		"pop", "snap", "ring", "beep", "honk", "cough", "pant", "sob", "gasp", "gulp",
		"chew", "chop", "slash", "slice", "poke", "prod", "shake", "tremble", "shiver",
		"shudder", "flinch", "twitch", "sparkle", "glint", "gleam", "flash", "glow", "huff",
	)

	private val CREDIT_LINE_REGEX = Regex(
		"""\b(?:translator|translation|proofreader|proofreading|cleaner|cleaning|redrawer|redraw|typesetter|typesetting|quality\s*checker|editor|raw\s*provider|scanlation|fansub|fansube|kurohana|asurascans|asura\s*scans|reaperscans|reaper\s*scans|flamescans|flame\s*scans|luminousscans|vortexscans|drakescans|manhuaplus|manhuaus|hivetoons|realmscans|leviathanscans|reset\s*scans|rizz\s*comic|cosmic\s*scans|night\s*scans|void\s*scans|recruiting|discord|patreon|paypal|ko-?fi|buymeacoffee|join\s+(?:our|us)|support\s+us|read\s+(?:it\s+)?(?:at|on|first)|chapter\s*\d+|çevirmen|çeviri|editör|dizgi|temizleme)\b|(?:^|\s)(?:tl|pr|cl|rd|ts|qc|ktl|tlc)\s*[:\-]?\s*[a-z0-9_]{2,14}$""",
		RegexOption.IGNORE_CASE,
	)

	private val INDIVIDUAL_PROMO_CREDIT_REGEX = Regex(
		"""(?:https?://|www\.|discord\.gg|\b(?:discord|patreon|paypal|ko-?fi|buymeacoffee|recruiting|scanlation|fansub|fansube|asurascans|asura\s*scans|reaperscans|reaper\s*scans|flamescans|flame\s*scans|luminousscans|vortexscans|drakescans|manhuaplus|manhuaus|hivetoons|realmscans|leviathanscans|reset\s*scans|rizz\s*comic|cosmic\s*scans|night\s*scans|void\s*scans)\b|\b(?:join\s+(?:our|us)|support\s+us|follow\s+us|read\s+(?:it\s+)?(?:at|on|first))\b|\b(?:translator|translation|proofreader|proofreading|cleaner|cleaning|redrawer|redraw|typesetter|typesetting|quality\s*checker|raw\s*provider|çevirmen|dizgi)\s*[:\-]|(?:^|\s)(?:tl|pr|cl|rd|ts|qc|ktl|tlc)\s*[:\-]\s*\S)""",
		RegexOption.IGNORE_CASE,
	)

	private val URL_LINE_REGEX = Regex(
		"""(?:https?://|www\.|discord\.gg|\b[a-z0-9-]{2,}\.(?:com|net|org|gg|io|me|to|xyz|club|online|site)\b)""",
		RegexOption.IGNORE_CASE,
	)

	private val STRONG_PROMO_REGEX = Regex(
		"""(?:https?://|www\.|discord|patreon|paypal|ko-?fi|buymeacoffee|recruiting|join\s+(?:our|us)|asurascans|asura\s*scans|\b[a-z0-9-]{2,}\.(?:com|net|org|gg|io|me|to|xyz|club|online|site)\b)""",
		RegexOption.IGNORE_CASE,
	)

	fun cleanOcrText(text: String): String {
		val normalized = WHITESPACE_REGEX.replace(text, " ").trim()
		if (normalized.isEmpty()) return ""
		val tokens = normalized.split(' ').toMutableList()
		fun isJunkToken(tok: String): Boolean =
			!LETTER_REGEX.containsMatchIn(tok) && !DIGIT_REGEX.containsMatchIn(tok) && !PUNCT_ONLY_ALLOW_REGEX.matches(tok)
		while (tokens.isNotEmpty() && isJunkToken(tokens.first())) tokens.removeAt(0)
		while (tokens.isNotEmpty() && isJunkToken(tokens.last())) tokens.removeAt(tokens.lastIndex)
		if (tokens.isEmpty()) return ""

		var result = tokens.joinToString(" ")
		result = EDGE_SYMBOLS_LEADING_REGEX.replace(result, "")
		result = EDGE_SYMBOLS_TRAILING_REGEX.replace(result, "")
		result = ANGLE_TAG_REGEX.replace(result, " ")
		result = ANGLE_BRACKET_REGEX.replace(result, " ")
		result = WHITESPACE_REGEX.replace(result, " ")
		result = ZERO_AFTER_ALPHA_REGEX.replace(result, "O")
		result = ZERO_BEFORE_ALPHA_REGEX.replace(result, "O")
		result = FIVE_ZERO_TO_SO_REGEX.replace(result, "SO$1")
		result = SPANISH_STRAY_C_REGEX.replace(result, "")
		result = SPANISH_QLE_REGEX.replace(result, "QUE")
		result = SPANISH_QLIEN_REGEX.replace(result, "QUIEN")
		result = SPANISH_MLCH_REGEX.replace(result) { m -> "Much${m.groupValues[1]}" }
		return result.trim()
	}

	internal fun joinHyphenatedWords(text: String): String =
		WHITESPACE_REGEX.replace(HYPHEN_JOIN_REGEX.replace(text, "$1$2"), " ").trim()

	internal fun countLetters(text: String): Int = LETTER_REGEX.findAll(text).count()

	internal fun countJunk(text: String): Int = JUNK_CHAR_REGEX.findAll(text).count()

	internal fun hasCjkOrHangulOrArabic(text: String): Boolean {
		for (i in 0 until text.length) {
			val block = Character.UnicodeBlock.of(text[i]) ?: continue
			if (
				block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
				block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
				block == Character.UnicodeBlock.HIRAGANA ||
				block == Character.UnicodeBlock.KATAKANA ||
				block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
				block == Character.UnicodeBlock.HANGUL_JAMO ||
				block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO ||
				block == Character.UnicodeBlock.ARABIC ||
				block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
				block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
			) {
				return true
			}
		}
		return false
	}

	internal fun isUsableOcrLine(
		line: RawOcrLine,
		minConfidence: Float = DEFAULT_MIN_LINE_CONFIDENCE,
	): Boolean {
		val text = line.text.trim()
		if (text.isEmpty()) return false
		val stripped = NON_LETTER_DIGIT_EDGE_REGEX.replace(text, "")
		val isShortMeaningful = SHORT_MEANINGFUL_WORD_REGEX.matches(stripped)
		val letters = countLetters(text)
		val digits = DIGIT_REGEX.findAll(text).count()
		val junk = countJunk(text)
		val multiScript = hasCjkOrHangulOrArabic(text)
		val minCleanLetters = if (multiScript) 2 else 3
		val isCleanText = junk == 0 &&
			letters >= minCleanLetters &&
			CLEAN_MULTI_TOKEN_REGEX.matches(stripped.ifEmpty { text })

		val effectiveMinConf = when {
			isShortMeaningful -> minOf(minConfidence, 40f)
			isCleanText -> minOf(minConfidence, 56f)
			multiScript && letters >= 1 && junk == 0 -> minOf(minConfidence, 58f)
			else -> minConfidence
		}

		if (line.confidence < effectiveMinConf) return false
		if (letters == 0) return false
		if (letters < 2 && !multiScript && !SINGLE_LATIN_WORD_REGEX.matches(text)) return false

		val total = maxOf(1, letters + digits + junk)
		if (junk.toFloat() / total > 0.25f) return false

		if (!multiScript) {
			val words = text.split(WHITESPACE_REGEX).filter { LETTER_REGEX.containsMatchIn(it) }
			if (words.size >= 3 && words.all { NON_LETTER_DIGIT_EDGE_REGEX.replace(it, "").length <= 1 }) {
				return false
			}
		}
		return true
	}

	private fun collapseRepeatedChars(word: String): String {
		val lower = word.lowercase(Locale.ROOT)
		return COLLAPSE_DOUBLE_REGEX.replace(COLLAPSE_TRIPLE_REGEX.replace(lower, "$1$1"), "$1")
	}

	fun isPureSoundEffect(text: String): Boolean {
		val cleaned = SFX_CLEAN_EDGE_REGEX.replace(text.trim(), "")
		if (cleaned.isEmpty()) return true

		val rawTokens = SFX_SPLIT_REGEX.split(cleaned)
			.map { it.trim().lowercase(Locale.ROOT) }
			.filter { it.isNotEmpty() }

		if (rawTokens.isEmpty() || rawTokens.size > 4) return false

		val joinedAlpha = NON_ASCII_LOWER_ALPHA_REGEX.replace(rawTokens.joinToString(""), "")
		if (joinedAlpha.isNotEmpty() && (joinedAlpha in PURE_SFX_STEMS || collapseRepeatedChars(joinedAlpha) in PURE_SFX_STEMS)) {
			return true
		}

		val allPureSfx = rawTokens.all { tok ->
			val exact = NON_ASCII_LOWER_ALPHA_REGEX.replace(tok, "")
			if (exact.isEmpty()) return@all true
			if (exact in PURE_SFX_STEMS) return@all true
			val collapsed = collapseRepeatedChars(exact)
			if (collapsed in PURE_SFX_STEMS) return@all true
			if (CONSONANT_STRETCH_SFX_REGEX.matches(exact)) return@all true
			if (exact.length >= 4 && VOWEL_GRUNT_STRETCH_REGEX.matches(exact)) return@all true
			false
		}
		if (allPureSfx) return true

		if (rawTokens.size >= 2) {
			val stems = rawTokens.map { NON_ASCII_LOWER_ALPHA_REGEX.replace(it, "") }
			val allRepeatedSfx = stems.all { s ->
				s in REPEATED_ONLY_SFX_STEMS || collapseRepeatedChars(s) in REPEATED_ONLY_SFX_STEMS
			}
			if (allRepeatedSfx) {
				val uniqueStems = stems.mapTo(HashSet()) { collapseRepeatedChars(it) }
				if (uniqueStems.size == 1) return true
				if ("tick" in uniqueStems && "tock" in uniqueStems && uniqueStems.size == 2) return true
			}
		}

		return false
	}

	fun isCreditOrPromoText(text: String): Boolean {
		val trimmed = text.trim()
		return CREDIT_LINE_REGEX.containsMatchIn(trimmed) || URL_LINE_REGEX.containsMatchIn(trimmed)
	}

	fun shouldSkipBlock(
		text: String,
		skipPureSfx: Boolean = true,
		filterPromoAndCredits: Boolean = true,
	): Boolean {
		val trimmed = text.trim()
		if (trimmed.isEmpty()) return true
		if (countLetters(trimmed) == 0) return true
		if (filterPromoAndCredits && (URL_LINE_REGEX.containsMatchIn(trimmed) || INDIVIDUAL_PROMO_CREDIT_REGEX.containsMatchIn(trimmed))) {
			return true
		}
		if (skipPureSfx && isPureSoundEffect(trimmed)) {
			return true
		}
		return false
	}

	fun isCreditsOrPromoPage(bubbles: List<OcrBubbleGroup>): Boolean {
		if (bubbles.isEmpty()) return false
		val promo = bubbles.count { isCreditOrPromoText(it.text) }
		if (promo == 0) return false
		if (promo == bubbles.size) return true

		val creditRolesCount = bubbles.count { CREDIT_LINE_REGEX.containsMatchIn(it.text) }
		if (
			creditRolesCount >= 2 &&
			bubbles.any { STRONG_PROMO_REGEX.containsMatchIn(it.text) } &&
			creditRolesCount.toFloat() / bubbles.size >= 0.45f
		) {
			return true
		}
		return false
	}

	fun filterBubbles(
		bubbles: List<OcrBubbleGroup>,
		skipPureSfx: Boolean = true,
		filterPromoAndCredits: Boolean = true,
	): List<OcrBubbleGroup> {
		if (bubbles.isEmpty()) return emptyList()
		if (filterPromoAndCredits && isCreditsOrPromoPage(bubbles)) {
			return emptyList()
		}
		return bubbles.filterNot { bubble ->
			shouldSkipBlock(
				text = bubble.text,
				skipPureSfx = skipPureSfx,
				filterPromoAndCredits = filterPromoAndCredits,
			)
		}
	}
}
