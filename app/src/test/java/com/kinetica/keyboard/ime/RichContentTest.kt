package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which copied images a field can take. */
class RichContentTest {

    @Test
    fun aFieldWithNoTypesTakesNoImage() {
        assertFalse(RichContent.accepts(null, "image/png"))
        assertFalse(RichContent.accepts(emptyArray(), "image/png"))
    }

    @Test
    fun exactAndWildcardTypesMatch() {
        assertTrue(RichContent.accepts(arrayOf("image/png"), "image/png"))
        assertTrue(RichContent.accepts(arrayOf("image/gif", "image/*"), "image/jpeg"))
        assertTrue(RichContent.accepts(arrayOf("*/*"), "image/webp"))
        assertTrue(RichContent.accepts(arrayOf("IMAGE/PNG"), "image/png"))
        assertFalse(RichContent.accepts(arrayOf("image/gif"), "image/png"))
        assertFalse(RichContent.accepts(arrayOf("video/*"), "image/png"))
        assertFalse(RichContent.accepts(arrayOf("garbage"), "image/png"))
    }

    @Test
    fun theClipImageTypeIsTheFirstConcreteOne() {
        assertEquals("image/png", RichContent.imageMime(listOf("text/uri-list", "image/png")))
        assertNull(RichContent.imageMime(listOf("text/plain")))
        assertNull(RichContent.imageMime(listOf("image/*")))
    }
}
