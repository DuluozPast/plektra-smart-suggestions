package rocks.pkb.plektra

import android.content.Context
import rocks.pkb.plektra.backup.PreferencesBackupHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScopedThemeRotationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val target = SettingsManager.KeyboardThemeTarget.HARDWARE
    private val day get() = LocalDate.now().toEpochDay()
    private val fixed get() = SettingsManager.defaultKeyboardTheme().copy(background = 0xFF001122.toInt())
    private val first get() = fixed.copy(background = 0xFF112233.toInt())
    private val second get() = fixed.copy(background = 0xFF445566.toInt())
    @Before fun reset() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setKeyboardTheme(context, target, fixed)
        SettingsManager.setKeyboardThemeAssignmentMode(context, target, SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_FIXED)
        SettingsManager.saveKeyboardTheme(context, "A", first)
        SettingsManager.saveKeyboardTheme(context, "B", second)
    }
    private fun save(names: List<String> = listOf("A", "B"), anchor: Long = day,
        locale: String = "fr", layout: String? = "azerty") {
        SettingsManager.upsertKeyboardThemeLayoutOverride(context, target, locale, layout, fixed,
            SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY, names, anchor)
    }
    @Test fun matchingLanguageLayoutRotatesWhileOtherLanguagesKeepTheirOwnTheme() {
        save(anchor = day - 1)
        assertEquals(second, SettingsManager.getEffectiveKeyboardTheme(context, target, "fr-FR", "azerty"))
        assertEquals(fixed, SettingsManager.getEffectiveKeyboardTheme(context, target, "de-DE", "qwertz"))
        assertEquals(fixed, SettingsManager.getEffectiveKeyboardTheme(context, target, "fr", "qwerty"))
        assertEquals(SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_FIXED, SettingsManager.getKeyboardThemeAssignmentMode(context, target))
        assertTrue(DailyThemeRotation.hasDailyRotation(context))
    }
    @Test fun languageOnlyAndMoreSpecificLayoutAssignmentsRemainIndependent() {
        save(listOf("A"), locale = "fr", layout = null)
        save(listOf("B"), locale = "fr", layout = "azerty")
        assertEquals(second, SettingsManager.getEffectiveKeyboardTheme(context, target, "fr-CA", "azerty"))
        assertEquals(first, SettingsManager.getEffectiveKeyboardTheme(context, target, "fr-FR", "qwerty"))
    }
    @Test fun cycleUsesCalendarDaysAndOneThemeRemainsConstant() {
        save()
        val assignment = SettingsManager.getKeyboardThemeLayoutOverrides(context, target).single()
        for (offset in 0L..7L) assertEquals(if (offset % 2 == 0L) first else second,
            DailyThemeRotation.themeForOverride(context, target, assignment, day + offset))
        save(listOf("B"))
        val single = SettingsManager.getKeyboardThemeLayoutOverrides(context, target).single()
        for (offset in 0L..14L) assertEquals(second, DailyThemeRotation.themeForOverride(context, target, single, day + offset))
    }
    @Test fun legacyAssignmentsRemainFixedAndNewFieldsSurviveSerialization() {
        SettingsManager.upsertKeyboardThemeLayoutOverride(context, target, "fr", "azerty", fixed)
        val prefs = SettingsManager.getPreferences(context)
        val key = "keyboard_theme_layout_overrides_hardware"
        val array = org.json.JSONArray(prefs.getString(key, "[]"))
        array.getJSONObject(0).remove("mode")
        array.getJSONObject(0).remove("rotationThemeNames")
        array.getJSONObject(0).remove("rotationAnchorDay")
        prefs.edit().putString(key, array.toString()).commit()
        val legacy = SettingsManager.getKeyboardThemeLayoutOverrides(context, target).single()
        assertEquals(SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_FIXED, legacy.mode)
        assertEquals(fixed, SettingsManager.getEffectiveKeyboardTheme(context, target, "fr", "azerty"))
        save(listOf("B", "A"), day - 3)
        val new = SettingsManager.getKeyboardThemeLayoutOverrides(context, target).single()
        assertEquals(listOf("B", "A"), new.rotationThemeNames)
        assertEquals(day - 3, new.rotationAnchorDay)
        assertEquals(first, SettingsManager.getEffectiveKeyboardTheme(context, target, "fr", "azerty"))
    }
    @Test fun deletedThemesAreSkippedAndAnEmptyCycleKeepsTheStoredFallback() {
        save()
        SettingsManager.deleteKeyboardTheme(context, "A")
        assertEquals(second, SettingsManager.getEffectiveKeyboardTheme(context, target, "fr", "azerty"))
        SettingsManager.deleteKeyboardTheme(context, "B")
        assertEquals(fixed, SettingsManager.getEffectiveKeyboardTheme(context, target, "fr", "azerty"))
    }
    @Test fun savedThemeEditsAndDifferentAssignmentsInvalidateTheSmallResolutionCache() {
        save(listOf("A"))
        assertEquals(first, SettingsManager.getEffectiveKeyboardTheme(context, target, "fr", "azerty"))
        SettingsManager.saveKeyboardTheme(context, "A", second)
        assertEquals(second, SettingsManager.getEffectiveKeyboardTheme(context, target, "fr", "azerty"))
        save(listOf("B"), locale = "de", layout = "qwertz")
        assertEquals(second, SettingsManager.getEffectiveKeyboardTheme(context, target, "de", "qwertz"))
        assertEquals(second, SettingsManager.getEffectiveKeyboardTheme(context, target, "fr", "azerty"))
    }
    @Test fun backupExportRestoreKeepsScopedCycleOrderAnchorAndSavedThemes() {
        save(listOf("B", "A"), day - 3)
        SettingsManager.getPreferences(context).edit().commit()
        val expected = SettingsManager.getKeyboardThemeLayoutOverrides(context, target)
        val dir = File(context.cacheDir, "scoped-theme-backup-test").apply { mkdirs() }
        try {
            PreferencesBackupHelper.dumpSharedPreferences(context, dir, false)
            val exported = PreferencesBackupHelper.readPreferencesFromBackup(dir)
            SettingsManager.getPreferences(context).edit().clear().commit()
            val result = PreferencesBackupHelper.restorePreferences(context, exported)
            assertTrue(result.skippedKeys.isEmpty())
            assertEquals(expected, SettingsManager.getKeyboardThemeLayoutOverrides(context, target))
            assertEquals(listOf("A", "B"), SettingsManager.getSavedKeyboardThemes(context).map { it.name })
            assertEquals(first, SettingsManager.getEffectiveKeyboardTheme(context, target, "fr", "azerty"))
        } finally { dir.deleteRecursively() }
    }
}
