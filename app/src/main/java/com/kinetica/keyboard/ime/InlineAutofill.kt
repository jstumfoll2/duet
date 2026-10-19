package com.kinetica.keyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.kinetica.keyboard.R

/**
 * The request a keyboard makes for inline autofill suggestions (Android 11+): the password
 * manager's or autofill service's saved logins, addresses, cards and one-time codes, drawn as
 * chips in the suggestion bar's place.
 *
 * The chips are rendered by the autofill service in its own process from the style sent here
 * (androidx.autofill builds the style bundle the platform expects); the keyboard only hosts the
 * views it gets back and never sees the secret behind a chip. A tap fills the field directly.
 */
@RequiresApi(Build.VERSION_CODES.R)
object InlineAutofill {

    const val MAX_SUGGESTIONS = 6

    // The style builders' setters are public API inherited from a base builder that
    // androidx.autofill marks library-restricted, which lint reports on every call; the
    // library's own samples call them the same way.
    @SuppressLint("RestrictedApi")
    fun request(context: Context, chipHeightPx: Int, textColor: Int): InlineSuggestionsRequest {
        val d = context.resources.displayMetrics.density
        val pad = (12 * d).toInt()
        val gap = (4 * d).toInt()
        val chip = ViewStyle.Builder()
            .setBackground(Icon.createWithResource(context, R.drawable.inline_chip_bg))
            .setPadding(pad, 0, pad, 0)
            .setLayoutMargin(gap, 0, gap, 0)
            .build()
        val style = InlineSuggestionUi.newStyleBuilder()
            .setChipStyle(chip)
            .setTitleStyle(TextViewStyle.Builder().setTextColor(textColor).setTextSize(14f).build())
            .setSubtitleStyle(TextViewStyle.Builder().setTextColor(textColor).setTextSize(12f).build())
            .build()
        val styles = UiVersions.newStylesBuilder().addStyle(style).build()
        val spec = InlinePresentationSpec.Builder(
            Size((48 * d).toInt(), chipHeightPx),
            Size((280 * d).toInt(), chipHeightPx),
        ).setStyle(styles).build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(MAX_SUGGESTIONS)
            .build()
    }

    /**
     * Inflates every chip of a non-empty [response] at [chipHeightPx] tall and hands them to
     * [onReady] once the last one is drawn, on the main thread. The service checks the chips
     * are still for the field it is showing.
     */
    fun inflate(context: Context, response: InlineSuggestionsResponse, chipHeightPx: Int, onReady: (List<View>) -> Unit) {
        val list = response.inlineSuggestions
        val views = arrayOfNulls<View>(list.size)
        var left = list.size
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, chipHeightPx)
        for ((i, suggestion) in list.withIndex()) {
            suggestion.inflate(context, size, context.mainExecutor) { v ->
                views[i] = v
                if (--left == 0) onReady(views.filterNotNull())
            }
        }
    }

    /** The scrolling row the chips sit in, on the bar's [background]. */
    fun strip(context: Context, chips: List<View>, background: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (chip in chips) row.addView(chip)
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(background)
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }
}
