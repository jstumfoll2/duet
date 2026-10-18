package com.kinetica.keyboard.ime

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Size
import android.view.inputmethod.InlineSuggestionsRequest
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
}
