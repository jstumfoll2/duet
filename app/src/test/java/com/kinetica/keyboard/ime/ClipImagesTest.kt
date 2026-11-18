package com.kinetica.keyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which copied-image URIs the keyboard will read on another app's behalf (audit #177). */
class ClipImagesTest {

    private val own = "io.github.jstumfoll2.duet.dev"

    private fun ok(scheme: String?, authority: String?) = ClipImages.readable(scheme, authority, own)

    @Test
    fun anotherAppsProviderIsRead() {
        assertTrue(ok("content", "com.android.providers.media.documents"))
        assertTrue(ok("content", "media"))
        assertTrue(ok("content", "com.example.app.fileprovider"))
    }

    @Test
    fun onlyContentUrisAreRead() {
        assertFalse(ok("file", ""))
        assertFalse(ok("file", null))
        assertFalse(ok("android.resource", "com.example"))
        assertFalse(ok("http", "example.com"))
        assertFalse(ok(null, "media"))
        assertFalse(ok("content", null))
        assertFalse(ok("content", ""))
    }

    @Test
    fun theKeyboardsOwnProvidersAreRefused() {
        assertFalse(ok("content", "io.github.jstumfoll2.duet.dev.clipimages"))
        assertFalse(ok("content", "io.github.jstumfoll2.duet.clipimages"))
        assertFalse(ok("content", "IO.GITHUB.JSTUMFOLL2.DUET.DEV.androidx-startup"))
        assertFalse(ok("content", "user@io.github.jstumfoll2.duet.dev.clipimages"))
    }

    @Test
    fun providersReachedThroughTheKeyboardsPermissionsAreRefused() {
        for (a in listOf("com.android.contacts", "contacts", "sms", "mms", "mms-sms", "call_log", "user_dictionary")) {
            assertFalse(a, ok("content", a))
        }
        assertFalse(ok("content", "media;sms"))
    }
}
