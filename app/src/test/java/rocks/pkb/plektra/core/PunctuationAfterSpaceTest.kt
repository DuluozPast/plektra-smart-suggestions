package rocks.pkb.plektra.core

import android.content.Context
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.InputEventRouter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PunctuationAfterSpaceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var input: BufferInputConnection
    @Before fun setup() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setSpaceAfterPunctuation(context, ";–?!:)")
        PunctuationAfterSpace.clear()
        DeferredPunctuationSpaceTracker.clear()
        input = BufferInputConnection(context)
    }
    @Test fun allRequestedSignsGetAnImmediateSpace() {
        ";–?!:)".forEach { punctuation ->
            input = BufferInputConnection(context)
            commit(punctuation.toString())
            assertEquals("$punctuation ", input.value)
            assertFalse(DeferredPunctuationSpaceTracker.isPending())
        }
    }
    @Test fun explicitSpaceAndFollowingLetterDoNotDuplicateAutomaticSpace() {
        commit("mot"); commit(":"); commit(" "); commit("a")
        assertEquals("mot: a", input.value)
    }
    @Test fun repeatedPunctuationKeepsOneFinalSpace() {
        commit("mot"); commit("?"); commit("!"); commit("a")
        assertEquals("mot?! a", input.value)
    }
    @Test fun onlyClosingParenthesisIsOffered() {
        assertFalse('(' in Punctuation.AUTO_SPACE_CANDIDATES)
        assertTrue(')' in Punctuation.AUTO_SPACE_CANDIDATES)
        assertTrue('–' in Punctuation.AUTO_SPACE_CANDIDATES)
        commit("("); commit("test"); commit(")")
        assertEquals("(test) ", input.value)
    }
    @Test fun newlineRemovesOnlyTheJustInsertedAutomaticSpace() {
        commit("mot"); commit("!"); commit("\n")
        assertEquals("mot!\n", input.value)
    }
    @Test fun unselectedPunctuationDoesNotReceiveSpace() {
        commit("mot"); commit(".")
        assertEquals("mot.", input.value)
    }
    @Test fun failedCommitDoesNotCreateTrailingSpace() {
        PunctuationAfterSpace.afterCommit(context, input, ')')
        assertEquals("", input.value)
    }
    @Test fun selectedPunctuationWorksThroughRouterWithSuggestionsDisabled() {
        input.commitText("mot", 1)
        val router = InputEventRouter(context, NavModeController(context, ModifierStateController(300L)))
        assertTrue(router.handleBoundaryText(context, ")", input, shouldDisableSuggestions = true,
            isAutoCorrectEnabled = false, autoCorrectionManager = AutoCorrectionManager(context), updateStatusBar = {}))
        assertEquals("mot) ", input.value)
    }
    private fun commit(text: String) {
        if (PunctuationAfterSpace.beforeTextCommit(input, text)) return
        DeferredPunctuationSpaceTracker.prepareForTextCommit(context, input, text)
        input.commitText(text, 1)
        if (text.length == 1) PunctuationAfterSpace.afterCommit(context, input, text[0])
    }
    private class BufferInputConnection(context: Context) : BaseInputConnection(View(context), true) {
        private val buffer = SpannableStringBuilder().apply { Selection.setSelection(this, 0) }
        val value get() = buffer.toString()
        override fun getEditable(): Editable = buffer
    }
}
