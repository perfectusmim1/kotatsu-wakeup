package org.koitharu.kotatsu.settings.sources.accounts

import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.setTextColorAttr
import org.koitharu.kotatsu.databinding.ItemSourceAccountBinding

class SourceAccountsAdapter(
	listener: OnListItemClickListener<SourceAccountItem>,
) : BaseListAdapter<SourceAccountItem>() {

	init {
		delegatesManager.addDelegate(sourceAccountItemDelegate(listener))
	}
}

private fun sourceAccountItemDelegate(
	listener: OnListItemClickListener<SourceAccountItem>,
) = adapterDelegateViewBinding<SourceAccountItem, SourceAccountItem, ItemSourceAccountBinding>(
	{ layoutInflater, parent -> ItemSourceAccountBinding.inflate(layoutInflater, parent, false) },
) {

	binding.root.setOnClickListener { v ->
		listener.onItemClick(item, v)
	}
	binding.root.setOnLongClickListener { v ->
		listener.onItemLongClick(item, v)
	}

	bind {
		binding.textViewTitle.text = item.source.getTitle(context)
		binding.imageViewIcon.setImageAsync(item.source)
		binding.imageViewStatus.isVisible = item.isSignedIn
		when {
			item.isChecking -> {
				binding.textViewSummary.setText(R.string.loading_)
				binding.textViewSummary.setTextColorAttr(android.R.attr.textColorSecondary)
			}

			item.isSignedIn -> {
				binding.textViewSummary.text = if (!item.username.isNullOrBlank()) {
					context.getString(R.string.logged_in_as, item.username)
				} else {
					context.getString(R.string.source_account_signed_in)
				}
				binding.textViewSummary.setTextColorAttr(android.R.attr.textColorPrimary)
			}

			else -> {
				binding.textViewSummary.setText(R.string.source_account_not_signed_in)
				binding.textViewSummary.setTextColorAttr(android.R.attr.textColorSecondary)
			}
		}
	}
}
