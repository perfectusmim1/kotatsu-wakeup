package org.koitharu.kotatsu.settings.sources.accounts

import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.MangaSource

data class SourceAccountItem(
	val source: MangaSource,
	val isEnabled: Boolean,
	val isSignedIn: Boolean,
	val isChecking: Boolean,
	val username: String?,
	val hasSignUpUrl: Boolean,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is SourceAccountItem && other.source == source
	}
}
