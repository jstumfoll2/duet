package com.kinetica.keyboard.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * What a key shows when a thumb lands on it or a swipe crosses it ([PressEffect]): the white
 * burst (radius 12 -> 28 dp, gone in 80 ms), a ripple ring, a star of sparks or a soft pulse,
 * the last three in the trail's hue. Cheap shapes; a handful are ever alive.
 */
class BurstRenderer(private val density: Float) {

    private class Burst(val x: Float, val y: Float, val t0: Long, val hue: Float, val effect: PressEffect)

    private val bursts = ArrayList<Burst>(8)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hsv = FloatArray(3)

    /** The effect new presses get; one already running keeps its own. */
    var effect = PressEffect.BURST

    /** Saturation and value the coloured effects use, the trail's. */
    var saturation = 0.85f
    var value = 1f

    fun spawn(x: Float, y: Float, t: Long, hue: Float = 0f) {
        if (effect == PressEffect.NONE) return
        bursts.add(Burst(x, y, t, hue, effect))
    }

    /** Drops finished bursts; returns true while any is still animating. */
    fun prune(now: Long): Boolean {
        bursts.removeAll { now - it.t0 >= it.effect.lifeMs }
        return bursts.isNotEmpty()
    }

    fun clear() = bursts.clear()

    private fun hueColor(hue: Float, alpha: Int, sat: Float = saturation): Int {
        hsv[0] = hue
        hsv[1] = sat
        hsv[2] = value
        return Color.HSVToColor(alpha.coerceIn(0, 255), hsv)
    }

    fun draw(canvas: Canvas, now: Long) {
        for (b in bursts) {
            val f = EffectShapes.progress(now - b.t0, b.effect.lifeMs)
            if (f >= 1f) continue
            when (b.effect) {
                PressEffect.BURST -> {
                    fill.color = Color.WHITE
                    fill.alpha = (255 * (1f - f)).toInt()
                    canvas.drawCircle(b.x, b.y, (12f + 16f * f) * density, fill)
                }
                PressEffect.RIPPLE -> {
                    ring.color = hueColor(b.hue, (230 * (1f - f)).toInt())
                    ring.strokeWidth = EffectShapes.rippleStrokeDp(f) * density
                    canvas.drawCircle(b.x, b.y, EffectShapes.rippleRadiusDp(f) * density, ring)
                }
                PressEffect.SPARKS -> {
                    val alpha = (255 * (1f - f * f)).toInt()
                    val r = EffectShapes.sparkSizeDp(f) * density
                    for (i in 0 until EffectShapes.PRESS_SPARKS) {
                        val angle = EffectShapes.sparkAngle(i, b.t0)
                        val (dx, dy) = EffectShapes.sparkOffsetDp(angle, EffectShapes.PRESS_SPARK_REACH_DP, f)
                        // Every other spark is whiter, so the star glitters instead of reading flat.
                        fill.color = hueColor(b.hue + i * 12f, alpha, if (i % 2 == 0) saturation else saturation * 0.35f)
                        canvas.drawCircle(b.x + dx * density, b.y + dy * density, r, fill)
                    }
                }
                PressEffect.PULSE -> {
                    fill.color = hueColor(b.hue, (120 * (1f - f)).toInt())
                    canvas.drawCircle(b.x, b.y, EffectShapes.pulseRadiusDp(f) * density, fill)
                    fill.color = hueColor(b.hue, (200 * (1f - f)).toInt(), saturation * 0.4f)
                    canvas.drawCircle(b.x, b.y, EffectShapes.pulseRadiusDp(f) * 0.45f * density, fill)
                }
                PressEffect.NONE -> Unit
            }
        }
    }
}
