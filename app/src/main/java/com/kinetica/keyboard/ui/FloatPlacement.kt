package com.kinetica.keyboard.ui

/** The floating keyboard's arithmetic, pure so the JVM tests reach it. */
object FloatPlacement {

    /** Narrowest floating keyboard, as a fraction of the screen: two thumbs need the room. */
    const val MIN_WIDTH = 0.5f

    /** Screen height kept free above the floating keyboard, for the status bar and the field. */
    const val TOP_ROOM_FRACTION = 0.15f

    /** Highest lift that keeps a keyboard [contentPx] tall on a [screenPx] screen. */
    fun maxLift(screenPx: Int, contentPx: Int): Int =
        (screenPx - contentPx - (screenPx * TOP_ROOM_FRACTION).toInt()).coerceAtLeast(0)

    fun clampLift(lift: Int, max: Int): Int = lift.coerceIn(0, max.coerceAtLeast(0))

    /** A stored percent as the fraction [FloatingFrame.setFloating] takes. */
    fun widthFraction(pct: Int): Float = (pct / 100f).coerceIn(MIN_WIDTH, 1f)
}
