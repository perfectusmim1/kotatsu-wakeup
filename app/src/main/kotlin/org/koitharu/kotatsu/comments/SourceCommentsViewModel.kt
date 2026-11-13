package org.koitharu.kotatsu.comments

import android.text.format.DateUtils
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.parcelable.ParcelableChapter
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.details.data.CommunityRepository
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class SourceCommentsViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val communityRepository: CommunityRepository,
) : BaseViewModel() {

	val manga: Manga = requireNotNull(savedStateHandle.get<ParcelableManga>(ARG_MANGA)).manga
	val chapter: MangaChapter? = savedStateHandle.get<ParcelableChapter>(ARG_CHAPTER)?.chapter
	val capabilities: SourceCommunityCapabilities =
		communityRepository.capabilities(manga.source) ?: SourceCommunityCapabilities()

	val effectiveChapter: MangaChapter? = chapter?.takeIf { capabilities.supportsChapterComments }

	val hasNativeTab: Boolean = capabilities.supportsNativeComments
	val hasWidgetTab: Boolean = capabilities.supportsWidgetFallback
	val showTabSwitcher: Boolean = hasNativeTab && hasWidgetTab
	val defaultTab: CommentsTab = if (hasNativeTab) CommentsTab.NATIVE else CommentsTab.WIDGET

	private val _selectedTab = MutableStateFlow(defaultTab)
	val selectedTab: StateFlow<CommentsTab> = _selectedTab.asStateFlow()

	private val initialSort: SourceCommentSort = when {
		SourceCommentSort.NEWEST in capabilities.supportedSorts -> SourceCommentSort.NEWEST
		else -> capabilities.supportedSorts.firstOrNull() ?: SourceCommentSort.NEWEST
	}

	private val _nativeState = MutableStateFlow(NativeCommentsUiState(sort = initialSort))
	val nativeState: StateFlow<NativeCommentsUiState> = _nativeState.asStateFlow()

	private val _widgetState = MutableStateFlow(WidgetCommentsUiState())
	val widgetState: StateFlow<WidgetCommentsUiState> = _widgetState.asStateFlow()

	val onAuthRequired = MutableEventFlow<MangaSource>()
	val onCommentPosted = MutableEventFlow<Unit>()
	val onPrefillComposer = MutableEventFlow<String>()
	val onMessage = MutableEventFlow<Int>()
	val onCommunityChanged = MutableEventFlow<Unit>()

	private val repliesMap = HashMap<String, ReplyThreadState>()
	private val revealedSpoilerIds = HashSet<String>()
	private val likingCommentIds = HashSet<String>()

	private var loadCommentsJob: Job? = null
	private var loadWidgetJob: Job? = null
	private var nativeLoadedOnce = false
	private var widgetLoadedOnce = false

	init {
		refreshInfo(force = false)
		if (defaultTab == CommentsTab.NATIVE) {
			loadComments(reset = true)
		} else if (hasWidgetTab) {
			loadWidgetContext(force = false)
		}
	}

	fun getUserAgent(): String? = communityRepository.getUserAgent(manga.source)

	fun selectTab(tab: CommentsTab) {
		if (_selectedTab.value == tab) {
			return
		}
		_selectedTab.value = tab
		when (tab) {
			CommentsTab.NATIVE -> if (!nativeLoadedOnce && hasNativeTab) {
				loadComments(reset = true)
			}

			CommentsTab.WIDGET -> if (!widgetLoadedOnce && hasWidgetTab) {
				loadWidgetContext(force = false)
			}
		}
	}

	fun selectSort(sort: SourceCommentSort) {
		if (_nativeState.value.sort == sort) {
			return
		}
		_nativeState.update { it.copy(sort = sort) }
		loadComments(reset = true)
	}

	fun refresh() {
		refreshInfo(force = true)
		when (_selectedTab.value) {
			CommentsTab.NATIVE -> if (hasNativeTab) {
				loadComments(reset = true, isRefresh = true)
			}

			CommentsTab.WIDGET -> if (hasWidgetTab) {
				loadWidgetContext(force = true)
			}
		}
	}

	fun onAuthCompleted() {
		launchJob(Dispatchers.Default) {
			communityRepository.invalidate(manga.source)
			refreshInfo(force = true)
			if (hasNativeTab) {
				loadComments(reset = true, isRefresh = true)
			}
			if (hasWidgetTab && _selectedTab.value == CommentsTab.WIDGET) {
				loadWidgetContext(force = true)
			}
		}
	}

	fun loadMoreComments() {
		val current = _nativeState.value
		if (current.isLoading || current.isLoadingMore || !current.hasNextPage) {
			return
		}
		loadCommentsJob = launchJob(Dispatchers.Default) {
			_nativeState.update { it.copy(isLoadingMore = true) }
			publishNativeItems()
			runCatchingCancellable {
				communityRepository.getComments(
					manga = manga,
					chapter = effectiveChapter,
					page = current.nextPage,
					sort = current.sort,
					cursor = current.nextCursor,
				)
			}.onSuccess { pageResult ->
				val merged = mergeComments(current.comments, pageResult.comments)
				cacheWidgetFromPageIfMissing(pageResult.widgetContext)
				_nativeState.update {
					it.copy(
						isLoadingMore = false,
						comments = merged,
						hasNextPage = pageResult.hasNextPage,
						nextPage = pageResult.page + 1,
						nextCursor = pageResult.nextCursor,
						totalCount = pageResult.totalCount ?: it.totalCount,
					)
				}
				publishNativeItems()
			}.onFailure { error ->
				error.printStackTraceDebug()
				_nativeState.update { it.copy(isLoadingMore = false) }
				publishNativeItems()
				handleActionError(error)
			}
		}
	}

	fun toggleSpoiler(commentId: String) {
		if (!revealedSpoilerIds.add(commentId)) {
			revealedSpoilerIds.remove(commentId)
		}
		publishNativeItems()
	}

	fun toggleReplies(comment: SourceComment) {
		val existing = repliesMap[comment.id]
		if (existing != null) {
			if (existing.isLoading) {
				return
			}
			if (existing.isExpanded) {
				repliesMap[comment.id] = existing.copy(isExpanded = false)
				publishNativeItems()
				return
			}
			if (existing.replies.isNotEmpty()) {
				repliesMap[comment.id] = existing.copy(isExpanded = true)
				publishNativeItems()
				return
			}
		}
		if (comment.replies.isNotEmpty() && comment.replies.size >= comment.repliesCount) {
			repliesMap[comment.id] = ReplyThreadState(
				replies = comment.replies,
				isExpanded = true,
				hasNextPage = false,
			)
			publishNativeItems()
			return
		}
		launchJob(Dispatchers.Default) {
			val baseState = existing ?: ReplyThreadState(replies = comment.replies)
			repliesMap[comment.id] = baseState.copy(isLoading = true)
			publishNativeItems()
			runCatchingCancellable {
				communityRepository.getReplies(
					manga = manga,
					comment = comment,
					chapter = effectiveChapter,
					page = 1,
				)
			}.onSuccess { pageResult ->
				val loaded = pageResult.comments.ifEmpty { comment.replies }
				repliesMap[comment.id] = ReplyThreadState(
					replies = loaded,
					isExpanded = true,
					isLoading = false,
					hasNextPage = pageResult.hasNextPage,
					nextPage = pageResult.page + 1,
					nextCursor = pageResult.nextCursor,
				)
				publishNativeItems()
			}.onFailure { error ->
				error.printStackTraceDebug()
				if (comment.replies.isNotEmpty()) {
					repliesMap[comment.id] = ReplyThreadState(
						replies = comment.replies,
						isExpanded = true,
						isLoading = false,
					)
				} else {
					repliesMap[comment.id] = baseState.copy(isLoading = false)
					handleActionError(error)
				}
				publishNativeItems()
			}
		}
	}

	fun loadMoreReplies(parentComment: SourceComment) {
		val thread = repliesMap[parentComment.id] ?: return
		if (thread.isLoading || !thread.hasNextPage) {
			return
		}
		launchJob(Dispatchers.Default) {
			repliesMap[parentComment.id] = thread.copy(isLoading = true)
			publishNativeItems()
			runCatchingCancellable {
				communityRepository.getReplies(
					manga = manga,
					comment = parentComment,
					chapter = effectiveChapter,
					page = thread.nextPage,
					cursor = thread.nextCursor,
				)
			}.onSuccess { pageResult ->
				val merged = mergeComments(thread.replies, pageResult.comments)
				repliesMap[parentComment.id] = thread.copy(
					replies = merged,
					isLoading = false,
					hasNextPage = pageResult.hasNextPage,
					nextPage = pageResult.page + 1,
					nextCursor = pageResult.nextCursor,
				)
				publishNativeItems()
			}.onFailure { error ->
				error.printStackTraceDebug()
				repliesMap[parentComment.id] = thread.copy(isLoading = false)
				publishNativeItems()
				handleActionError(error)
			}
		}
	}

	fun startReply(comment: SourceComment) {
		_nativeState.update { it.copy(composerMode = ComposerMode.Reply(comment)) }
	}

	fun startEdit(comment: SourceComment) {
		_nativeState.update { it.copy(composerMode = ComposerMode.Edit(comment)) }
		onPrefillComposer.call(comment.body)
	}

	fun cancelCompose() {
		_nativeState.update { it.copy(composerMode = ComposerMode.NewComment) }
	}

	fun submitComposer(rawText: String, isSpoiler: Boolean) {
		val text = rawText.trim()
		if (text.isEmpty() || _nativeState.value.isSubmitting) {
			return
		}
		val mode = _nativeState.value.composerMode
		launchJob(Dispatchers.Default) {
			_nativeState.update { it.copy(isSubmitting = true) }
			runCatchingCancellable {
				when (mode) {
					is ComposerMode.NewComment -> {
						val created = communityRepository.postComment(
							manga = manga,
							text = text,
							chapter = effectiveChapter,
							parentComment = null,
							isSpoiler = isSpoiler,
						)
						_nativeState.update { state ->
							val pinned = state.comments.filter { it.isPinned }
							val regular = state.comments.filterNot { it.isPinned || it.id == created.id }
							val updatedList = pinned + created + regular
							state.copy(
								comments = updatedList,
								totalCount = (state.totalCount ?: state.comments.size) + 1,
								composerMode = ComposerMode.NewComment,
							)
						}
					}

					is ComposerMode.Reply -> {
						val parent = mode.parentComment
						val created = communityRepository.postComment(
							manga = manga,
							text = text,
							chapter = effectiveChapter,
							parentComment = parent,
							isSpoiler = isSpoiler,
						)
						val existingThread = repliesMap[parent.id] ?: ReplyThreadState(
							replies = parent.replies,
							isExpanded = true,
						)
						val mergedReplies = mergeComments(existingThread.replies, listOf(created))
						repliesMap[parent.id] = existingThread.copy(
							replies = mergedReplies,
							isExpanded = true,
						)
						_nativeState.update { state ->
							val updatedList = state.comments.map { item ->
								if (item.id == parent.id) {
									item.copy(
										repliesCount = maxOf(item.repliesCount + 1, mergedReplies.size),
										replies = mergedReplies,
									)
								} else {
									item
								}
							}
							state.copy(
								comments = updatedList,
								totalCount = state.totalCount?.plus(1),
								composerMode = ComposerMode.NewComment,
							)
						}
					}

					is ComposerMode.Edit -> {
						val updated = communityRepository.editComment(
							manga = manga,
							comment = mode.comment,
							newText = text,
							chapter = effectiveChapter,
						)
						replaceCommentInState(updated)
						_nativeState.update { it.copy(composerMode = ComposerMode.NewComment) }
					}
				}
			}.onSuccess {
				_nativeState.update { it.copy(isSubmitting = false) }
				publishNativeItems()
				onCommentPosted.call(Unit)
				onCommunityChanged.call(Unit)
				refreshInfo(force = true)
			}.onFailure { error ->
				error.printStackTraceDebug()
				_nativeState.update { it.copy(isSubmitting = false) }
				handleActionError(error)
			}
		}
	}

	fun deleteComment(comment: SourceComment) {
		launchJob(Dispatchers.Default) {
			runCatchingCancellable {
				communityRepository.deleteComment(manga, comment, effectiveChapter)
			}.onSuccess {
				removeCommentFromState(comment)
				publishNativeItems()
				onMessage.call(R.string.community_comment_deleted)
				onCommunityChanged.call(Unit)
				refreshInfo(force = true)
			}.onFailure { error ->
				error.printStackTraceDebug()
				handleActionError(error)
			}
		}
	}

	fun toggleLike(comment: SourceComment, isLike: Boolean) {
		if (!likingCommentIds.add(comment.id)) {
			return
		}
		publishNativeItems()
		launchJob(Dispatchers.Default) {
			runCatchingCancellable {
				communityRepository.toggleCommentLike(
					manga = manga,
					comment = comment,
					isLike = isLike,
					chapter = effectiveChapter,
				)
			}.onSuccess { updated ->
				likingCommentIds.remove(comment.id)
				replaceCommentInState(updated)
				publishNativeItems()
			}.onFailure { error ->
				error.printStackTraceDebug()
				likingCommentIds.remove(comment.id)
				publishNativeItems()
				handleActionError(error)
			}
		}
	}

	fun loadWidgetContext(force: Boolean = false) {
		if (!hasWidgetTab) {
			return
		}
		if (!force && _widgetState.value.context != null) {
			return
		}
		widgetLoadedOnce = true
		loadWidgetJob?.cancel()
		loadWidgetJob = launchJob(Dispatchers.Default) {
			_widgetState.update { it.copy(isLoading = true, error = null) }
			runCatchingCancellable {
				communityRepository.resolveWidgetContext(manga, effectiveChapter)
			}.onSuccess { ctx ->
				_widgetState.value = WidgetCommentsUiState(
					isLoading = false,
					context = ctx,
					error = if (ctx == null) IllegalStateException("Comment widget unavailable") else null,
				)
			}.onFailure { error ->
				error.printStackTraceDebug()
				_widgetState.value = WidgetCommentsUiState(
					isLoading = false,
					context = _widgetState.value.context,
					error = error,
				)
			}
		}
	}

	private fun loadComments(reset: Boolean, isRefresh: Boolean = false) {
		if (!hasNativeTab) {
			return
		}
		nativeLoadedOnce = true
		loadCommentsJob?.cancel()
		loadCommentsJob = launchJob(Dispatchers.Default) {
			val sort = _nativeState.value.sort
			_nativeState.update {
				it.copy(
					isLoading = reset && !isRefresh && it.comments.isEmpty(),
					isRefreshing = isRefresh,
					isLoadingMore = false,
					error = null,
				)
			}
			runCatchingCancellable {
				communityRepository.getComments(
					manga = manga,
					chapter = effectiveChapter,
					page = 1,
					sort = sort,
					cursor = null,
				)
			}.onSuccess { pageResult ->
				if (reset) {
					repliesMap.clear()
				}
				cacheWidgetFromPageIfMissing(pageResult.widgetContext)
				_nativeState.update {
					it.copy(
						isLoading = false,
						isRefreshing = false,
						error = null,
						comments = pageResult.comments,
						hasNextPage = pageResult.hasNextPage,
						nextPage = pageResult.page + 1,
						nextCursor = pageResult.nextCursor,
						totalCount = pageResult.totalCount,
					)
				}
				publishNativeItems()
			}.onFailure { error ->
				error.printStackTraceDebug()
				_nativeState.update {
					it.copy(
						isLoading = false,
						isRefreshing = false,
						error = error,
					)
				}
				publishNativeItems()
			}
		}
	}

	private fun refreshInfo(force: Boolean) {
		launchJob(Dispatchers.Default) {
			val info = runCatchingCancellable {
				communityRepository.getInfo(manga, effectiveChapter, force = force)
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrNull()
			if (info != null) {
				_nativeState.update { it.copy(info = info) }
			}
		}
	}

	private fun cacheWidgetFromPageIfMissing(widgetContext: org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext?) {
		if (widgetContext != null && _widgetState.value.context == null) {
			_widgetState.value = WidgetCommentsUiState(isLoading = false, context = widgetContext)
		}
	}

	private fun replaceCommentInState(updated: SourceComment) {
		_nativeState.update { state ->
			val topUpdated = state.comments.map { item ->
				if (item.id == updated.id) {
					updated
				} else if (item.replies.any { it.id == updated.id }) {
					item.copy(replies = item.replies.map { r -> if (r.id == updated.id) updated else r })
				} else {
					item
				}
			}
			state.copy(comments = topUpdated)
		}
		for ((parentId, thread) in repliesMap) {
			if (thread.replies.any { it.id == updated.id }) {
				repliesMap[parentId] = thread.copy(
					replies = thread.replies.map { if (it.id == updated.id) updated else it },
				)
			}
		}
	}

	private fun removeCommentFromState(target: SourceComment) {
		var removedTopLevel = false
		var decrementedParentId: String? = null
		_nativeState.update { state ->
			val filteredTop = state.comments.filterNot {
				val match = it.id == target.id
				if (match) removedTopLevel = true
				match
			}.map { item ->
				if (item.replies.any { it.id == target.id }) {
					decrementedParentId = item.id
					val newReplies = item.replies.filterNot { it.id == target.id }
					item.copy(
						replies = newReplies,
						repliesCount = (item.repliesCount - 1).coerceAtLeast(newReplies.size),
					)
				} else {
					item
				}
			}
			state.copy(
				comments = filteredTop,
				totalCount = if (removedTopLevel) state.totalCount?.let { (it - 1).coerceAtLeast(0) } else state.totalCount,
			)
		}
		repliesMap.remove(target.id)
		for ((parentId, thread) in repliesMap) {
			if (thread.replies.any { it.id == target.id }) {
				val remaining = thread.replies.filterNot { it.id == target.id }
				repliesMap[parentId] = thread.copy(replies = remaining)
				if (decrementedParentId == null) {
					_nativeState.update { state ->
						state.copy(
							comments = state.comments.map { item ->
								if (item.id == parentId) {
									item.copy(repliesCount = (item.repliesCount - 1).coerceAtLeast(remaining.size))
								} else {
									item
								}
							},
						)
					}
				}
			}
		}
	}

	private fun publishNativeItems() {
		_nativeState.update { state ->
			val built = ArrayList<SourceCommentListItem>(state.comments.size * 2)
			for (comment in state.comments) {
				val thread = repliesMap[comment.id]
				val isExpanded = thread?.isExpanded == true
				val isLoadingReplies = thread?.isLoading == true
				built.add(
					SourceCommentListItem.CommentItem(
						comment = comment,
						depth = 0,
						isSpoilerRevealed = comment.id in revealedSpoilerIds,
						isRepliesExpanded = isExpanded,
						isLoadingReplies = isLoadingReplies,
						isLiking = comment.id in likingCommentIds,
						formattedTime = formatTime(comment),
						capabilities = capabilities,
					),
				)
				if (isExpanded && thread != null) {
					for (reply in thread.replies) {
						built.add(
							SourceCommentListItem.CommentItem(
								comment = reply,
								depth = 1,
								isSpoilerRevealed = reply.id in revealedSpoilerIds,
								isRepliesExpanded = false,
								isLoadingReplies = false,
								isLiking = reply.id in likingCommentIds,
								formattedTime = formatTime(reply),
								capabilities = capabilities,
							),
						)
					}
					if (thread.hasNextPage) {
						built.add(
							SourceCommentListItem.LoadMoreRepliesItem(
								parentComment = comment,
								isLoading = thread.isLoading,
							),
						)
					}
				}
			}
			if (state.hasNextPage) {
				built.add(SourceCommentListItem.LoadMoreCommentsItem(isLoading = state.isLoadingMore))
			}
			state.copy(items = built)
		}
	}

	private fun mergeComments(existing: List<SourceComment>, incoming: List<SourceComment>): List<SourceComment> {
		if (incoming.isEmpty()) return existing
		val seen = existing.mapTo(HashSet(existing.size)) { it.id }
		val result = ArrayList<SourceComment>(existing.size + incoming.size)
		result.addAll(existing)
		for (item in incoming) {
			if (seen.add(item.id)) {
				result.add(item)
			}
		}
		return result
	}

	private fun formatTime(comment: SourceComment): CharSequence? {
		comment.createdAtText?.takeIf { it.isNotBlank() }?.let { return it }
		val epoch = comment.createdAtEpochMs?.takeIf { it > 0L } ?: return null
		return DateUtils.getRelativeTimeSpanString(
			epoch,
			System.currentTimeMillis(),
			DateUtils.MINUTE_IN_MILLIS,
			DateUtils.FORMAT_ABBREV_RELATIVE,
		)
	}

	private fun handleActionError(error: Throwable) {
		if (error is AuthRequiredException) {
			onAuthRequired.call(error.source)
		} else {
			errorEvent.call(error)
		}
	}

	companion object {
		const val ARG_MANGA = "manga"
		const val ARG_CHAPTER = "chapter"
	}
}
