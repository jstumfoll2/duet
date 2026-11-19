package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.FieldClass
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A field change ends the old word under the old field's privacy (review M8, #184). */
class FieldSessionTest {

    private val password = EditorState.DEFAULT.copy(privateMode = true)
    private val plain = EditorState.DEFAULT

    @After
    fun reset() {
        DecodeTrace.suppressed = false
    }

    @Test
    fun theOldWordEndsUnderTheOldFieldsPrivacy() {
        val session = FieldSession()
        session.start(password, FieldClass.OTHER, incognito = false) {}
        assertTrue(DecodeTrace.suppressed)
        var suppressedWhileEnding = false
        session.start(plain, FieldClass.OTHER, incognito = false) {
            suppressedWhileEnding = DecodeTrace.suppressed
        }
        assertTrue("the password field's word must not reach the trace", suppressedWhileEnding)
        assertFalse(DecodeTrace.suppressed)
        assertFalse(session.state.teachesNothing)
    }

    @Test
    fun incognitoAppliesToTheOpenField() {
        val session = FieldSession()
        session.start(plain, FieldClass.OTHER, incognito = false) {}
        assertFalse(session.state.teachesNothing)
        session.setIncognito(true)
        assertTrue(session.state.teachesNothing)
        assertTrue(DecodeTrace.suppressed)
        assertEquals(plain, session.described)
        session.setIncognito(false)
        assertFalse(session.state.teachesNothing)
        assertFalse(DecodeTrace.suppressed)
    }

    @Test
    fun aNewFieldTakesItsClassAndTheSwitch() {
        val session = FieldSession()
        session.start(plain, FieldClass.values().last(), incognito = true) {}
        assertEquals(FieldClass.values().last(), session.fieldClass)
        assertTrue(session.state.teachesNothing)
    }
}
