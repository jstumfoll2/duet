package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyboard pads itself by the navigation bar only when its window is edge-to-edge (#186). */
class NavInsetsTest {

    @Test
    fun edgeToEdgeNeedsAndroid15AndTarget35() {
        assertFalse(NavInsets.edgeToEdge(sdk = 34, targetSdk = 35))
        assertFalse(NavInsets.edgeToEdge(sdk = 35, targetSdk = 34))
        assertTrue(NavInsets.edgeToEdge(sdk = 35, targetSdk = 35))
        assertTrue(NavInsets.edgeToEdge(sdk = 36, targetSdk = 35))
    }

    @Test
    fun theGapIsTheSettingPlusTheBar() {
        assertEquals(0, NavInsets.gap(0, 0))
        assertEquals(12, NavInsets.gap(12, 0))
        assertEquals(60, NavInsets.gap(12, 48))
        assertEquals(48, NavInsets.gap(-5, 48))
    }
}
