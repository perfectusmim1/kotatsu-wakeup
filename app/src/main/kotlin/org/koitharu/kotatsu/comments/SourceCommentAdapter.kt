package org.koitharu.kotatsu.comments

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.fallback
import coil3.request.placeholder
import coil3.target.ImageViewTarget
import coil3.util.CoilUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.loadingindicator.LoadingIndicator
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.enqueueWith
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.resolveDp
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemSourceCommentBinding
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

class SourceCommentAdapter(
	private val source: MangaSource,
	private val coil: ImageLoader,
	private val listener: Listener,
) : ListAdapter<SourceCommentListItem, RecyclerView.ViewHolder>(DiffCallback) {

	interface Listener {
		fun onToggleSpoiler(commentId: String)
		fun onToggleLike(comment: SourceComment, isLike: Boolean)
		fun onReplyClick(comment: SourceComment)
		fun onToggleReplies(comment: SourceComment)
		fun onEditClick(comment: SourceComment)
		fun onDeleteClick(comment: SourceComment)
		fun onLoadMoreReplies(parentComment: SourceComment)
		fun onLoadMoreComments()
	}

	override fun getItemViewType(position: Int): Int = when (getItem(position)) {
		is SourceCommentListItem.CommentItem -> VIEW_TYPE_COMMENT
		is SourceCommentListItem.LoadMoreRepliesItem -> VIEW_TYPE_LOAD_MORE_REPLIES
		is SourceCommentListItem.LoadMoreCommentsItem -> VIEW_TYPE_LOAD_MORE_COMMENTS
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
		val context = parent.context
		return when (viewType) {
			VIEW_TYPE_COMMENT -> CommentViewHolder(
				ItemSourceCommentBinding.inflate(LayoutInflater.from(context), parent, false),
			)

			VIEW_TYPE_LOAD_MORE_REPLIES, VIEW_TYPE_LOAD_MORE_COMMENTS -> {
				val container = FrameLayout(context).apply {
					layoutParams = ViewGroup.MarginLayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT,
						ViewGroup.LayoutParams.WRAP_CONTENT,
					)
					val padV = resources.resolveDp(4)
					setPadding(0, padV, 0, padV)
				}
				val button = MaterialButton(
					context,
					null,
					appcompatR.attr.borderlessButtonStyle,
				).apply {
					layoutParams = FrameLayout.LayoutParams(
						ViewGroup.LayoutParams.WRAP_CONTENT,
						ViewGroup.LayoutParams.WRAP_CONTENT,
						Gravity.CENTER,
					)
				}
				val indicator = LoadingIndicator(context).apply {
					layoutParams = FrameLayout.LayoutParams(
						ViewGroup.LayoutParams.WRAP_CONTENT,
						ViewGroup.LayoutParams.WRAP_CONTENT,
						Gravity.CENTER,
					)
					isVisible = false
				}
				container.addView(button)
				container.addView(indicator)
				LoadMoreViewHolder(container, button, indicator)
			}

			else -> error("Unknown viewType $viewType")
		}
	}

	override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		when (val item = getItem(position)) {
			is SourceCommentListItem.CommentItem -> (holder as CommentViewHolder).bind(item)
			is SourceCommentListItem.LoadMoreRepliesItem -> (holder as LoadMoreViewHolder).bindReplies(item)
			is SourceCommentListItem.LoadMoreCommentsItem -> (holder as LoadMoreViewHolder).bindComments(item)
		}
	}

	private inner class CommentViewHolder(
		private val binding: ItemSourceCommentBinding,
	) : RecyclerView.ViewHolder(binding.root) {

		private var boundItem: SourceCommentListItem.CommentItem? = null

		init {
			binding.buttonRevealSpoiler.setOnClickListener {
				boundItem?.let { listener.onToggleSpoiler(it.comment.id) }
			}
			binding.buttonLike.setOnClickListener {
				boundItem?.let { listener.onToggleLike(it.comment, isLike = true) }
			}
			binding.buttonDislike.setOnClickListener {
				boundItem?.let { listener.onToggleLike(it.comment, isLike = false) }
			}
			binding.buttonReply.setOnClickListener {
				boundItem?.let { listener.onReplyClick(it.comment) }
			}
			binding.buttonReplies.setOnClickListener {
				boundItem?.let { listener.onToggleReplies(it.comment) }
			}
			binding.buttonMore.setOnClickListener { anchor ->
				val comment = boundItem?.comment ?: return@setOnClickListener
				showCommentMenu(anchor, comment)
			}
		}

		fun bind(item: SourceCommentListItem.CommentItem) {
			boundItem = item
			val comment = item.comment
			val caps = item.capabilities
			val context = binding.root.context

			val indentDp = item.depth.coerceAtMost(2) * 20
			binding.containerComment.updateLayoutParams<LinearLayout.LayoutParams> {
				marginStart = context.resources.resolveDp(indentDp)
			}
			val bgRes = when {
				item.depth > 0 && comment.isOwnComment -> R.drawable.bg_comment_reply_mine
				item.depth > 0 -> R.drawable.bg_comment_reply
				comment.isOwnComment -> R.drawable.bg_comment_mine
				else -> R.drawable.bg_comment
			}
			binding.containerComment.setBackgroundResource(bgRes)

			val avatarUrl = comment.authorAvatarUrl?.takeIf { it.isNotBlank() }
			if (avatarUrl != null) {
				ImageRequest.Builder(context)
					.data(avatarUrl)
					.mangaSourceExtra(source)
					.crossfade(true)
					.placeholder(R.drawable.ic_user)
					.error(R.drawable.ic_user)
					.fallback(R.drawable.ic_user)
					.target(ImageViewTarget(binding.imageViewAvatar))
					.enqueueWith(coil)
			} else {
				CoilUtils.dispose(binding.imageViewAvatar)
				binding.imageViewAvatar.setImageResource(R.drawable.ic_user)
			}

			binding.textViewAuthor.text = comment.authorName
			binding.imageViewPinned.isVisible = comment.isPinned
			binding.textViewRole.textAndVisible = comment.authorRole?.takeIf { it.isNotBlank() }

			val timeText = buildString {
				item.formattedTime?.let { append(it) }
				if (comment.isEdited) {
					if (isNotEmpty()) append(" · ")
					append(context.getString(R.string.community_comment_edited))
				}
			}.ifEmpty { null }
			binding.textViewTime.textAndVisible = timeText

			binding.buttonMore.isVisible = comment.canEdit || comment.canDelete

			val showSpoilerMask = comment.isSpoiler && !item.isSpoilerRevealed
			binding.textViewBody.isVisible = !showSpoilerMask
			binding.buttonRevealSpoiler.isVisible = showSpoilerMask
			if (!showSpoilerMask) {
				val bodyText = comment.body.ifBlank {
					comment.bodyHtml?.let {
						HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).trim()
					} ?: ""
				}
				binding.textViewBody.text = bodyText
			}

			val primaryColor = context.getThemeColor(appcompatR.attr.colorPrimary)
			val errorColor = context.getThemeColor(appcompatR.attr.colorError)
			val neutralColor = context.getThemeColor(materialR.attr.colorOnSurfaceVariant)

			binding.buttonLike.isVisible = comment.canLike || caps.supportsCommentLikes || comment.likesCount > 0
			binding.buttonLike.isEnabled = comment.canLike && !item.isLiking
			binding.buttonLike.text = if (comment.likesCount > 0) comment.likesCount.toString() else ""
			val likeTint = ColorStateList.valueOf(if (comment.userVote > 0) primaryColor else neutralColor)
			binding.buttonLike.iconTint = likeTint
			binding.buttonLike.setTextColor(likeTint)

			binding.buttonDislike.isVisible = comment.canDislike || caps.supportsCommentDislikes || comment.dislikesCount > 0
			binding.buttonDislike.isEnabled = comment.canDislike && !item.isLiking
			binding.buttonDislike.text = if (comment.dislikesCount > 0) comment.dislikesCount.toString() else ""
			val dislikeTint = ColorStateList.valueOf(if (comment.userVote < 0) errorColor else neutralColor)
			binding.buttonDislike.iconTint = dislikeTint
			binding.buttonDislike.setTextColor(dislikeTint)

			binding.buttonReply.isVisible = comment.canReply ||
				(caps.supportsReplies && caps.supportsCommentPosting && item.depth == 0)

			val replyCount = maxOf(comment.repliesCount, comment.replies.size)
			binding.progressReplies.isVisible = item.isLoadingReplies
			binding.buttonReplies.isVisible = !item.isLoadingReplies && (replyCount > 0 || item.isRepliesExpanded)
			binding.buttonReplies.text = if (item.isRepliesExpanded) {
				context.getString(R.string.community_hide_replies)
			} else {
				context.getString(R.string.community_show_replies, replyCount)
			}
		}

		private fun showCommentMenu(anchor: View, comment: SourceComment) {
			val popup = PopupMenu(anchor.context, anchor)
			if (comment.canEdit) {
				popup.menu.add(0, MENU_EDIT, 0, R.string.community_comment_edit)
			}
			if (comment.canDelete) {
				popup.menu.add(0, MENU_DELETE, 1, R.string.community_comment_delete)
			}
			popup.setOnMenuItemClickListener { menuItem ->
				when (menuItem.itemId) {
					MENU_EDIT -> {
						listener.onEditClick(comment)
						true
					}

					MENU_DELETE -> {
						listener.onDeleteClick(comment)
						true
					}

					else -> false
				}
			}
			popup.show()
		}
	}

	private inner class LoadMoreViewHolder(
		private val container: FrameLayout,
		private val button: MaterialButton,
		private val indicator: LoadingIndicator,
	) : RecyclerView.ViewHolder(container) {

		fun bindReplies(item: SourceCommentListItem.LoadMoreRepliesItem) {
			val lp = button.layoutParams as FrameLayout.LayoutParams
			lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
			lp.marginStart = container.resources.resolveDp(24)
			button.layoutParams = lp
			button.setText(R.string.community_load_more_replies)
			button.isVisible = !item.isLoading
			indicator.isVisible = item.isLoading
			button.setOnClickListener { listener.onLoadMoreReplies(item.parentComment) }
		}

		fun bindComments(item: SourceCommentListItem.LoadMoreCommentsItem) {
			val lp = button.layoutParams as FrameLayout.LayoutParams
			lp.gravity = Gravity.CENTER
			lp.marginStart = 0
			button.layoutParams = lp
			button.setText(R.string.community_load_more)
			button.isVisible = !item.isLoading
			indicator.isVisible = item.isLoading
			button.setOnClickListener { listener.onLoadMoreComments() }
		}
	}

	private object DiffCallback : DiffUtil.ItemCallback<SourceCommentListItem>() {
		override fun areItemsTheSame(
			oldItem: SourceCommentListItem,
			newItem: SourceCommentListItem,
		): Boolean = oldItem.itemId == newItem.itemId

		override fun areContentsTheSame(
			oldItem: SourceCommentListItem,
			newItem: SourceCommentListItem,
		): Boolean = oldItem == newItem
	}

	private companion object {
		const val VIEW_TYPE_COMMENT = 0
		const val VIEW_TYPE_LOAD_MORE_REPLIES = 1
		const val VIEW_TYPE_LOAD_MORE_COMMENTS = 2

		const val MENU_EDIT = 1
		const val MENU_DELETE = 2
	}
}
