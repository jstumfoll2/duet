package com.kinetica.keyboard.ui

import java.lang.ref.WeakReference

/**
 * The keyboard and suggestion bar most recently attached to a window, held weakly.
 *
 * Read only by the instrumented screenshot tour (androidTest `SiteMediaTour`), which runs in the
 * app's process and needs to know where keys are on screen to inject touches. Nothing in the app
 * reads it, and holding a weak reference keeps no view alive.
 */
object ViewProbe {
    @Volatile
    var keyboard: WeakReference<KeyboardView>? = null
        internal set

    @Volatile
    var bar: WeakReference<SuggestionBarView>? = null
        internal set
}
