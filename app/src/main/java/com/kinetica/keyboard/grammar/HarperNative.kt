package com.kinetica.keyboard.grammar

/**
 * The native Harper bridge, libduet_harper.so, built from native/harper-jni. Nothing here
 * loads the library: [HarperGrammar] does on first use, and a JVM test loads a host build
 * by path. Every call but [version] must come from one thread at a time.
 */
object HarperNative {
    /** A checker for [dialect] (0 American, 1 British); 0 never comes back. */
    @JvmStatic external fun create(dialect: Int): Long

    /** [text]'s problems, encoded as engine/HarperLints.parse reads them. */
    @JvmStatic external fun lint(handle: Long, text: String): String

    @JvmStatic external fun destroy(handle: Long)

    /** The harper-core release the library was built from. */
    @JvmStatic external fun version(): String

    const val LIBRARY = "duet_harper"
}
