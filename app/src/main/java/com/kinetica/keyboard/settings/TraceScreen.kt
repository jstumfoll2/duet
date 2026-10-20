package com.kinetica.keyboard.settings

import android.content.Context
import android.content.Intent

/**
 * The DEV build's trace, practice and export screen, reached from inside the app.
 *
 * The screen lives in the debug source set and its manifest, so a release build has no such
 * activity and every way in is hidden there. Looked up by name rather than referenced, because
 * main code cannot see debug classes. It used to be a second launcher icon; one app with the
 * screen in Settings and on the start screen is the same thing without the second icon.
 */
object TraceScreen {

    const val CLASS_NAME = "com.kinetica.keyboard.ime.TraceActivity"

    /** The settings row that opens it, removed in a build without the screen. */
    const val PREF_KEY = "pref_traces_screen"

    /** An intent for the screen, or null in a build that has none. */
    fun intent(context: Context): Intent? {
        val intent = Intent().setClassName(context.packageName, CLASS_NAME)
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }
}
