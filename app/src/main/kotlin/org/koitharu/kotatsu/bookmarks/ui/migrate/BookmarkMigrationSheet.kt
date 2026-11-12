package org.koitharu.kotatsu.bookmarks.ui.migrate

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.BookmarkMigrationCandidate
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.setProgressIcon
import org.koitharu.kotatsu.core.util.ext.withArgs
import org.koitharu.kotatsu.databinding.SheetBookmarkMigrationBinding
import org.koitharu.kotatsu.list.ui.adapter.ListStateHolderListener
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

@AndroidEntryPoint
class BookmarkMigrationSheet :
	BaseAdaptiveSheet<SheetBookmarkMigrationBinding>(),
	OnListItemClickListener<BookmarkMigrationCandidateItem>,
	ListStateHolderListener,
	View.OnClickListener {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by viewModels<BookmarkMigrationViewModel>()
	private var migrationAdapter: BookmarkMigrationAdapter? = null

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	): SheetBookmarkMigrationBinding {
		return SheetBookmarkMigrationBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetBookmarkMigrationBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		disableFitToContents()
		val adapter = BookmarkMigrationAdapter(
			coil = coil,
			lifecycleOwner = viewLifecycleOwner,
			clickListener = this,
			stateHolderListener = this,
		)
		migrationAdapter = adapter
		with(binding.recyclerView) {
			this.adapter = adapter
			addItemDecoration(TypedListSpacingDecoration(context, false))
		}
		binding.buttonRescan.setOnClickListener(this)
		val sourceManga = viewModel.sourceManga
		binding.textViewSeries.text = getString(
			R.string.download_summary_pattern,
			sourceManga.title,
			sourceManga.source.getTitle(requireContext()),
		)

		viewModel.items.observe(viewLifecycleOwner, adapter)
		viewModel.status.observe(viewLifecycleOwner, ::onStatusChanged)
		viewModel.isMigrating.observe(viewLifecycleOwner, ::onMigratingChanged)
		viewModel.onError.observeEvent(viewLifecycleOwner) { error ->
			Toast.makeText(requireContext(), error.getDisplayMessage(resources), Toast.LENGTH_LONG).show()
		}
		viewModel.onMigrated.observeEvent(viewLifecycleOwner) { target ->
			Toast.makeText(
				requireContext(),
				getString(R.string.bookmark_migration_success, target.source.getTitle(requireContext())),
				Toast.LENGTH_SHORT,
			).show()
			dismissAllowingStateLoss()
		}
	}

	override fun onDestroyView() {
		migrationAdapter = null
		super.onDestroyView()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val basePadding = v.resources.getDimensionPixelOffset(R.dimen.list_spacing_normal)
		viewBinding?.recyclerView?.updatePadding(
			bottom = basePadding + insets.getInsets(typeMask).bottom,
		)
		return insets.consume(v, typeMask, bottom = true)
	}

	override fun onClick(v: View) {
		if (v.id == R.id.button_rescan) {
			viewModel.rescan()
		}
	}

	override fun onItemClick(item: BookmarkMigrationCandidateItem, view: View) {
		val candidate = item.candidate
		if (candidate.totalChaptersCount <= 0 || viewModel.isMigrating.value) {
			return
		}
		confirmMigration(candidate)
	}

	override fun onRetryClick(error: Throwable) {
		viewModel.rescan()
	}

	override fun onEmptyActionClick() {
		viewModel.rescan()
	}

	private fun onStatusChanged(status: BookmarkMigrationStatus) {
		val binding = viewBinding ?: return
		binding.progressBar.isVisible = status.isSearching
		if (status.isSearching) {
			if (status.totalSources > 0) {
				binding.progressBar.isIndeterminate = false
				binding.progressBar.max = status.totalSources
				binding.progressBar.progress = status.completedSources.coerceIn(0, status.totalSources)
			} else {
				binding.progressBar.isIndeterminate = true
			}
		}
		binding.buttonRescan.isEnabled = !status.isSearching && !viewModel.isMigrating.value
		binding.textViewStatus.text = when {
			status.isSearching && status.totalSources == 0 -> getString(R.string.bookmark_migration_searching)
			status.totalSources > 0 -> getString(
				R.string.bookmark_migration_progress,
				status.completedSources,
				status.totalSources,
				status.resultsCount,
			)

			else -> ""
		}
	}

	private fun onMigratingChanged(isMigrating: Boolean) {
		val binding = viewBinding ?: return
		binding.buttonRescan.isEnabled = !isMigrating && !viewModel.status.value.isSearching
		if (isMigrating) {
			binding.buttonRescan.setProgressIcon()
		} else {
			binding.buttonRescan.icon = null
		}
	}

	private fun confirmMigration(candidate: BookmarkMigrationCandidate) {
		val context = requireContext()
		val sourceManga = viewModel.sourceManga
		val bookmarksCount = viewModel.bookmarksCount.value
		val oldSourceName = sourceManga.source.getTitle(context)
		val newSourceName = candidate.manga.source.getTitle(context)
		val message = if (candidate.matchedBookmarkedChapters > 0 || candidate.totalBookmarkedChapters == 0) {
			getString(
				R.string.bookmark_migration_confirm,
				bookmarksCount,
				sourceManga.title,
				oldSourceName,
				candidate.manga.title,
				newSourceName,
				candidate.matchedBookmarkedChapters,
				candidate.totalBookmarkedChapters,
			)
		} else {
			getString(
				R.string.bookmark_migration_confirm_no_overlap,
				bookmarksCount,
				sourceManga.title,
				oldSourceName,
				candidate.manga.title,
				newSourceName,
			)
		}
		buildAlertDialog(context, isCentered = true) {
			setTitle(R.string.migrate_bookmarks)
			setMessage(message)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.migrate) { _, _ ->
				viewModel.migrate(candidate)
			}
		}.show()
	}

	companion object {
		const val ARG_MANGA = "manga"
		private const val TAG = "BookmarkMigrationSheet"

		fun show(fragmentManager: FragmentManager, manga: Manga) {
			if (fragmentManager.findFragmentByTag(TAG) == null) {
				BookmarkMigrationSheet().withArgs(1) {
					putParcelable(ARG_MANGA, ParcelableManga(manga))
				}.show(fragmentManager, TAG)
			}
		}
	}
}
