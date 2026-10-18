package com.kinetica.keyboard.ime

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
}
