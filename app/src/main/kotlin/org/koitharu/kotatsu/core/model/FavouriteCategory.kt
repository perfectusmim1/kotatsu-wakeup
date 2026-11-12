package org.koitharu.kotatsu.core.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.model.ListModel
import java.time.Instant

@Parcelize
data class FavouriteCategory(
	val id: Long,
	val title: String,
	val sortKey: Int,
	val order: ListSortOrder,
	val createdAt: Instant,
	val isTrackingEnabled: Boolean,
	val isVisibleInLibrary: Boolean,
	val icon: String? = null,
) : Parcelable, ListModel {

	val resolvedIcon: FavouriteIcon
		get() = FavouriteIcon.fromKey(icon)

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is FavouriteCategory && id == other.id
	}

	override fun getChangePayload(previousState: ListModel): Any? {
		if (previousState !is FavouriteCategory) {
			return null
		}
		return if (
			isTrackingEnabled != previousState.isTrackingEnabled ||
			isVisibleInLibrary != previousState.isVisibleInLibrary ||
			icon != previousState.icon
		) {
			ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED
		} else {
			null
		}
	}
}
