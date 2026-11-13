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
	private val QUOTE_CONTRACTION_REGEX = Regex(
		"""(?<=\p{L})["“”](?=(?:s|t|m|d|re|ve|ll)\b)""",
		RegexOption.IGNORE_CASE,
	)
	private val QUOTE_BETWEEN_ALPHA_REGEX = Regex("""(?<=[A-Za-z])["“”](?=[A-Za-z])""")
	private val HYPHEN_TRAILING_QUOTE_REGEX = Regex("""(\p{L})-[\"'“”‘’`]+(?=\s|$)""")
	private val HYPHEN_JOIN_REGEX = Regex("""(\p{L})-[\"'“”‘’`]?\s+(\p{L})""")
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
	private val TRIPLE_SAME_CHAR_REGEX = Regex("""^(.)\1{2,}$""", RegexOption.IGNORE_CASE)
	private val LATIN_VOWEL_REGEX = Regex(
		"""[aeiouyáàâäãåéèêëíìîïóòôöõøúùûüýÿ]""",
		RegexOption.IGNORE_CASE,
	)
	private val ASCII_VOWEL_REGEX = Regex("""[aeiouy]""", RegexOption.IGNORE_CASE)
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
	private val NON_ASCII_ALPHA_REGEX = Regex("""[^a-zA-Z]""")
	private val NON_UNICODE_LOWER_ALPHA_REGEX = Regex("""[^\p{L}]""")
	private val SFX_CLEAN_EDGE_REGEX = Regex(
		"""^[¡¿.,!?:;…\-_~*^'"/\\()\[\]{}<>◁▷◀▶★☆•\s]+|[¡¿.,!?:;…\-_~*^'"/\\()\[\]{}<>◁▷◀▶★☆•\s]+$""",
	)
	private val SFX_SPLIT_REGEX = Regex("""[\s¡¿.,!?:;…\-_~*^'"/\\()\[\]{}<>]+""")

	private val PURE_SFX_STEMS = hashSetOf(
		// Darbe / Patlama / Çarpma
		"boom", "kaboom", "kabam", "kapow", "kerplunk", "kaching", "bam", "baam", "bang",
		"clang", "clank", "clink", "clunk", "clash", "clack", "clatter", "crash", "crack",
		"crackle", "cruck", "crach", "grack", "craek", "krack", "crunch", "thud", "thump",
		"thwack", "wham", "whack", "slam", "smack", "smash", "pow", "poof", "bonk", "boing",
		"bong", "ding", "dong", "kring", "ting", "plink", "plop", "splat", "splish", "splash",
		"sploosh", "squelch", "squish", "thwip", "twang", "zing", "zap", "zzt", "zzzt", "bzz",
		"bzzt", "vroom", "screech", "skrrt", "squeak", "squawk",
		// Rüzgar / Kesme / Hareket / İrkilme / Yüz ifadesi efektleri
		"swoosh", "whoosh", "swish", "shing", "shwing", "schwing", "fwip", "fwoosh",
		"whir", "whirr", "rustle", "patter", "stomp", "startle", "startles", "flinch",
		"flinches", "shudder", "shudders", "tremble", "trembles", "twitch", "twitches",
		"jolt", "jolts", "shiver", "gasp", "grin", "smirk", "stare", "glare",
		// Titreme / Çevre / Hayvan / Kalp atışı
		"rumble", "grumble", "rattle", "creak", "sizzle", "fizz", "glug", "gulp",
		"slurp", "munch", "chomp", "woof", "meow", "purr", "grr", "grrr", "roar", "growl", "howl",
		"hiss", "snarl", "badump", "badum", "lubdub", "doki", "dokidoki",
		// Nefes / Gülme / Seslenme / Anlamsız homurtu efektleri
		"wheeze", "sniffle", "achoo", "hic", "zzz", "bwahaha", "muahaha", "mwahaha",
		"kekeke", "kukuku", "agh", "aghh", "aghhh", "ugh", "ughh", "ughhh", "argh", "urgh",
		"ergh", "mmph", "mmmph", "mph", "hmph", "hmmph", "huff", "pant", "phew", "tsk",
		"tch", "pff", "pfft", "bleh", "gack", "guh", "blub", "glub", "aaah", "aaaah",
		"sigh", "groan", "grunt", "snort", "snicker",
		// Korece / Japonca romanize saf ses efektleri
		"kwang", "kwaang", "kung", "kuung", "kkung", "kukung", "kukuung", "dugeun", "dukgeun",
		"dugeundugeun", "dudun", "dudung", "jjeoreong", "puk", "peok", "ssuk", "geuk", "kkeuk",
		"heok", "heuk", "deolkeok", "chirik", "sarak", "ttuk", "jjak", "hwak", "ssak", "seuk",
		"wajak", "ujik", "jjirit", "zzirit", "jirit", "shwik", "shwiik", "chwiiik", "ppak",
		"bbak", "ttak", "duduk", "tuduk", "papak", "pittik", "jjeong", "kkeung", "teok",
		"tadak", "tadatadak", "chiik", "chiiik", "gogo", "gogogo", "menacing", "dokaan",
		"dokan", "bakiin", "gashaan", "kirakira", "pikapika", "gorogoro", "garagara", "kyaa",
		"kyaaa", "byuun",
		// Korece / Japonca alfabe saf ses efektleri
		"쾅", "쿵", "퍽", "두근두근", "덜컥", "우르릉", "끼이익", "바스락", "찌릿",
		"ゴゴゴ", "ドキドキ", "ガシャン", "ドカン", "ズキューン", "バキィ",
		// Türkçe yansıma / ses efekti karşılıkları
		"çat", "pat", "küt", "güm", "şak", "tak", "tık", "çatır", "çatırt", "çatırtı",
		"şırrak", "şangır", "gıcırt", "gıcırtı", "hışırt", "hışırtı", "vın", "vınn",
		"vız", "vızz", "hart", "kırt", "fış", "fışş", "cız", "cızz", "şlap", "irülür", "irkilir",
	)

	/** Tek başına anlamlı kelime olabilecek ama 2-3 kez arka arkaya tekrarlandığında veya balon dışında ses efekti olan kelimeler */
	val REPEATED_ONLY_SFX_STEMS: Set<String> = hashSetOf(
		"tap", "step", "drip", "drop", "clap", "pat", "knock", "tick", "tock", "click",
		"pop", "snap", "ring", "beep", "honk", "cough", "pant", "sob", "gasp", "gulp",
		"chew", "chop", "slash", "slice", "poke", "prod", "shake", "tremble", "shiver",
		"shudder", "flinch", "twitch", "sparkle", "glint", "gleam", "flash", "glow", "huff",
	)

	private val CREDIT_LINE_REGEX = Regex(
		"""(?<![\p{L}\p{N}_])(?:translator|translation|proofreader|proofreading|cleaner|cleaning|redrawer|redraw|typesetter|typesetting|quality\s*checker|editor|raw\s*provider|scanlation|fansub|fansube|kurohana|asurascans|asuras?\s*scans?|asuragd\w*|reaperscans|reaper\s*scans|flamescans|flame\s*scans|flame[\s-]*chan|reaper[\s-]*chan|asura[\s-]*chan|luminousscans|vortexscans|drakescans|manhuaplus|manhuaus|hivetoons|realmscans|leviathanscans|reset\s*scans|rizz\s*comic|rizz\s*fables|cosmic\s*scans|night\s*scans|void\s*scans|disaster\s*scans|surya\s*scans|templescans?|temple\s*scans?|thunderscans?|thunder\s*scans?|philiascans?|philia\s*scans?|arven\s*comics|nex[o0c]*mic|[gc]radex[\s.\-]*(?:tan|tay)trums?|taytrums?|webtoon\s*xyz|webtoon\s+ip\s+holder|kunmanga|manganato|mangakakalot|zinmanga|1stkissmanga|manhwaclub|manhwafreak|tappytoon|toomics|tapas|lezhin|recruiting|discord|patreon|paypal|ko-?fi|buymeacoffee|join\s+(?:our|us)|support\s+us|read\s+(?:it\s+)?(?:at|on|first)|chapter\s*\d+|çevirmen|çeviri|editör|dizgi|temizleme)(?![\p{L}\p{N}_])|^dsns$|(?:^|\s)(?:tl|pr|cl|rd|ts|qc|ktl|tlc)(?:\s*[:\-]\s*|\s+)[a-z0-9_]{2,14}$""",
		RegexOption.IGNORE_CASE,
	)

	private val STANDALONE_SCANLATION_ROLE_REGEX = Regex(
		"""^(?:tl|pr|cl|rd|ts|qc|ktl|tlc|tl/pr|cl/rd|ts/qc)\s*[:\-]?$""",
		RegexOption.IGNORE_CASE,
	)

	private val CREDIT_HANDLE_TOKEN_REGEX = Regex("""^[A-Z0-9_]{2,16}$""")

	private val INDIVIDUAL_PROMO_CREDIT_REGEX = Regex(
		"""(?:https?://|www\.|discord\.gg|(?<![\p{L}\p{N}_])(?:discord|patreon|paypal|ko-?fi|buymeacoffee|recruiting|scanlation|fansub|fansube|kurohana|asurascans|asuras?\s*scans?|asuragd\w*|reaperscans|reaper\s*scans|flamescans|flame\s*scans|flame[\s-]*chan|reaper[\s-]*chan|asura[\s-]*chan|luminousscans|vortexscans|drakescans|manhuaplus|manhuaus|hivetoons|realmscans|leviathanscans|reset\s*scans|rizz\s*comic|rizz\s*fables|cosmic\s*scans|night\s*scans|void\s*scans|disaster\s*scans|surya\s*scans|templescans?|temple\s*scans?|thunderscans?|thunder\s*scans?|philiascans?|philia\s*scans?|arven\s*comics|nex[o0c]*mic|[gc]radex[\s.\-]*(?:tan|tay)trums?|taytrums?|webtoon\s*xyz|webtoon\s+ip\s+holder|kunmanga|manganato|mangakakalot|zinmanga|1stkissmanga|manhwaclub|manhwafreak|tappytoon|toomics|lezhin)(?![\p{L}\p{N}_])|^dsns$|^rgb$|(?<![\p{L}\p{N}_])(?:join\s+(?:our|us)|support\s+us|follow\s+us|read\s+(?:it\s+)?(?:at|on|first))(?![\p{L}\p{N}_])|(?<![\p{L}\p{N}_])(?:translator|translation|proofreader|proofreading|cleaner|cleaning|redrawer|redraw|typesetter|typesetting|quality\s*checker|editor|raw\s*provider|çevirmen|çeviri|editör|dizgi|temizleme)\s*[:\-]|(?:^|\s)(?:tl|pr|cl|rd|ts|qc|ktl|tlc)\s*[:\-]\s*\S)""",
		RegexOption.IGNORE_CASE,
	)

	private val URL_LINE_REGEX = Regex(
		"""(?:https?://|www\.|discord\.gg|\b[a-z0-9-]{2,}\.(?:com|net|org|gg|io|me|to|xyz|club|online|site)\b)""",
		RegexOption.IGNORE_CASE,
	)

	private val STRONG_PROMO_REGEX = Regex(
		"""(?:https?://|www\.|discord|patreon|paypal|ko-?fi|buymeacoffee|recruiting|join\s+(?:our|us)|asurascans|asuras?\s*scans?|asuragd\w*|reaperscans|reaper\s*scans|flamescans|flame\s*scans|disaster\s*scans|surya\s*scans|\b[a-z0-9-]{2,}\.(?:com|net|org|gg|io|me|to|xyz|club|online|site)\b)""",
		RegexOption.IGNORE_CASE,
	)

	private val KNOWN_RPG_STAT_KEY_REGEX = Regex(
		"""^(?:name|isim|ad|level|lvl|lv|seviye|race|type|t[üu]r|class|s[ıi]n[ıi]f|rank|r[üu]tbe|title|unvan|hp|mp|sp|xp|exp|health|can|mana|b[üu]y[üu]|stamina|dayan[ıi]kl[ıi]l[ıi]k|atk|attack|sald[ıi]r[ıi]|def|defense|savunma|str|strength|g[üu][çc]|agi|agility|[çc]eviklik|dex|int|intelligence|zeka|vit|vitality|luk|luck|[şs]ans|speed|h[ıi]z|weapon|silah|armor|z[ıi]rh|skill|skills|yetenek|yetenekler|ability|abilities|owner|sahip|master|stat|stats|status|durum|power|g[üu][çc]\s*puan[ıi]|evolution(?:\s*points?)?|evrim(?:\s*puan[ıi])?|points?|tier|grade|element|attribute|effect|passive|active|reward|[öo]d[üu]l|quest|g[öo]rev|mission|condition|ko[şs]ul|age|ya[şs]|gender|cinsiyet|affiliation|clan|klan|sect|tarikat|realm|diyar|stage|a[şs]ama|cultivation|trait|traits|buff|debuff|equipment|ekipman|item|e[şs]ya|description|a[çc][ıi]klama|durability|host|system)$""",
		RegexOption.IGNORE_CASE,
	)

	private val RPG_KEY_VALUE_REGEX = Regex("""^([A-Za-zÇĞİÖŞÜçğıöşü0-9\s/()+-]{2,26}):\s*(.+)$""")
	private val RPG_DIALOGUE_ENDING_REGEX = Regex("""[?]$|,$""")
	private val RPG_DIALOGUE_EXCLAMATION_START_REGEX = Regex(
		"""^(?:oh|ah|uh|um|hey|well|wait|what|why|how|damn|shit|fuck)$""",
		RegexOption.IGNORE_CASE,
	)
	private val RPG_YES_NO_START_REGEX = Regex("""^(?:no|yes)$""", RegexOption.IGNORE_CASE)
	private val RPG_NUMERIC_START_REGEX = Regex("""^[+-]?\d""")
	private val RPG_UPPER_CODE_VAL_REGEX = Regex("""^[A-Z0-9+/().\s-]+$""")

	private val SYSTEM_BOX_HEADER_REGEX = Regex(
		"""^(?:SYSTEM(?:\s+PROMPT|\s+NOTICE|\s+MESSAGE|\s+ALERT|\s+WINDOW)?|QUEST(?:\s+INFO|\s+ALERT|\s+NOTICE|\s+UPDATED?)?|NOTICE|WARNING|ALERT|INFO|INFORMATION|NOTIFICATION|TIP|STATUS|PROFILE|STATS|SKILLS?|SKILLS?\s+LIST|INVENTORY|EVOLUTION|ANNOUNCEMENT|CONGRATULATIONS|HINT|OBJECTIVE)\b[:!]?$""",
		RegexOption.IGNORE_CASE,
	)

	fun cleanOcrText(text: String): String {
		var preCleaned = WHITESPACE_REGEX.replace(text, " ").trim()
		if (preCleaned.isEmpty()) return ""
		preCleaned = ANGLE_TAG_REGEX.replace(preCleaned, " ")
		preCleaned = ANGLE_BRACKET_REGEX.replace(preCleaned, " ")
		preCleaned = WHITESPACE_REGEX.replace(preCleaned, " ").trim()
		if (preCleaned.isEmpty()) return ""
		val tokens = preCleaned.split(' ').toMutableList()
		fun isJunkToken(tok: String): Boolean =
			!LETTER_REGEX.containsMatchIn(tok) && !DIGIT_REGEX.containsMatchIn(tok) && !PUNCT_ONLY_ALLOW_REGEX.matches(tok)
		while (tokens.isNotEmpty() && isJunkToken(tokens.first())) tokens.removeAt(0)
		while (tokens.isNotEmpty() && isJunkToken(tokens.last())) tokens.removeAt(tokens.lastIndex)
		if (tokens.isEmpty()) return ""

		var result = tokens.joinToString(" ")
		result = EDGE_SYMBOLS_LEADING_REGEX.replace(result, "")
		result = EDGE_SYMBOLS_TRAILING_REGEX.replace(result, "")
		result = WHITESPACE_REGEX.replace(result, " ")
		result = QUOTE_CONTRACTION_REGEX.replace(result, "'")
		result = QUOTE_BETWEEN_ALPHA_REGEX.replace(result, "'")
		result = HYPHEN_TRAILING_QUOTE_REGEX.replace(result, "$1-")
		result = ZERO_AFTER_ALPHA_REGEX.replace(result, "O")
		result = ZERO_BEFORE_ALPHA_REGEX.replace(result, "O")
		result = FIVE_ZERO_TO_SO_REGEX.replace(result, "SO$1")
		result = SPANISH_STRAY_C_REGEX.replace(result, "")
		result = SPANISH_QLE_REGEX.replace(result, "QUE")
		result = SPANISH_QLIEN_REGEX.replace(result, "QUIEN")
		result = SPANISH_MLCH_REGEX.replace(result) { m -> "Much${m.groupValues[1]}" }
		return WHITESPACE_REGEX.replace(result, " ").trim()
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
		val cleanTarget = stripped.ifEmpty { text }
		val isCleanText = junk == 0 &&
			letters >= minCleanLetters &&
			digits == 0 &&
			!TRIPLE_SAME_CHAR_REGEX.matches(cleanTarget) &&
			CLEAN_MULTI_TOKEN_REGEX.matches(cleanTarget)
		val hasLatinVowel = LATIN_VOWEL_REGEX.containsMatchIn(stripped)
		val alphaOnly = NON_ASCII_ALPHA_REGEX.replace(stripped, "")
		val isMixedWeirdCasing = alphaOnly.length in 2..4 &&
			alphaOnly.first().isUpperCase() &&
			alphaOnly.last().isLowerCase() &&
			alphaOnly.drop(1).dropLast(1).any { it.isUpperCase() }
		val isGiantArtworkShortWord = line.height >= 110f &&
			letters <= 4 &&
			!stripped.contains(' ')
		val isTinyLowerJunk = !multiScript &&
			alphaOnly.length <= 2 &&
			alphaOnly.isNotEmpty() &&
			alphaOnly.all { it.isLowerCase() } &&
			!isShortMeaningful

		val effectiveMinConf = when {
			isMixedWeirdCasing -> maxOf(minConfidence, 85f)
			isGiantArtworkShortWord -> maxOf(minConfidence, 82f)
			isTinyLowerJunk -> maxOf(minConfidence, 76f)
			isShortMeaningful -> minOf(minConfidence, 40f)
			isCleanText && letters >= 3 && hasLatinVowel -> minOf(minConfidence, 48f)
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
			val allSingleLetter = words.all { NON_LETTER_DIGIT_EDGE_REGEX.replace(it, "").length <= 1 }
			if (allSingleLetter && (words.size >= 3 || (words.size >= 2 && (junk > 0 || digits > 0)))) {
				return false
			}
		}
		return true
	}

	private fun collapseTripleToDouble(word: String): String =
		COLLAPSE_TRIPLE_REGEX.replace(word.lowercase(Locale.ROOT), "$1$1")

	private fun collapseRepeatedChars(word: String): String =
		COLLAPSE_DOUBLE_REGEX.replace(collapseTripleToDouble(word), "$1")

	private fun matchesPureSfxStem(alpha: String): Boolean =
		alpha in PURE_SFX_STEMS ||
			collapseTripleToDouble(alpha) in PURE_SFX_STEMS ||
			collapseRepeatedChars(alpha) in PURE_SFX_STEMS

	fun isPureSoundEffect(text: String): Boolean {
		val cleaned = SFX_CLEAN_EDGE_REGEX.replace(text.trim(), "")
		if (cleaned.isEmpty()) return true

		val rawTokens = SFX_SPLIT_REGEX.split(cleaned)
			.map { it.trim().lowercase(Locale.ROOT) }
			.filter { it.isNotEmpty() }

		if (rawTokens.isEmpty() || rawTokens.size > 4) return false

		val joinedUnicodeAlpha = NON_UNICODE_LOWER_ALPHA_REGEX.replace(rawTokens.joinToString(""), "")
		val joinedAsciiAlpha = NON_ASCII_LOWER_ALPHA_REGEX.replace(rawTokens.joinToString(""), "")
		if (joinedUnicodeAlpha.isNotEmpty() && matchesPureSfxStem(joinedUnicodeAlpha)) {
			return true
		}
		if (
			joinedAsciiAlpha.isNotEmpty() &&
			joinedAsciiAlpha != joinedUnicodeAlpha &&
			matchesPureSfxStem(joinedAsciiAlpha)
		) {
			return true
		}

		var matchedSfxTokenCount = 0
		val allPureSfx = rawTokens.all { tok ->
			if (!LETTER_REGEX.containsMatchIn(tok)) {
				return@all !DIGIT_REGEX.containsMatchIn(tok)
			}
			val unicodeExact = NON_UNICODE_LOWER_ALPHA_REGEX.replace(tok, "")
			val asciiExact = NON_ASCII_LOWER_ALPHA_REGEX.replace(tok, "")
			val matched = (unicodeExact.isNotEmpty() && matchesPureSfxStem(unicodeExact)) ||
				(asciiExact.isNotEmpty() && (
					matchesPureSfxStem(asciiExact) ||
						CONSONANT_STRETCH_SFX_REGEX.matches(asciiExact) ||
						(asciiExact.length >= 4 && VOWEL_GRUNT_STRETCH_REGEX.matches(asciiExact))
					))
			if (matched) matchedSfxTokenCount++
			matched
		}
		if (allPureSfx && matchedSfxTokenCount > 0) return true

		if (rawTokens.size >= 2) {
			val stems = rawTokens.map { NON_ASCII_LOWER_ALPHA_REGEX.replace(it, "") }
			if (stems.any { it.isEmpty() }) return false
			val allRepeatedSfx = stems.all { s ->
				s in REPEATED_ONLY_SFX_STEMS ||
					collapseTripleToDouble(s) in REPEATED_ONLY_SFX_STEMS ||
					collapseRepeatedChars(s) in REPEATED_ONLY_SFX_STEMS
			}
			if (allRepeatedSfx) {
				val uniqueStems = stems.mapTo(HashSet()) { collapseRepeatedChars(it) }
				if (uniqueStems.size == 1) return true
				if ("tick" in uniqueStems && "tock" in uniqueStems && uniqueStems.size == 2) return true
			}
		}

		return false
	}

	/**
	 * RPG / Sistem durum penceresi anahtar-değer satırlarını ("HEALTH: 100", "NAME: JIANG CHENG")
	 * normal konuşma diyaloglarından ("CHICKITO: OH DAMN,") güvenle ayırır.
	 */
	fun isRpgStatLine(rawText: String): Boolean {
		val cleaned = WHITESPACE_REGEX.replace(rawText.trim(), " ")
		val match = RPG_KEY_VALUE_REGEX.matchEntire(cleaned) ?: return false
		val key = match.groupValues[1].trim()
		val value = match.groupValues[2].trim()
		if (value.isEmpty()) return false
		if (RPG_DIALOGUE_ENDING_REGEX.containsMatchIn(value)) return false
		val valueWords = value.split(WHITESPACE_REGEX)
		val firstWord = valueWords.firstOrNull().orEmpty()
		if (
			RPG_DIALOGUE_EXCLAMATION_START_REGEX.matches(firstWord) ||
			(RPG_YES_NO_START_REGEX.matches(firstWord) && valueWords.size >= 2)
		) {
			return false
		}
		if (KNOWN_RPG_STAT_KEY_REGEX.matches(key)) return true
		if (RPG_NUMERIC_START_REGEX.containsMatchIn(value)) return true
		return valueWords.size <= 3 && !value.contains('!') && !value.contains('?') && RPG_UPPER_CODE_VAL_REGEX.matches(value)
	}

	internal fun isSystemBoxHeader(text: String): Boolean =
		SYSTEM_BOX_HEADER_REGEX.matches(text.trim())

	private fun isConsonantWatermark(trimmed: String): Boolean {
		val alphaOnly = NON_ASCII_ALPHA_REGEX.replace(trimmed, "")
		if (alphaOnly.length !in 4..14 || alphaOnly.length != trimmed.length) return false
		if (ASCII_VOWEL_REGEX.containsMatchIn(alphaOnly)) return false
		val distinct = HashSet<Char>(alphaOnly.length)
		for (ch in alphaOnly) {
			distinct.add(ch.lowercaseChar())
		}
		return distinct.size >= 3
	}

	fun isCreditOrPromoText(text: String): Boolean {
		val trimmed = text.trim()
		return CREDIT_LINE_REGEX.containsMatchIn(trimmed) ||
			URL_LINE_REGEX.containsMatchIn(trimmed) ||
			isConsonantWatermark(trimmed)
	}

	fun shouldSkipBlock(
		text: String,
		skipPureSfx: Boolean = true,
		filterPromoAndCredits: Boolean = true,
	): Boolean {
		val trimmed = text.trim()
		if (trimmed.isEmpty()) return true
		if (countLetters(trimmed) == 0) return true
		if (
			filterPromoAndCredits && (
				URL_LINE_REGEX.containsMatchIn(trimmed) ||
					INDIVIDUAL_PROMO_CREDIT_REGEX.containsMatchIn(trimmed) ||
					isConsonantWatermark(trimmed)
				)
		) {
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

		val hasStrongPromo = bubbles.any { STRONG_PROMO_REGEX.containsMatchIn(it.text) }
		val creditRolesCount = bubbles.count {
			val trimmed = it.text.trim()
			CREDIT_LINE_REGEX.containsMatchIn(trimmed) || STANDALONE_SCANLATION_ROLE_REGEX.matches(trimmed)
		}
		if (hasStrongPromo && creditRolesCount >= 2 && creditRolesCount.toFloat() / bubbles.size >= 0.45f) {
			return true
		}

		if (hasStrongPromo && creditRolesCount >= 2) {
			val creditLikeCount = bubbles.count { b ->
				val trimmed = b.text.trim()
				isCreditOrPromoText(trimmed) ||
					STANDALONE_SCANLATION_ROLE_REGEX.matches(trimmed) ||
					CREDIT_HANDLE_TOKEN_REGEX.matches(trimmed)
			}
			val hasDialogueSentence = bubbles.any { b ->
				!isCreditOrPromoText(b.text) &&
					b.text.trim().split(WHITESPACE_REGEX).size >= 4
			}
			if (!hasDialogueSentence && creditLikeCount.toFloat() / bubbles.size >= 0.65f) {
				return true
			}
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
