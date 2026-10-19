package com.kinetica.keyboard.ime

import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype

/** Finding the voice typing keyboard the voice key hands input to. */
internal object VoiceInput {

    /** InputMethodSubtype.getMode() of a voice typing subtype. */
    const val VOICE_MODE = "voice"

    /**
     * The first enabled input method other than [ownPackage] with a voice subtype, as its id
     * and that subtype, or else a voice-only one that declares no subtypes, with none; null
     * when there is neither.
     */
    fun target(imm: InputMethodManager, ownPackage: String): Pair<String, InputMethodSubtype?>? {
        var target: Pair<String, InputMethodSubtype?>? = null
        for (imi in imm.enabledInputMethodList) {
            if (imi.packageName == ownPackage) continue
            val voice = imm.getEnabledInputMethodSubtypeList(imi, true).firstOrNull { it.mode == VOICE_MODE }
            if (voice != null) {
                target = imi.id to voice
                break
            }
            // A voice-only input method that declares no subtypes.
            if (target == null && imi.subtypeCount == 0 && imi.id.contains("voice", ignoreCase = true)) {
                target = imi.id to null
            }
        }
        return target
    }
}
