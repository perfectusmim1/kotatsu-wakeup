package org.koitharu.kotatsu.details.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.getThemeColorStateList
import org.koitharu.kotatsu.core.util.ext.getThemeResId
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemChapterBinding
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.list.ui.model.ListModel
import com.google.android.material.R as materialR

fun chapterListItemAD(
	clickListener: OnListItemClickListener<ChapterListItem>,
) = adapterDelegateViewBinding<ChapterListItem, ListModel, ItemChapterBinding>(
	viewBinding = { inflater, parent -> ItemChapterBinding.inflate(inflater, parent, false) },
	on = { item, _, _ -> item is ChapterListItem && !item.isGrid },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)
	val isExpressive = context.getThemeResId(R.attr.detailsChapterListItemStyle, 0) ==
		R.style.Widget_Kotatsu_Details_ChapterCard_Expressive
	val titleTypeface = binding.textViewTitle.typeface
	val descriptionTypeface = binding.textViewDescription.typeface

	val colorPrimaryContainer: Int
	val colorOnPrimaryContainer: Int
	val colorSecondaryContainer: Int
	val colorOnSecondaryContainer: Int
	val colorSurfaceContainer: Int
	val colorOnSurface: Int
	val colorSurfaceContainerLow: Int
	val colorOnSurfaceVariant: Int

	val textColorPrimary: ColorStateList?
	val textColorOutline: ColorStateList?
	val textColorHint: ColorStateList?
	val drawableCurrent: Drawable?
	val drawableNew: Drawable?
	var currentDrawableStart: Drawable? = binding.textViewTitle.drawableStart

	if (isExpressive) {
		colorPrimaryContainer = context.getThemeColor(materialR.attr.colorPrimaryContainer)
		colorOnPrimaryContainer = context.getThemeColor(materialR.attr.colorOnPrimaryContainer)
		colorSecondaryContainer = context.getThemeColor(materialR.attr.colorSecondaryContainer)
		colorOnSecondaryContainer = context.getThemeColor(materialR.attr.colorOnSecondaryContainer)
		colorSurfaceContainer = context.getThemeColor(materialR.attr.colorSurfaceContainer)
		colorOnSurface = context.getThemeColor(materialR.attr.colorOnSurface)
		colorSurfaceContainerLow = context.getThemeColor(materialR.attr.colorSurfaceContainerLow)
		colorOnSurfaceVariant = context.getThemeColor(materialR.attr.colorOnSurfaceVariant)
		textColorPrimary = null
		textColorOutline = null
		textColorHint = null
		drawableCurrent = null
		drawableNew = null
		if (currentDrawableStart != null) {
			currentDrawableStart = null
			binding.textViewTitle.drawableStart = null
		}
	} else {
		colorPrimaryContainer = 0
		colorOnPrimaryContainer = 0
		colorSecondaryContainer = 0
		colorOnSecondaryContainer = 0
		colorSurfaceContainer = 0
		colorOnSurface = 0
		colorSurfaceContainerLow = 0
		colorOnSurfaceVariant = 0
		textColorPrimary = context.getThemeColorStateList(android.R.attr.textColorPrimary)
		textColorOutline = context.getThemeColorStateList(materialR.attr.colorOutline)
		textColorHint = context.getThemeColorStateList(android.R.attr.textColorHint)
		drawableCurrent = ContextCompat.getDrawable(context, R.drawable.ic_current_chapter)
		drawableNew = ContextCompat.getDrawable(context, R.drawable.ic_new)
		binding.root.setCardBackgroundColor(Color.TRANSPARENT)
		binding.imageViewNew.isVisible = false
	}

	bind {
		binding.textViewTitle.text = item.getTitle(context.resources)
		binding.textViewDescription.textAndVisible = item.description
		if (isExpressive) {
			val containerColor: Int
			val contentColor: Int
			val supportingColor: Int
			when {
				item.isCurrent -> {
					containerColor = colorPrimaryContainer
					contentColor = colorOnPrimaryContainer
					supportingColor = colorOnPrimaryContainer
				}

				item.isUnread && item.isNew -> {
					containerColor = colorSecondaryContainer
					contentColor = colorOnSecondaryContainer
					supportingColor = colorOnSecondaryContainer
				}

				item.isUnread -> {
					containerColor = colorSurfaceContainer
					contentColor = colorOnSurface
					supportingColor = colorOnSurfaceVariant
				}

				else -> {
					containerColor = colorSurfaceContainerLow
					contentColor = colorOnSurfaceVariant
					supportingColor = colorOnSurfaceVariant
				}
			}
			binding.root.setCardBackgroundColor(containerColor)
			binding.textViewTitle.setTextColor(contentColor)
			binding.textViewDescription.setTextColor(supportingColor)
			binding.textViewTitle.setTypeface(
				titleTypeface,
				if (item.isCurrent || item.isNew) Typeface.BOLD else Typeface.NORMAL,
			)
			binding.textViewDescription.setTypeface(
				descriptionTypeface,
				if (item.isCurrent) Typeface.BOLD else Typeface.NORMAL,
			)
			binding.imageViewNew.isVisible = item.isUnread && item.isNew
		} else {
			val targetDrawable: Drawable?
			val titleColor: ColorStateList?
			val descColor: ColorStateList?
			val titleStyle: Int
			val descStyle: Int
			when {
				item.isCurrent -> {
					targetDrawable = drawableCurrent
					titleColor = textColorPrimary
					descColor = textColorPrimary
					titleStyle = Typeface.BOLD
					descStyle = Typeface.BOLD
				}

				item.isUnread -> {
					targetDrawable = if (item.isNew) drawableNew else null
					titleColor = textColorPrimary
					descColor = textColorOutline
					titleStyle = Typeface.NORMAL
					descStyle = Typeface.NORMAL
				}

				else -> {
					targetDrawable = null
					titleColor = textColorHint
					descColor = textColorHint
					titleStyle = Typeface.NORMAL
					descStyle = Typeface.NORMAL
				}
			}
			if (currentDrawableStart !== targetDrawable) {
				currentDrawableStart = targetDrawable
				binding.textViewTitle.drawableStart = targetDrawable
			}
			if (titleColor != null) {
				binding.textViewTitle.setTextColor(titleColor)
			}
			if (descColor != null) {
				binding.textViewDescription.setTextColor(descColor)
			}
			binding.textViewTitle.setTypeface(titleTypeface, titleStyle)
			binding.textViewDescription.setTypeface(descriptionTypeface, descStyle)
		}
		binding.imageViewBookmarked.isVisible = item.isBookmarked
		binding.imageViewDownloaded.isVisible = item.isDownloaded
	}
}
