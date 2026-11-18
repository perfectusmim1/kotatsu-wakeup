package org.koitharu.kotatsu.details.data

import org.koitharu.kotatsu.core.model.getLocale
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.withOverride
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.reader.data.filterChapters
import java.util.Locale

data class MangaDetails(
    private val manga: Manga,
    private val localManga: LocalManga?,
    private val override: MangaOverride?,
    val description: CharSequence?,
    val isLoaded: Boolean,
) {

    constructor(manga: Manga) : this(
        manga = manga,
        localManga = null,
        override = null,
        description = null,
        isLoaded = false,
    )

    val id: Long
        get() = manga.id

    val allChapters: List<MangaChapter> by lazy { mergeChapters() }

    val chapters: Map<String?, List<MangaChapter>> by lazy {
        allChapters.groupBy { it.branch }
    }

    val isLocal
        get() = manga.isLocal

    val local: LocalManga?
        get() = localManga ?: if (manga.isLocal) LocalManga(manga) else null

    val coverUrl: String?
        get() = override?.coverUrl
            .ifNullOrEmpty { manga.largeCoverUrl }
            .ifNullOrEmpty { manga.coverUrl }
            .ifNullOrEmpty { localManga?.manga?.coverUrl }
            ?.nullIfEmpty()

    val isRestricted: Boolean
        get() = manga.state == MangaState.RESTRICTED

    private val mergedManga by lazy {
        if (localManga == null) {
            // fast path
            manga.withOverride(override)
        } else {
            manga.copy(
                title = override?.title.ifNullOrEmpty { manga.title },
                coverUrl = override?.coverUrl.ifNullOrEmpty { manga.coverUrl },
                largeCoverUrl = override?.coverUrl.ifNullOrEmpty { manga.largeCoverUrl },
                contentRating = override?.contentRating ?: manga.contentRating,
                chapters = allChapters,
            )
        }
    }

    private val cachedLocale: Locale? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        findAppropriateLocale(chapters.keys.singleOrNull()) ?: manga.source.getLocale()
    }

    fun toManga() = mergedManga

    fun getLocale(): Locale? = cachedLocale

    fun filterChapters(branch: String?) = copy(
        manga = manga.filterChapters(branch),
        localManga = localManga?.run {
            copy(manga = manga.filterChapters(branch))
        },
    )

    private fun mergeChapters(): List<MangaChapter> {
        val chapters = manga.chapters
        val localChapters = local?.manga?.chapters.orEmpty()
        if (chapters.isNullOrEmpty()) {
            return localChapters
        }
        val localMap = if (localChapters.isNotEmpty()) {
            localChapters.associateByTo(LinkedHashMap(localChapters.size)) { it.id }
        } else {
            null
        }
        val result = ArrayList<MangaChapter>(chapters.size)
        for (chapter in chapters) {
            val local = localMap?.remove(chapter.id)
            result += local ?: chapter
        }
        if (!localMap.isNullOrEmpty()) {
            result.addAll(localMap.values)
        }
        return result
    }

    companion object {

        private val localeLookup: Map<String, Locale> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            val locales = Locale.getAvailableLocales()
            val map = LinkedHashMap<String, Locale>(locales.size * 2)
            for (lc in locales) {
                if (lc.language.isEmpty()) continue
                fun putName(raw: String) {
                    if (raw.isNotEmpty()) {
                        val key = raw.lowercase(Locale.ROOT)
                        if (key !in map) {
                            map[key] = lc
                        }
                    }
                }
                putName(lc.getDisplayName(lc))
                putName(lc.getDisplayName(Locale.ENGLISH))
                putName(lc.getDisplayLanguage(lc))
                putName(lc.getDisplayLanguage(Locale.ENGLISH))
            }
            map
        }

        private fun findAppropriateLocale(name: String?): Locale? {
            if (name.isNullOrEmpty()) {
                return null
            }
            val lowerName = name.lowercase(Locale.ROOT)
            localeLookup[lowerName]?.let { return it }
            for ((key, locale) in localeLookup) {
                if (lowerName.contains(key)) {
                    return locale
                }
            }
            return null
        }
    }
}
