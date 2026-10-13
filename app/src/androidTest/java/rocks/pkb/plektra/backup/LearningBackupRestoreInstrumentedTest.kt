package rocks.pkb.plektra.backup

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.DatabaseErrorHandler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LearningBackupRestoreInstrumentedTest {

    @Test
    fun exportAndRestorePreservesLearnedPhraseCounts() {
        val base = InstrumentationRegistry
            .getInstrumentation()
            .targetContext

        val root = File(
            base.cacheDir,
            "isolated_learning_restore_test"
        )

        root.deleteRecursively()
        assertTrue(root.mkdirs())

        val context = IsolatedContext(base, root)

        try {
            val store =
                rocks.pkb.plektra.core.suggestions
                    .PhraseUsageStore(context)

            try {
                repeat(3) {
                    store.learn(
                        Locale.FRENCH,
                        listOf("bonjour", "monde")
                    )
                }

                assertEquals(
                    3,
                    store.getUsage(
                        Locale.FRENCH,
                        listOf("bonjour", "monde")
                    )?.count
                )
            } finally {
                store.close()
            }

            val backup = File(root, "backup")

            LearningBackupHelper.export(context, backup)
            LearningBackupHelper.validateBackup(backup)

            val changedStore =
                rocks.pkb.plektra.core.suggestions
                    .PhraseUsageStore(context)

            try {
                repeat(2) {
                    changedStore.learn(
                        Locale.FRENCH,
                        listOf("bonjour", "monde")
                    )
                }

                assertEquals(
                    5,
                    changedStore.getUsage(
                        Locale.FRENCH,
                        listOf("bonjour", "monde")
                    )?.count
                )
            } finally {
                changedStore.close()
            }

            LearningBackupHelper.importBackup(context, backup)

            val restoredStore =
                rocks.pkb.plektra.core.suggestions
                    .PhraseUsageStore(context)

            try {
                assertEquals(
                    3,
                    restoredStore.getUsage(
                        Locale.FRENCH,
                        listOf("bonjour", "monde")
                    )?.count
                )

                assertTrue(
                    restoredStore.predictNext(
                        Locale.FRENCH,
                        listOf("bonjour")
                    ).any { it.word == "monde" }
                )
            } finally {
                restoredStore.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failureAfterFirstDatabaseRestoresOriginalLearning() {
        val base = InstrumentationRegistry
            .getInstrumentation()
            .targetContext

        val root = File(
            base.cacheDir,
            "isolated_learning_rollback_test"
        )

        root.deleteRecursively()
        assertTrue(root.mkdirs())

        val context = IsolatedContext(base, root)

        try {
            val store =
                rocks.pkb.plektra.core.suggestions
                    .PhraseUsageStore(context)

            try {
                repeat(3) {
                    store.learn(
                        Locale.FRENCH,
                        listOf("bonjour", "monde")
                    )
                }
            } finally {
                store.close()
            }

            val backup = File(root, "backup")
            LearningBackupHelper.export(context, backup)

            val changedStore =
                rocks.pkb.plektra.core.suggestions
                    .PhraseUsageStore(context)

            try {
                repeat(2) {
                    changedStore.learn(
                        Locale.FRENCH,
                        listOf("bonjour", "monde")
                    )
                }
            } finally {
                changedStore.close()
            }

            var injectionCount = 0

            LearningBackupHelper.restoreFailureInjector = {
                injectionCount++
                if (injectionCount == 1) {
                    throw IllegalStateException("SIMULATED_RESTORE_FAILURE")
                }
            }

            try {
                var failedAsExpected = false

                try {
                    LearningBackupHelper.importBackup(context, backup)
                } catch (error: IllegalStateException) {
                    assertEquals(
                        "SIMULATED_RESTORE_FAILURE",
                        error.message
                    )
                    failedAsExpected = true
                }

                assertTrue(
                    "The simulated restore failure was not triggered",
                    failedAsExpected
                )
            } finally {
                LearningBackupHelper.restoreFailureInjector = null
            }

            val verifiedStore =
                rocks.pkb.plektra.core.suggestions
                    .PhraseUsageStore(context)

            try {
                assertEquals(
                    "Rollback must restore the original count of 5",
                    5,
                    verifiedStore.getUsage(
                        Locale.FRENCH,
                        listOf("bonjour", "monde")
                    )?.count
                )

                assertTrue(
                    verifiedStore.predictNext(
                        Locale.FRENCH,
                        listOf("bonjour")
                    ).any { it.word == "monde" }
                )
            } finally {
                verifiedStore.close()
            }

        } finally {
            LearningBackupHelper.restoreFailureInjector = null
            root.deleteRecursively()
        }
    }

    private class IsolatedContext(
        base: Context,
        private val root: File
    ) : ContextWrapper(base) {

        private val databases =
            File(root, "databases").apply { mkdirs() }

        private val files =
            File(root, "files").apply { mkdirs() }

        private val cache =
            File(root, "cache").apply { mkdirs() }

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = files

        override fun getCacheDir(): File = cache

        override fun getDatabasePath(name: String): File =
            File(databases, name)

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?
        ): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(
                getDatabasePath(name),
                factory
            )

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?
        ): SQLiteDatabase =
            SQLiteDatabase.openDatabase(
                getDatabasePath(name).absolutePath,
                factory,
                SQLiteDatabase.CREATE_IF_NECESSARY,
                errorHandler
            )

        override fun deleteDatabase(name: String): Boolean =
            getDatabasePath(name).delete()
    }
}
