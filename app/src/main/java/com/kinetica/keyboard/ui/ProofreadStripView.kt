package com.kinetica.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.kinetica.keyboard.engine.HarperLints

/**
 * A slim row above the suggestion bar that mirrors the sentence being written, with Harper's
 * grammar problems underlined. It is the keyboard's own drawing, so it works in every app,
 * unlike the spell checker's underlines, which only fields that ask for them show.
 *
 * Tap an underlined word and the row turns into that problem's fixes; tap a fix to apply it,
 * anywhere else to go back. A clean sentence is drawn dimmed, so the check is seen to run.
 */
class ProofreadStripView(context: Context) : View(context) {

    interface Listener {
        /** Fix [fix] of [mark] in [proof] was tapped. */
        fun onProofFix(proof: HarperLints.Proof, mark: HarperLints.Mark, fix: String)
    }

    var listener: Listener? = null

    private var proof: HarperLints.Proof? = null
    private var open = -1

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ERROR_RED }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipRect = RectF()

    // Laid out in onDraw, read by onTouchEvent: each underlined word's or fix chip's x range.
    private val markLeft = ArrayList<Float>()
    private val markRight = ArrayList<Float>()
    private val chipLeft = ArrayList<Float>()
    private val chipRight = ArrayList<Float>()

    fun setColors(bg: Int, text: Int, chip: Int) {
        bgPaint.color = bg
        textPaint.color = text
        dimPaint.color = (text and 0x00FFFFFF) or (0x80 shl 24)
        chipPaint.color = chip
        invalidate()
    }

    /** Shows [p], or clears the row when null. An open fix list closes. */
    fun show(p: HarperLints.Proof?) {
        if (p == proof) return
        proof = p
        open = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        markLeft.clear()
        markRight.clear()
        chipLeft.clear()
        chipRight.clear()
        val p = proof ?: return
        if (p.sentence.isEmpty()) return
        val size = h * TEXT_FRACTION
        textPaint.textSize = size
        dimPaint.textSize = size
        val pad = PAD_DP * density
        val baseY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        val mark = p.marks.getOrNull(open)
        if (mark != null) {
            drawFixes(canvas, p, mark, w, h, pad, baseY)
            return
        }
        val paint = if (p.marks.isEmpty()) dimPaint else textPaint
        // The end of the sentence is where the typing is, so a long one is cut at the start.
        var from = 0
        val room = w - 2 * pad
        val ellipsis = "…"
        val ellW = paint.measureText(ellipsis)
        if (paint.measureText(p.sentence) > room) {
            from = p.sentence.length
            while (from > 0 && paint.measureText(p.sentence, from - 1, p.sentence.length) <= room - ellW) from--
        }
        var x = pad
        if (from > 0) {
            canvas.drawText(ellipsis, x, baseY, paint)
            x += ellW
        }
        canvas.drawText(p.sentence, from, p.sentence.length, x, baseY, paint)
        val lineY = baseY + textPaint.descent() * 0.6f
        linePaint.strokeWidth = LINE_DP * density
        for (m in p.marks) {
            val s = m.start.coerceAtLeast(from)
            val e = (m.start + m.length).coerceAtMost(p.sentence.length)
            if (e <= s) {
                markLeft.add(-1f)
                markRight.add(-1f)
                continue
            }
            val left = x + paint.measureText(p.sentence, from, s)
            val right = x + paint.measureText(p.sentence, from, e)
            canvas.drawLine(left, lineY, right, lineY, linePaint)
            markLeft.add(left)
            markRight.add(right)
        }
    }

    private fun drawFixes(canvas: Canvas, p: HarperLints.Proof, m: HarperLints.Mark, w: Float, h: Float, pad: Float, baseY: Float) {
        val problem = p.sentence.substring(m.start, m.start + m.length)
        var x = pad
        canvas.drawText(problem, x, baseY, textPaint)
        val pw = textPaint.measureText(problem)
        linePaint.strokeWidth = LINE_DP * density
        val lineY = baseY + textPaint.descent() * 0.6f
        canvas.drawLine(x, lineY, x + pw, lineY, linePaint)
        x += pw
        val arrow = "  →  "
        canvas.drawText(arrow, x, baseY, dimPaint)
        x += dimPaint.measureText(arrow)
        val inset = 3f * density
        val corner = 6f * density
        for (fix in m.suggestions) {
            val fw = textPaint.measureText(fix) + 2 * CHIP_PAD_DP * density
            if (x + fw > w - pad) break
            chipRect.set(x, inset, x + fw, h - inset)
            canvas.drawRoundRect(chipRect, corner, corner, chipPaint)
            canvas.drawText(fix, x + CHIP_PAD_DP * density, baseY, textPaint)
            chipLeft.add(x)
            chipRight.add(x + fw)
            x += fw + GAP_DP * density
        }
        // With room left, Harper's reason, dimmed; without a fix it is all there is.
        if (x < w - pad) {
            val reason = m.message.replace("`", "")
            val room = w - pad - x
            val n = dimPaint.breakText(reason, true, room, null)
            if (n > 0) canvas.drawText(if (n < reason.length) reason.take((n - 1).coerceAtLeast(0)) + "…" else reason, x, baseY, dimPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val p = proof ?: return false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (ev.actionMasked != MotionEvent.ACTION_UP) return true
        val x = ev.x
        val m = p.marks.getOrNull(open)
        if (m != null) {
            val i = chipLeft.indices.firstOrNull { x >= chipLeft[it] && x <= chipRight[it] }
            open = -1
            if (i != null) listener?.onProofFix(p, m, m.suggestions[i])
            invalidate()
            return true
        }
        // The nearest underline within a finger's reach, so a short word like `a` is hittable.
        val slop = TOUCH_SLOP_DP * density
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in markLeft.indices) {
            if (markLeft[i] < 0f) continue
            val d = when {
                x < markLeft[i] -> markLeft[i] - x
                x > markRight[i] -> x - markRight[i]
                else -> 0f
            }
            if (d <= slop && d < bestD) {
                best = i
                bestD = d
            }
        }
        if (best >= 0) {
            open = best
            invalidate()
        }
        return true
    }

    private companion object {
        const val TEXT_FRACTION = 0.5f
        const val PAD_DP = 10f
        const val LINE_DP = 2f
        const val CHIP_PAD_DP = 10f
        const val GAP_DP = 6f
        const val TOUCH_SLOP_DP = 16f
        // Spell-check red, the colour people already read as "wrong" under text.
        const val ERROR_RED = 0xFFE53935.toInt()
    }
}
