package rocks.pkb.plektra

import android.view.inputmethod.EditorInfo
import android.text.InputType
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.backup.PreferenceSchemas
import rocks.pkb.plektra.backup.PreferenceValueType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DailyThemeRotationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val target = SettingsManager.KeyboardThemeTarget.HARDWARE
    @Before fun reset() { SettingsManager.getPreferences(context).edit().clear().commit() }
    @Test fun defaultsAreOptional() {
        // *Preserve Plektra's system theme default; daily rotation remains opt-in.*
        assertEquals(SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_FOLLOW_SYSTEM, SettingsManager.getKeyboardThemeAssignmentMode(context, target))
        assertEquals("", SettingsManager.getSpaceAfterPunctuation(context))
        assertEquals("", SettingsManager.getAutoSpacePunctuation(context))
        assertFalse(SettingsManager.getCommaSpace(context))
    }
    @Test fun oneThemeRemainsTheSameAndThreeCycleAcrossMissedDays() {
        for (day in 0L..14L) assertEquals(0, DailyThemeRotation.index(day, 0, 1))
        assertEquals(listOf(0,1,2,0,1,2,0), (0L..6L).map { DailyThemeRotation.index(it,0,3) })
        assertEquals(2,DailyThemeRotation.index(-1,0,3))
        assertEquals(1,DailyThemeRotation.index(100,0,3))
    }
    @Test fun selectedThemesPersistDeletedThemesAreSkippedAndCacheInvalidates() {
        val fixed = SettingsManager.defaultKeyboardTheme()
        val first = fixed.copy(background=0xFF112233.toInt())
        val second = fixed.copy(background=0xFF445566.toInt())
        SettingsManager.saveKeyboardTheme(context,"A",first)
        SettingsManager.saveKeyboardTheme(context,"B",second)
        DailyThemeRotation.select(context,target,listOf("A","B"))
        val today=LocalDate.now().toEpochDay()
        assertEquals(first,DailyThemeRotation.theme(context,target,today))
        assertEquals(second,DailyThemeRotation.theme(context,target,today+1))
        assertEquals(listOf("A","B"),DailyThemeRotation.selectedNames(context,target))
        SettingsManager.deleteKeyboardTheme(context,"A")
        assertEquals(second,DailyThemeRotation.theme(context,target,today))
        SettingsManager.deleteKeyboardTheme(context,"B")
        assertNull(DailyThemeRotation.theme(context,target,today))
    }
    @Test fun dailyModePersistsAndFixedModeDoesNotApplyRotation() {
        val fixed=SettingsManager.defaultKeyboardTheme()
        SettingsManager.setKeyboardTheme(context,target,fixed)
        val rotating=fixed.copy(background=0xFF112233.toInt())
        SettingsManager.saveKeyboardTheme(context,"A",rotating)
        DailyThemeRotation.select(context,target,listOf("A"))
        assertEquals(fixed,SettingsManager.getEffectiveKeyboardTheme(context,target))
        SettingsManager.setKeyboardThemeAssignmentMode(context,target,SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY)
        assertEquals("daily",SettingsManager.getKeyboardThemeAssignmentMode(context,target))
        assertEquals(rotating,SettingsManager.getEffectiveKeyboardTheme(context,target))
        SettingsManager.deleteKeyboardTheme(context,"A")
        assertEquals(fixed,SettingsManager.getEffectiveKeyboardTheme(context,target))
    }
    @Test fun rotationPreferencesAreIncludedInBackup() {
        SettingsManager.KeyboardThemeTarget.entries.forEach {
            assertEquals(PreferenceValueType.STRING,PreferenceSchemas.expectedType("plektra_prefs",DailyThemeRotation.namesKey(it)))
            assertEquals(PreferenceValueType.LONG,PreferenceSchemas.expectedType("plektra_prefs",DailyThemeRotation.anchorKey(it)))
        }
    }
    @Test fun localMidnightHandlesBothDstTransitions() {
        val zone=ZoneId.of("Europe/Paris")
        assertEquals(23*3600000L,DailyThemeRotationScheduler.delayToNextDay(ZonedDateTime.of(2026,3,29,0,0,0,0,zone)))
        assertEquals(25*3600000L,DailyThemeRotationScheduler.delayToNextDay(ZonedDateTime.of(2026,10,25,0,0,0,0,zone)))
    }
    @Test fun literalEditorsDisableAutomaticChangesWithoutTreatingThemAsPasswords() {
        val info=EditorInfo().apply { inputType=InputType.TYPE_CLASS_TEXT; privateImeOptions="other.option,pastiera.literal_entry" }
        val state=InputContextState.fromEditorInfo(info)
        assertTrue(state.shouldDisableAutoCapitalize)
        assertTrue(state.shouldDisableAutoCorrect)
        assertFalse(state.isPasswordField)
        assertFalse(state.shouldDisableVariations)
        info.privateImeOptions=null
        assertFalse(InputContextState.fromEditorInfo(info).shouldDisableAutoCapitalize)
    }
}
