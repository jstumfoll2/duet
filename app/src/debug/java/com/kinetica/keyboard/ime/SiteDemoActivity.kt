package com.kinetica.keyboard.ime

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * DEV only: a plain note screen with one text field, for the website's screenshot tour
 * (androidTest `SiteMediaTour`). Not exported and not linked from anywhere in the app.
 */
class SiteDemoActivity : Activity() {
    lateinit var field: EditText
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF8EF"))
            setPadding((20 * dp).toInt(), (28 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        }
        root.addView(TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE) ?: "Notes"
            setTextColor(Color.parseColor("#2A1E17"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            paint.isFakeBoldText = true
        })
        field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            gravity = Gravity.TOP or Gravity.START
            setTextColor(Color.parseColor("#2A1E17"))
            setHintTextColor(Color.parseColor("#9C8878"))
            hint = "Start typing"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = null
        }
        root.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        field.requestFocus()
    }

    companion object {
        const val EXTRA_TITLE = "title"
    }
}
