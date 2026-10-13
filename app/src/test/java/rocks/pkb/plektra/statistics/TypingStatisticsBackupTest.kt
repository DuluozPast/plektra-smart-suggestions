package rocks.pkb.plektra.statistics

import android.os.Looper
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.backup.PreferencesBackupHelper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TypingStatisticsBackupTest {
    @Test fun countersSurvivePreferenceExportAndRestore() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = SettingsManager.getPreferences(context)
        preferences.edit().putLong(TypingStatisticsStore.MANUAL_KEY, 12)
            .putLong(TypingStatisticsStore.SUGGESTED_KEY, 20).commit()
        val store = TypingStatisticsStore.get(context)
        store.record(1, 2)
        store.flush()
        preferences.edit().commit()
        val directory = File(context.cacheDir, "statistics-backup").apply { mkdirs() }
        val components = PreferencesBackupHelper.dumpSharedPreferences(context, directory, false)
        assertTrue(components.contains("prefs/plektra_prefs.json"))
        val entries = JSONObject(File(directory, "plektra_prefs.json").readText()).getJSONObject("entries")
        assertEquals(13L, entries.getJSONObject(TypingStatisticsStore.MANUAL_KEY).getLong("value"))
        assertEquals(22L, entries.getJSONObject(TypingStatisticsStore.SUGGESTED_KEY).getLong("value"))
        preferences.edit().putLong(TypingStatisticsStore.MANUAL_KEY, 0)
            .putLong(TypingStatisticsStore.SUGGESTED_KEY, 0).commit()
        PreferencesBackupHelper.restorePreferences(context, PreferencesBackupHelper.readPreferencesFromBackup(directory))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(TypingStatisticsSnapshot(13, 22), store.snapshot.value)
    }
}
