package com.kinetica.keyboard.ime

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.kinetica.keyboard.R
import com.kinetica.keyboard.engine.trace.PracticeProse
import java.util.Random

/**
 * Prose practice: a sentence or two to copy, with the passage following along as it is typed.
 *
 * The word prompts of [PracticeActivity] record one word in an empty field; this records words in
 * running text, with their context, the keyboard's automatic spaces, capitals and punctuation, the
 * way the keyboard is really used. Each word's trace line is labelled with its passage word (the
 * same `target` the word prompts write), read from the field by [PracticeProse.progress]: the
 * word being typed is underlined, finished words turn green when they came out as shown and red
 * when not. A wrong word can be fixed as anywhere else; its first attempt keeps its label, so
 * it still counts as a miss in the traces, and the retype is recorded as one more attempt.
 *
 * A finished passage (its last word followed by its full stop or a space) is replaced by the next.
 * Recording runs only while this screen is in front, as in word practice.
 */
class ProsePracticeActivity : AppCompatActivity() {

    private lateinit var passageView: TextView
    private lateinit var progressView: TextView
    private lateinit var input: EditText
    private val rnd = Random()
    private var index = -1
    private var passage: List<PracticeProse.Word> = emptyList()
    private var shown = PracticeProse.Progress(emptyList(), 0)

    // Over the visit: passages and words finished, and words that came out as shown.
    private var passages = 0
    private var words = 0
    private var hits = 0

    // A word finished since the last discard: there is a line to withdraw.
    private var canDiscard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.prose_title)
        val pad = (16 * resources.displayMetrics.density).toInt()

        passageView = TextView(this).apply {
            textSize = 22f
            setLineSpacing(0f, 1.2f)
        }
        progressView = TextView(this).apply { textSize = 14f }
        input = EditText(this).apply {
            // A message field, as the keyboard is really used: sentence capitals, several lines.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_NONE
            minLines = 3
        }
        val discard = Button(this).apply {
            text = getString(R.string.practice_discard)
            setOnClickListener { discardLast() }
        }
        val skip = Button(this).apply {
            text = getString(R.string.prose_next)
            setOnClickListener { nextPassage() }
        }
        val explain = TextView(this).apply {
            text = getString(R.string.prose_explain)
            textSize = 13f
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(discard)
            addView(skip)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad)
                addView(passageView)
                addView(input)
                addView(progressView)
                addView(buttons)
                addView(explain)
            },
        )

        if (savedInstanceState != null) {
            passages = savedInstanceState.getInt(KEY_PASSAGES)
            words = savedInstanceState.getInt(KEY_WORDS)
            hits = savedInstanceState.getInt(KEY_HITS)
            show(savedInstanceState.getInt(KEY_INDEX, -1).takeIf { it in PracticeProse.PASSAGES.indices })
            input.setText(savedInstanceState.getString(KEY_TEXT).orEmpty())
            input.setSelection(input.text.length)
        } else {
            show(null)
        }
        // After the restore, so putting the text back is not read as typing.
        shown = PracticeProse.progress(passage, input.text.toString())
        render()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) = onTyped(s.toString())
        })
    }

    override fun onResume() {
        super.onResume()
        TraceRecorder.words.practiceTarget = target()
    }

    override fun onPause() {
        TraceRecorder.words.practiceTarget = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_INDEX, index)
        outState.putString(KEY_TEXT, input.text.toString())
        outState.putInt(KEY_PASSAGES, passages)
        outState.putInt(KEY_WORDS, words)
        outState.putInt(KEY_HITS, hits)
    }

    private fun target(): String? = shown.current?.let { passage[it].label }

    private fun onTyped(text: String) {
        val before = shown
        val now = PracticeProse.progress(passage, text)
        // Counted when a word is finished for the first time; a word deleted and retyped at the
        // same place counts again, as its trace has a second line.
        if (now.finished.size > before.finished.size) {
            for (i in before.finished.size until now.finished.size) {
                words++
                if (now.finished[i]) hits++
            }
            canDiscard = true
        }
        shown = now
        if (now.current == null) {
            passages++
            nextPassage()
            return
        }
        TraceRecorder.words.practiceTarget = target()
        render()
    }

    private fun nextPassage() {
        show(null)
        // Cleared after the new passage is in place, so the watcher reads the empty field
        // against it; the field's leftover space or full stop is not a word of the next one.
        input.text.clear()
        shown = PracticeProse.progress(passage, "")
        TraceRecorder.words.practiceTarget = target()
        render()
    }

    private fun show(i: Int?) {
        index = i ?: PracticeProse.pick(rnd, index)
        passage = PracticeProse.words(PracticeProse.PASSAGES[index])
    }

    /** Withdraws the last word's line; delete the word and type it again for a clean attempt. */
    private fun discardLast() {
        if (!canDiscard) return
        TraceRecorder.words.discardLast()
        canDiscard = false
        words--
        if (shown.finished.lastOrNull() == true) hits--
        render()
    }

    private fun render() {
        val sb = SpannableStringBuilder()
        for ((i, w) in passage.withIndex()) {
            if (i > 0) sb.append(' ')
            val start = sb.length
            sb.append(w.shown)
            val end = sb.length
            when {
                i < shown.finished.size -> {
                    sb.span(ForegroundColorSpan(if (shown.finished[i]) GREEN else RED), start, end)
                    if (!shown.finished[i]) sb.span(StrikethroughSpan(), start, end)
                }
                i == shown.current -> {
                    sb.span(StyleSpan(Typeface.BOLD), start, end)
                    sb.span(UnderlineSpan(), start, end)
                    sb.span(BackgroundColorSpan(HIGHLIGHT), start, end)
                }
            }
        }
        passageView.text = sb
        progressView.text = getString(
            R.string.prose_progress,
            shown.finished.size, passage.size, shown.hits, passages, words, hits,
        )
    }

    private fun SpannableStringBuilder.span(what: Any, start: Int, end: Int) =
        setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

    private companion object {
        const val KEY_INDEX = "passage"
        const val KEY_TEXT = "text"
        const val KEY_PASSAGES = "passages"
        const val KEY_WORDS = "words"
        const val KEY_HITS = "hits"

        // Readable on light and dark backgrounds alike.
        val GREEN = Color.rgb(0x2E, 0x9E, 0x4F)
        val RED = Color.rgb(0xD2, 0x3F, 0x3F)
        val HIGHLIGHT = Color.argb(0x55, 0xFF, 0xC1, 0x07)
    }
}
