package rocks.pkb.plektra

import android.content.Context
import android.widget.TextView
import rocks.pkb.plektra.backup.BackupPreferenceContract
import rocks.pkb.plektra.backup.PreferenceValueType
import rocks.pkb.plektra.inputmethod.ui.VariationBarView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyboardThemeNameResolverTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val target = SettingsManager.KeyboardThemeTarget.HARDWARE
    private val day = LocalDate.now().toEpochDay()
    private val first = SettingsManager.defaultKeyboardTheme().copy(background = 0xff123456.toInt())
    private val second = first.copy(background = 0xff654321.toInt())
    private fun name(theme: SettingsManager.KeyboardThemeSettings = first, locale: String? = null,
        layout: String? = null, at: Long = day) = KeyboardThemeNameResolver.resolve(context,target,locale,layout,theme,at)
    @Before fun setup() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.saveKeyboardTheme(context,"A",first)
        SettingsManager.saveKeyboardTheme(context,"B",second)
        SettingsManager.setKeyboardTheme(context,target,first)
    }
    @Test fun fixedThemeUsesItsSavedTitle() { assertEquals("A",name()) }
    @Test fun renameInvalidatesCachedLabelWithoutColourChange() {
        assertEquals("A",name())
        SettingsManager.deleteKeyboardTheme(context,"A")
        SettingsManager.saveKeyboardTheme(context,"A renommé",first)
        assertEquals("A renommé",name())
    }
    @Test fun scopedCycleChoosesTheNamedEntryEvenWhenBothThemesHaveIdenticalColours() {
        SettingsManager.saveKeyboardTheme(context,"B",first)
        SettingsManager.upsertKeyboardThemeLayoutOverride(context,target,"fr","azerty",first,
            SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY,listOf("A","B"),day)
        assertEquals("A",name(locale="fr-FR",layout="azerty"))
        assertEquals("B",name(locale="fr-FR",layout="azerty",at=day+1))
        assertEquals("A",name(locale="fr-FR",layout="azerty",at=day+2))
    }
    @Test fun languageLayoutPriorityMatchesTheRenderedTheme() {
        SettingsManager.upsertKeyboardThemeLayoutOverride(context,target,"fr",null,first,
            SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY,listOf("A"),day)
        SettingsManager.upsertKeyboardThemeLayoutOverride(context,target,"fr","azerty",second,
            SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY,listOf("B"),day)
        assertEquals("B",name(second,"fr-CA","azerty"))
        assertEquals("A",name(first,"fr-FR","qwerty"))
        assertEquals("A",name(first,"de-DE","qwertz"))
    }
    @Test fun globalCycleUsesTheSameCalendarAnchorAsTheTheme() {
        DailyThemeRotation.select(context,target,listOf("B","A"))
        SettingsManager.setKeyboardThemeAssignmentMode(context,target,SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY)
        assertEquals("B",name(second))
        assertEquals("A",name(first,at=day+1))
    }
    @Test fun deletedCycleEntriesAreSkippedAndEmptyCycleUsesFallbackTitle() {
        SettingsManager.upsertKeyboardThemeLayoutOverride(context,target,"fr","azerty",first,
            SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY,listOf("missing","B"),day)
        assertEquals("B",name(second,"fr","azerty"))
        SettingsManager.deleteKeyboardTheme(context,"B")
        assertEquals("A",name(first,"fr","azerty"))
    }
    @Test fun builtInNamesMatchBothKeyboardTargets() {
        val preset=keyboardThemePresets().first()
        assertEquals(preset.name,name(preset.toSettingsTheme()))
        val software=preset.withSoftwareKeyboardDefaults().toSettingsTheme()
        assertEquals(preset.name,KeyboardThemeNameResolver.resolve(context,
            SettingsManager.KeyboardThemeTarget.SOFTWARE,null,null,software))
    }
    @Test fun unsavedThemeUsesTheLocalizedCustomLabel() {
        assertEquals(context.getString(R.string.keyboard_theme_label_custom),name(first.copy(background=12345)))
    }
    @Test fun titleDisplayCanBeDisabledAndItsPreferenceIsBackedUpAsBoolean() {
        assertTrue(SettingsManager.isKeyboardThemeNameShown(context))
        SettingsManager.setKeyboardThemeNameShown(context,false)
        assertFalse(SettingsManager.isKeyboardThemeNameShown(context))
        assertTrue(SettingsManager.isKeyboardThemePreferenceKey(SettingsManager.KEY_KEYBOARD_THEME_SHOW_NAME))
        assertEquals(PreferenceValueType.BOOLEAN,
            BackupPreferenceContract.expectedExportType("plektra_prefs",SettingsManager.KEY_KEYBOARD_THEME_SHOW_NAME))
    }
    @Test fun themeTitleUsesConfiguredSwipeColourAndToggleRestoresOldHintColour() {
        val bar=VariationBarView(context)
        bar.themeOverride=first.copy(background=android.graphics.Color.WHITE,
            textAndIcons=android.graphics.Color.WHITE,cursorSwipe=android.graphics.Color.BLACK).toKeyboardThemeColors()
        bar.themeName="Pale theme"
        bar.ensureView()
        val field=VariationBarView::class.java.getDeclaredField("emptyHintView").apply { isAccessible=true }
        val hint=field.get(bar) as TextView
        assertEquals(android.graphics.Color.BLACK,hint.currentTextColor)
        bar.themeName=null
        assertEquals(0x78ffffff,hint.currentTextColor)
        bar.themeName="Pale theme"
        assertEquals(android.graphics.Color.BLACK,hint.currentTextColor)
    }
    @Test fun unreadableTitleColourAdaptsToWhiteOrBlackWithoutChangingSavedTheme() {
        val bar=VariationBarView(context)
        val pale=first.copy(background=android.graphics.Color.WHITE,cursorSwipe=android.graphics.Color.WHITE)
        bar.themeOverride=pale.toKeyboardThemeColors()
        bar.themeName="Pale theme"
        bar.ensureView()
        val field=VariationBarView::class.java.getDeclaredField("emptyHintView").apply { isAccessible=true }
        val hint=field.get(bar) as TextView
        assertEquals(android.graphics.Color.BLACK,hint.currentTextColor)
        bar.themeOverride=first.copy(background=android.graphics.Color.BLACK,cursorSwipe=android.graphics.Color.BLACK).toKeyboardThemeColors()
        assertEquals(android.graphics.Color.WHITE,hint.currentTextColor)
        assertEquals(android.graphics.Color.WHITE,pale.cursorSwipe)
        assertEquals(first,SettingsManager.getKeyboardTheme(context,target))
    }
    @Test fun hintUpdatesLongTitlesAndRestoresLatestTitleAfterSpeechPrompt() {
        val bar=VariationBarView(context)
        bar.themeName="A"
        bar.ensureView()
        val field=VariationBarView::class.java.getDeclaredField("emptyHintView").apply { isAccessible=true }
        val hint=field.get(bar) as TextView
        assertEquals("A",hint.text.toString())
        assertEquals(1,hint.maxLines)
        bar.showSpeechRecognitionHint(true)
        bar.themeName="Un titre de thème très long pour vérifier son affichage"
        assertEquals(context.getString(R.string.speech_recognition_prompt),hint.text.toString())
        bar.showSpeechRecognitionHint(false)
        assertEquals(bar.themeName,hint.text.toString())
        bar.themeName=null
        assertEquals(context.getString(R.string.swipe_to_move_cursor),hint.text.toString())
    }
}
