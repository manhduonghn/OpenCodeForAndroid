package com.opencode.android

import android.view.View

/**
 * Small, interruptible fade shared by the native full-screen overlays.
 *
 * The overlays are plain views above the retained WebView, so the animation only touches their
 * alpha; it never detaches or reloads the page below them.
 */
internal object OverlayMotion {

    private const val REVEAL_MS = 160L
    private const val CONCEAL_MS = 120L

    fun reveal(view: View) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(REVEAL_MS).start()
    }

    fun conceal(view: View) {
        view.animate().cancel()
        if (view.visibility != View.VISIBLE) return
        view.animate()
            .alpha(0f)
            .setDuration(CONCEAL_MS)
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
            }
            .start()
    }
}
