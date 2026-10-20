package com.kinetica.keyboard.ime

/**
 * Which copied images a field can take. A field lists the types it accepts in
 * EditorInfo.contentMimeTypes (a chat box: "image/png", "image/gif", or the wildcard image type); a field that
 * lists none takes text only, and the bar offers it no image.
 *
 * Pure, so the matching runs on the JVM: the same rule as ClipDescription.compareMimeTypes.
 */
object RichContent {

    /** True when a field accepting [fieldTypes] takes content of type [mime]. */
    fun accepts(fieldTypes: Array<String>?, mime: String): Boolean =
        fieldTypes?.any { matches(it, mime) } == true

    /** The first image type among a clip's [types], or null when it carries no image. */
    fun imageMime(types: List<String>): String? =
        types.firstOrNull { it.lowercase().startsWith("image/") && !it.endsWith("/*") }

    /** [pattern] (an exact type, or one with a star for either half) against a concrete [mime], ignoring case. */
    private fun matches(pattern: String, mime: String): Boolean {
        val p = pattern.lowercase().split('/')
        val m = mime.lowercase().split('/')
        if (p.size != 2 || m.size != 2) return false
        return (p[0] == "*" || p[0] == m[0]) && (p[1] == "*" || p[1] == m[1])
    }
}
