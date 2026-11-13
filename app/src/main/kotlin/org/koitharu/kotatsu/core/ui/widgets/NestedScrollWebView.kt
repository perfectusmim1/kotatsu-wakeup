package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView

/**
 * [WebView] that prevents ancestor sheet/scroll containers from intercepting vertical or
 * nested-iframe touch drags while the user is interacting with web content.
 */
class NestedScrollWebView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = android.R.attr.webViewStyle,
) : WebView(context, attrs, defStyleAttr) {

	override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
		updateParentDisallowIntercept(ev.actionMasked)
		return try {
			super.dispatchTouchEvent(ev)
		} finally {
			// Re-assert after Chromium dispatch so outer BottomSheetBehavior/SideSheetBehavior
			// does not snatch mid-drag gestures when outer scrollY == 0 (e.g. iframe scroll).
			updateParentDisallowIntercept(ev.actionMasked)
		}
	}

	override fun onDetachedFromWindow() {
		parent?.requestDisallowInterceptTouchEvent(false)
		super.onDetachedFromWindow()
	}

	private fun updateParentDisallowIntercept(actionMasked: Int) {
		when (actionMasked) {
			MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
				parent?.requestDisallowInterceptTouchEvent(true)
			}

			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				parent?.requestDisallowInterceptTouchEvent(false)
			}
		}
	}
}
