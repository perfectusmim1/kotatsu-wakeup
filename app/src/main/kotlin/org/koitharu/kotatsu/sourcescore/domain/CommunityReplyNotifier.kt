package org.koitharu.kotatsu.sourcescore.domain

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sourcescore.data.CommunityApi
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import org.koitharu.kotatsu.sourcescore.data.NotificationDto
import org.koitharu.kotatsu.main.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CommunityReplies"

/**
 * Tells the user when somebody replied to them.
 *
 * Polled while the app is open rather than pushed: push would mean a device token, a Google dependency
 * and a third party learning when this user opens a manga app, all to save a request that costs
 * nothing (PLAN.md §5). Checking only at launch missed every reply that arrived during a reading
 * session, so it also checks whenever the app comes back to the foreground and every minute while it
 * stays there - and never in the background.
 *
 * "Open" is tracked by counting started activities rather than through ProcessLifecycleOwner, which
 * never leaves INITIALIZED in this app: the manifest removes androidx.startup's provider, and its
 * initializer cannot be run by hand afterwards.
 *
 * Delivered **once**. The cursor lives here on the device and the server keeps no record of what it
 * has sent, because a table of who-was-told-what is exactly the kind of per-user history this design
 * refuses to hold - so if this cursor is not advanced, the same reply is announced forever.
 */
@Singleton
class CommunityReplyNotifier @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: CommunitySettings,
	private val api: CommunityApi,
) {

	private val isWatching = AtomicBoolean(false)

	/** One poll at a time, so two overlapping checks can never announce the same reply twice. */
	private val pollMutex = Mutex()

	/**
	 * Starts checking for replies for as long as the process lives. Idempotent, so an activity being
	 * recreated does not start a second loop.
	 *
	 * Tied to the process lifecycle rather than an activity's: the loop pauses the moment no screen of
	 * the app is visible and resumes, with an immediate check, when one is again. The community switch
	 * is read on every round, so turning the feature on or off needs no restart.
	 */
	fun watchWhileOpen(activity: Activity) {
		if (!isWatching.compareAndSet(false, true)) return
		activity.application.registerActivityLifecycleCallbacks(ForegroundTracker())
		// The calling activity is created but not yet started, so the tracker sees its onStart.
		processLifecycleScope.launch {
			isForeground.collectLatest { foreground ->
				// collectLatest cancels the loop the moment the app leaves the foreground, and starts a
				// fresh one - with an immediate check - when it comes back.
				while (foreground && isActive) {
					if (settings.isEnabled) {
						pollMutex.withLock { poll() }
					}
					delay(POLL_INTERVAL_MS)
				}
			}
		}
	}

	private val isForeground = MutableStateFlow(false)

	/** Counts started activities; any one of them on screen means the app is open. */
	private inner class ForegroundTracker : Application.ActivityLifecycleCallbacks {

		private var started = 0

		override fun onActivityStarted(activity: Activity) {
			started++
			isForeground.value = true
		}

		override fun onActivityStopped(activity: Activity) {
			started = (started - 1).coerceAtLeast(0)
			// Activity transitions start the next screen before stopping the previous one, so this
			// only drops to zero when the app has really gone to the background.
			if (started == 0) isForeground.value = false
		}

		override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
		override fun onActivityResumed(activity: Activity) = Unit
		override fun onActivityPaused(activity: Activity) = Unit
		override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
		override fun onActivityDestroyed(activity: Activity) = Unit
	}

	private suspend fun poll() {
		val response = runCatchingCancellable { api.fetchNotifications(settings.replyCursor) }
			.onFailure { Log.d(TAG, "Reply poll failed", it) }
			.getOrNull() ?: return

		// Advanced even when there is nothing to show, so a quiet week does not re-ask for a month of
		// history on every launch.
		response.cursor.takeIf { it.isNotEmpty() }?.let { settings.replyCursor = it }
		if (response.replies.isEmpty()) return

		notify(response.replies.size, response.replies.first())
	}

	private fun notify(count: Int, newest: NotificationDto) {
		val author = newest.author
		val preview = newest.preview
		// The user may simply have said no, which is a perfectly good answer: replies are a courtesy,
		// not something worth prompting for on app open.
		if (
			ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
			PackageManager.PERMISSION_GRANTED
		) {
			Log.d(TAG, "Notifications are not permitted; $count replies not shown")
			return
		}

		val manager = NotificationManagerCompat.from(context)
		manager.createNotificationChannel(
			NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
				.setName(context.getString(R.string.community_replies))
				.setShowBadge(true)
				.setVibrationEnabled(false)
				.setLightsEnabled(false)
				.build(),
		)

		// One notification for the lot, not one each: the point is "somebody replied", and the thread
		// is where the conversation actually is.
		val title = if (count == 1) {
			context.getString(R.string.community_reply_from, author)
		} else {
			context.resources.getQuantityString(R.plurals.community_replies_count, count, count)
		}

		val notification = NotificationCompat.Builder(context, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_stat_comment)
			.setContentTitle(title)
			.setContentText(preview)
			.setStyle(NotificationCompat.BigTextStyle().bigText(preview))
			// Tapping opens the thread of the newest reply, scrolled to it. With several replies across
			// works that is the only one it can open; the rest are one tap away from their manga.
			.setContentIntent(openThreadIntent(newest))
			.setAutoCancel(true)
			.setSilent(true)
			.build()

		runCatching { manager.notify(TAG, NOTIFICATION_ID, notification) }
			.onFailure { Log.w(TAG, "Could not post the reply notification", it) }
	}

	private fun openThreadIntent(reply: NotificationDto): PendingIntent {
		val intent = Intent(context, MainActivity::class.java)
			.setAction(ACTION_OPEN_COMMENTS)
			.putExtra(EXTRA_WORK_ID, reply.workId)
			.putExtra(EXTRA_COMMENT_ID, reply.commentId)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
		reply.chapterId?.toLongOrNull()?.let { intent.putExtra(EXTRA_CHAPTER_ID, it) }
		return PendingIntent.getActivity(
			context,
			NOTIFICATION_ID,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
	}

	companion object {

		const val ACTION_OPEN_COMMENTS = "org.koitharu.kotatsu.action.OPEN_COMMUNITY_COMMENTS"
		const val EXTRA_WORK_ID = "community_work_id"
		const val EXTRA_CHAPTER_ID = "community_chapter_id"
		const val EXTRA_COMMENT_ID = "community_comment_id"

		private const val CHANNEL_ID = "community_replies"
		private const val NOTIFICATION_ID = 1

		/** Well inside the server's per-minute limit, and quick enough that a reply feels live. */
		private const val POLL_INTERVAL_MS = 60_000L
	}
}
