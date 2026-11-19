package com.kinetica.keyboard.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * Opening a database a newer Duet wrote (review L2). Room has no path down: without a migration
 * it throws on every open, and its destructive fallback would delete every learned word.
 *
 * Instead the newer file is kept beside the database, untouched, and a fresh one at [version]
 * takes its place with every row it can hold: for each of the fresh schema's tables, the columns
 * both files have are copied. Duet's schema changes have all been additive (new tables, added
 * columns), so a newer file has what an older build reads. Columns and tables the older build
 * does not know stay in the kept copy.
 */
object DbDowngrade {

    private const val TAG = "DbDowngrade"

    /**
     * Moves a newer database [name] aside before Room opens it. Returns the kept copy, or null
     * when the file is missing, not newer than [version], or could not be read.
     */
    fun setAside(context: Context, name: String, version: Int): File? {
        val file = context.getDatabasePath(name)
        if (!file.exists()) return null
        val found = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        } catch (e: RuntimeException) {
            Log.w(TAG, "can't read $name's version", e)
            return null
        }
        if (found <= version) return null
        val kept = File(file.parentFile, "$name.v$found")
        // A kept copy from an earlier downgrade is replaced only by a newer one.
        if (kept.exists()) kept.delete()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val from = File(file.path + suffix)
            if (from.exists() && !from.renameTo(File(kept.path + suffix))) {
                Log.w(TAG, "can't move $name$suffix aside")
                return null
            }
        }
        Log.w(TAG, "$name is version $found, newer than $version: kept as ${kept.name}")
        return kept
    }

    /**
     * Copies into [db], just created by Room, every row of [kept] that fits: the columns both
     * have, for each of [db]'s own tables. Rows that clash with ones already there are skipped.
     * Returns the rows copied. Reads [kept] through its own read-only connection, not ATTACH,
     * which would switch Room's connection out of write-ahead logging for the process.
     */
    fun restore(db: SupportSQLiteDatabase, kept: File): Int {
        var rows = 0
        SQLiteDatabase.openDatabase(kept.path, null, SQLiteDatabase.OPEN_READONLY).use { from ->
            val keptTables = tables { from.rawQuery(it, null) }.toSet()
            db.beginTransaction()
            try {
                for (table in tables { db.query(it) }) {
                    if (table !in keptTables) continue
                    val keptColumns = columns(table) { from.rawQuery(it, null) }.toSet()
                    val shared = columns(table) { db.query(it) }.filter { it in keptColumns }
                    if (shared.isEmpty()) continue
                    rows += try {
                        copy(from, db, table, shared)
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "can't copy $table back", e)
                        0
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return rows
    }

    private fun copy(from: SQLiteDatabase, to: SupportSQLiteDatabase, table: String, columns: List<String>): Int {
        var rows = 0
        val list = columns.joinToString(", ") { quote(it) }
        from.rawQuery("SELECT $list FROM ${quote(table)}", null).use { c ->
            while (c.moveToNext()) {
                val values = ContentValues(columns.size)
                for ((i, name) in columns.withIndex()) {
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> values.putNull(name)
                        Cursor.FIELD_TYPE_INTEGER -> values.put(name, c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> values.put(name, c.getDouble(i))
                        Cursor.FIELD_TYPE_BLOB -> values.put(name, c.getBlob(i))
                        else -> values.put(name, c.getString(i))
                    }
                }
                if (to.insert(table, SQLiteDatabase.CONFLICT_IGNORE, values) != -1L) rows++
            }
        }
        return rows
    }

    private fun tables(query: (String) -> Cursor): List<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildList { while (c.moveToNext()) c.getString(0)?.let { if (!ownTable(it)) add(it) } }
        }

    private fun columns(table: String, query: (String) -> Cursor): List<String> =
        query("PRAGMA table_info(${quote(table)})").use { c ->
            val at = c.getColumnIndex("name")
            buildList { while (c.moveToNext()) add(c.getString(at)) }
        }

    private fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

    /** Names that are Room's or SQLite's own, never copied. */
    fun ownTable(name: String): Boolean =
        name == "room_master_table" || name == "android_metadata" || name.startsWith("sqlite_")
}
