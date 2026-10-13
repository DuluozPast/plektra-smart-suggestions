package rocks.pkb.plektra.backup

import android.net.Uri
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.suggestions.UserNGramStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IntegrationLearningMigrationTest {
    @Test fun restoresIntegrationPreferencesAndRealNumericLearningCounts() = runBlocking {
        org.robolectric.shadows.ShadowLog.stream = System.out
        val context = rocks.pkb.plektra.test.ShortPathContext.wrap(RuntimeEnvironment.getApplication())
        LearningOperationGate.prepareForRestore = null
        val archive = File(context.cacheDir, "legacy-integration.zip")
        javaClass.classLoader!!.getResourceAsStream("integration-learning-fixture.zip")!!.use { input ->
            archive.outputStream().use { input.copyTo(it) }
        }
        val result = RestoreManager.restore(context, Uri.fromFile(archive))
        assertTrue(result.toString(), result is RestoreResult.Success)
        val prefs = SettingsManager.getPreferences(context)
        assertTrue(prefs.getBoolean("multi_word_suggestions_enabled", false))
        assertEquals(200L, prefs.getLong("typing_statistics_manual_words", 0))
        assertTrue(prefs.getBoolean("trackpad_calibration_enabled", false))
        UserNGramStore(context).use { store ->
            val numbers = store.predict("fr-FR", "a", 3)
            assertEquals("11", numbers.first().word)
            assertEquals(27, numbers.first().count)
            assertEquals(1, numbers.first { it.word == "ZZ" }.count)
        }
        val exported = File(context.cacheDir, "learning-export")
        LearningBackupHelper.export(context, exported)
        assertTrue(File(exported, "next_word_usage.json").readText().contains("11"))
    }
}
