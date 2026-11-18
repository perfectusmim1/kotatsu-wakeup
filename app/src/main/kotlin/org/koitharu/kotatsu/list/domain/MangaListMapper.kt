package org.koitharu.kotatsu.list.domain

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.IntDef
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteIcon
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.list.ui.model.MangaDetailedListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import org.koitharu.kotatsu.tracker.domain.model.TrackingLogItem
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import javax.inject.Inject

@Reusable
class MangaListMapper @Inject constructor(
	@ApplicationContext context: Context,
	private val settings: AppSettings,
	private val trackingRepository: TrackingRepository,
	private val historyRepository: HistoryRepository,
	private val favouritesRepository: FavouritesRepository,
	private val localMangaIndex: LocalMangaIndex,
	private val dataRepository: MangaDataRepository,
) {


	suspend fun toListModelList(
		manga: Collection<Manga>,
		mode: ListMode,
		@Flags flags: Int = DEFAULTS,
	): List<MangaListModel> = ArrayList<MangaListModel>(manga.size).apply {
		toListModelList(
			destination = this,
			manga = manga,
			mode = mode,
			flags = flags,
		)
	}

	suspend fun toListModelList(
		destination: MutableCollection<in MangaListModel>,
		manga: Collection<Manga>,
		mode: ListMode,
		@Flags flags: Int = DEFAULTS,
		pinnedIds: Set<Long> = emptySet(),
	) {
		if (manga.isEmpty()) {
			return
		}
		val options = getOptions(flags)
		val ids = manga.map { it.id }
		val overrides = dataRepository.getOverrides()
		val counters = if (isCounterEnabled(options)) {
			trackingRepository.getNewChaptersCounts(ids)
		} else {
			emptyMap()
		}
		val isDetailed = mode != ListMode.LIST
		val favouriteIcons = if (isDetailed && options.isBadgeEnabled(FAVORITE)) {
			favouritesRepository.getFavouriteIcons(ids)
		} else {
			emptyMap()
		}
		val progressMode = if (isDetailed) getProgressMode(options) else ProgressIndicatorMode.NONE
		val progressMap = if (progressMode != ProgressIndicatorMode.NONE) {
			historyRepository.getProgressMap(ids, progressMode)
		} else {
			emptyMap()
		}
		val savedIds = if (isDetailed && options.isBadgeEnabled(SAVED)) {
			localMangaIndex.getSavedMangaIds(ids)
		} else {
			emptySet()
		}
		manga.mapTo(destination) {
			toListModelImpl(
				manga = it,
				mode = mode,
				override = overrides[it.id],
				counter = counters[it.id] ?: 0,
				progress = progressMap[it.id],
				isSaved = it.id in savedIds,
				isPinned = it.id in pinnedIds,
				favouriteIcon = favouriteIcons[it.id],
			)
		}
	}

	suspend fun toListModel(
		manga: Manga,
		mode: ListMode,
		@Flags flags: Int = DEFAULTS,
	): MangaListModel {
		val options = getOptions(flags)
		val override = dataRepository.getOverride(manga.id)
		val counter = getCounter(manga.id, options)
		if (mode == ListMode.LIST) {
			return toCompactListModel(manga, override, counter)
		}
		val favouriteIcon = if (options.isBadgeEnabled(FAVORITE)) {
			favouritesRepository.getFavouriteIcons(listOf(manga.id))[manga.id]
		} else {
			null
		}
		return toListModelImpl(
			manga = manga,
			mode = mode,
			override = override,
			counter = counter,
			progress = getProgress(manga.id, options),
			isSaved = isSaved(manga.id, options),
			favouriteIcon = favouriteIcon,
		)
	}

	suspend fun toFeedItems(logItems: Collection<TrackingLogItem>): List<FeedItem> {
		if (logItems.isEmpty()) {
			return emptyList()
		}
		val overrides = dataRepository.getOverrides()
		return logItems.map { logItem ->
			FeedItem(
				id = logItem.id,
				override = overrides[logItem.manga.id],
				count = logItem.chapters.size,
				manga = logItem.manga,
				isNew = logItem.isNew,
			)
		}
	}

	suspend fun toFeedItem(logItem: TrackingLogItem) = FeedItem(
		id = logItem.id,
		override = dataRepository.getOverride(logItem.manga.id),
		count = logItem.chapters.size,
		manga = logItem.manga,
		isNew = logItem.isNew,
	)

	fun mapTags(tags: Collection<MangaTag>) = tags.map {
		ChipsView.ChipModel(
			tint = getTagTint(it),
			title = it.title,
			data = it,
		)
	}

	private fun toCompactListModel(
		manga: Manga,
		override: MangaOverride?,
		counter: Int,
	) = MangaCompactListModel(
		manga = manga,
		override = override,
		subtitle = manga.tags.joinToString(", ") { it.title },
		counter = counter,
	)

	private fun toDetailedListModel(
		manga: Manga,
		override: MangaOverride?,
		counter: Int,
		progress: ReadingProgress?,
		isSaved: Boolean,
		isPinned: Boolean = false,
		favouriteIcon: FavouriteIcon? = null,
	) = MangaDetailedListModel(
		subtitle = manga.altTitles.firstOrNull(),
		manga = manga,
		override = override,
		counter = counter,
		progress = progress,
		isFavorite = favouriteIcon != null,
		isSaved = isSaved,
		tags = mapTags(manga.tags),
		isPinned = isPinned,
		favouriteIcon = favouriteIcon,
	)

	private fun toGridModel(
		manga: Manga,
		override: MangaOverride?,
		counter: Int,
		progress: ReadingProgress?,
		isSaved: Boolean,
		isPinned: Boolean = false,
		favouriteIcon: FavouriteIcon? = null,
	) = MangaGridModel(
		manga = manga,
		override = override,
		counter = counter,
		progress = progress,
		isFavorite = favouriteIcon != null,
		isSaved = isSaved,
		isPinned = isPinned,
		favouriteIcon = favouriteIcon,
	)

	private fun toListModelImpl(
		manga: Manga,
		mode: ListMode,
		override: MangaOverride?,
		counter: Int,
		progress: ReadingProgress?,
		isSaved: Boolean,
		isPinned: Boolean = false,
		favouriteIcon: FavouriteIcon? = null,
	): MangaListModel = when (mode) {
		ListMode.LIST -> toCompactListModel(manga, override, counter)
		ListMode.DETAILED_LIST -> toDetailedListModel(manga, override, counter, progress, isSaved, isPinned, favouriteIcon)
		ListMode.GRID -> toGridModel(manga, override, counter, progress, isSaved, isPinned, favouriteIcon)
	}

	private fun isCounterEnabled(@Options options: Int): Boolean {
		return options.isBadgeEnabled(COUNTER) && settings.isTrackerEnabled
	}

	private fun getProgressMode(@Options options: Int): ProgressIndicatorMode {
		return if (options.isBadgeEnabled(PROGRESS)) {
			settings.progressIndicatorMode
		} else {
			ProgressIndicatorMode.NONE
		}
	}

	private suspend fun getCounter(mangaId: Long, @Options options: Int): Int {
		return if (isCounterEnabled(options)) {
			trackingRepository.getNewChaptersCount(mangaId)
		} else {
			0
		}
	}

	private suspend fun getProgress(mangaId: Long, @Options options: Int): ReadingProgress? {
		val progressMode = getProgressMode(options)
		return if (progressMode != ProgressIndicatorMode.NONE) {
			historyRepository.getProgress(mangaId, progressMode)
		} else {
			null
		}
	}

	private suspend fun isSaved(mangaId: Long, @Options options: Int): Boolean {
		return options.isBadgeEnabled(SAVED) && mangaId in localMangaIndex
	}

	@ColorRes
	private fun getTagTint(tag: MangaTag): Int {
		return 0
	}


	private fun Int.isBadgeEnabled(@Options badge: Int) = this and badge == badge

	@Options
	@SuppressLint("WrongConstant")
	private fun getOptions(@Flags flags: Int): Int {
		var options = settings.getMangaListBadges() or PROGRESS or COUNTER
		options = options and flags.inv()
		return options
	}

	@IntDef(DEFAULTS, NO_SAVED, NO_PROGRESS, NO_FAVORITE, NO_BADGES, flag = true)
	@Retention(AnnotationRetention.SOURCE)
	annotation class Flags

	@IntDef(NONE, SAVED, FAVORITE, PROGRESS, COUNTER)
	@Retention(AnnotationRetention.SOURCE)
	private annotation class Options

	companion object {

		private const val NONE = 0
		private const val SAVED = 1
		private const val PROGRESS = 2
		private const val FAVORITE = 4
		private const val COUNTER = 8

		const val DEFAULTS = NONE
		const val NO_SAVED = SAVED
		const val NO_PROGRESS = PROGRESS
		const val NO_FAVORITE = FAVORITE

		/** For a list of what is not in the library: no badge could apply, so none of them gets looked up. */
		const val NO_BADGES = SAVED or PROGRESS or FAVORITE or COUNTER
	}
}
