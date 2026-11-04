package com.kinetica.keyboard.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * The sparks a [TrailStyle.COMET] trail sheds: each drifts away from where the thumb was and
 * fades. Pooled and capped, so a long swipe allocates nothing after its first sparks.
 */
class SparkRenderer(private val density: Float) {

    private class Spark(var x: Float, var y: Float, var dx: Float, var dy: Float, var t0: Long, var hue: Float)

    private val live = ArrayList<Spark>(MAX_SPARKS)
    private val pool = ArrayList<Spark>(MAX_SPARKS)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hsv = FloatArray(3)
    private var count = 0L

    var saturation = 0.85f
    var value = 1f

    /** One spark from ([x], [y]) at [t], in [hue]; its direction turns with every spark shed. */
    fun emit(x: Float, y: Float, t: Long, hue: Float) {
        if (live.size >= MAX_SPARKS) return
        val angle = EffectShapes.sparkAngle((count % EffectShapes.PRESS_SPARKS).toInt(), count * 47L)
        count++
        val (dx, dy) = EffectShapes.sparkOffsetDp(angle, REACH_DP, 1f)
        val s = if (pool.isEmpty()) Spark(x, y, dx, dy, t, hue) else pool.removeAt(pool.size - 1).also {
            it.x = x; it.y = y; it.dx = dx; it.dy = dy; it.t0 = t; it.hue = hue
        }
        live.add(s)
    }

    fun prune(now: Long): Boolean {
        val it = live.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (now - s.t0 >= LIFE_MS) {
                it.remove()
                if (pool.size < MAX_SPARKS) pool.add(s)
            }
        }
        return live.isNotEmpty()
    }

    fun clear() {
        pool.addAll(live.take(MAX_SPARKS - pool.size))
        live.clear()
    }

    fun draw(canvas: Canvas, now: Long) {
        for (s in live) {
            val f = EffectShapes.progress(now - s.t0, LIFE_MS)
            if (f >= 1f) continue
            val e = EffectShapes.easeOut(f)
            hsv[0] = s.hue
            hsv[1] = saturation * 0.5f
            hsv[2] = value
            paint.color = Color.HSVToColor((255 * (1f - f)).toInt(), hsv)
            canvas.drawCircle(
                s.x + s.dx * e * density,
                s.y + s.dy * e * density,
                EffectShapes.sparkSizeDp(f) * density,
                paint,
            )
        }
    }

    private companion object {
        const val LIFE_MS = 420L
        const val REACH_DP = 16f
        const val MAX_SPARKS = 96
    }
}
