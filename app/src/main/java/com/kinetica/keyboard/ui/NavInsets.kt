package com.kinetica.keyboard.ui

import android.os.Build
import android.view.WindowInsets

/**
 * The navigation-bar inset under the keyboard (#186). From targetSdk 35 on Android 15 the input
 * window is edge-to-edge: it reaches under the navigation bar and the system ignores
 * `window.navigationBarColor`, so the keyboard pads itself by the bar's inset and paints the
 * band with its own background.
 */
object NavInsets {

    /** True when the window is edge-to-edge: Android 15+ and this app targets 35+. */
    fun edgeToEdge(sdk: Int, targetSdk: Int): Boolean =
        sdk >= VANILLA_ICE_CREAM && targetSdk >= VANILLA_ICE_CREAM

    /** The gap below the keys: the setting plus the bar's inset, never negative. */
    fun gap(settingPx: Int, insetPx: Int): Int = settingPx.coerceAtLeast(0) + insetPx.coerceAtLeast(0)

    /** The bottom navigation-bar inset of [insets], zero before Android 11. */
    fun bottom(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            0
        }

    private const val VANILLA_ICE_CREAM = 35
}
