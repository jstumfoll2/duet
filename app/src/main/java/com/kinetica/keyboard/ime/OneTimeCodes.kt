package com.kinetica.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * One-time codes read out of text messages, for the bar to offer in a code field.
 *
 * A text message carries its code beside a word that says so ("code", "OTP", "PIN",
 * "verification", "passcode", "one-time"), or in the "G-123456" form. A bare number is not
 * enough: an amount, an order number or a date is also digits. Codes are 4 to 8 digits, or 6 to
 * 8 letters and digits with at least two digits, which is what services send.
 *
 * Pure: the caller reads the messages (with the user's permission) and passes body and time.
 */
object OneTimeCodes {

    data class Message(val body: String, val atMs: Long)

    data class Code(val code: String, val atMs: Long)

    /** How far back a message may be for its code to still be offered: codes expire in minutes. */
    const val MAX_AGE_MS = 10 * 60 * 1000L

    private val KEYWORD = Regex(
        "(?i)\\b(code|codes|otp|pin|passcode|password|verification|verify|one[- ]time|security|login|" +
            "c[oó]digo|codice|kod|kód|mot de passe|bestätigungscode|token)\\b",
    )
    private val DASHED = Regex("\\b[A-Z]-(\\d{4,8})\\b")
    private val DIGITS = Regex("(?<![\\d.,:/-])(\\d{4,8})(?![\\d.,:/]\\d|%)")
    private val SPLIT_DIGITS = Regex("(?<![\\d])(\\d{3})[ -](\\d{3})(?![\\d])")
    private val ALNUM = Regex("\\b(?=[A-Z0-9]*\\d[A-Z0-9]*\\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{6,8}\\b")
    private val YEAR = Regex("^(19|20)\\d\\d$")

    /** The code in [body], or null when the message does not read as carrying one. */
    fun find(body: String): String? {
        DASHED.find(body)?.let { return it.groupValues[1] }
        val kw = KEYWORD.find(body) ?: return null
        // The candidate nearest the keyword wins: "Your code is 4821. Order 99120" is 4821.
        val candidates = ArrayList<Pair<String, Int>>()
        for (m in DIGITS.findAll(body)) {
            val v = m.groupValues[1]
            if (v.length == 4 && YEAR.matches(v)) continue
            candidates.add(v to m.range.first)
        }
        for (m in SPLIT_DIGITS.findAll(body)) candidates.add(m.groupValues[1] + m.groupValues[2] to m.range.first)
        if (candidates.isEmpty()) {
            for (m in ALNUM.findAll(body)) candidates.add(m.value to m.range.first)
        }
        val at = kw.range.first
        return candidates.minByOrNull { kotlin.math.abs(it.second - at) }?.first
    }

    /**
     * How long a code stays on offer in a field that does not say it wants one, but could
     * ([Field.MAYBE]). Shorter than [MAX_AGE_MS]: there the chip is a guess, and a code that came
     * minutes ago was most likely typed already.
     */
    const val MAYBE_MAX_AGE_MS = 5 * 60 * 1000L

    /** The codes of [messages] no older than [maxAgeMs] at [nowMs], newest first, each once. */
    fun recent(messages: List<Message>, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): List<Code> =
        messages.asSequence()
            .filter { nowMs - it.atMs in 0..maxAgeMs }
            .sortedByDescending { it.atMs }
            .mapNotNull { m -> find(m.body)?.let { Code(it, m.atMs) } }
            .distinctBy { it.code }
            .toList()

    /**
     * Whether a field asks for a one-time code, from what the app tells the keyboard: its
     * autofill hints, its hint text and its name. A number field alone is not one: a phone
     * number or a price is a number field too.
     */
    fun isCodeField(hints: List<String>, hintText: CharSequence?, fieldName: String?): Boolean {
        if (hints.any { h -> CODE_HINTS.any { h.contains(it, ignoreCase = true) } }) return true
        val text = listOfNotNull(hintText?.toString(), fieldName).joinToString(" ")
        return text.isNotEmpty() && FIELD_WORDS.containsMatchIn(text)
    }

    private val CODE_HINTS = listOf("otp", "smsotp", "onetimecode", "one-time-code", "2fa")
    private val FIELD_WORDS = Regex(
        "(?i)((?<![a-z])(code|otp|pin|2fa)(?![a-z])|one[- _]?time|verification|passcode|c[oó]digo|codice)",
    )

    /** Where a field stands on being offered a code. */
    enum class Field {
        /** Says it wants a code (hint or name), or is a number or PIN field. */
        CODE,

        /**
         * A single-line text, password or phone-pad field that says nothing either way: what most web forms
         * and many apps put up for a code. Offered only fresh, unused codes, and only while empty.
         */
        MAYBE,

        /** Never offered one: chat, search, email, web address, date, name, address. */
        NEVER,
    }

    /**
     * Sorts a field by what the app tells the keyboard about it: [inputType] and [imeOptions] as
     * in [EditorInfo], its hint text and its name. [anyField] off keeps the older rule, where
     * only a [Field.CODE] field is offered codes.
     */
    fun fieldKind(
        inputType: Int,
        imeOptions: Int,
        hintText: CharSequence?,
        fieldName: String?,
        anyField: Boolean,
    ): Field {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        if (isCodeField(emptyList(), hintText, fieldName)) return Field.CODE
        if (cls == InputType.TYPE_CLASS_NUMBER) return Field.CODE
        if (!anyField) return Field.NEVER
        // Many apps put a code on the phone pad for its digits; a phone-number field gets the
        // same guarded offer as any other plain field.
        if (cls == InputType.TYPE_CLASS_PHONE) return Field.MAYBE
        if (cls != InputType.TYPE_CLASS_TEXT) return Field.NEVER
        if (inputType and (InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0) {
            return Field.NEVER
        }
        if ((inputType and InputType.TYPE_MASK_VARIATION) in NEVER_VARIATIONS) return Field.NEVER
        when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_SEARCH -> return Field.NEVER
        }
        return Field.MAYBE
    }

    /**
     * The codes to put on the bar in a field of [kind]: all of [codes] in a [Field.CODE] field;
     * in a [Field.MAYBE] field only those from the last [MAYBE_MAX_AGE_MS], none already picked
     * ([used]), and none once the field has text in it ([fieldEmpty] false).
     */
    fun offer(kind: Field, codes: List<Code>, nowMs: Long, used: Set<String>, fieldEmpty: Boolean): List<Code> =
        when (kind) {
            Field.CODE -> codes
            Field.NEVER -> emptyList()
            Field.MAYBE ->
                if (!fieldEmpty) emptyList()
                else codes.filter { nowMs - it.atMs in 0..MAYBE_MAX_AGE_MS && it.code !in used }
        }

    private val NEVER_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
        InputType.TYPE_TEXT_VARIATION_URI,
        InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
        InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
        InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
        InputType.TYPE_TEXT_VARIATION_FILTER,
        InputType.TYPE_TEXT_VARIATION_PHONETIC,
    )
}
