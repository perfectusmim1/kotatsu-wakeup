package org.koitharu.kotatsu.settings.sources.accounts

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.network.cookies.AndroidCookieJar
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.parser.SourceTokenStore
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class SourceAccountsViewModel @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val mangaSourcesRepository: MangaSourcesRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val cookieJar: MutableCookieJar,
	private val sourceTokenStore: SourceTokenStore,
	private val settings: AppSettings,
) : BaseViewModel() {

	private val allItems = MutableStateFlow<List<SourceAccountItem>>(emptyList())
	private val searchQuery = MutableStateFlow("")
	private var refreshJob: Job? = null

	val onActionDone = MutableEventFlow<ReversibleAction>()

	val items: StateFlow<List<SourceAccountItem>> = combine(allItems, searchQuery) { list, query ->
		val trimmed = query.trim()
		if (trimmed.isEmpty()) {
			list
		} else {
			list.filter { item ->
				item.source.getTitle(context).contains(trimmed, ignoreCase = true) ||
					item.source.name.contains(trimmed, ignoreCase = true) ||
					item.username?.contains(trimmed, ignoreCase = true) == true
			}
		}
	}.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

	init {
		refreshAll(showLoading = true)
	}

	fun performSearch(query: String?) {
		searchQuery.value = query.orEmpty()
	}

	fun refreshAll(showLoading: Boolean = true) {
		refreshJob?.cancel()
		refreshJob = if (showLoading) {
			launchLoadingJob(Dispatchers.Default) {
				loadAllAccounts()
			}
		} else {
			launchJob(Dispatchers.Default) {
				loadAllAccounts()
			}
		}
	}

	fun refreshSource(source: MangaSource) {
		val target = source.unwrap()
		launchLoadingJob(Dispatchers.Default) {
			val repo = mangaRepositoryFactory.create(target) as? ParserMangaRepository ?: return@launchLoadingJob
			val authProvider = repo.getAuthProvider() ?: return@launchLoadingJob
			repo.invalidateCache()
			updateSingleItem(target) { it.copy(isChecking = true) }
			val (signedIn, username) = checkAuthAndUsername(authProvider)
			updateSingleItem(target) {
				it.copy(
					isSignedIn = signedIn,
					isChecking = false,
					username = username,
				)
			}
		}
	}

	fun signOut(source: MangaSource) {
		val target = source.unwrap()
		launchLoadingJob(Dispatchers.Default) {
			val repo = mangaRepositoryFactory.create(target) as? ParserMangaRepository ?: return@launchLoadingJob
			val authProvider = repo.getAuthProvider() ?: return@launchLoadingJob
			updateSingleItem(target) { it.copy(isChecking = true) }
			runCatchingCancellable {
				authProvider.logout()
			}
			clearCookiesForAuth(repo, authProvider)
			sourceTokenStore.clear(target)
			repo.invalidateCache()
			val (signedIn, username) = checkAuthAndUsername(authProvider)
			updateSingleItem(target) {
				it.copy(
					isSignedIn = signedIn,
					isChecking = false,
					username = username,
				)
			}
			onActionDone.call(ReversibleAction(R.string.source_signed_out, null))
		}
	}

	private suspend fun loadAllAccounts() {
		val skipNsfw = settings.isNsfwContentDisabled
		val enabledSet = mangaSourcesRepository.getEnabledSources().mapToSet { it.unwrap() }
		val existingBySource = allItems.value.associateBy { it.source }

		val authSources = mangaSourcesRepository.allMangaSources
			.asSequence()
			.filter { !skipNsfw || !it.isNsfw() }
			.mapNotNull { source ->
				val repo = mangaRepositoryFactory.create(source) as? ParserMangaRepository
				val authProvider = repo?.getAuthProvider() ?: return@mapNotNull null
				Triple(source, repo, authProvider)
			}
			.toList()

		val initial = authSources.map { (source, _, authProvider) ->
			val existing = existingBySource[source]
			SourceAccountItem(
				source = source,
				isEnabled = source in enabledSet,
				isSignedIn = existing?.isSignedIn ?: false,
				isChecking = existing == null,
				username = existing?.username,
				hasSignUpUrl = authProvider.signUpUrl != null,
			)
		}.sortedWith(itemComparator())

		allItems.value = initial

		val authSemaphore = Semaphore(8)
		val authStates = coroutineScope {
			authSources.map { (source, _, authProvider) ->
				async {
					authSemaphore.withPermit {
						val authorized = runCatchingCancellable { authProvider.isAuthorized() }.getOrDefault(false)
						source to authorized
					}
				}
			}.awaitAll().toMap()
		}

		val afterAuth = authSources.map { (source, _, authProvider) ->
			val existing = existingBySource[source]
			val isSignedIn = authStates[source] == true
			SourceAccountItem(
				source = source,
				isEnabled = source in enabledSet,
				isSignedIn = isSignedIn,
				isChecking = false,
				username = if (isSignedIn) existing?.username else null,
				hasSignUpUrl = authProvider.signUpUrl != null,
			)
		}.sortedWith(itemComparator())

		allItems.value = afterAuth

		val usernameSemaphore = Semaphore(4)
		coroutineScope {
			authSources.filter { authStates[it.first] == true }.map { (source, _, authProvider) ->
				async {
					usernameSemaphore.withPermit {
						val (stillSignedIn, username) = fetchUsernameSafely(authProvider)
						updateSingleItem(source) { current ->
							current.copy(
								isSignedIn = stillSignedIn,
								username = username,
							)
						}
					}
				}
			}.awaitAll()
		}
	}

	private suspend fun checkAuthAndUsername(authProvider: MangaParserAuthProvider): Pair<Boolean, String?> {
		val authorized = runCatchingCancellable { authProvider.isAuthorized() }.getOrDefault(false)
		if (!authorized) {
			return false to null
		}
		return fetchUsernameSafely(authProvider)
	}

	private suspend fun fetchUsernameSafely(authProvider: MangaParserAuthProvider): Pair<Boolean, String?> {
		return try {
			val name = authProvider.getUsername().takeIf { it.isNotBlank() }
			true to name
		} catch (_: AuthRequiredException) {
			false to null
		} catch (_: Exception) {
			true to null
		}
	}

	private fun clearCookiesForAuth(
		repository: ParserMangaRepository,
		authProvider: MangaParserAuthProvider,
	) {
		val hosts = linkedSetOf(repository.domain)
		authProvider.authUrl.toHttpUrlOrNull()?.host?.let(hosts::add)
		authProvider.signUpUrl?.toHttpUrlOrNull()?.host?.let(hosts::add)
		for (host in hosts) {
			if (host.isBlank()) continue
			val httpsUrl = HttpUrl.Builder().scheme("https").host(host).build()
			cookieJar.removeCookies(httpsUrl, null)
			if (cookieJar !is AndroidCookieJar) {
				val httpUrl = HttpUrl.Builder().scheme("http").host(host).build()
				cookieJar.removeCookies(httpUrl, null)
			}
		}
	}

	private fun updateSingleItem(source: MangaSource, transform: (SourceAccountItem) -> SourceAccountItem) {
		allItems.update { list ->
			list.map { item ->
				if (item.source == source) transform(item) else item
			}.sortedWith(itemComparator())
		}
	}

	private fun itemComparator(): Comparator<SourceAccountItem> =
		compareBy<SourceAccountItem>({ !it.isSignedIn }, { !it.isEnabled }, { it.source.getTitle(context).lowercase() })
}
