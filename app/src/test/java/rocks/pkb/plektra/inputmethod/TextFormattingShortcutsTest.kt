package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import java.util.Locale
import rocks.pkb.plektra.core.AutoSpaceTracker
import rocks.pkb.plektra.inputmethod.suggestions.SuggestionButtonHandler
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.ModifierStateController
import rocks.pkb.plektra.core.NavModeController
import rocks.pkb.plektra.data.mappings.KeyMappingLoader
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TextFormattingShortcutsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var input: Buffer
    @Before fun setup() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setNavModeCtrlHoldEnabled(context, false)
        input = Buffer(context)
        AutoSpaceTracker.clear()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
    }
    private fun editor(app: String = "com.microsoft.office.word") = EditorInfo().apply {
        packageName = app; inputType = InputType.TYPE_CLASS_TEXT
    }
    private fun format(action: String, app: String = "com.whatsapp", event: KeyEvent? = null) =
        TextFormattingShortcuts.handle(context, input, editor(app), action, event)

    @Test fun nativeEditorsReceiveBalancedStandardChords() {
        for ((action, key) in listOf("format_bold" to KeyEvent.KEYCODE_B, "format_italic" to KeyEvent.KEYCODE_I, "format_underline" to KeyEvent.KEYCODE_U)) {
            input.events.clear()
            assertTrue(format(action, "com.microsoft.office.word"))
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), input.events.map { it.action })
            assertTrue(input.events.all { it.keyCode == key && it.isCtrlPressed })
            assertEquals("", input.value)
        }
    }
    @Test fun whatsappItalicPressesOpenAndCloseTheSameMarker() {
        format("format_italic"); input.commitText("bonjour", 1); format("format_italic")
        assertEquals("_bonjour_", input.value)
        assertTrue(input.events.isEmpty())
    }
    @Test fun whatsappBusinessUsesSingleAsteriskForBold() {
        format("format_bold", "com.whatsapp.w4b"); input.commitText("bonjour", 1); format("format_bold", "com.whatsapp.w4b")
        assertEquals("*bonjour*", input.value)
    }
    @Test fun selectedTextCanBeWrappedAndUnwrappedWithoutTouchingSurroundings() {
        input.commitText("avant bonjour après", 1); input.setSelection(6, 13)
        format("format_italic")
        assertEquals("avant _bonjour_ après", input.value)
        input.setSelection(6, 15); format("format_italic")
        assertEquals("avant bonjour après", input.value)
    }
    @Test fun redditDefaultsToNativeFormatting() {
        format("format_bold", "com.reddit.frontpage")
        assertEquals(listOf(KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_B), input.events.map { it.keyCode })
        assertEquals("", input.value)
    }
    @Test fun redditMarkdownUsesDoubleAsterisksForBold() {
        SettingsManager.setRedditMarkdownFormatting(context, true)
        format("format_bold", "com.reddit.frontpage"); input.commitText("bonjour", 1); format("format_bold", "com.reddit.frontpage")
        assertEquals("**bonjour**", input.value)
    }
    @Test fun redditMarkdownUsesSingleAsterisksForItalic() {
        SettingsManager.setRedditMarkdownFormatting(context, true)
        format("format_italic", "com.reddit.frontpage"); input.commitText("bonjour", 1); format("format_italic", "com.reddit.frontpage")
        assertEquals("*bonjour*", input.value)
    }
    @Test fun unsupportedMessagingFormatsDoNotAlterTheMessage() {
        input.commitText("bonjour", 1)
        format("format_underline")
        SettingsManager.setRedditMarkdownFormatting(context, true)
        format("format_underline", "com.reddit.frontpage")
        assertEquals("bonjour", input.value); assertTrue(input.events.isEmpty())
    }
    @Test fun passwordFieldsAreNotEdited() {
        val info = editor("com.whatsapp").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        TextFormattingShortcuts.handle(context, input, info, "format_bold", null)
        assertEquals("", input.value); assertTrue(input.events.isEmpty())
    }
    @Test fun formattingPreferencesAreIncludedInTheBackupContract() {
        for (key in listOf("french_formatting_shortcuts", "reddit_markdown_formatting")) {
            assertEquals(rocks.pkb.plektra.backup.PreferenceValueType.BOOLEAN,
                rocks.pkb.plektra.backup.BackupPreferenceContract.expectedExportType("plektra_prefs", key))
        }
    }
    @Test fun keyRepeatDoesNotInsertAdditionalMarkers() {
        val repeatEvent = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I, 2)
        format("format_italic", event = repeatEvent)
        assertEquals("", input.value)
    }
    private fun route(key: Int, app: String = "com.microsoft.office.word", nav: Boolean = false,
                      oneShot: Boolean = false, allow: Boolean = true,
                      mappings: Map<Int, KeyMappingLoader.CtrlMapping> = emptyMap(), clear: () -> Unit = {}, locale: Locale = Locale.FRANCE) {
        val router = InputEventRouter(context, NavModeController(context, ModifierStateController(300L)))
        router.editorInfoProvider = { editor(app) }
        router.typingLocaleProvider = { locale }
        assertTrue(router.handleCtrlModifiedKey(key, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, key, 0, KeyEvent.META_CTRL_ON), input,
            mappings, nav, oneShot, !oneShot, clearCtrlOneShot = clear, updateStatusBar = {}, callSuper = { false },
            toggleMinimalUi = {}, allowFormatting = allow))
    }
    @Test fun frenchPresetTranslatesGToNativeB() {
        route(KeyEvent.KEYCODE_G)
        assertEquals(listOf(KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_B), input.events.map { it.keyCode })
    }
    @Test fun oneShotFnIsConsumedOnceAndUsesTheActiveMessagingApp() {
        var cleared = 0
        route(KeyEvent.KEYCODE_I, "com.whatsapp", oneShot = true, clear = { cleared++ })
        assertEquals(1, cleared); assertEquals("_", input.value)
    }
    @Test fun disablingPresetRestoresNativeG() {
        SettingsManager.setFrenchFormattingShortcuts(context, false)
        route(KeyEvent.KEYCODE_G)
        assertEquals(listOf(KeyEvent.KEYCODE_G), input.events.map { it.keyCode })
    }
    @Test fun heldNavigationRetainsItsOriginalSMapping() {
        SettingsManager.setNavModeCtrlHoldEnabled(context, true)
        route(KeyEvent.KEYCODE_S, mappings = mapOf(KeyEvent.KEYCODE_S to KeyMappingLoader.CtrlMapping("keycode", "DPAD_LEFT")))
        assertTrue(input.events.all { it.keyCode == KeyEvent.KEYCODE_DPAD_LEFT })
    }
    @Test fun explicitFormattingCanBeAssignedToAnotherNavigationKey() {
        route(KeyEvent.KEYCODE_T, "com.whatsapp", nav = true,
            mappings = mapOf(KeyEvent.KEYCODE_T to KeyMappingLoader.CtrlMapping("action", "format_bold")))
        assertEquals("*", input.value)
    }
    @Test fun internalSearchCanBypassFormatting() {
        route(KeyEvent.KEYCODE_I, "com.whatsapp", allow = false)
        assertEquals("", input.value)
        assertEquals(listOf(KeyEvent.KEYCODE_I), input.events.map { it.keyCode })
    }
    private fun acceptBonjour() {
        input.commitText("bonj", 1)
        SuggestionButtonHandler.createSuggestionClickListener("bonjour", input,
            shouldDisableAutoCapitalize = true).onClick(View(context))
    }
    @Test fun acceptedSuggestionSpaceMovesOutsideWhatsappBold() {
        format("format_bold"); acceptBonjour()
        assertEquals("*bonjour ", input.value)
        format("format_bold")
        assertEquals("*bonjour* ", input.value)
    }
    @Test fun acceptedSuggestionSpaceMovesOutsideWhatsappItalic() {
        format("format_italic"); acceptBonjour(); format("format_italic")
        assertEquals("_bonjour_ ", input.value)
    }
    @Test fun acceptedSuggestionSpaceMovesOutsideBothRedditMarkerWidths() {
        SettingsManager.setRedditMarkdownFormatting(context, true)
        format("format_bold", "com.reddit.frontpage"); acceptBonjour(); format("format_bold", "com.reddit.frontpage")
        assertEquals("**bonjour** ", input.value)
        input = Buffer(context); AutoSpaceTracker.clear()
        format("format_italic", "com.reddit.frontpage"); acceptBonjour(); format("format_italic", "com.reddit.frontpage")
        assertEquals("*bonjour* ", input.value)
    }
    @Test fun openingMarkerKeepsTheSeparatorAfterASuggestion() {
        acceptBonjour(); format("format_bold")
        assertEquals("bonjour *", input.value)
    }
    @Test fun manuallyTypedSpaceIsNotRemoved() {
        format("format_italic"); input.commitText("bonjour ", 1); format("format_italic")
        assertEquals("_bonjour _", input.value)
    }
    @Test fun editorSwitchDoesNotConsumeAnotherEditorsSpace() {
        format("format_bold"); acceptBonjour()
        input = Buffer(context); input.commitText("*autre ", 1); format("format_bold")
        assertEquals("*autre *", input.value)
    }
    @Test fun closedOrEscapedMarkersDoNotConsumeAnOpeningSeparator() {
        input.commitText("*ancien* ", 1); AutoSpaceTracker.markSuggestionAutoSpace(input)
        format("format_bold"); assertEquals("*ancien* *", input.value)
        input = Buffer(context); input.commitText("\\*ancien ", 1); AutoSpaceTracker.markSuggestionAutoSpace(input)
        format("format_bold"); assertEquals("\\*ancien *", input.value)
    }
    @Test fun changingTypingLanguageChangesThePresetWithoutChangingTheAppLanguage() {
        route(KeyEvent.KEYCODE_G, locale = Locale.CANADA_FRENCH)
        route(KeyEvent.KEYCODE_G, locale = Locale.ENGLISH)
        route(KeyEvent.KEYCODE_B, locale = Locale.ENGLISH)
        route(KeyEvent.KEYCODE_S, locale = Locale.FRENCH)
        assertEquals(listOf(KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_G,
            KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_U), input.events.map { it.keyCode })
    }
    private class Buffer(context: Context) : BaseInputConnection(View(context), true) {
        private val text = SpannableStringBuilder().apply { Selection.setSelection(this, 0) }
        val events = mutableListOf<KeyEvent>()
        val value get() = text.toString()
        override fun getEditable(): Editable = text
        override fun sendKeyEvent(event: KeyEvent): Boolean { events.add(event); return true }
    }
}
