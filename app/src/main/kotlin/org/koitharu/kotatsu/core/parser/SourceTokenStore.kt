package org.koitharu.kotatsu.core.parser

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceTokenStore @Inject constructor(
	@ApplicationContext context: Context,
) {

	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	fun get(source: MangaSource, key: String): String? = synchronized(this) {
		prefs.getString(prefKey(source, key), null)
	}

	fun set(source: MangaSource, key: String, value: String?) = synchronized(this) {
		val fullKey = prefKey(source, key)
		prefs.edit {
			if (value == null) {
				remove(fullKey)
			} else {
				putString(fullKey, value)
			}
		}
	}

	fun clear(source: MangaSource) = synchronized(this) {
		val prefix = sourcePrefix(source)
		val keysToRemove = prefs.all.keys.filter { it.startsWith(prefix) }
		if (keysToRemove.isNotEmpty()) {
			prefs.edit {
				for (key in keysToRemove) {
					remove(key)
				}
			}
		}
	}

	fun hasTokens(source: MangaSource): Boolean = synchronized(this) {
		val prefix = sourcePrefix(source)
		prefs.all.keys.any { it.startsWith(prefix) }
	}

	private fun sourcePrefix(source: MangaSource): String =
		"${Uri.encode(source.unwrap().name)}:"

	private fun prefKey(source: MangaSource, key: String): String =
		"${sourcePrefix(source)}$key"

	private companion object {
		const val PREFS_NAME = "source_auth_tokens"
	}
}
