package org.koitharu.kotatsu.core.ui.util

import android.app.Activity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.core.ui.DefaultActivityLifecycleCallbacks
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the currently resumed Activity so callers (e.g. the headless WebView used for CloudFlare auto-solving)
 * can briefly attach views to it. Some Cloudflare Turnstile challenges only complete when the WebView is actually
 * attached to a real window — a detached WebView reliably returns "rBxl / GPZI" anti-bot rejections.
 */
@Singleton
class ForegroundActivityHolder @Inject constructor() : DefaultActivityLifecycleCallbacks {

	@Volatile
	private var activityRef: WeakReference<Activity>? = null
	private val waiters = CopyOnWriteArrayList<CompletableDeferred<Activity>>()

	val current: Activity?
		get() = activityRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

	suspend fun awaitCurrent(timeoutMs: Long = 1_500L): Activity? {
		current?.let { return it }
		val deferred = CompletableDeferred<Activity>()
		waiters.add(deferred)
		current?.let {
			waiters.remove(deferred)
			return it
		}
		return try {
			withTimeoutOrNull(timeoutMs) { deferred.await() }
		} finally {
			waiters.remove(deferred)
		}
	}

	override fun onActivityResumed(activity: Activity) {
		activityRef = WeakReference(activity)
		if (waiters.isNotEmpty()) {
			val snapshot = waiters.toList()
			waiters.clear()
			snapshot.forEach { it.complete(activity) }
		}
	}

	override fun onActivityPaused(activity: Activity) {
		if (activityRef?.get() === activity) {
			activityRef = null
		}
	}
}
