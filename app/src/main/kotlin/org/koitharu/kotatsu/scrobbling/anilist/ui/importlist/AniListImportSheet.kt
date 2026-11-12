package org.koitharu.kotatsu.scrobbling.anilist.ui.importlist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.databinding.SheetAnilistImportBinding
import org.koitharu.kotatsu.scrobbling.anilist.ui.importlist.AniListImportViewModel.ImportMode
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import java.util.EnumMap

@AndroidEntryPoint
class AniListImportSheet :
	BaseAdaptiveSheet<SheetAnilistImportBinding>(),
	View.OnClickListener {

	private val viewModel by viewModels<AniListImportViewModel>()
	private var lastDropdownLabels: List<String> = emptyList()
	private var lastSelectedIndex: Int = -1

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	): SheetAnilistImportBinding {
		return SheetAnilistImportBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetAnilistImportBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)

		binding.buttonStart.setOnClickListener(this)
		binding.buttonCancel.setOnClickListener(this)

		binding.toggleGroupMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (!isChecked) return@addOnButtonCheckedListener
			when (checkedId) {
				R.id.button_mode_status -> viewModel.setMode(ImportMode.BY_STATUS)
				R.id.button_mode_category -> viewModel.setMode(ImportMode.SINGLE_CATEGORY)
			}
		}

		binding.editCategorySelect.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
			val categories = viewModel.uiState.value.categories
			if (position in categories.indices) {
				viewModel.selectExistingCategory(categories[position].id)
			} else {
				viewModel.selectNewCategory()
			}
		}

		viewModel.uiState.observe(viewLifecycleOwner, ::renderState)
		viewModel.onImportError.observeEvent(viewLifecycleOwner) { error ->
			Toast.makeText(
				requireContext(),
				error.getDisplayMessage(resources),
				Toast.LENGTH_LONG,
			).show()
		}
	}

	override fun onDestroyView() {
		lastDropdownLabels = emptyList()
		lastSelectedIndex = -1
		super.onDestroyView()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
		val basePadding = v.resources.getDimensionPixelOffset(R.dimen.margin_normal)
		viewBinding?.scrollView?.updatePadding(
			bottom = basePadding + insets.getInsets(typeMask).bottom,
		)
		return insets.consume(v, typeMask, bottom = true)
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_start -> startImport()
			R.id.button_cancel -> {
				if (viewModel.uiState.value.isRunning) {
					viewModel.cancelImport()
				} else {
					dismissAllowingStateLoss()
				}
			}
		}
	}

	private fun startImport() {
		val binding = viewBinding ?: return
		val statusStrings = resources.getStringArray(R.array.scrobbling_statuses)
		val statusMap = EnumMap<ScrobblingStatus, String>(ScrobblingStatus::class.java)
		for (status in ScrobblingStatus.entries) {
			val label = statusStrings.getOrNull(status.ordinal)
			if (!label.isNullOrBlank()) {
				statusMap[status] = label
			}
		}
		val newName = binding.editTextCategoryName.text?.toString().orEmpty()
		viewModel.startImport(
			newCategoryName = newName,
			defaultNewCategoryName = getString(R.string.anilist_import_new_category_default),
			statusTitles = statusMap,
		)
	}

	private fun renderState(state: AniListImportViewModel.UiState) {
		val binding = viewBinding ?: return

		val expectedToggleId = when (state.mode) {
			ImportMode.BY_STATUS -> R.id.button_mode_status
			ImportMode.SINGLE_CATEGORY -> R.id.button_mode_category
		}
		if (binding.toggleGroupMode.checkedButtonId != expectedToggleId) {
			binding.toggleGroupMode.check(expectedToggleId)
		}

		binding.buttonModeStatus.isEnabled = !state.isRunning
		binding.buttonModeCategory.isEnabled = !state.isRunning
		binding.inputLayoutCategorySelect.isEnabled = !state.isRunning
		binding.editCategorySelect.isEnabled = !state.isRunning
		binding.inputLayoutCategoryName.isEnabled = !state.isRunning
		binding.editTextCategoryName.isEnabled = !state.isRunning

		binding.layoutCategoryPicker.isVisible = state.mode == ImportMode.SINGLE_CATEGORY
		val labels = buildList(state.categories.size + 1) {
			state.categories.forEach { add(it.title) }
			add(getString(R.string.anilist_import_new_category))
		}
		val selectedIdx = if (state.isNewCategorySelected || state.selectedCategoryId == null) {
			labels.lastIndex
		} else {
			state.categories.indexOfFirst { it.id == state.selectedCategoryId }
				.takeIf { it >= 0 } ?: labels.lastIndex
		}
		if (labels != lastDropdownLabels) {
			lastDropdownLabels = labels
			lastSelectedIndex = -1
			val adapter = ArrayAdapter(
				requireContext(),
				android.R.layout.simple_list_item_1,
				labels,
			)
			binding.editCategorySelect.setAdapter(adapter)
		}
		if (selectedIdx != lastSelectedIndex && selectedIdx in labels.indices) {
			lastSelectedIndex = selectedIdx
			binding.editCategorySelect.setText(labels[selectedIdx], false)
		}

		val showNewName = state.mode == ImportMode.SINGLE_CATEGORY &&
			(state.isNewCategorySelected || state.selectedCategoryId == null)
		binding.inputLayoutCategoryName.isVisible = showNewName
		if (showNewName && binding.editTextCategoryName.text.isNullOrBlank()) {
			binding.editTextCategoryName.setText(R.string.anilist_import_new_category_default)
		}

		val progress = state.progress
		val inlineError = state.inlineErrorRes
		binding.layoutProgress.isVisible = state.isRunning || progress != null || inlineError != null

		if (inlineError != null) {
			binding.progressBar.isVisible = false
			binding.textViewStatus.setText(inlineError)
			binding.textViewCounts.isVisible = false
			binding.layoutUnmatched.isVisible = false
		} else if (progress != null) {
			binding.progressBar.isVisible = state.isRunning || !progress.isCompleted
			if (progress.isFetchingList) {
				binding.progressBar.isIndeterminate = true
				binding.textViewStatus.setText(R.string.anilist_import_fetching)
				binding.textViewCounts.isVisible = false
			} else {
				binding.progressBar.isIndeterminate = false
				binding.progressBar.max = progress.totalCount.coerceAtLeast(1)
				binding.progressBar.setProgressCompat(progress.currentIndex, true)
				binding.textViewStatus.text = when {
					progress.isCompleted && progress.totalCount == 0 -> getString(R.string.anilist_import_empty)
					progress.isCompleted -> getString(
						R.string.anilist_import_completed_status,
						progress.currentIndex,
						progress.totalCount,
					)
					else -> getString(
						R.string.anilist_import_progress_status,
						progress.currentIndex,
						progress.totalCount,
						progress.currentTitle.orEmpty(),
					)
				}
				binding.textViewCounts.isVisible = progress.totalCount > 0
				if (progress.totalCount > 0) {
					binding.textViewCounts.text = getString(
						R.string.anilist_import_counts,
						progress.importedCount,
						progress.existingCount,
						progress.unmatchedTitles.size,
					)
				}
			}

			val hasUnmatched = progress.unmatchedTitles.isNotEmpty()
			binding.layoutUnmatched.isVisible = hasUnmatched
			if (hasUnmatched) {
				binding.textViewUnmatched.text = progress.unmatchedTitles.joinToString("\n")
			}
		}

		binding.buttonCancel.setText(if (state.isRunning) android.R.string.cancel else R.string.close)
		binding.buttonStart.isEnabled = !state.isRunning
		binding.buttonStart.setText(
			if (progress?.isCompleted == true) {
				R.string.anilist_import_retry
			} else {
				R.string.anilist_import_start
			},
		)
	}

	companion object {
		const val TAG = "AniListImportSheet"
	}
}
