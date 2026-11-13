package org.koitharu.kotatsu.comments

import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext

enum class CommentsTab {
	NATIVE,
	WIDGET,
}

sealed interface ComposerMode {
	data object NewComment : ComposerMode
	data class Reply(val parentComment: SourceComment) : ComposerMode
	data class Edit(val comment: SourceComment) : ComposerMode
}

sealed interface SourceCommentListItem {
	val itemId: String

	data class CommentItem(
		val comment: SourceComment,
		val depth: Int,
		val isSpoilerRevealed: Boolean,
		val isRepliesExpanded: Boolean,
		val isLoadingReplies: Boolean,
		val isLiking: Boolean,
		val formattedTime: CharSequence?,
		val capabilities: SourceCommunityCapabilities,
	) : SourceCommentListItem {
		override val itemId: String
			get() = "comment:${comment.id}"
	}

	data class LoadMoreRepliesItem(
		val parentComment: SourceComment,
		val isLoading: Boolean,
	) : SourceCommentListItem {
		override val itemId: String
			get() = "more_replies:${parentComment.id}"
	}

	data class LoadMoreCommentsItem(
		val isLoading: Boolean,
	) : SourceCommentListItem {
		override val itemId: String
			get() = "more_comments"
	}
}

data class ReplyThreadState(
	val replies: List<SourceComment> = emptyList(),
	val isExpanded: Boolean = false,
	val isLoading: Boolean = false,
	val hasNextPage: Boolean = false,
	val nextPage: Int = 1,
	val nextCursor: String? = null,
)

data class NativeCommentsUiState(
	val isLoading: Boolean = false,
	val isRefreshing: Boolean = false,
	val isLoadingMore: Boolean = false,
	val isSubmitting: Boolean = false,
	val error: Throwable? = null,
	val comments: List<SourceComment> = emptyList(),
	val items: List<SourceCommentListItem> = emptyList(),
	val hasNextPage: Boolean = false,
	val nextPage: Int = 1,
	val nextCursor: String? = null,
	val totalCount: Int? = null,
	val sort: SourceCommentSort = SourceCommentSort.NEWEST,
	val composerMode: ComposerMode = ComposerMode.NewComment,
	val info: SourceMangaCommunityInfo? = null,
)

data class WidgetCommentsUiState(
	val isLoading: Boolean = false,
	val context: SourceWidgetContext? = null,
	val error: Throwable? = null,
)
