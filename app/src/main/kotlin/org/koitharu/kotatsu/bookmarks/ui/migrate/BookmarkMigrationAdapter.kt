package org.koitharu.kotatsu.bookmarks.ui.migrate

import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.lifecycle.LifecycleOwner
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.request.error
import coil3.request.fallback
import coil3.request.lifecycle
import coil3.request.placeholder
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.image.ChipIconTarget
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.enqueueWith
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.databinding.ItemBookmarkMigrationCandidateBinding
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.ListStateHolderListener
import org.koitharu.kotatsu.list.ui.adapter.emptyStateListAD
import org.koitharu.kotatsu.list.ui.adapter.errorStateListAD
import org.koitharu.kotatsu.list.ui.adapter.loadingStateAD
import org.koitharu.kotatsu.list.ui.model.ListModel
import com.google.android.material.R as materialR

class BookmarkMigrationAdapter(
	coil: ImageLoader,
	lifecycleOwner: LifecycleOwner,
	clickListener: OnListItemClickListener<BookmarkMigrationCandidateItem>,
	stateHolderListener: ListStateHolderListener,
) : BaseListAdapter<ListModel>() {

	init {
		addDelegate(
			ListItemType.MANGA_LIST_DETAILED,
			bookmarkMigrationCandidateAD(coil, lifecycleOwner, clickListener),
		)
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
		addDelegate(ListItemType.STATE_EMPTY, emptyStateListAD(stateHolderListener))
		addDelegate(ListItemType.STATE_ERROR, errorStateListAD(stateHolderListener))
	}
}

private fun bookmarkMigrationCandidateAD(
	coil: ImageLoader,
	lifecycleOwner: LifecycleOwner,
	listener: OnListItemClickListener<BookmarkMigrationCandidateItem>,
) = adapterDelegateViewBinding<BookmarkMigrationCandidateItem, ListModel, ItemBookmarkMigrationCandidateBinding>(
	{ inflater, parent -> ItemBookmarkMigrationCandidateBinding.inflate(inflater, parent, false) },
) {
	val colorGreen = ContextCompat.getColor(context, R.color.common_green)
	val colorYellow = ContextCompat.getColor(context, R.color.common_yellow)
	val colorRed = ContextCompat.getColor(context, R.color.common_red)
	val clickAdapter = AdapterDelegateClickListenerAdapter(this, listener)
	itemView.setOnClickListener(clickAdapter)
	binding.buttonMigrate.setOnClickListener(clickAdapter)

	bind {
		val candidate = item.candidate
		val manga = candidate.manga
		binding.textViewTitle.text = manga.title
		binding.buttonMigrate.isEnabled = candidate.totalChaptersCount > 0
		binding.textViewCompatibility.text = buildSpannedString {
			if (candidate.totalBookmarkedChapters > 0) {
				val matchColor = when {
					candidate.matchedBookmarkedChapters == candidate.totalBookmarkedChapters -> colorGreen
					candidate.matchedBookmarkedChapters > 0 -> colorYellow
					else -> colorRed
				}
				inSpans(ForegroundColorSpan(matchColor)) {
					append(
						context.getString(
							R.string.bookmark_migration_chapters_matched,
							candidate.matchedBookmarkedChapters,
							candidate.totalBookmarkedChapters,
						),
					)
				}
				append(" · ")
			}
			val chaptersLabel = if (candidate.totalChaptersCount > 0) {
				context.resources.getQuantityStringSafe(
					R.plurals.chapters,
					candidate.totalChaptersCount,
					candidate.totalChaptersCount,
				)
			} else {
				context.getString(R.string.bookmark_migration_unavailable_no_chapters)
			}
			append(chaptersLabel)
		}
		binding.chipSource.also { chip ->
			chip.text = manga.source.getTitle(chip.context)
			ImageRequest.Builder(context)
				.data(manga.source.faviconUri())
				.lifecycle(lifecycleOwner)
				.crossfade(false)
				.size(context.resources.getDimensionPixelSize(materialR.dimen.m3_chip_icon_size))
				.target(ChipIconTarget(chip))
				.placeholder(R.drawable.ic_web)
				.fallback(R.drawable.ic_web)
				.error(R.drawable.ic_web)
				.mangaSourceExtra(manga.source)
				.transformations(RoundedCornersTransformation(context.resources.getDimension(R.dimen.chip_icon_corner)))
				.allowRgb565(true)
				.enqueueWith(coil)
		}
		binding.imageViewCover.setImageAsync(manga.coverUrl, manga)
	}
}
