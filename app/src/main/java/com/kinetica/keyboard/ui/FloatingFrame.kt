package com.kinetica.keyboard.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout

/**
 * The input view's root. Docked, it is just the keyboard. Floating, the keyboard is narrower
 * and sits where the handle left it, lifted off the bottom; the rest of the frame is empty,
 * drawn by nothing and handed to the app below: the service reports [touchableRect] as its
 * only touchable region.
 */
class FloatingFrame(context: Context, val content: View) : FrameLayout(context) {

    var floating = false
        private set

    /** Width of the floating keyboard as a fraction of the frame. */
    private var widthFraction = 1f

    /** Where the keyboard sits across the free width, 0 at the left edge and 1 at the right. */
    private var leftFraction = 0.5f

    /** Height above the bottom, in px, before clamping to the room the screen has. */
    private var liftPx = 0

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Docks the keyboard, or floats it at the stored place. */
    fun setFloating(on: Boolean, widthFraction: Float, leftFraction: Float, liftPx: Int) {
        floating = on
        this.widthFraction = widthFraction.coerceIn(FloatPlacement.MIN_WIDTH, 1f)
        this.leftFraction = leftFraction.coerceIn(0f, 1f)
        this.liftPx = liftPx.coerceAtLeast(0)
        place()
    }

    /** Moves the floating keyboard by a drag's travel since the last call, in px. */
    fun moveBy(dx: Float, dy: Float) {
        if (!floating || width <= 0) return
        val free = width - content.width
        if (free > 0) leftFraction = ((leftFraction * free + dx) / free).coerceIn(0f, 1f)
        liftPx = FloatPlacement.clampLift((liftPx - dy).toInt(), maxLift())
        place()
    }

    /** The place the keyboard sits now, for the service to store. */
    fun leftFraction(): Float = leftFraction

    fun liftPx(): Int = liftPx

    /** The keyboard's rectangle in the frame's coordinates. */
    fun touchableRect(out: Rect) {
        out.set(content.left, content.top, content.right, content.bottom)
    }

    private fun maxLift(): Int {
        val screen = resources.displayMetrics.heightPixels
        return FloatPlacement.maxLift(screen, content.height)
    }

    private fun place() {
        val lp = content.layoutParams as LayoutParams
        if (!floating) {
            lp.width = LayoutParams.MATCH_PARENT
            lp.leftMargin = 0
            lp.bottomMargin = 0
        } else {
            val w = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val kw = (w * widthFraction).toInt()
            lp.width = kw
            lp.leftMargin = ((w - kw) * leftFraction).toInt()
            lp.bottomMargin = if (content.height > 0) FloatPlacement.clampLift(liftPx, maxLift()) else liftPx
        }
        content.layoutParams = lp
        requestLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A rotation changes the width the fractions are of.
        if (floating && w != oldw) post { place() }
    }
}
