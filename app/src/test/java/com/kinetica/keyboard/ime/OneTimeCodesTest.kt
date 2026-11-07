package com.kinetica.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One-time codes picked out of text messages for the bar. */
class OneTimeCodesTest {

    @Test
    fun theCodeBesideItsKeywordIsFound() {
        assertEquals("482913", OneTimeCodes.find("Your verification code is 482913. Don't share it."))
        assertEquals("123456", OneTimeCodes.find("G-123456 is your Google verification code."))
        assertEquals("7741", OneTimeCodes.find("Use 7741 as your login PIN"))
        assertEquals("553201", OneTimeCodes.find("Your code: 553 201"))
        assertEquals("90210", OneTimeCodes.find("OTP 90210 for txn of $45.00 on 12/03"))
        assertEquals("A7K9Q2", OneTimeCodes.find("Your one-time passcode is A7K9Q2"))
        assertEquals("4821", OneTimeCodes.find("Your code is 4821. Order 99120 ships soon."))
    }

    @Test
    fun aMessageWithNumbersButNoCodeIsLeft() {
        assertNull(OneTimeCodes.find("Your order 99120 ships on 2026-10-18"))
        assertNull(OneTimeCodes.find("Call me at 5551234 when you land"))
        assertNull(OneTimeCodes.find("Your code is ready"))
        // A year beside the word is not a code.
        assertNull(OneTimeCodes.find("Security update for 2026"))
    }

    @Test
    fun onlyRecentMessagesCountNewestFirst() {
        val now = 1_000_000_000L
        val codes = OneTimeCodes.recent(
            listOf(
                OneTimeCodes.Message("Your code is 111111", now - 60_000),
                OneTimeCodes.Message("Your code is 222222", now - 5_000),
                OneTimeCodes.Message("Your code is 333333", now - OneTimeCodes.MAX_AGE_MS - 1),
                OneTimeCodes.Message("See you at 8", now - 1_000),
                OneTimeCodes.Message("Your code is 222222", now - 4_000),
            ),
            now,
        )
        assertEquals(listOf("222222", "111111"), codes.map { it.code })
    }

    @Test
    fun aCodeFieldIsToldByItsHintsNotByBeingNumeric() {
        assertTrue(OneTimeCodes.isCodeField(listOf("smsOTPCode"), null, null))
        assertTrue(OneTimeCodes.isCodeField(emptyList(), "Enter verification code", null))
        assertTrue(OneTimeCodes.isCodeField(emptyList(), null, "otp_input"))
        assertFalse(OneTimeCodes.isCodeField(emptyList(), "Phone number", "phone"))
        assertFalse(OneTimeCodes.isCodeField(emptyList(), null, null))
        // `zip code` is a code field to the words, which only costs an offered chip.
        assertFalse(OneTimeCodes.isCodeField(listOf("postalCode"), null, null))
    }

    private val text = InputType.TYPE_CLASS_TEXT
    private val done = EditorInfo.IME_ACTION_DONE

    private fun kind(
        type: Int,
        action: Int = done,
        hint: String? = null,
        name: String? = null,
        anyField: Boolean = true,
    ) = OneTimeCodes.fieldKind(type, action, hint, name, anyField)

    @Test
    fun aFieldThatAsksOrIsNumericIsACodeFieldEitherWay() {
        for (any in listOf(true, false)) {
            assertEquals(OneTimeCodes.Field.CODE, kind(InputType.TYPE_CLASS_NUMBER, anyField = any))
            assertEquals(
                OneTimeCodes.Field.CODE,
                kind(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, anyField = any),
            )
            assertEquals(OneTimeCodes.Field.CODE, kind(text, hint = "Enter the 6-digit code", anyField = any))
            assertEquals(OneTimeCodes.Field.CODE, kind(InputType.TYPE_CLASS_PHONE, name = "otp", anyField = any))
        }
    }

    @Test
    fun withAnyFieldOffAPlainFieldIsLeftAsBefore() {
        assertEquals(OneTimeCodes.Field.NEVER, kind(text, anyField = false))
        assertEquals(OneTimeCodes.Field.NEVER, kind(InputType.TYPE_CLASS_PHONE, anyField = false))
        assertEquals(
            OneTimeCodes.Field.NEVER,
            kind(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, anyField = false),
        )
    }

    @Test
    fun plainTextPasswordWebAndPhonePadFieldsMayTakeACode() {
        assertEquals(OneTimeCodes.Field.MAYBE, kind(text))
        assertEquals(OneTimeCodes.Field.MAYBE, kind(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertEquals(OneTimeCodes.Field.MAYBE, kind(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertEquals(OneTimeCodes.Field.MAYBE, kind(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertEquals(OneTimeCodes.Field.MAYBE, kind(text or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT))
        assertEquals(OneTimeCodes.Field.MAYBE, kind(text, action = EditorInfo.IME_ACTION_GO))
        assertEquals(OneTimeCodes.Field.MAYBE, kind(text, action = EditorInfo.IME_ACTION_NEXT))
        assertEquals(OneTimeCodes.Field.MAYBE, kind(InputType.TYPE_CLASS_PHONE))
    }

    @Test
    fun chatSearchEmailAndAddressFieldsNeverTakeOne() {
        assertEquals(OneTimeCodes.Field.NEVER, kind(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertEquals(OneTimeCodes.Field.NEVER, kind(text or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE))
        assertEquals(OneTimeCodes.Field.NEVER, kind(text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE))
        assertEquals(OneTimeCodes.Field.NEVER, kind(text, action = EditorInfo.IME_ACTION_SEND))
        assertEquals(OneTimeCodes.Field.NEVER, kind(text, action = EditorInfo.IME_ACTION_SEARCH))
        assertEquals(OneTimeCodes.Field.NEVER, kind(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertEquals(OneTimeCodes.Field.NEVER, kind(text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS))
        assertEquals(OneTimeCodes.Field.NEVER, kind(text or InputType.TYPE_TEXT_VARIATION_URI))
        assertEquals(OneTimeCodes.Field.NEVER, kind(text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME))
        assertEquals(OneTimeCodes.Field.NEVER, kind(text or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS))
        assertEquals(OneTimeCodes.Field.NEVER, kind(text or InputType.TYPE_TEXT_VARIATION_FILTER))
        assertEquals(OneTimeCodes.Field.NEVER, kind(InputType.TYPE_CLASS_DATETIME))
        // Even a chat field named "code" asks for a code: the words win, as before.
        assertEquals(OneTimeCodes.Field.CODE, kind(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE, hint = "Code"))
    }

    @Test
    fun aMaybeFieldGetsOnlyFreshUnusedCodesWhileEmpty() {
        val now = 1_000_000_000L
        val fresh = OneTimeCodes.Code("482913", now - 30_000)
        val older = OneTimeCodes.Code("111111", now - OneTimeCodes.MAYBE_MAX_AGE_MS - 1)
        val codes = listOf(fresh, older)
        val maybe = OneTimeCodes.Field.MAYBE
        assertEquals(listOf(fresh), OneTimeCodes.offer(maybe, codes, now, emptySet(), fieldEmpty = true))
        assertEquals(emptyList<OneTimeCodes.Code>(), OneTimeCodes.offer(maybe, codes, now, setOf("482913"), true))
        assertEquals(emptyList<OneTimeCodes.Code>(), OneTimeCodes.offer(maybe, codes, now, emptySet(), false))
        assertEquals(emptyList<OneTimeCodes.Code>(), OneTimeCodes.offer(OneTimeCodes.Field.NEVER, codes, now, emptySet(), true))
    }

    @Test
    fun aCodeFieldStillGetsEveryRecentCode() {
        val now = 1_000_000_000L
        val codes = listOf(
            OneTimeCodes.Code("482913", now - 30_000),
            OneTimeCodes.Code("111111", now - OneTimeCodes.MAYBE_MAX_AGE_MS - 1),
        )
        // Used or not, empty or not: a field that asks for a code is offered them all, as before.
        assertEquals(codes, OneTimeCodes.offer(OneTimeCodes.Field.CODE, codes, now, setOf("482913"), fieldEmpty = false))
    }
}
