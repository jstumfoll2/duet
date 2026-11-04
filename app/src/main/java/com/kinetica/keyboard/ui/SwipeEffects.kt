package com.kinetica.keyboard.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * How the swipe trail is drawn. Every style keeps the trail's colour, its fade and its life; only
 * the stroke changes. No Android imports, so the shapes are testable without a device.
 */
enum class TrailStyle(val value: String) {
    /** One stroke that thins as it fades: the trail Duet always drew. */
    CLASSIC("classic"),

    /** A wide soft halo under a bright core, like neon. */
    GLOW("glow"),

    /** A row of dots that shrink as they fade. */
    DOTS("dots"),

    /** The classic stroke shedding sparks behind the thumb. */
    COMET("comet"),
    ;

    companion object {
        /** An unknown value is the classic trail, as before the choice existed. */
        fun fromPref(value: String?): TrailStyle = entries.firstOrNull { it.value == value } ?: CLASSIC
    }
}

/**
 * What a key does when a thumb lands on it or a swipe crosses it. Each runs for [lifeMs] and is
 * drawn in the trail's hue, except [BURST], the white flash Duet always drew.
 */
enum class PressEffect(val value: String, val lifeMs: Long) {
    BURST("burst", 80L),
    RIPPLE("ripple", 320L),
    SPARKS("sparks", 380L),
    PULSE("pulse", 220L),
    NONE("none", 0L),
    ;

    companion object {
        /** An unknown value is the burst, as before the choice existed. */
        fun fromPref(value: String?): PressEffect = entries.firstOrNull { it.value == value } ?: BURST
    }
}

/** The shapes behind [TrailStyle] and [PressEffect], as plain numbers. Sizes are in dp. */
object EffectShapes {

    /** How many sparks a key press throws. */
    const val PRESS_SPARKS = 8

    /** How far a press spark travels by the end of its life. */
    const val PRESS_SPARK_REACH_DP = 34f

    /** A comet sheds a spark every this many trail points (~8 ms each). */
    const val COMET_EVERY_POINTS = 3

    /** Fraction of life spent, clamped to [0, 1]. */
    fun progress(ageMs: Long, lifeMs: Long): Float =
        if (lifeMs <= 0L) 1f else (ageMs.coerceAtLeast(0L) / lifeMs.toFloat()).coerceIn(0f, 1f)

    /** Fast out, slow in: sparks and ripples rush out and settle. */
    fun easeOut(f: Float): Float {
        val c = f.coerceIn(0f, 1f)
        return 1f - (1f - c) * (1f - c)
    }

    /** Radius of a ripple ring at progress [f]. */
    fun rippleRadiusDp(f: Float): Float = 8f + 30f * easeOut(f)

    /** Stroke of a ripple ring: thick at the start, a hairline at the end. */
    fun rippleStrokeDp(f: Float): Float = 1f + 3f * (1f - f.coerceIn(0f, 1f))

    /** Radius of a pulse's glow, wider than a key's half-width so it reads around the thumb. */
    fun pulseRadiusDp(f: Float): Float = 18f + 10f * easeOut(f)

    /** Size of a spark's dot. */
    fun sparkSizeDp(f: Float): Float = 0.8f + 2.4f * (1f - f.coerceIn(0f, 1f))

    /**
     * Direction of press spark [i] of [PRESS_SPARKS], in radians. [seed] turns the whole star so
     * two presses never look stamped; the same seed gives the same star.
     */
    fun sparkAngle(i: Int, seed: Long): Double {
        val turn = ((seed % 360L + 360L) % 360L) * PI / 180.0
        return turn + i * 2.0 * PI / PRESS_SPARKS
    }

    /** Offset of a spark thrown at [angle] with [reachDp], at progress [f], as (dx, dy) in dp. */
    fun sparkOffsetDp(angle: Double, reachDp: Float, f: Float): Pair<Float, Float> {
        val d = reachDp * easeOut(f)
        return (cos(angle) * d).toFloat() to (sin(angle) * d).toFloat()
    }

    /** Halo and core widths of a glow trail at fade [f] (1 = fresh). */
    fun glowHaloDp(f: Float): Float = 8f + 12f * f.coerceIn(0f, 1f)
    fun glowCoreDp(f: Float): Float = 1.5f + 3.5f * f.coerceIn(0f, 1f)

    /** Radius of a dotted trail's dot at fade [f]. */
    fun dotRadiusDp(f: Float): Float = 1.5f + 4f * f.coerceIn(0f, 1f)
}
