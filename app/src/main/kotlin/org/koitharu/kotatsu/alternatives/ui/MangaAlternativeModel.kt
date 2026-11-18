package org.koitharu.kotatsu.alternatives.ui

import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.parsers.model.Manga

data class MangaAlternativeModel(
	val mangaModel: MangaGridModel,
	val referenceChapters: Int,
	val isMigrationEnabled: Boolean = true,
	val titleMatchScore: Int = 0,
	val sourcePriority: Int = 0,
) : ListModel {

	val manga: Manga
		get() = mangaModel.manga

	val chaptersCount = manga.chaptersCount()

	val chaptersDiff: Int
		get() = if (referenceChapters == 0 || chaptersCount == 0) 0 else chaptersCount - referenceChapters

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is MangaAlternativeModel && other.manga.id == manga.id
	}

	override fun getChangePayload(previousState: ListModel): Any? = if (previousState is MangaAlternativeModel) {
		mangaModel.getChangePayload(previousState.mangaModel)
			?: ListModelDiffCallback.PAYLOAD_ANYTHING_CHANGED.takeIf {
				referenceChapters != previousState.referenceChapters ||
					isMigrationEnabled != previousState.isMigrationEnabled ||
					chaptersCount != previousState.chaptersCount
			}
	} else {
		null
	}
}

data class MangaAlternativeReference(
	val manga: Manga,
	val chaptersCount: Int = manga.chaptersCount(),
)
