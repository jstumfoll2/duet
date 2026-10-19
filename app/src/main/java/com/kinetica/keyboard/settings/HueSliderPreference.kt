package com.kinetica.keyboard.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.SeekBar
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import com.kinetica.keyboard.R
import com.kinetica.keyboard.ui.Hsv

/**
 * A 0-360 hue slider that shows the colour it stands for. A bare number told nobody what
 * "233" looks like, so the row carries a filled dot in the icon slot, the thumb takes the
 * picked colour, and the track is a rainbow so the colour under the thumb is visible before
 * it is dropped there.
 *
 * Stores the same int a plain [SeekBarPreference] did, so existing settings and backups read
 * unchanged. The swatch is drawn at full value and [swatchSaturation][R.attr.swatchSaturation],
 * which each row sets to what the keyboard renders that hue at.
 */
class HueSliderPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SeekBarPreference(context, attrs) {

    // Nullable and filled in init: the base constructor may call overrides before this
    // class's initialisers have run (see SteppedSliderPreference.onGetDefaultValue).
    private var saturation: Float = DEFAULT_SATURATION
    private var dot: GradientDrawable? = null
    private var bar: SeekBar? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.HueSliderPreference)
        saturation = a.getFloat(R.styleable.HueSliderPreference_swatchSaturation, DEFAULT_SATURATION)
        a.recycle()
        // The dot has to follow the thumb, and the change listener only fires mid-drag with this.
        updatesContinuously = true
        val px = (DOT_DP * context.resources.displayMetrics.density).toInt()
        dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(px, px)
            setStroke((context.resources.displayMetrics.density + 0.5f).toInt(), OUTLINE)
        }
        icon = dot
    }

    /** The swatch colour for [hue] degrees; 360 is red again, as on the keyboard. */
    fun colorAt(hue: Int): Int = Hsv.toColor(hue.toFloat(), saturation, 1f)

    override fun onSetInitialValue(defaultValue: Any?) {
        super.onSetInitialValue(defaultValue)
        paint(value)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val seek = holder.findViewById(androidx.preference.R.id.seekbar) as? SeekBar
        bar = seek
        seek?.let {
            it.progressDrawable = hueTrack(it.context)
            // The theme's tint would paint the rainbow over in one accent colour.
            it.progressTintList = null
            it.progressBackgroundTintList = null
        }
        paint(value)
    }

    override fun callChangeListener(newValue: Any?): Boolean {
        val accepted = super.callChangeListener(newValue)
        if (accepted) (newValue as? Int)?.let(::paint)
        return accepted
    }

    private fun paint(hue: Int) {
        val c = colorAt(hue)
        dot?.setColor(c)
        bar?.thumbTintList = ColorStateList.valueOf(c)
    }

    private fun hueTrack(ctx: Context): LayerDrawable {
        val stops = IntArray(HUE_STOPS + 1) { i -> colorAt(i * 360 / HUE_STOPS) }
        val density = ctx.resources.displayMetrics.density
        val rainbow = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, stops).apply {
            cornerRadius = TRACK_DP * density / 2f
        }
        return LayerDrawable(arrayOf(rainbow)).apply {
            setLayerHeight(0, (TRACK_DP * density).toInt())
            setLayerGravity(0, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
        }
    }

    private companion object {
        /** KeyboardTheme's custom-accent saturation, for rows that do not set the attribute. */
        const val DEFAULT_SATURATION = 0.72f
        const val DOT_DP = 24f
        const val TRACK_DP = 6f
        const val HUE_STOPS = 12
        const val OUTLINE = 0x66888888
    }
}
