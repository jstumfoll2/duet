package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.FieldClass

/**
 * The field the keyboard is typing into, and its privacy (split out of KineticaIME, #184).
 *
 * One place decides what a field may learn and log, and in what order a field change happens:
 * the old field's word ends while the old field's privacy still holds, and only then does the
 * new field's state take over (review M8: a restart that skips onFinishInput let a password
 * field's buffer reach the DEV trace once the same view became a plain field). The incognito
 * switch applies to the field already open. Main thread only.
 */
class FieldSession {

    /** The field as the app described it, before the incognito switch is applied. */
    var described: EditorState = EditorState.DEFAULT
        private set

    /** The field as the keyboard treats it: [described] with incognito applied. */
    var state: EditorState = EditorState.DEFAULT
        private set

    /** The field's class, for per-field learning. */
    var fieldClass: FieldClass = FieldClass.OTHER
        private set

    private var incognito = false

    /**
     * A new field: [endOld] finishes whatever was in hand under the old field's rules, then the
     * new field's state, and its trace suppression, take over.
     */
    fun start(described: EditorState, fieldClass: FieldClass, incognito: Boolean, endOld: () -> Unit) {
        endOld()
        this.described = described
        this.fieldClass = fieldClass
        this.incognito = incognito
        apply()
    }

    /** The incognito switch changed: it applies to the open field at once. */
    fun setIncognito(on: Boolean) {
        if (on == incognito) return
        incognito = on
        apply()
    }

    private fun apply() {
        state = described.withIncognito(incognito)
        // The DEV build's trace names what is typed; a private field leaves no line in it.
        DecodeTrace.suppressed = state.teachesNothing
    }
}
