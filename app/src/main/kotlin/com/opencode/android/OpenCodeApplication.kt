package com.opencode.android

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Applies the Material You dynamic color palette to every activity.
 *
 * On Android 12+ [DynamicColors] replaces the seed palette of [Theme.OpenCode] with the tones the
 * system derives from the user's wallpaper, so the app looks like the rest of the device. Older
 * releases keep the orange seed palette from `colors.xml` without any extra code.
 */
class OpenCodeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
