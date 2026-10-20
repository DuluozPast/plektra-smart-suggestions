package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Looper
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.AutoSpaceTracker
import rocks.pkb.plektra.core.ModifierStateController
import rocks.pkb.plektra.core.NavModeController
import rocks.pkb.plektra.core.PunctuationAfterSpace
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MappedImmediateQuotesTest {
    private lateinit var context: Context
    private lateinit var manager: AlternateCharacterManager
    private lateinit var router: InputEventRouter
    private var disabled = false
    private var originalNotifications = 0
    private var pipelineCommits = 0

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setSmartQuotesMode(context, SettingsManager.SMART_QUOTES_IMMEDIATE)
        AutoSpaceTracker.clear(); PunctuationAfterSpace.clear()
        router = InputEventRouter(context, NavModeController(context, ModifierStateController(300L)))
        router.typingLocaleProvider = { Locale.FRENCH }
        router.onCommitText = { pipelineCommits++ }
        val prefs = context.getSharedPreferences("mapped_quote_test", Context.MODE_PRIVATE)
        prefs.edit().clear().putLong("long_press_threshold", 50L).commit()
        manager = AlternateCharacterManager(context.assets, prefs, context)
        manager.onSmartQuoteRequested = { text, input -> router.handleImmediateQuote(text, input, disabled) {} }
        manager.onAltCharInserted = { originalNotifications++ }
    }

    private fun altL(input: Input, mapped: String = "\"") {
        assertTrue(manager.handleAltCombination(KeyEvent.KEYCODE_L, input,
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L), mapOf(KeyEvent.KEYCODE_L to mapped)) { _, _ -> false })
    }

    @Test fun dedicatedAltLUsesImmediateQuotesAndDoesNotEmitRawCharacterAgain() {
        val input = Input("")
        altL(input)
        assertEquals("«\u202f", input.buffer.toString())
        input.commitText("Je ne sais pas.", 1)
        altL(input)
        assertEquals("«\u202fJe ne sais pas.\u202f»", input.buffer.toString())
        assertEquals(2, pipelineCommits)
        assertEquals(0, originalNotifications)
    }

    @Test fun mappedCurlyQuotesAlsoReachTheFrenchProfile() {
        for (quote in listOf("“", "”")) {
            val input = Input(""); altL(input, quote)
            assertEquals("«\u202f", input.buffer.toString())
        }
    }

    @Test fun altLRespectsNestingAndMovesAnIdentifiedSuggestionSpaceOutside() {
        val input = Input("«\u202fIl dit : ")
        altL(input)
        assertEquals("«\u202fIl dit : “", input.buffer.toString())
        input.commitText("bonjour ", 1)
        AutoSpaceTracker.markSuggestionAutoSpace(input)
        altL(input)
        assertEquals("«\u202fIl dit : “bonjour” ", input.buffer.toString())
        altL(input)
        assertEquals("«\u202fIl dit : “bonjour”\u202f» ", input.buffer.toString())
    }

    @Test fun disabledAndDeferredModesKeepTheOriginalAltMapping() {
        for (mode in listOf(SettingsManager.SMART_QUOTES_OFF, SettingsManager.SMART_QUOTES_DEFERRED)) {
            SettingsManager.setSmartQuotesMode(context, mode)
            val input = Input(""); altL(input)
            assertEquals("\"", input.buffer.toString())
        }
        assertEquals(0, pipelineCommits)
        assertEquals(2, originalNotifications)
    }

    @Test fun restrictedFieldAndNonFrenchInputKeepTheOriginalMapping() {
        disabled = true
        val restricted = Input(""); altL(restricted)
        assertEquals("\"", restricted.buffer.toString())
        disabled = false
        router.typingLocaleProvider = { Locale.ITALIAN }
        val italian = Input(""); altL(italian)
        assertEquals("\"", italian.buffer.toString())
        assertEquals(0, pipelineCommits)
    }

    @Test fun ordinaryAltCharactersStillUseTheirOriginalCallbacks() {
        val input = Input(""); altL(input, "#")
        assertEquals("#", input.buffer.toString())
        assertEquals(0, pipelineCommits)
        assertEquals(1, originalNotifications)
    }

    @Test fun longPressAltQuoteIsHandledAfterItsTemporaryLetterIsRemoved() {
        SettingsManager.setLongPressModifier(context, "alt")
        @Suppress("UNCHECKED_CAST")
        (manager.getAltModifierMappings() as MutableMap<Int, String>)[KeyEvent.KEYCODE_L] = "\""
        val input = Input("début ")
        assertTrue(manager.handleKeyWithAltMapping(keyCode=KeyEvent.KEYCODE_L, inputConnection=input,
            event=KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L), capsLockEnabled=false, layoutChar='l'))
        assertEquals("début l", input.buffer.toString())
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        assertEquals("début «\u202f", input.buffer.toString())
        assertEquals(1, pipelineCommits)
        assertEquals(0, originalNotifications)
    }

    private class Input(text: String) : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        val buffer get() = requireNotNull(editable)
        init { buffer.append(text); Selection.setSelection(buffer, buffer.length) }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText = ExtractedText().apply {
            this.text = buffer.toString(); startOffset = 0
            selectionStart = Selection.getSelectionStart(buffer); selectionEnd = Selection.getSelectionEnd(buffer)
        }
    }
}
