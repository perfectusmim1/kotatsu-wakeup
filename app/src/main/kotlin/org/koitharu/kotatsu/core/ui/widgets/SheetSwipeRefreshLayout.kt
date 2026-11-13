package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.view.children
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * [SwipeRefreshLayout] subclass safe for use inside a [BottomSheetBehavior] sheet.
 *
 * Stock [SwipeRefreshLayout] inherits [View.canScrollVertically] (`false`), causing
 * [BottomSheetBehavior] to treat nested scroll-up gestures as already at top and dismiss the
 * sheet instead of scrolling the child list back up.
 */
class SheetSwipeRefreshLayout @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : SwipeRefreshLayout(context, attrs) {

	private var hasScrolledContentInGesture = false

	override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
		when (ev.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				hasScrolledContentInGesture = canContentScrollVertically(-1)
			}

			MotionEvent.ACTION_MOVE -> {
				if (canContentScrollVertically(-1)) {
					hasScrolledContentInGesture = true
				}
			}
		}
		return try {
			super.dispatchTouchEvent(ev)
		} finally {
			when (ev.actionMasked) {
				MotionEvent.ACTION_MOVE -> {
					if (canContentScrollVertically(-1)) {
						hasScrolledContentInGesture = true
					}
				}

				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					hasScrolledContentInGesture = false
				}
			}
		}
	}

	override fun canChildScrollUp(): Boolean = canContentScrollVertically(-1)

	override fun canScrollVertically(direction: Int): Boolean {
		if (direction < 0 && hasScrolledContentInGesture) {
			return true
		}
		return canContentScrollVertically(direction)
	}

	override fun onDetachedFromWindow() {
		hasScrolledContentInGesture = false
		super.onDetachedFromWindow()
	}

	private fun canContentScrollVertically(direction: Int): Boolean =
		children.any { it.visibility != View.GONE && it.canScrollVertically(direction) }
}
