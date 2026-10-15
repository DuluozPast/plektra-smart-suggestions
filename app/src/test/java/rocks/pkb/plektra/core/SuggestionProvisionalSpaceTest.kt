package rocks.pkb.plektra.core

import android.content.Context
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.InputEventRouter
import rocks.pkb.plektra.inputmethod.suggestions.SuggestionButtonHandler
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SuggestionProvisionalSpaceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var input: Buffer
    @Before fun setup() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setFrenchPunctuationSpacing(context, false)
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        AutoSpaceTracker.clear(); PunctuationAfterSpace.clear(); DeferredPunctuationSpaceTracker.clear()
        input = Buffer(context)
    }
    private fun accepted() {
        input.commitText("voudrais ", 1)
        AutoSpaceTracker.markSuggestionAutoSpace(input)
    }
    private fun type(text: String) {
        if (PunctuationAfterSpace.beforeTextCommit(input, text)) return
        if (!(text.length == 1 && SettingsManager.shouldApplyFrenchPunctuationSpacing(context) &&
                Punctuation.commitFrenchSpacedPunctuation(input, text[0]))) input.commitText(text, 1)
        if (text.length == 1) PunctuationAfterSpace.afterCommit(context, input, text[0])
    }
    @Test fun closingSignsRemoveOnlySuggestionSpaceWithoutForcingAfterSpace() {
        ".,;:!?)]}".forEach {
            AutoSpaceTracker.clear(); input = Buffer(context); accepted(); type(it.toString())
            assertEquals("voudrais$it", input.value)
        }
    }
    @Test fun openingParenthesisKeepsWordSeparator() {
        accepted(); type("("); type("demain"); type(")")
        assertEquals("voudrais (demain)", input.value)
    }
    @Test fun frenchSignsUseExactlyOneNarrowNonbreakingSpace() {
        SettingsManager.setFrenchPunctuationSpacing(context, true)
        SettingsManager.setFrenchPunctuationOnlyFrenchLayouts(context, false)
        ";:!?".forEach {
            AutoSpaceTracker.clear(); input = Buffer(context); accepted(); type(it.toString())
            assertEquals("voudrais\u202F$it", input.value)
        }
    }
    @Test fun configuredAfterSpaceIsIndependentAndNotDuplicated() {
        SettingsManager.setSpaceAfterPunctuation(context, ")!")
        accepted(); type(")"); type(" "); type("demain")
        assertEquals("voudrais) demain", input.value)
    }
    @Test fun frenchBeforeSpaceAndConfiguredAfterSpaceCoexist() {
        SettingsManager.setFrenchPunctuationSpacing(context, true)
        SettingsManager.setFrenchPunctuationOnlyFrenchLayouts(context, false)
        SettingsManager.setSpaceAfterPunctuation(context, "!")
        accepted(); type("!"); type("demain")
        assertEquals("voudrais\u202F! demain", input.value)
    }
    @Test fun manualSpaceIsPreserved() {
        input.commitText("voudrais ", 1); type(")")
        assertEquals("voudrais )", input.value)
    }
    @Test fun continuingWordMakesFollowingManualSpacePermanent() {
        accepted(); type("demain"); type(" "); type(")")
        assertEquals("voudrais demain )", input.value)
    }
    @Test fun anotherEditorDoesNotConsumeSuggestionSpace() {
        accepted(); input = Buffer(context); input.commitText("voudrais ", 1); type(")")
        assertEquals("voudrais )", input.value)
    }
    @Test fun openingQuoteKeepsSpaceWhileClosingQuoteRemovesIt() {
        accepted(); type("\""); assertEquals("voudrais \"", input.value)
        AutoSpaceTracker.clear(); input = Buffer(context)
        input.commitText("\"voudrais ", 1); AutoSpaceTracker.markSuggestionAutoSpace(input); type("\"")
        assertEquals("\"voudrais\"", input.value)
    }
    @Test fun punctuatedSuggestionTapConsumesExistingCommaWithoutDoublingSpace() {
        input.commitText("mons, suite", 1)
        input.setSelection(4, 4)
        SuggestionButtonHandler.createSuggestionClickListener("monsieur, je me permets", input,
            shouldDisableAutoCapitalize = true).onClick(View(context))
        assertEquals("monsieur, je me permets suite", input.value)
    }

    @Test fun suggestionTapMarksSpaceAndClosingParenthesisRemovesIt() {
        input.commitText("voud", 1)
        SuggestionButtonHandler.createSuggestionClickListener("voudrais", input,
            shouldDisableAutoCapitalize = true).onClick(View(context))
        assertEquals("voudrais ", input.value)
        type(")"); assertEquals("voudrais)", input.value)
    }
    @Test fun terminalSuggestionTapKeepsNarrowSpaceAndDoesNotDuplicateExistingSign() {
        input.commitText("Emmanuel Cast\u202f! suite", 1)
        input.setSelection(13, 13)
        SuggestionButtonHandler.createSuggestionClickListener("Castel\u202f!", input,
            shouldDisableAutoCapitalize = true).onClick(View(context))
        assertEquals("Emmanuel Castel\u202f! suite", input.value)
    }
    @Test fun altBoundaryRouterHonorsTrimAndConfiguredAfterSpace() {
        SettingsManager.setSpaceAfterPunctuation(context, ")")
        accepted()
        val router = InputEventRouter(context, NavModeController(context, ModifierStateController(300L)))
        assertTrue(router.handleBoundaryText(context, ")", input, true, false, AutoCorrectionManager(context), {}))
        assertEquals("voudrais) ", input.value)
    }
    private class Buffer(context: Context) : BaseInputConnection(View(context), true) {
        private val buffer = SpannableStringBuilder().apply { Selection.setSelection(this, 0) }
        val value get() = buffer.toString()
        override fun getEditable(): Editable = buffer
    }
}
