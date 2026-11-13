package org.koitharu.kotatsu.details.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.ui.MangaUnavailableDialog
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.exceptions.EmptyMangaException
import org.koitharu.kotatsu.core.exceptions.UnsupportedSourceException
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler
import org.koitharu.kotatsu.core.exceptions.resolve.ErrorObserver
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.util.ext.findCloudFlareException
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.isContentNotFound
import org.koitharu.kotatsu.core.util.ext.isNetworkError
import org.koitharu.kotatsu.core.util.ext.isSerializable
import org.koitharu.kotatsu.details.ui.pager.EmptyMangaReason
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource

class DetailsErrorObserver(
	override val activity: DetailsActivity,
	private val viewModel: DetailsViewModel,
	private val resolver: ExceptionResolver?,
) : ErrorObserver(
	activity.viewBinding.scrollView, null, resolver,
	{ isResolved ->
		if (isResolved) {
			viewModel.reload()
		}
	},
) {

	private val captchaEntryPoint by lazy {
		EntryPointAccessors.fromApplication(
			activity.applicationContext,
			CaptchaHandler.CaptchaEntryPoint::class.java,
		)
	}
	private var unavailableDialog: MangaUnavailableDialog? = null
	private var autoResolveAttempted = false
	private var resetAutoResolveOnLoadSuccess = true
	private var sawNotLoaded = false
	private var pendingBrowserRetry = false
	private var lastObservedSource: MangaSource? = null
	private var lastHandledBrowserVersion = captchaEntryPoint.captchaHandler().currentBrowserCompletionVersion()

	init {
		viewModel.mangaDetails
			.filterNotNull()
			.onEach { details ->
				details.toManga().source.takeUnless { it == UnknownMangaSource }?.let {
					lastObservedSource = it
				}
				if (!details.isLoaded) {
					sawNotLoaded = true
				} else if (sawNotLoaded) {
					sawNotLoaded = false
					if (resetAutoResolveOnLoadSuccess) {
						autoResolveAttempted = false
					}
				}
			}
			.launchIn(activity.lifecycleScope)

		// The prompt is an activity-hosted dialog, so it has to go down with the activity.
		activity.lifecycle.addObserver(
			LifecycleEventObserver { _: LifecycleOwner, event ->
				when (event) {
					Lifecycle.Event.ON_RESUME -> handleResumeFromBrowser()
					Lifecycle.Event.ON_DESTROY -> {
						unavailableDialog?.dismiss()
						unavailableDialog = null
					}

					else -> Unit
				}
			},
		)
	}

	private fun currentSource(): MangaSource? =
		(viewModel.getMangaOrNull()?.source ?: lastObservedSource)?.takeUnless { it == UnknownMangaSource }

	private fun handleResumeFromBrowser() {
		val source = currentSource()
		val captchaHandler = captchaEntryPoint.captchaHandler()
		val latestVersion = source?.let { captchaHandler.latestBrowserCompletionVersion(it) } ?: 0L
		val hasBrowserCompletion = source != null && latestVersion > lastHandledBrowserVersion
		if (hasBrowserCompletion) {
			lastHandledBrowserVersion = latestVersion
		}
		if (!pendingBrowserRetry && !hasBrowserCompletion) {
			return
		}
		pendingBrowserRetry = false
		autoResolveAttempted = false
		resetAutoResolveOnLoadSuccess = true
		activity.lifecycleScope.launch {
			if (source != null) {
				captchaHandler.discard(source)
				captchaEntryPoint.captchaAutoResolveCoordinator().notifyManualResolveSuccess(source)
			}
			viewModel.reload()
		}
	}

	override suspend fun emit(value: Throwable) {
		// Manga details is an explicit user action ("opened a manga"), so auto-resolve is appropriate
		// at most once per load cycle. If the per-source toggle disables it, or auto-resolve doesn't
		// succeed, fall back to the standard snackbar with the "Solve" action below.
		val cf = value.findCloudFlareException()
		if (cf != null && cf.source != UnknownMangaSource) {
			lastObservedSource = cf.source
		}
		if (cf is CloudFlareProtectedException && resolver != null && !autoResolveAttempted) {
			val autoDisabled = SourceSettings(host.context, cf.source).isCaptchaAutoResolveDisabled
			if (!autoDisabled) {
				autoResolveAttempted = true
				resetAutoResolveOnLoadSuccess = viewModel.mangaDetails.value?.isLoaded != true
				val resolved = resolver.resolve(cf, tryAutoResolve = true)
				if (resolved) {
					viewModel.reload()
					return
				}
			}
		}
		// A title that is gone from its source is a dead end, not something a one-line snackbar can
		// help with, so ask up front whether to go looking for it on another source.
		val missingManga = viewModel.getMangaOrNull()?.takeIf { !it.isLocal && value.isContentNotFound() }
		if (missingManga != null && showUnavailableDialog(missingManga)) {
			return
		}
		val snackbar = Snackbar.make(host, value.getDisplayMessage(host.context.resources), Snackbar.LENGTH_SHORT)
		snackbar.setAnchorView(activity.viewBinding.containerBottomSheet)
		if (value is NotFoundException || value is UnsupportedSourceException) {
			snackbar.duration = Snackbar.LENGTH_INDEFINITE
		}
		when {
			canResolve(value) -> {
				snackbar.setAction(ExceptionResolver.getResolveStringId(value)) {
					autoResolveAttempted = false
					resetAutoResolveOnLoadSuccess = true
					if (value is NotFoundException || (value is EmptyMangaException && value.reason == EmptyMangaReason.RESTRICTED)) {
						pendingBrowserRetry = true
					}
					resolve(value)
				}
			}

			value is ParseException -> {
				val router = router()
				if (router != null && value.isSerializable()) {
					snackbar.setAction(R.string.details) {
						router.showErrorDialog(value)
					}
				}
			}

			value.isNetworkError() -> {
				snackbar.setAction(R.string.try_again) {
					autoResolveAttempted = false
					resetAutoResolveOnLoadSuccess = true
					viewModel.reload()
				}
			}
		}
		snackbar.show()
	}

	/**
	 * @return `true` if the prompt is on screen and the caller should not show anything else. A dialog
	 * that is already up is left alone: reloads re-emit the same error and stacking dialogs on top of
	 * each other would trap the user behind a pile of them.
	 */
	private fun showUnavailableDialog(manga: Manga): Boolean {
		if (unavailableDialog?.isShowing == true) {
			return true
		}
		if (activity.isDestroyed || activity.isFinishing) {
			return false
		}
		val router = activity.router
		unavailableDialog = MangaUnavailableDialog.Builder(activity, manga)
			.setOnAlternativesClickListener { router.openAlternatives(manga) }
			.create()
			.also { it.show() }
		return true
	}
}
