package org.koitharu.kotatsu.reader.domain.translate.api

import androidx.compose.runtime.Immutable
import org.koitharu.kotatsu.reader.domain.translate.model.OcrSourceLanguage
import java.util.Locale

@Immutable
data class TranslationLanguageOption(
	val code: String,
	val nameEn: String,
	val nameTr: String,
) {
	fun displayName(locale: Locale = Locale.getDefault()): String {
		return when {
			locale.language.equals("tr", ignoreCase = true) -> nameTr
			locale.language.equals("en", ignoreCase = true) -> nameEn
			else -> {
				val localized = Locale.forLanguageTag(code).getDisplayName(locale)
				if (localized.isNotBlank() && !localized.equals(code, ignoreCase = true)) {
					localized.replaceFirstChar {
						if (it.isLowerCase()) it.titlecase(locale) else it.toString()
					}
				} else {
					nameEn
				}
			}
		}
	}
}

/**
 * Target and source language catalog for comic/manga translation.
 */
object TranslationLanguages {

	val targetLanguages: List<TranslationLanguageOption> = listOf(
		TranslationLanguageOption("en", "English", "İngilizce"),
		TranslationLanguageOption("tr", "Turkish", "Türkçe"),
		TranslationLanguageOption("es", "Spanish", "İspanyolca"),
		TranslationLanguageOption("fr", "French", "Fransızca"),
		TranslationLanguageOption("de", "German", "Almanca"),
		TranslationLanguageOption("it", "Italian", "İtalyanca"),
		TranslationLanguageOption("pt", "Portuguese", "Portekizce"),
		TranslationLanguageOption("ru", "Russian", "Rusça"),
		TranslationLanguageOption("ja", "Japanese", "Japonca"),
		TranslationLanguageOption("ko", "Korean", "Korece"),
		TranslationLanguageOption("zh", "Chinese (Simplified)", "Çince (Basitleştirilmiş)"),
		TranslationLanguageOption("zh-TW", "Chinese (Traditional)", "Çince (Geleneksel)"),
		TranslationLanguageOption("ar", "Arabic", "Arapça"),
		TranslationLanguageOption("hi", "Hindi", "Hintçe"),
		TranslationLanguageOption("id", "Indonesian", "Endonezce"),
		TranslationLanguageOption("nl", "Dutch", "Felemenkçe"),
		TranslationLanguageOption("pl", "Polish", "Lehçe"),
		TranslationLanguageOption("sv", "Swedish", "İsveççe"),
		TranslationLanguageOption("no", "Norwegian", "Norveççe"),
		TranslationLanguageOption("da", "Danish", "Danca"),
		TranslationLanguageOption("fi", "Finnish", "Fince"),
		TranslationLanguageOption("el", "Greek", "Yunanca"),
		TranslationLanguageOption("cs", "Czech", "Çekçe"),
		TranslationLanguageOption("sk", "Slovak", "Slovakça"),
		TranslationLanguageOption("hu", "Hungarian", "Macarca"),
		TranslationLanguageOption("ro", "Romanian", "Rumence"),
		TranslationLanguageOption("bg", "Bulgarian", "Bulgarca"),
		TranslationLanguageOption("uk", "Ukrainian", "Ukraynaca"),
		TranslationLanguageOption("sr", "Serbian", "Sırpça"),
		TranslationLanguageOption("hr", "Croatian", "Hırvatça"),
		TranslationLanguageOption("bs", "Bosnian", "Boşnakça"),
		TranslationLanguageOption("sl", "Slovenian", "Slovence"),
		TranslationLanguageOption("mk", "Macedonian", "Makedonca"),
		TranslationLanguageOption("sq", "Albanian", "Arnavutça"),
		TranslationLanguageOption("az", "Azerbaijani", "Azerice"),
		TranslationLanguageOption("kk", "Kazakh", "Kazakça"),
		TranslationLanguageOption("uz", "Uzbek", "Özbekçe"),
		TranslationLanguageOption("ky", "Kyrgyz", "Kırgızca"),
		TranslationLanguageOption("tk", "Turkmen", "Türkmence"),
		TranslationLanguageOption("tt", "Tatar", "Tatarca"),
		TranslationLanguageOption("fa", "Persian", "Farsça"),
		TranslationLanguageOption("ur", "Urdu", "Urduca"),
		TranslationLanguageOption("he", "Hebrew", "İbranice"),
		TranslationLanguageOption("th", "Thai", "Tayca"),
		TranslationLanguageOption("vi", "Vietnamese", "Vietnamca"),
		TranslationLanguageOption("ms", "Malay", "Malayca"),
		TranslationLanguageOption("tl", "Filipino (Tagalog)", "Filipince (Tagalog)"),
		TranslationLanguageOption("bn", "Bengali", "Bengalce"),
		TranslationLanguageOption("ta", "Tamil", "Tamilce"),
		TranslationLanguageOption("te", "Telugu", "Teluguca"),
		TranslationLanguageOption("ml", "Malayalam", "Malayalamca"),
		TranslationLanguageOption("kn", "Kannada", "Kannadaca"),
		TranslationLanguageOption("mr", "Marathi", "Marathice"),
		TranslationLanguageOption("gu", "Gujarati", "Guceratça"),
		TranslationLanguageOption("pa", "Punjabi", "Pencapça"),
		TranslationLanguageOption("ne", "Nepali", "Nepalce"),
		TranslationLanguageOption("si", "Sinhala", "Seylanca"),
		TranslationLanguageOption("my", "Burmese", "Birmanca"),
		TranslationLanguageOption("km", "Khmer", "Kmerce"),
		TranslationLanguageOption("lo", "Lao", "Laoca"),
		TranslationLanguageOption("mn", "Mongolian", "Moğolca"),
		TranslationLanguageOption("ka", "Georgian", "Gürcüce"),
		TranslationLanguageOption("hy", "Armenian", "Ermenice"),
		TranslationLanguageOption("am", "Amharic", "Amharca"),
		TranslationLanguageOption("sw", "Swahili", "Svahili"),
		TranslationLanguageOption("ha", "Hausa", "Hausaca"),
		TranslationLanguageOption("yo", "Yoruba", "Yorubaca"),
		TranslationLanguageOption("zu", "Zulu", "Zuluca"),
		TranslationLanguageOption("af", "Afrikaans", "Afrikaanca"),
		TranslationLanguageOption("et", "Estonian", "Estonca"),
		TranslationLanguageOption("lv", "Latvian", "Letonca"),
		TranslationLanguageOption("lt", "Lithuanian", "Litvanca"),
		TranslationLanguageOption("be", "Belarusian", "Belarusça"),
		TranslationLanguageOption("ca", "Catalan", "Katalanca"),
		TranslationLanguageOption("gl", "Galician", "Galiçyaca"),
		TranslationLanguageOption("eu", "Basque", "Baskça"),
		TranslationLanguageOption("is", "Icelandic", "İzlandaca"),
		TranslationLanguageOption("ga", "Irish", "İrlandaca"),
		TranslationLanguageOption("mt", "Maltese", "Maltaca"),
		TranslationLanguageOption("lb", "Luxembourgish", "Lüksemburgca"),
	)

	private val languageByLowerCode: Map<String, TranslationLanguageOption> =
		targetLanguages.associateBy { it.code.lowercase(Locale.ROOT) }

	val sourceLanguages: List<OcrSourceLanguage>
		get() = OcrSourceLanguage.entries

	fun normalizeTargetCode(rawCode: String): String {
		val trimmed = rawCode.trim().replace('_', '-')
		if (trimmed.isEmpty()) return "en"
		return when (val lower = trimmed.lowercase(Locale.ROOT)) {
			"zh-cn", "zh-hans", "zh" -> "zh"
			"zh-tw", "zh-hant", "zh-hk" -> "zh-TW"
			"iw" -> "he"
			"in" -> "id"
			"fil" -> "tl"
			else -> languageByLowerCode[lower]?.code ?: trimmed
		}
	}

	fun findTargetLanguage(code: String): TranslationLanguageOption? {
		val normalized = normalizeTargetCode(code)
		return languageByLowerCode[normalized.lowercase(Locale.ROOT)]
	}

	fun getTargetDisplayName(
		code: String,
		locale: Locale = Locale.getDefault(),
	): String {
		val normalized = normalizeTargetCode(code)
		val entry = findTargetLanguage(normalized)
		if (entry != null) return entry.displayName(locale)
		val localized = Locale.forLanguageTag(normalized).getDisplayName(locale)
		return localized.takeIf { it.isNotBlank() && !it.equals(normalized, ignoreCase = true) }
			?: normalized
	}

	fun getPromptLanguageName(code: String): String {
		val normalized = normalizeTargetCode(code)
		return findTargetLanguage(normalized)?.nameEn
			?: Locale.forLanguageTag(normalized).getDisplayName(Locale.ENGLISH)
				.takeIf { it.isNotBlank() && !it.equals(normalized, ignoreCase = true) }
			?: normalized
	}

	fun getSourceDisplayName(
		source: OcrSourceLanguage,
		locale: Locale = Locale.getDefault(),
	): String {
		val isTurkish = locale.language.equals("tr", ignoreCase = true)
		return when (source) {
			OcrSourceLanguage.ENGLISH -> if (isTurkish) "İngilizce" else "English"
			OcrSourceLanguage.JAPANESE -> if (isTurkish) "Japonca" else "Japanese"
			OcrSourceLanguage.KOREAN -> if (isTurkish) "Korece" else "Korean"
			OcrSourceLanguage.CHINESE -> if (isTurkish) "Çince" else "Chinese"
			OcrSourceLanguage.LATIN -> if (isTurkish) "Latin Alfabesi (ES/FR/DE/PT…)" else "Latin Script (ES/FR/DE/PT…)"
			OcrSourceLanguage.CYRILLIC -> if (isTurkish) "Kiril Alfabesi (RU/UK…)" else "Cyrillic Script (RU/UK…)"
			OcrSourceLanguage.ARABIC -> if (isTurkish) "Arapça / Farsça" else "Arabic / Persian"
		}
	}

	fun OcrSourceLanguage.toGoogleSourceCode(): String = when (this) {
		OcrSourceLanguage.ENGLISH -> "en"
		OcrSourceLanguage.JAPANESE -> "ja"
		OcrSourceLanguage.KOREAN -> "ko"
		OcrSourceLanguage.CHINESE -> "zh-CN"
		OcrSourceLanguage.LATIN -> "auto"
		OcrSourceLanguage.CYRILLIC -> "auto"
		OcrSourceLanguage.ARABIC -> "auto"
	}

	fun OcrSourceLanguage.toGeminiPromptLabel(): String = when (this) {
		OcrSourceLanguage.ENGLISH -> "English"
		OcrSourceLanguage.JAPANESE -> "Japanese"
		OcrSourceLanguage.KOREAN -> "Korean"
		OcrSourceLanguage.CHINESE -> "Chinese"
		OcrSourceLanguage.LATIN -> "the Latin-script source language (auto-detect per context, e.g., Spanish, French, Portuguese, German, Italian)"
		OcrSourceLanguage.CYRILLIC -> "the Cyrillic-script source language (e.g., Russian or Ukrainian)"
		OcrSourceLanguage.ARABIC -> "the Arabic-script source language (e.g., Arabic or Persian)"
	}

	fun toGoogleTargetCode(code: String): String {
		return when (val normalized = normalizeTargetCode(code)) {
			"zh" -> "zh-CN"
			else -> normalized
		}
	}
}
