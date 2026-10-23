package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Dictation from the speech recognizer, on its way back to the field it was asked in. */
class VoiceInputTest {

    @Test
    fun heardTextGoesToTheFieldItWasAskedInOnce() {
        VoiceInput.expect("com.chat", 7, 0)
        VoiceInput.deliver("  see you soon ", 100)
        assertNull(VoiceInput.take("com.other", 7, 200))
        assertNull(VoiceInput.take("com.chat", 8, 200))
        assertEquals("see you soon", VoiceInput.take("com.chat", 7, 300))
        assertNull(VoiceInput.take("com.chat", 7, 400))
    }

    @Test
    fun cancelledOrStaleDictationIsDropped() {
        VoiceInput.expect("com.chat", 7, 0)
        VoiceInput.deliver(null, 100)
        assertNull(VoiceInput.take("com.chat", 7, 200))

        VoiceInput.expect("com.chat", 7, 0)
        VoiceInput.deliver("late", 100)
        assertNull(VoiceInput.take("com.chat", 7, 100 + VoiceInput.PENDING_MS + 1))
    }

    @Test
    fun aDeliveryWithNoRequestIsIgnored() {
        VoiceInput.expect("com.chat", 7, 0)
        VoiceInput.deliver("first", 10)
        VoiceInput.take("com.chat", 7, 20)
        VoiceInput.deliver("again", 30)
        assertNull(VoiceInput.take("com.chat", 7, 40))
    }

    @Test
    fun aSpaceSeparatesDictationFromTheWordBefore() {
        assertEquals("hello", VoiceInput.joined(null, "hello"))
        assertEquals("hello", VoiceInput.joined("", "hello"))
        assertEquals("hello", VoiceInput.joined(" ", "hello"))
        assertEquals(" hello", VoiceInput.joined("t", "hello"))
        assertEquals(" hello", VoiceInput.joined(".", "hello"))
        assertEquals("hello", VoiceInput.joined("(", "hello"))
        assertEquals(", ok", VoiceInput.joined("t", ", ok"))
    }
}
