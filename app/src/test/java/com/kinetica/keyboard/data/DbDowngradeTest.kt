package com.kinetica.keyboard.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/** A database a newer Duet wrote opens with its learned words, and the newer file is kept (review L2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DbDowngradeTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun aNewerFileIsKeptAndItsWordsComeBack() {
        val file = context.getDatabasePath("user_dict.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // A future schema: one more column on user_words and a table this build lacks.
            db.execSQL(
                "CREATE TABLE user_words (word TEXT NOT NULL, lang TEXT NOT NULL, " +
                    "frequency INTEGER NOT NULL, updatedAt INTEGER NOT NULL, display TEXT, " +
                    "caseVotes INTEGER NOT NULL DEFAULT 0, future TEXT, PRIMARY KEY(word, lang))",
            )
            db.execSQL("INSERT INTO user_words VALUES ('duet', 'en', 7, 100, 'Duet', 2, 'x')")
            db.execSQL("INSERT INTO user_words VALUES ('ciao', 'it', 3, 200, NULL, 0, NULL)")
            db.execSQL("CREATE TABLE later_feature (id INTEGER PRIMARY KEY)")
            db.version = KineticaDb.VERSION + 1
        }

        // Room refuses the main thread, which is the test's own here.
        val (count, en) = Executors.newSingleThreadExecutor().submit<Pair<Int, List<UserWord>>> {
            val words = KineticaDb.get(context).userWords()
            words.count() to words.allForLanguage("en")
        }.get()
        assertEquals(2, count)
        val duet = en.single()
        assertEquals("duet", duet.word)
        assertEquals(7, duet.frequency)
        assertEquals("Duet", duet.display)
        assertEquals(2, duet.caseVotes)

        val kept = context.getDatabasePath("user_dict.db.v${KineticaDb.VERSION + 1}")
        assertTrue("the newer file is kept", kept.exists())
        SQLiteDatabase.openDatabase(kept.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(KineticaDb.VERSION + 1, it.version)
        }
    }

    @Test
    fun aCurrentOrMissingFileIsLeftAlone() {
        assertNull(DbDowngrade.setAside(context, "absent.db", KineticaDb.VERSION))
        val file = context.getDatabasePath("same.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = KineticaDb.VERSION }
        assertNull(DbDowngrade.setAside(context, "same.db", KineticaDb.VERSION))
        assertTrue(file.exists())
    }
}
