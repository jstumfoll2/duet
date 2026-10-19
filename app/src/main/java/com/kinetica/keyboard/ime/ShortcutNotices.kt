package com.kinetica.keyboard.ime

import android.content.Context
import com.kinetica.keyboard.R
import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.settings.ActionLabels

/** The spacebar's words for what a shortcut left behind, or null when it says nothing. */
internal fun Context.noticeText(notice: ActionRow.Notice): String? = when (notice) {
    is ActionRow.Notice.Sent -> ActionLabels.noticeRes(notice.action)?.let { getString(it) }
    is ActionRow.Notice.Autospace -> getString(
        if (notice.on) R.string.notice_autospace_on else R.string.notice_autospace_off,
    )
    is ActionRow.Notice.Toggle -> ActionLabels.toggleNameRes(notice.action)?.let {
        getString(if (notice.on) R.string.notice_toggle_on else R.string.notice_toggle_off, getString(it))
    }
    is ActionRow.Notice.Language ->
        entryFor(R.array.language_values, R.array.language_entries, notice.code)
    is ActionRow.Notice.Layout ->
        entryFor(R.array.layout_mode_values, R.array.layout_mode_entries, notice.mode)
}

/** The settings list's own wording for [value], so the spacebar and the chooser agree. */
internal fun Context.entryFor(valuesRes: Int, entriesRes: Int, value: String): String {
    val values = resources.getStringArray(valuesRes)
    val entries = resources.getStringArray(entriesRes)
    return entries.getOrNull(values.indexOf(value)) ?: value
}
