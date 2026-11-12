package org.koitharu.kotatsu.sync.data.model

import android.database.Cursor
import androidx.core.database.getStringOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.core.util.ext.buildContentValues
import org.koitharu.kotatsu.core.util.ext.getBoolean

@Serializable
data class FavouriteCategorySyncDto(
	@SerialName("category_id") val categoryId: Int,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("title") val title: String,
	@SerialName("order") val order: String,
	@SerialName("track") val track: Boolean,
	@SerialName("show_in_lib") val isVisibleInLibrary: Boolean,
	@SerialName("deleted_at") val deletedAt: Long,
	@SerialName("icon") val icon: String? = null,
) {

	constructor(cursor: Cursor) : this(
		categoryId = cursor.getInt(cursor.getColumnIndexOrThrow("category_id")),
		createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
		sortKey = cursor.getInt(cursor.getColumnIndexOrThrow("sort_key")),
		title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
		order = cursor.getString(cursor.getColumnIndexOrThrow("order")),
		track = cursor.getBoolean(cursor.getColumnIndexOrThrow("track")),
		isVisibleInLibrary = cursor.getBoolean(cursor.getColumnIndexOrThrow("show_in_lib")),
		deletedAt = cursor.getLong(cursor.getColumnIndexOrThrow("deleted_at")),
		icon = cursor.getColumnIndex("icon").takeIf { it >= 0 }?.let(cursor::getStringOrNull),
	)

	fun toContentValues() = buildContentValues(9) {
		put("category_id", categoryId)
		put("created_at", createdAt)
		put("sort_key", sortKey)
		put("title", title)
		put("`order`", order)
		put("track", track)
		put("show_in_lib", isVisibleInLibrary)
		put("deleted_at", deletedAt)
		put("icon", icon)
	}
}
