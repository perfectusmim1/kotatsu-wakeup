package org.koitharu.kotatsu.sourcescore.ui

import android.content.Context
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.sourcescore.data.ApiErrorDto

/** Why the server refused a nickname, from its error code - the server sends codes, never prose. */
fun nicknameRejectionMessage(context: Context, error: ApiErrorDto): String = when {
	error.error == ApiErrorDto.FILTER_BLOCKED ->
		context.getString(R.string.community_nickname_blocked, error.term.orEmpty())

	error.error == ApiErrorDto.TOO_SHORT -> context.getString(R.string.community_nickname_too_short)
	error.field == "nickname_too_long" -> context.getString(R.string.community_nickname_too_long)
	error.field == "nickname_reserved" -> context.getString(R.string.community_nickname_reserved)
	else -> context.getString(R.string.community_nickname_invalid)
}
