package com.kinetica.keyboard.settings

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import androidx.preference.Preference
import com.kinetica.keyboard.R

/**
 * An exact colour stored as `#RRGGBB`, picked in [ColourDialog]: sliders, swatches or the code
 * typed. Blank means none is set and the row's hue slider decides; the summary says so.
 *
 * [fallbackColor] is what the dialog opens on while blank, so it starts from the colour the
 * keyboard shows now rather than from black.
 */
class ColorHexPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    var fallbackColor: Int = 0xFF475DFF.toInt()

    /** The stored code, or null while blank. */
    val hex: String?
        get() = KeyboardConfig.exactColor(getPersistedString(null))?.let(HexColor::format)

    /** Clears the colour, as when the hue slider is moved. */
    fun clear() {
        if (getPersistedString(null).isNullOrEmpty()) return
        persistString("")
        notifyChanged()
    }

    override fun getSummary(): CharSequence {
        val code = hex ?: return context.getString(R.string.pref_color_hex_unset)
        // A square in the colour itself, ahead of the code: the code alone says little.
        return SpannableStringBuilder("■  ").apply {
            setSpan(ForegroundColorSpan(HexColor.colorOr(code, 0)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(code)
        }
    }

    override fun onClick() {
        ColourDialog.show(context, title, hex ?: HexColor.format(fallbackColor)) { value ->
            if (callChangeListener(value)) {
                persistString(value)
                notifyChanged()
            }
        }
    }
}
