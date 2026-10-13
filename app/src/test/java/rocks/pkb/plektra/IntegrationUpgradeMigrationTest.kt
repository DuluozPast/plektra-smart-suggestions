package rocks.pkb.plektra

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IntegrationUpgradeMigrationTest {
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getPackageName() = "it.palsoftware.pastiera.custom.multimotstest"
    }
    @Before fun setup() {
        listOf("pastiera_prefs", "plektra_prefs", "integration_upgrade_migration").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    @Test fun migratesCountersCalibrationAndMultiwordsWithoutDeletingLegacy() {
        val legacy = context.getSharedPreferences("pastiera_prefs", Context.MODE_PRIVATE)
        legacy.edit().putLong("typing_statistics_manual_words", 200L)
            .putLong("typing_statistics_suggested_words", 40L)
            .putBoolean("multi_word_suggestions_enabled", true)
            .putBoolean("trackpad_calibration_enabled", true)
            .putFloat("trackpad_calibration_left_center_boundary", .3f)
            .putString("app_enter_send_shift_side", "right").commit()
        assertEquals(6, IntegrationUpgradeMigration.migrate(context))
        val target = SettingsManager.getPreferences(context)
        assertEquals(200L, target.getLong("typing_statistics_manual_words", 0))
        assertEquals(40L, target.getLong("typing_statistics_suggested_words", 0))
        assertTrue(target.getBoolean("multi_word_suggestions_enabled", false))
        assertEquals(.3f, target.getFloat("trackpad_calibration_left_center_boundary", 0f), .001f)
        assertEquals("right", target.getString("app_enter_send_shift_side", null))
        assertEquals(200L, legacy.getLong("typing_statistics_manual_words", 0))
        assertEquals(0, IntegrationUpgradeMigration.migrate(context))
    }
    @Test fun keepsExistingPlektraPreferenceAndMigratesSlimAlias() {
        context.getSharedPreferences("pastiera_prefs", Context.MODE_PRIVATE).edit()
            .putString("pastierina_mode_override", "pastierina")
            .putLong("typing_statistics_manual_words", 100L).commit()
        SettingsManager.getPreferences(context).edit().putLong("typing_statistics_manual_words", 300L).commit()
        IntegrationUpgradeMigration.migrate(context)
        assertEquals(300L, SettingsManager.getPreferences(context).getLong("typing_statistics_manual_words", 0))
        assertEquals("slim", SettingsManager.getPreferences(context).getString("slim_mode_override", null))
    }
}
