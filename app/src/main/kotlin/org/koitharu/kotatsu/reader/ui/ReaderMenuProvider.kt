package org.koitharu.kotatsu.reader.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuItemCompat
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import androidx.appcompat.R as appcompatR

class ReaderMenuProvider(
	private val context: Context,
	private val viewModel: ReaderViewModel,
	private val onHiddenImages: () -> Unit,
	private val onTranslateClick: () -> Unit,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_reader, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		super.onPrepareMenu(menu)
		val translateItem = menu.findItem(R.id.action_translate) ?: return
		val enabled = viewModel.isTranslationEnabled.value
		translateItem.isChecked = enabled
		translateItem.setTitle(if (enabled) R.string.translation_disable else R.string.translation_enable)
		val tint = if (enabled) {
			ColorStateList.valueOf(context.getThemeColor(appcompatR.attr.colorPrimary))
		} else {
			ColorStateList.valueOf(context.getThemeColor(appcompatR.attr.colorControlNormal))
		}
		MenuItemCompat.setIconTintList(translateItem, tint)
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		return when (menuItem.itemId) {
			R.id.action_info -> {
				// TODO
				true
			}

			R.id.action_translate -> {
				onTranslateClick()
				true
			}

			// Right where a mistake is noticed: the page it went from is what brings it to mind
			R.id.action_hidden_images -> {
				onHiddenImages()
				true
			}

			else -> false
		}
	}
}
