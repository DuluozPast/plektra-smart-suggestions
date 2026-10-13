
package rocks.pkb.plektra.backup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LearningSqliteInstrumentedTest {

    @Test
    fun vacuumIntoCreatesConsistentSnapshot() {
        val context = InstrumentationRegistry
            .getInstrumentation()
            .targetContext

        val databaseName = "pastiera_snapshot_test.db"
        val database = context.openOrCreateDatabase(
            databaseName,
            Context.MODE_PRIVATE,
            null
        )

        val snapshot = File(
            context.cacheDir,
            "pastiera_snapshot_test_copy.db"
        )

        try {
            snapshot.delete()

            database.execSQL(
                "CREATE TABLE IF NOT EXISTS test_entries " +
                        "(word TEXT PRIMARY KEY, count INTEGER NOT NULL)"
            )

            database.execSQL(
                "INSERT OR REPLACE INTO test_entries(word, count) " +
                        "VALUES('bonjour', 42)"
            )

            database.execSQL(
                "VACUUM INTO ?",
                arrayOf(snapshot.absolutePath)
            )

            assertTrue(snapshot.isFile)

            val snapshotDb = android.database.sqlite.SQLiteDatabase
                .openDatabase(
                    snapshot.absolutePath,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                )

            try {
                snapshotDb.rawQuery(
                    "SELECT count FROM test_entries WHERE word = 'bonjour'",
                    null
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(42, cursor.getInt(0))
                }
            } finally {
                snapshotDb.close()
            }

        } finally {
            database.close()
            snapshot.delete()
            context.deleteDatabase(databaseName)
        }
    }
}
