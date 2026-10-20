package rocks.pkb.plektra.core

import android.graphics.Typeface
import android.text.Selection
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import rocks.pkb.plektra.SettingsManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImmediateSmartQuotesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { AutoSpaceTracker.clear(); PunctuationAfterSpace.clear() }
    private fun decision(before: String, after: String = "", action: ImmediateQuotePolicy.Action = ImmediateQuotePolicy.Action.AUTO) =
        ImmediateQuotePolicy.decide(before, after, Locale.FRENCH, action)?.text

    @Test fun frenchNestingIsRebuiltFromSurroundingText() {
        assertEquals("«\u202f", decision(""))
        assertEquals("“", decision("«\u202fIl dit : "))
        assertEquals("”", decision("«\u202fIl dit : “été 😀"))
        assertEquals("\u202f»", decision("«\u202fIl dit : “été 😀”"))
        assertEquals("«\u202f", decision("«\u202ftexte\u202f» "))
        assertEquals("\"", decision("«\u202f“autre : "))
        assertNull(decision("l'été"))
    }

    @Test fun changedPastedContextAndAnExistingCloserDetermineTheLevel() {
        assertEquals("\u202f»", decision("collé", "\u202f»"))
        assertEquals("”", decision("«\u202fdéjà “texte", "” suite\u202f»"))
        assertEquals("“", decision("«\u202fcitation déplacée : "))
        assertEquals("\u202f»", decision("«\u202ftexte ", action = ImmediateQuotePolicy.Action.CLOSE))
        assertEquals("\"", decision("«\u202ftexte", action = ImmediateQuotePolicy.Action.STRAIGHT))
        assertNull(ImmediateQuotePolicy.decide("", "", Locale.ITALIAN))
    }

    @Test fun unrelatedEnglishQuotesDoNotBecomeAnInnerFrenchCitation() {
        assertEquals("«\u202f", decision("“bon. “ “Bon”.\n”Je ne sais quoi te dire. “\n\n"))
        assertEquals("«\u202f", decision("Une ancienne citation “incomplète\n\nBon, "))
        assertEquals("«\u202f", decision("Une citation anglaise “fermée” puis "))
        // A real French citation still supports an inner quote, including across lines.
        assertEquals("“", decision("«\u202fIl explique :\n"))
        assertEquals("”", decision("«\u202fIl explique : “bonjour"))
    }

    @Test fun automaticSuggestionSpaceMovesOutsideTheClosingQuote() {
        val input = RichInput("«\u202fbonjour ")
        AutoSpaceTracker.markSuggestionAutoSpace(input)
        assertTrue(ImmediateQuoteEditor.apply(input, Locale.FRENCH).consumed)
        assertEquals("«\u202fbonjour\u202f» ", input.buffer.toString())
        assertTrue(AutoSpaceTracker.isPending())
        assertEquals(listOf("\u202f» "), input.commits)
    }

    @Test fun automaticPunctuationSpaceClosesTheOuterCitationAndMovesOutside() {
        SettingsManager.setSpaceAfterPunctuation(context, ".!?")
        for (sign in listOf('.', '!', '?')) {
            AutoSpaceTracker.clear(); PunctuationAfterSpace.clear()
            val prefix = "«\u202fBonjour" + if (sign == '.') "." else "\u202f$sign"
            val input = RichInput(prefix)
            PunctuationAfterSpace.afterCommit(context, input, sign)
            assertEquals(prefix + " ", input.buffer.toString())
            val result = ImmediateQuoteEditor.apply(input, java.util.Locale.FRENCH)
            assertTrue(result.consumed)
            assertEquals(prefix + "\u202f» ", input.buffer.toString())
            assertTrue(AutoSpaceTracker.isPending())
        }
    }

    @Test fun punctuationSpaceMovesPastBothClosersForNestedCitations() {
        SettingsManager.setSpaceAfterPunctuation(context, ".")
        val input = RichInput("«\u202fIl dit : “Bonjour.")
        PunctuationAfterSpace.afterCommit(context, input, '.')
        ImmediateQuoteEditor.apply(input, java.util.Locale.FRENCH)
        assertEquals("«\u202fIl dit : “Bonjour.” ", input.buffer.toString())
        ImmediateQuoteEditor.apply(input, java.util.Locale.FRENCH)
        assertEquals("«\u202fIl dit : “Bonjour.”\u202f» ", input.buffer.toString())
    }

    @Test fun manualOrChangedPunctuationSpaceDoesNotForceACloser() {
        val input = RichInput("«\u202fBonjour. ")
        ImmediateQuoteEditor.apply(input, java.util.Locale.FRENCH)
        assertEquals("«\u202fBonjour. “", input.buffer.toString())
        PunctuationAfterSpace.recordAutoSpace(input, '.')
        assertFalse(PunctuationAfterSpace.hasAutoSpaceForFormatting(input))
        val other = RichInput("«\u202fBonjour. ")
        PunctuationAfterSpace.recordAutoSpace(other, '.')
        assertFalse(PunctuationAfterSpace.hasAutoSpaceForFormatting(input))
        other.commitText("suite ", 1)
        assertFalse(PunctuationAfterSpace.hasAutoSpaceForFormatting(other))
    }

    @Test fun automaticFrenchProfileDoesNotChangeASelectionInAnotherInputLanguage() {
        val input = RichInput("testo")
        Selection.setSelection(input.buffer, 0, 5)
        assertFalse(ImmediateQuoteEditor.apply(input, Locale.ITALIAN).consumed)
        assertEquals("testo", input.buffer.toString())
        assertTrue(input.commits.isEmpty())
    }

    @Test fun manualFineSpaceIsNotDuplicated() {
        val input = RichInput("«\u202fbonjour\u202f")
        ImmediateQuoteEditor.apply(input, Locale.FRENCH, ImmediateQuotePolicy.Action.CLOSE)
        assertEquals("«\u202fbonjour\u202f»", input.buffer.toString())
    }

    @Test fun existingCloserIsOvertypedWithoutRewritingTheBody() {
        val input = RichInput("«\u202fété\u202f»")
        Selection.setSelection(input.buffer, 5)
        val result = ImmediateQuoteEditor.apply(input, Locale.FRENCH, ImmediateQuotePolicy.Action.CLOSE)
        assertTrue(result.selectionChanged)
        assertEquals(7, Selection.getSelectionEnd(input.buffer))
        assertEquals("«\u202fété\u202f»", input.buffer.toString())
        assertTrue(input.commits.isEmpty())
    }

    @Test fun selectedMixedStylesAndEmojiAreWrappedByEdgesOnlyInEitherDirection() {
        for (reverse in listOf(false, true)) {
            val input = RichInput("début été 😀 fin")
            val bold = StyleSpan(Typeface.BOLD)
            val italic = StyleSpan(Typeface.ITALIC)
            input.buffer.setSpan(bold, 6, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            input.buffer.setSpan(italic, 10, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            Selection.setSelection(input.buffer, if (reverse) 12 else 6, if (reverse) 6 else 12)
            val result = ImmediateQuoteEditor.apply(input, Locale.FRENCH)
            assertTrue(result.consumed)
            assertFalse(result.unsupportedSelection)
            assertEquals("début «\u202fété 😀\u202f» fin", input.buffer.toString())
            assertEquals(8, input.buffer.getSpanStart(bold)); assertEquals(11, input.buffer.getSpanEnd(bold))
            assertEquals(12, input.buffer.getSpanStart(italic)); assertEquals(14, input.buffer.getSpanEnd(italic))
            assertEquals(listOf("«\u202f", "\u202f»"), input.commits)
            assertEquals(1, input.batches); assertEquals(1, input.ends)
        }
    }

    @Test fun partialExtractionOffsetsAreAddedBeforeMovingTheSelection() {
        val input = RichInput("prefixe texte suffixe", offset = 4)
        Selection.setSelection(input.buffer, 8, 13)
        assertFalse(ImmediateQuoteEditor.apply(input, Locale.FRENCH).unsupportedSelection)
        assertEquals("prefixe «\u202ftexte\u202f» suffixe", input.buffer.toString())
    }

    @Test fun explicitSelectedActionsNeverReplaceTheSelectedBody() {
        for ((action, expected) in listOf(ImmediateQuotePolicy.Action.OPEN to "«\u202ftexte",
            ImmediateQuotePolicy.Action.CLOSE to "texte\u202f»", ImmediateQuotePolicy.Action.STRAIGHT to "\"texte")) {
            val input = RichInput("texte")
            Selection.setSelection(input.buffer, 0, 5)
            ImmediateQuoteEditor.apply(input, Locale.FRENCH, action)
            assertEquals(expected, input.buffer.toString())
            assertTrue(input.commits.none { it.contains("texte") })
        }
    }

    @Test fun unavailableSelectionDoesNotFallBackToFlatteningRichText() {
        val input = RichInput("texte", extraction = false)
        Selection.setSelection(input.buffer, 0, 5)
        val result = ImmediateQuoteEditor.apply(input, Locale.FRENCH)
        assertTrue(result.unsupportedSelection)
        assertEquals("texte", input.buffer.toString()); assertTrue(input.commits.isEmpty())
    }

    @Test fun deferredModeAlsoPreservesInteriorSpansAndCommitsOnlyEdges() {
        val input = RichInput("dit \"été 😀\"")
        val italic = StyleSpan(Typeface.ITALIC)
        input.buffer.setSpan(italic, 5, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val result = ImmediateQuoteEditor.replacePair(input, input.buffer.toString(), 4, "«\u202f", "\u202f»", " ")
        assertTrue(result.consumed)
        assertEquals("dit «\u202fété 😀\u202f» ", input.buffer.toString())
        assertEquals(6, input.buffer.getSpanStart(italic)); assertEquals(12, input.buffer.getSpanEnd(italic))
        assertEquals(listOf("\u202f» ", "«\u202f"), input.commits)
        assertEquals(1, input.batches); assertEquals(1, input.ends)
    }

    @Test fun oldBooleanMigratesWithoutEnablingTheNewMode() {
        val prefs = SettingsManager.getPreferences(context)
        prefs.edit().remove("smart_quotes_mode").putBoolean("smart_quotes", true).commit()
        assertEquals(SettingsManager.SMART_QUOTES_DEFERRED, SettingsManager.getSmartQuotesMode(context))
        SettingsManager.setSmartQuotesMode(context, SettingsManager.SMART_QUOTES_IMMEDIATE)
        assertTrue(SettingsManager.getSmartQuotes(context))
        SettingsManager.setSmartQuotes(context, false)
        assertEquals(SettingsManager.SMART_QUOTES_OFF, SettingsManager.getSmartQuotesMode(context))
    }

    @Test fun staleExtractedContextCannotTurnAQuoteEditIntoABodyReplacement() {
        val input = RichInput("dit \"texte\"", extractedOverride = "dit abcdefg")
        val result = ImmediateQuoteEditor.replacePair(input, input.buffer.toString(), 4, "«\u202f", "\u202f»", " ")
        assertFalse(result.consumed)
        assertEquals("dit \"texte\"", input.buffer.toString())
        assertTrue(input.commits.isEmpty())
    }

    @Test fun rejectedCursorMovementKeepsTheSelectedBodyIntact() {
        for (failure in listOf(1,2)) {
            val input = RichInput("texte", failMove = failure)
            Selection.setSelection(input.buffer,0,5)
            assertTrue(ImmediateQuoteEditor.apply(input,Locale.FRENCH).unsupportedSelection)
            assertEquals(if (failure == 1) "texte" else "«\u202ftexte",input.buffer.toString())
            assertTrue(input.commits.none { it.contains("texte") })
            assertEquals(input.batches,input.ends)
        }
    }

    private class RichInput(text: String, val offset: Int = 0, val extraction: Boolean = true,
        val extractedOverride: String? = null, val failMove: Int = 0) :
        BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        val buffer: android.text.Editable get() = requireNotNull(editable)
        val commits = mutableListOf<String>()
        var batches = 0; var ends = 0
        private var depth = 0; private var moves = 0
        init { buffer.append(text); Selection.setSelection(buffer, text.length) }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits.add(text.toString()); return super.commitText(text, newCursorPosition)
        }
        override fun beginBatchEdit(): Boolean { if (depth++ == 0) batches++; return true }
        override fun endBatchEdit(): Boolean { if (--depth == 0) ends++; return true }
        override fun setSelection(start: Int, end: Int): Boolean =
            if (++moves == failMove) false else super.setSelection(start,end)
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
            if (!extraction) null else ExtractedText().apply {
                text = extractedOverride ?: buffer.subSequence(offset, buffer.length)
                startOffset = offset
                selectionStart = Selection.getSelectionStart(buffer) - offset
                selectionEnd = Selection.getSelectionEnd(buffer) - offset
            }
    }
}
