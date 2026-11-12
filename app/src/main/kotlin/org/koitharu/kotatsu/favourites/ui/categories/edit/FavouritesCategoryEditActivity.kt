package org.koitharu.kotatsu.favourites.ui.categories.edit

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Filter
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.FavouriteIcon
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.util.DefaultTextWatcher
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getSerializableCompat
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.setChecked
import org.koitharu.kotatsu.core.util.ext.sortedByOrdinal
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.ActivityCategoryEditBinding
import org.koitharu.kotatsu.list.domain.ListSortOrder

@AndroidEntryPoint
class FavouritesCategoryEditActivity :
	BaseActivity<ActivityCategoryEditBinding>(),
	AdapterView.OnItemClickListener,
	View.OnClickListener,
	DefaultTextWatcher {

	private val viewModel by viewModels<FavouritesCategoryEditViewModel>()
	private var selectedSortOrder: ListSortOrder? = null
	private var selectedIcon: FavouriteIcon? = null
	private val sortOrders = ListSortOrder.FAVORITES.sortedByOrdinal()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityCategoryEditBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		initSortSpinner()
		initIconChips()
		savedInstanceState?.getSerializableCompat<FavouriteIcon>(KEY_ICON)?.let {
			selectedIcon = it
			selectIconChip(it)
		}
		viewBinding.buttonDone.setOnClickListener(this)
		viewBinding.editName.addTextChangedListener(this)
		afterTextChanged(viewBinding.editName.text)

		viewModel.onSaved.observeEvent(this) { finishAfterTransition() }
		viewModel.category.observe(this, ::onCategoryChanged)
		viewModel.isLoading.observe(this, ::onLoadingStateChanged)
		viewModel.onError.observeEvent(this, ::onError)
		viewModel.isTrackerEnabled.observe(this) {
			viewBinding.switchTracker.isVisible = it
		}
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.root.setPadding(
			barsInsets.left,
			barsInsets.top,
			barsInsets.right,
			barsInsets.bottom,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putSerializable(KEY_SORT_ORDER, selectedSortOrder)
		outState.putSerializable(KEY_ICON, selectedIcon)
	}

	override fun onRestoreInstanceState(savedInstanceState: Bundle) {
		super.onRestoreInstanceState(savedInstanceState)
		savedInstanceState.getSerializableCompat<ListSortOrder>(KEY_SORT_ORDER)?.let {
			selectedSortOrder = it
		}
		savedInstanceState.getSerializableCompat<FavouriteIcon>(KEY_ICON)?.let {
			selectedIcon = it
			selectIconChip(it)
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_done -> viewModel.save(
				title = viewBinding.editName.text?.toString()?.trim().orEmpty(),
				sortOrder = getSelectedSortOrder(),
				isTrackerEnabled = viewBinding.switchTracker.isChecked,
				isVisibleOnShelf = viewBinding.switchShelf.isChecked,
				icon = selectedIcon ?: FavouriteIcon.DEFAULT,
			)
		}
	}

	override fun afterTextChanged(s: Editable?) {
		viewBinding.buttonDone.isEnabled = !s.isNullOrBlank() && !viewModel.isLoading.value
	}

	override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
		selectedSortOrder = sortOrders.getOrNull(position)
	}

	private fun onCategoryChanged(category: FavouriteCategory?) {
		setTitle(if (category == null) R.string.create_category else R.string.edit_category)
		if (category != null && selectedIcon == null) {
			val icon = category.resolvedIcon
			selectedIcon = icon
			selectIconChip(icon)
		} else if (selectedIcon != null) {
			selectIconChip(selectedIcon ?: FavouriteIcon.DEFAULT)
		} else {
			selectIconChip(FavouriteIcon.DEFAULT)
		}
		if (selectedSortOrder != null) {
			return
		}
		viewBinding.editName.setText(category?.title)
		selectedSortOrder = category?.order
		val sortText = getString((category?.order ?: ListSortOrder.NEWEST).titleResId)
		viewBinding.editSort.setText(sortText, false)
		viewBinding.switchTracker.setChecked(category?.isTrackingEnabled != false, false)
		viewBinding.switchShelf.setChecked(category?.isVisibleInLibrary != false, false)
	}

	private fun onError(e: Throwable) {
		viewBinding.textViewError.text = e.getDisplayMessage(resources)
		viewBinding.textViewError.isVisible = true
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.buttonDone.isEnabled = !isLoading && !viewBinding.editName.text.isNullOrBlank()
		viewBinding.editSort.isEnabled = !isLoading
		viewBinding.editName.isEnabled = !isLoading
		viewBinding.switchTracker.isEnabled = !isLoading
		viewBinding.switchShelf.isEnabled = !isLoading
		for (child in viewBinding.chipGroupIcons.children) {
			child.isEnabled = !isLoading
		}
		if (isLoading) {
			viewBinding.textViewError.isVisible = false
		}
	}

	private fun initIconChips() {
		val chipGroup = viewBinding.chipGroupIcons
		chipGroup.removeAllViews()
		for (icon in FavouriteIcon.entries) {
			val chip = Chip(this).apply {
				setChipDrawable(ChipDrawable.createFromAttributes(context, null, 0, R.style.Widget_Kotatsu_Chip_Filter))
				id = View.generateViewId()
				setText(icon.labelRes)
				setChipIconResource(icon.iconRes)
				isChipIconVisible = true
				isCheckedIconVisible = false
				isCheckable = true
				setEnsureMinTouchTargetSize(false)
				tag = icon
			}
			chipGroup.addView(chip)
		}
		chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
			val checkedChip = checkedIds.firstOrNull()?.let { group.findViewById<Chip>(it) }
			(checkedChip?.tag as? FavouriteIcon)?.let { selectedIcon = it }
		}
	}

	private fun selectIconChip(icon: FavouriteIcon) {
		for (child in viewBinding.chipGroupIcons.children) {
			val chip = child as? Chip ?: continue
			if (chip.tag == icon) {
				if (!chip.isChecked) {
					chip.isChecked = true
				}
				break
			}
		}
	}

	private fun initSortSpinner() {
		val entries = sortOrders.map { getString(it.titleResId) }
		val adapter = SortAdapter(this, entries)
		viewBinding.editSort.setAdapter(adapter)
		viewBinding.editSort.onItemClickListener = this
	}

	private fun getSelectedSortOrder(): ListSortOrder {
		selectedSortOrder?.let { return it }
		val entries = sortOrders.map { getString(it.titleResId) }
		val index = entries.indexOf(viewBinding.editSort.text.toString())
		return sortOrders.getOrNull(index) ?: ListSortOrder.NEWEST
	}

	private class SortAdapter(
		context: Context,
		entries: List<String>,
	) : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, entries) {

		override fun getFilter(): Filter = EmptyFilter

		private object EmptyFilter : Filter() {
			override fun performFiltering(constraint: CharSequence?) = FilterResults()
			override fun publishResults(constraint: CharSequence?, results: FilterResults?) = Unit
		}
	}

	companion object {

		const val NO_ID = -1L
		private const val KEY_SORT_ORDER = "sort"
		private const val KEY_ICON = "icon"
	}
}
