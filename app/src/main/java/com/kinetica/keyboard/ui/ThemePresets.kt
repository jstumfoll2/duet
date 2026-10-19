package com.kinetica.keyboard.ui

/**
 * Named, hand-tuned palettes offered next to the bundled, Material You and custom-hue sources.
 *
 * Each preset is a complete role set in one brightness, so it ignores both the brightness and the
 * hue settings: a preset is a look someone picked whole, and re-deriving it for the other
 * brightness would give a different look under the same name. The ids share [PREFIX] so a stored
 * value is recognisable as a preset, and an id that is no longer shipped falls through
 * [KeyboardTheme.resolve] to the bundled palette like any other unknown mode.
 *
 * Literals only, no android.graphics.Color, so the JVM tests can check every preset's contrast.
 */
object ThemePresets {

    const val PREFIX = "preset_"

    /** Stored ids in the order the settings list shows them; the arrays.xml rows must match. */
    val IDS: List<String>
        get() = palettes.keys.toList()

    fun isPreset(mode: String): Boolean = mode in palettes

    /** The preset stored as [mode], or null when [mode] is not a shipped preset. */
    fun find(mode: String): KeyboardTheme? = palettes[mode]

    private fun c(argb: Long): Int = argb.toInt()

    private val palettes: Map<String, KeyboardTheme> = linkedMapOf(
        // True black for OLED screens: unlit pixels behind the keys, keys barely raised.
        "${PREFIX}amoled" to KeyboardTheme(
            background = c(0xFF000000), key = c(0xFF161616), keySpecial = c(0xFF0D0D0D),
            keyPressed = c(0xFF3A3A3A), keyText = c(0xFFF2F2F2), keyHint = c(0xFF9A9A9A),
            suggestionBg = c(0xFF000000), suggestionText = c(0xFFD6D6D6),
            suggestionPrimary = c(0xFFFFFFFF), chip = c(0xFF262626), popupBg = c(0xFF1E1E1E),
            accent = c(0xFF5468FF),
        ),
        "${PREFIX}ocean" to KeyboardTheme(
            background = c(0xFF0B1A2A), key = c(0xFF15304A), keySpecial = c(0xFF102538),
            keyPressed = c(0xFF24527A), keyText = c(0xFFE6F1FA), keyHint = c(0xFF8FB3CF),
            suggestionBg = c(0xFF0E2033), suggestionText = c(0xFFCFE2F0),
            suggestionPrimary = c(0xFFFFFFFF), chip = c(0xFF1D4366), popupBg = c(0xFF1A3A58),
            accent = c(0xFF3FC5F0),
        ),
        "${PREFIX}forest" to KeyboardTheme(
            background = c(0xFF0F1A13), key = c(0xFF1D3024), keySpecial = c(0xFF16261C),
            keyPressed = c(0xFF2F5A3D), keyText = c(0xFFE8F3EA), keyHint = c(0xFF95B89E),
            suggestionBg = c(0xFF13221A), suggestionText = c(0xFFD2E6D7),
            suggestionPrimary = c(0xFFFFFFFF), chip = c(0xFF274A33), popupBg = c(0xFF24402F),
            accent = c(0xFF6FCF7F),
        ),
        "${PREFIX}sunset" to KeyboardTheme(
            background = c(0xFF1C1018), key = c(0xFF33202C), keySpecial = c(0xFF281823),
            keyPressed = c(0xFF5A3346), keyText = c(0xFFF8E9EF), keyHint = c(0xFFC79AAE),
            suggestionBg = c(0xFF221420), suggestionText = c(0xFFEBD3DD),
            suggestionPrimary = c(0xFFFFFFFF), chip = c(0xFF4A2A3A), popupBg = c(0xFF3E2634),
            accent = c(0xFFFF8A50),
        ),
        "${PREFIX}paper" to KeyboardTheme(
            background = c(0xFFE9E4DA), key = c(0xFFFBF8F2), keySpecial = c(0xFFDCD5C8),
            keyPressed = c(0xFFC9BFAE), keyText = c(0xFF2A2520), keyHint = c(0xFF6E655A),
            suggestionBg = c(0xFFF2EEE6), suggestionText = c(0xFF3A342D),
            suggestionPrimary = c(0xFF1A1612), chip = c(0xFFE0D6C4), popupBg = c(0xFFFFFFFF),
            accent = c(0xFFB5651D),
        ),
        "${PREFIX}lavender" to KeyboardTheme(
            background = c(0xFFE6E1F2), key = c(0xFFFAF8FF), keySpecial = c(0xFFD9D2EC),
            keyPressed = c(0xFFC2B7E3), keyText = c(0xFF231D3A), keyHint = c(0xFF655C85),
            suggestionBg = c(0xFFF0ECFA), suggestionText = c(0xFF332B52),
            suggestionPrimary = c(0xFF17112E), chip = c(0xFFDCD3F5), popupBg = c(0xFFFFFFFF),
            accent = c(0xFF7A5AD8),
        ),
        // Maximum legibility: white on black with a yellow highlight, for bright sun or low vision.
        "${PREFIX}contrast" to KeyboardTheme(
            background = c(0xFF000000), key = c(0xFF262626), keySpecial = c(0xFF1A1A1A),
            keyPressed = c(0xFF5C5C5C), keyText = c(0xFFFFFFFF), keyHint = c(0xFFFFD60A),
            suggestionBg = c(0xFF000000), suggestionText = c(0xFFFFFFFF),
            suggestionPrimary = c(0xFFFFD60A), chip = c(0xFF3D3D3D), popupBg = c(0xFF262626),
            accent = c(0xFFFFD60A),
        ),
    )
}
