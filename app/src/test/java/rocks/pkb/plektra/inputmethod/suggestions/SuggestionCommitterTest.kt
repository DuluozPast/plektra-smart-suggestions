package rocks.pkb.plektra.inputmethod.suggestions
import android.content.Context
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class SuggestionCommitterTest {
    private class RichEditor(context: Context) : BaseInputConnection(View(context), true) {
        val buffer = SpannableStringBuilder().apply { Selection.setSelection(this, 0) }
        override fun getEditable(): Editable = buffer
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
            if (text?.toString() == " ") true else super.commitText(text, newCursorPosition)
    }
    @Test fun richEditorThatDropsStandaloneSpacesReceivesAtomicWordAndSpace() {
        val input = RichEditor(RuntimeEnvironment.getApplication())
        assertTrue(SuggestionCommitter.commit(input, "bonjour", 0, 0, ""))
        assertEquals("bonjour ", input.buffer.toString())
    }
    @Test fun existingFrenchNonbreakingSpaceIsPreservedWithoutAnExtraSpace() {
        val input = RichEditor(RuntimeEnvironment.getApplication())
        input.commitText("bonj\u202f!", 1); input.setSelection(4, 4)
        assertTrue(SuggestionCommitter.commit(input, "bonjour", 4, 0, "\u202f!"))
        assertEquals("bonjour\u202f!", input.buffer.toString())
    }
    @Test fun paragraphBoundaryAfterCursorDoesNotSuppressTheProvisionalSeparator() {
        for (ending in listOf("\n", "\r\n", "\u2028", "\u2029")) {
            val input = RichEditor(RuntimeEnvironment.getApplication())
            input.commitText("bonj" + ending + "suite", 1); input.setSelection(4, 4)
            assertTrue(SuggestionCommitter.commit(input, "bonjour", 4, 0, ending + "suite"))
            assertEquals("bonjour " + ending + "suite", input.buffer.toString())
            input.commitText("demain", 1)
            assertEquals("bonjour demain" + ending + "suite", input.buffer.toString())
        }
    }
    @Test fun paragraphSeparatorRemainsProvisionalForClosingFormatting() {
        val input = RichEditor(RuntimeEnvironment.getApplication())
        input.commitText("*bonj\n", 1); input.setSelection(5, 5)
        assertTrue(SuggestionCommitter.commit(input, "bonjour", 4, 0, "\n"))
        val info = android.view.inputmethod.EditorInfo().apply {
            packageName = "com.whatsapp"; inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        rocks.pkb.plektra.inputmethod.TextFormattingShortcuts.handle(RuntimeEnvironment.getApplication(), input, info, "format_bold", null)
        assertEquals("*bonjour* \n", input.buffer.toString())
    }
    @Test fun apostropheBeforeParagraphStillContinuesWithoutAnInsertedSeparator() {
        val input = RichEditor(RuntimeEnvironment.getApplication())
        input.commitText("l\n", 1); input.setSelection(1, 1)
        assertTrue(SuggestionCommitter.commit(input, "l'", 1, 0, "\n"))
        input.commitText("amour", 1)
        assertEquals("l'amour\n", input.buffer.toString())
    }
    @Test fun successiveSuggestionTapsBeforeParagraphAppendRatherThanReplaceThePreviousWord() {
        val context: Context = RuntimeEnvironment.getApplication()
        rocks.pkb.plektra.SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        val input = RichEditor(context)
        input.commitText("bonj\n", 1); input.setSelection(4, 4)
        SuggestionButtonHandler.createSuggestionClickListener("bonjour", input,
            shouldDisableAutoCapitalize = true).onClick(View(context))
        assertEquals("bonjour \n", input.buffer.toString())
        SuggestionButtonHandler.createSuggestionClickListener("monsieur", input,
            shouldDisableAutoCapitalize = true).onClick(View(context))
        assertEquals("bonjour monsieur \n", input.buffer.toString())
        input.commitText("demain", 1)
        assertEquals("bonjour monsieur demain\n", input.buffer.toString())
    }
    @Test fun apostropheContinuationDoesNotGetASeparator() {
        val input = RichEditor(RuntimeEnvironment.getApplication())
        assertTrue(SuggestionCommitter.commit(input, "l'", 0, 0, ""))
        assertEquals("l'", input.buffer.toString())
    }

    // Compatibility models, not claims about the exact internals of Outlook or Google Docs.
    private class SeparateSpaceEditor(context: Context, private val trimAtBatchEnd: Boolean = false,
                                      private val exposeContext: Boolean = true) : BaseInputConnection(View(context), true) {
        private val buffer = SpannableStringBuilder().apply { Selection.setSelection(this, 0) }
        val value get() = buffer.toString()
        val commits = mutableListOf<String>()
        private var batchDepth = 0
        private var groupedSeparator = false
        override fun beginBatchEdit(): Boolean { batchDepth++; return super.beginBatchEdit() }
        override fun getEditable(): Editable = buffer
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
            if (exposeContext) super.getTextBeforeCursor(n, flags) else null
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val value = text?.toString().orEmpty()
            commits.add(value)
            // BaseInputConnection wraps EACH commit internally. Model only an extra caller-owned batch.
            if (trimAtBatchEnd && batchDepth > 0 && value.endsWith(" ")) groupedSeparator = true
            // Some bridges distinguish a standalone separator from trailing whitespace in a word commit.
            return super.commitText(if (value == " ") value else value.trimEnd(' '), newCursorPosition)
        }
        override fun endBatchEdit(): Boolean {
            if (batchDepth == 1 && groupedSeparator && buffer.endsWith(" ")) {
                buffer.delete(buffer.length - 1, buffer.length)
                Selection.setSelection(buffer, buffer.length)
            }
            if (batchDepth == 1) groupedSeparator = false
            batchDepth = (batchDepth - 1).coerceAtLeast(0)
            return super.endBatchEdit()
        }
    }
    @Test fun separateSpaceCommitRestoresTheLegacyInsertionSequence() {
        val input = SeparateSpaceEditor(RuntimeEnvironment.getApplication())
        assertTrue(SuggestionCommitter.commit(input, "bonjour", 0, 0, ""))
        assertEquals("bonjour ", input.value)
        assertEquals(listOf("bonjour", " "), input.commits)
    }
    @Test fun richEditorDoesNotLoseTheSeparatorAtBatchEnd() {
        val input = SeparateSpaceEditor(RuntimeEnvironment.getApplication(), trimAtBatchEnd = true)
        assertTrue(SuggestionCommitter.commit(input, "bonjour", 0, 0, ""))
        assertEquals("bonjour ", input.value)
        input.commitText("demain", 1)
        assertEquals("bonjour demain", input.value)
    }
    @Test fun primarySpaceCommitDoesNotDependOnReadableEditorContext() {
        val input = SeparateSpaceEditor(RuntimeEnvironment.getApplication(), exposeContext = false)
        assertTrue(SuggestionCommitter.commit(input, "bonjour", 0, 0, ""))
        assertEquals("bonjour ", input.value)
        assertEquals(listOf("bonjour", " "), input.commits)
    }
    @Test fun twoSuggestionTapsKeepTheirOwnWordsInASeparateSpaceEditor() {
        val context: Context = RuntimeEnvironment.getApplication()
        val input = SeparateSpaceEditor(context)
        SuggestionButtonHandler.createSuggestionClickListener("bonjour", input,
            shouldDisableAutoCapitalize = true).onClick(View(context))
        SuggestionButtonHandler.createSuggestionClickListener("monsieur", input,
            shouldDisableAutoCapitalize = true).onClick(View(context))
        input.commitText("demain", 1)
        assertEquals("bonjour monsieur demain", input.value)
    }
}
