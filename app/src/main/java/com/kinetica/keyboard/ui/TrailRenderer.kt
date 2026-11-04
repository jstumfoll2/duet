package com.kinetica.keyboard.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.kinetica.keyboard.engine.models.StreamId

/**
 * Per-stream swipe trails. Hue starts at the configured base (offset for the right thumb so
 * simultaneous trails can be told apart) and advances 30 deg on every key transition; points
 * fade and shrink over TRAIL_LIFE_MS.
 *
 * One smoothed piece per sample, so a fast swipe does not read as facets; the joins are
 * [TrailPath]'s and are tested there.
 */
class TrailRenderer(private val density: Float) {

    private class TrailPoint(
        var x: Float,
        var y: Float,
        var t: Long,
        var hue: Float,
        var breakBefore: Boolean,
    )

    private val trails = arrayOf(ArrayDeque<TrailPoint>(), ArrayDeque<TrailPoint>())
    private val pointPool = ArrayDeque<TrailPoint>()
    private val hues = floatArrayOf(0f, 0f)
    private val forceNextPoint = BooleanArray(2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val hsv = FloatArray(3)

    // Both reused every frame: one piece is drawn at a time, so smoothing allocates nothing.
    private val path = Path()
    private val quad = FloatArray(TrailPath.SIZE)

    /** Base hue in degrees; the right stream starts offset by 60 deg. */
    var baseHue = 0f

    /** Saturation and value every point is drawn at: a typed colour keeps its own. */
    var saturation = 0.85f
    var value = 1f

    /** How the stroke is drawn ([TrailStyle]). */
    var style = TrailStyle.CLASSIC

    /** Where a [TrailStyle.COMET] trail sheds its sparks; none without it. */
    var sparks: SparkRenderer? = null
    private var pointsSinceSpark = 0

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun startStream(stream: StreamId) {
        val i = stream.ordinal
        hues[i] = (baseHue + if (stream == StreamId.RIGHT) 60f else 0f) % 360f
        forceNextPoint[i] = true
    }

    fun addPoint(stream: StreamId, x: Float, y: Float, t: Long) {
        val i = stream.ordinal
        val trail = trails[i]
        val hue = hues[i]
        val last = trail.lastOrNull()
        // MotionEvent history can deliver several samples per frame. The decoder keeps them
        // all; the trail needs one point per ~8 ms, so the endpoint moves instead, bounding
        // draw work and allocation on high-refresh screens. A hue change forces a new point.
        if (!forceNextPoint[i] && last != null && last.hue == hue &&
            t >= last.t && t - last.t < MIN_SAMPLE_INTERVAL_MS
        ) {
            last.x = x
            last.y = y
            last.t = t
            return
        }
        val breakBefore = forceNextPoint[i]
        forceNextPoint[i] = false
        val point = if (pointPool.isEmpty()) {
            TrailPoint(x, y, t, hue, breakBefore)
        } else {
            pointPool.removeLast().also {
                it.x = x
                it.y = y
                it.t = t
                it.hue = hue
                it.breakBefore = breakBefore
            }
        }
        trail.addLast(point)
        if (style == TrailStyle.COMET && !breakBefore) {
            pointsSinceSpark++
            if (pointsSinceSpark >= EffectShapes.COMET_EVERY_POINTS) {
                pointsSinceSpark = 0
                sparks?.emit(x, y, t, hue)
            }
        }
    }

    /** The hue [stream] is drawing in now. */
    fun hueOf(stream: StreamId): Float = hues[stream.ordinal]

    fun bumpHue(stream: StreamId) {
        hues[stream.ordinal] = (hues[stream.ordinal] + HUE_STEP) % 360f
    }

    /** Drops expired points; returns true while anything is still visible. */
    fun prune(now: Long): Boolean {
        var alive = false
        for (trail in trails) {
            while (trail.isNotEmpty() && now - trail.first().t > TRAIL_LIFE_MS) {
                recycle(trail.removeFirst())
            }
            if (trail.isNotEmpty()) alive = true
        }
        return alive
    }

    fun clear() {
        for (trail in trails) {
            while (trail.isNotEmpty()) recycle(trail.removeFirst())
        }
        forceNextPoint.fill(false)
    }

    private fun recycle(point: TrailPoint) {
        if (pointPool.size < MAX_POOLED_POINTS) pointPool.addLast(point)
    }

    fun draw(canvas: Canvas, now: Long) {
        for (trail in trails) {
            if (trail.size < 2) continue
            for (i in 1 until trail.size) {
                val p = trail[i]
                val a = trail[i - 1]
                if (p.breakBefore) continue
                val age = (now - p.t).coerceAtLeast(0)
                val f = 1f - age / TRAIL_LIFE_MS.toFloat()
                if (f <= 0f) continue
                hsv[0] = p.hue
                hsv[1] = saturation
                hsv[2] = value
                if (style == TrailStyle.DOTS) {
                    dotPaint.color = Color.HSVToColor((220 * f).toInt(), hsv)
                    canvas.drawCircle(p.x, p.y, EffectShapes.dotRadiusDp(f) * density, dotPaint)
                    continue
                }
                // A run ends at a break as well as at the finger, so a lift stays a gap, not a
                // curve across the keyboard.
                val next = trail.getOrNull(i + 1)?.takeIf { !it.breakBefore }
                TrailPath.quadInto(
                    quad,
                    a.x, a.y, p.x, p.y,
                    next?.x ?: p.x, next?.y ?: p.y,
                    isFirst = i == 1 || a.breakBefore,
                    isLast = next == null,
                )
                path.rewind()
                path.moveTo(quad[0], quad[1])
                path.quadTo(quad[2], quad[3], quad[4], quad[5])
                if (style == TrailStyle.GLOW) {
                    // A wide faint halo, then a core washed toward white: neon without a blur
                    // filter, which would force a software layer on every frame.
                    paint.color = Color.HSVToColor((70 * f).toInt(), hsv)
                    paint.strokeWidth = EffectShapes.glowHaloDp(f) * density
                    canvas.drawPath(path, paint)
                    hsv[1] = saturation * 0.3f
                    paint.color = Color.HSVToColor((240 * f).toInt(), hsv)
                    paint.strokeWidth = EffectShapes.glowCoreDp(f) * density
                } else {
                    paint.color = Color.HSVToColor((200 * f).toInt(), hsv)
                    paint.strokeWidth = (3f + 7f * f) * density
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    private companion object {
        const val TRAIL_LIFE_MS = 250L
        const val MIN_SAMPLE_INTERVAL_MS = 8L
        const val MAX_POOLED_POINTS = 128
        const val HUE_STEP = 30f
    }
}
