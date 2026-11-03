package org.koitharu.kotatsu.sourcescore.ui

import androidx.fragment.app.FragmentManager
import dagger.Reusable
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sourcescore.data.CommunityApi
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import org.koitharu.kotatsu.sourcescore.data.nicknameRejection
import javax.inject.Inject

/**
 * Makes sure the server knows this user's name before anything they write is published.
 *
 * There is no anonymous participation, but nothing used to enforce that: someone who quit onboarding
 * before the nickname step - or whose name never reached the server - had their first comment or
 * rating create the account, and it was published as `anon#xxxx`.
 */
@Reusable
class CommunityNameGate @Inject constructor(
	private val settings: CommunitySettings,
	private val api: CommunityApi,
) {

	/**
	 * @return true when the server has this user's name and the action can go ahead. False when the
	 * name step was opened instead - the user repeats the action once they have picked one.
	 */
	suspend fun ensureNamed(fm: FragmentManager): Boolean {
		val nickname = settings.nickname
		if (nickname.isNullOrEmpty()) {
			// A registered key may already carry a name the device lost, e.g. restored from an older
			// backup. Only asked when already registered: saying hello to an unknown key creates an
			// account, which is exactly what this is here to delay until there is a name for it.
			if (settings.isRegistered) {
				val known = runCatchingCancellable { api.hello() }.getOrNull()?.nickname
				if (!known.isNullOrEmpty()) {
					settings.nickname = known
					settings.syncedNickname = known
					return true
				}
			}
			CommunityOnboardingDialog.showNicknameStep(fm)
			return false
		}
		if (nickname != settings.syncedNickname) {
			val result = runCatchingCancellable { api.setNickname(nickname) }
			if (result.isSuccess) {
				settings.syncedNickname = nickname
			} else if (result.exceptionOrNull()?.let(::nicknameRejection) != null) {
				// The server refused the stored name (reserved, or caught by the filter). Posting now
				// would publish anonymously, so ask for another one instead.
				settings.nickname = null
				CommunityOnboardingDialog.showNicknameStep(fm)
				return false
			}
			// Unreachable: the action itself will fail the same way and say so.
		}
		return true
	}
}
