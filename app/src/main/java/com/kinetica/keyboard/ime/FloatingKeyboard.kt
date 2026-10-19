package com.kinetica.keyboard.ime

import android.content.Context
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.settings.KeyboardConfig
import com.kinetica.keyboard.settings.Prefs
import com.kinetica.keyboard.ui.FloatPlacement
import com.kinetica.keyboard.ui.FloatingFrame
import com.kinetica.keyboard.ui.InputContainerView
import kotlin.math.roundToInt

/**
 * The floating keyboard's side of the service: docking or floating the input view, the handle
 * that moves it, and the insets that leave the app the whole screen.
 */
internal class FloatingKeyboard(private val context: Context) {

    /** The input view's root, which floats the container when the setting is on. */
    var frame: FloatingFrame? = null

    private val rect = Rect()
    private val origin = IntArray(2)

    /**
     * Docks or floats the keyboard. Floating, the handle moves it instead of resizing it, and
     * shows even when its height setting is zero, since it is the only way to move it.
     */
    fun apply(config: KeyboardConfig, container: InputContainerView, dpToPx: (Float) -> Int) {
        val frame = frame ?: return
        val on = config.floating
        frame.setFloating(
            on,
            FloatPlacement.widthFraction(config.floatWidthPct),
            config.floatLeftPct / 100f,
            dpToPx(config.floatLiftDp.toFloat()),
        )
        container.mover = if (on) mover else null
        val handleDp = if (on) maxOf(config.dragHandleDp, FLOAT_HANDLE_DP) else config.dragHandleDp
        container.setHandleHeight(dpToPx(handleDp.toFloat()))
    }

    private val mover = object : InputContainerView.Mover {
        override fun onMove(dx: Float, dy: Float) {
            frame?.moveBy(dx, dy)
        }

        override fun onMoveEnded() {
            val frame = frame ?: return
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt(Prefs.FLOAT_LEFT_PCT, (frame.leftFraction() * 100f).roundToInt())
                .putInt(Prefs.FLOAT_LIFT_DP, (frame.liftPx() / context.resources.displayMetrics.density).roundToInt())
                .apply()
        }
    }

    /**
     * Floating, the app keeps the whole screen: it is not resized for the keyboard, and only
     * the keyboard's own rectangle takes touches, so the empty frame around it passes them on.
     * Called after the service's own computation, which it leaves alone while docked.
     */
    fun computeInsets(outInsets: InputMethodService.Insets, inputViewShown: Boolean) {
        val frame = frame ?: return
        if (!frame.floating || !inputViewShown || frame.height <= 0) return
        frame.getLocationInWindow(origin)
        val bottom = origin[1] + frame.height
        outInsets.contentTopInsets = bottom
        outInsets.visibleTopInsets = bottom
        frame.touchableRect(rect)
        rect.offset(origin[0], origin[1])
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(rect)
    }

    private companion object {
        /** The floating keyboard's handle when the handle setting is smaller: a target to drag. */
        const val FLOAT_HANDLE_DP = 18
    }
}
