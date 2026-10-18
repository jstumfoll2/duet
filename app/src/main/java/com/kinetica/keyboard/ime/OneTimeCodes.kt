package com.kinetica.keyboard.ime

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

    /** The codes of [messages] no older than [MAX_AGE_MS] at [nowMs], newest first, each once. */
    fun recent(messages: List<Message>, nowMs: Long): List<Code> =
        messages.asSequence()
            .filter { nowMs - it.atMs in 0..MAX_AGE_MS }
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
}
