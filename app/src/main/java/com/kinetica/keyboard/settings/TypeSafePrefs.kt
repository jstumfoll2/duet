package com.kinetica.keyboard.settings

import android.content.SharedPreferences

/**
 * [base] with every typed read falling back to its default when the stored value has another
 * type. SharedPreferences throws ClassCastException then, and a setting read on the keyboard's
 * start would crash it on every start, with no way to type a fix. The keys found mistyped are
 * remembered; [removeMistyped] deletes them, so they read as their defaults everywhere after.
 */
class TypeSafePrefs(private val base: SharedPreferences) : SharedPreferences by base {

    private val mistyped = LinkedHashSet<String>()

    private inline fun <T> safe(key: String?, def: T, read: () -> T): T =
        try {
            read()
        } catch (e: ClassCastException) {
            if (key != null) synchronized(mistyped) { mistyped.add(key) }
            def
        }

    override fun getString(key: String?, defValue: String?): String? = safe(key, defValue) { base.getString(key, defValue) }
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        safe(key, defValues) { base.getStringSet(key, defValues) }
    override fun getInt(key: String?, defValue: Int): Int = safe(key, defValue) { base.getInt(key, defValue) }
    override fun getLong(key: String?, defValue: Long): Long = safe(key, defValue) { base.getLong(key, defValue) }
    override fun getFloat(key: String?, defValue: Float): Float = safe(key, defValue) { base.getFloat(key, defValue) }
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = safe(key, defValue) { base.getBoolean(key, defValue) }

    /** The keys read so far whose stored value had the wrong type. */
    fun mistyped(): Set<String> = synchronized(mistyped) { mistyped.toSet() }

    /** Deletes the mistyped keys; true when there were any. */
    fun removeMistyped(): Boolean {
        val bad = mistyped()
        if (bad.isEmpty()) return false
        val e = base.edit()
        for (k in bad) e.remove(k)
        e.apply()
        return true
    }
}
