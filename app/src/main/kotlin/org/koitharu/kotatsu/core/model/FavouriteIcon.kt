package org.koitharu.kotatsu.core.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

/**
 * Curated icons that can be assigned to a favourite category and shown as its cover badge.
 */
enum class FavouriteIcon(
	val key: String,
	@DrawableRes val iconRes: Int,
	@DrawableRes val outlineIconRes: Int = iconRes,
	@StringRes val labelRes: Int,
) {
	DEFAULT("default", R.drawable.ic_heart, R.drawable.ic_heart_outline, R.string.fav_icon_default),
	STAR("star", R.drawable.ic_fav_badge_star, labelRes = R.string.fav_icon_star),
	FIRE("fire", R.drawable.ic_fav_badge_fire, labelRes = R.string.fav_icon_fire),
	CROWN("crown", R.drawable.ic_fav_badge_crown, labelRes = R.string.fav_icon_crown),
	BOOK("book", R.drawable.ic_book_page, labelRes = R.string.fav_icon_book),
	BOOKMARK("bookmark", R.drawable.ic_bookmark, labelRes = R.string.fav_icon_bookmark),
	EYE("eye", R.drawable.ic_eye, labelRes = R.string.fav_icon_eye),
	BOLT("bolt", R.drawable.ic_flash, labelRes = R.string.fav_icon_bolt),
	CLOCK("clock", R.drawable.ic_fav_badge_clock, labelRes = R.string.fav_icon_clock),
	FOLDER("folder", R.drawable.ic_fav_badge_folder, labelRes = R.string.fav_icon_folder),
	CHECK("check", R.drawable.ic_check, labelRes = R.string.fav_icon_check),
	SPARKLES("sparkles", R.drawable.ic_fav_badge_sparkles, labelRes = R.string.fav_icon_sparkles),
	;

	val persistedKey: String?
		get() = if (this == DEFAULT) null else key

	companion object {

		fun fromKey(key: String?): FavouriteIcon {
			if (key.isNullOrEmpty()) {
				return DEFAULT
			}
			return entries.firstOrNull { it.key == key } ?: DEFAULT
		}
	}
}
