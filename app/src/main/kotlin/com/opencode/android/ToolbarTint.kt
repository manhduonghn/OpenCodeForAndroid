package com.opencode.android

import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors

/**
 * The vector glyphs are plain white because the notification builder reuses a few of them and
 * small notification icons are alpha-only. A white icon is invisible on the light toolbar, so
 * each toolbar explicitly tints its navigation icon and every menu item with the current
 * `colorOnSurface` role. The tint is re-applied whenever a menu is inflated or a navigation icon
 * is swapped.
 */
internal fun MaterialToolbar.tintActionIcons() {
    val color = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorOnSurface,
        ContextCompat.getColor(context, R.color.oc_on_surface),
    )
    navigationIcon = navigationIcon?.mutate()?.apply { DrawableCompat.setTint(this, color) }
    for (index in 0 until menu.size()) {
        val item = menu.getItem(index)
        val icon = item.icon?.mutate() ?: continue
        DrawableCompat.setTint(icon, color)
        item.icon = icon
    }
}
