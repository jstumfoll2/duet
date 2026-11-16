package com.kinetica.keyboard.settings

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.R
import com.kinetica.keyboard.engine.UsageStats
import java.util.Locale
import java.util.TimeZone

/**
 * The typing counts of the last days (issue #135): words, the share picked from the bar, the
 * share retyped or corrected, and words per minute. Read from the preference the keyboard
 * writes when a field is left; nothing here leaves the phone, and no word is stored.
 */
class UsageActivity : AppCompatActivity() {

    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        body = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        val reset = Button(this).apply {
            text = getString(R.string.usage_reset)
            setOnClickListener {
                PreferenceManager.getDefaultSharedPreferences(this@UsageActivity).edit().remove(Prefs.USAGE_COUNTS).apply()
                show()
            }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
            addView(body)
            addView(reset)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        show()
    }

    private fun show() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val stats = UsageStats.parse(prefs.getString(Prefs.USAGE_COUNTS, null))
        val on = prefs.getBoolean(Prefs.USAGE_STATS, Prefs.DEFAULT_USAGE_STATS)
        val now = System.currentTimeMillis()
        val today = ((now + TimeZone.getDefault().getOffset(now)) / 86_400_000L).toInt()
        val sb = StringBuilder()
        sb.append(getString(if (on) R.string.usage_intro_on else R.string.usage_intro_off)).append("\n\n")
        if (stats.isEmpty) {
            sb.append(getString(R.string.usage_empty))
        } else {
            sb.append(block(getString(R.string.usage_today), stats.day(today)))
            sb.append(block(getString(R.string.usage_last_days, 7), stats.last(7, today)))
            sb.append(block(getString(R.string.usage_last_days, 30), stats.last(30, today)))
            sb.append(getString(R.string.usage_note))
        }
        body.text = sb
    }

    private fun block(title: String, d: UsageStats.Day): String {
        if (d.words == 0) return "$title\n${getString(R.string.usage_none)}\n\n"
        val wpm = if (d.wpm > 0f) String.format(Locale.getDefault(), "%.0f", d.wpm) else getString(R.string.usage_wpm_unknown)
        return "$title\n" + getString(
            R.string.usage_block,
            d.words,
            String.format(Locale.getDefault(), "%.1f", 100f * d.fixShare),
            String.format(Locale.getDefault(), "%.1f", 100f * d.pickShare),
            wpm,
        ) + "\n\n"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
