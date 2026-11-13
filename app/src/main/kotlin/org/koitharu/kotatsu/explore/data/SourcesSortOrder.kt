package org.koitharu.kotatsu.explore.data

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

enum class SourcesSortOrder(
	@StringRes val titleResId: Int,
) {
	ALPHABETIC(R.string.by_name),

	/**
	 * Note this is *local* popularity - how many manga from the source are in this device's own
	 * database.
	 */
	POPULARITY(R.string.popular),
	MANUAL(R.string.manual),
	LAST_USED(R.string.last_used),
	;
}
