package org.koitharu.kotatsu.sourcescore.data

/**
 * Whether the server refused a nickname, as opposed to not being reachable.
 *
 * The distinction decides what happens to the name: a refused name must never be kept on the device,
 * or the app shows a name while every comment still says `anon#xxxx`. An unreachable server only
 * means "not yet", so the name stays pending. 401 is registration still in progress and 429 is a rate
 * limit - neither says anything about the name.
 */
fun isNicknameRejection(status: Int): Boolean = status in 400..499 && status != 401 && status != 429

/** The server's reason, when [error] is a refusal of the nickname itself. */
fun nicknameRejection(error: Throwable): ApiErrorDto? =
	(error as? CommunityApiException)?.takeIf { isNicknameRejection(it.status) }?.error
