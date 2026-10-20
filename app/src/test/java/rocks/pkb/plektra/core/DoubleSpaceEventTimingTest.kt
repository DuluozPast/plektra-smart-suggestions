package rocks.pkb.plektra.core

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import rocks.pkb.plektra.SettingsManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubleSpaceEventTimingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: TextInputController
    private lateinit var input: BaseInputConnection

    @Before fun setup() {
        SettingsManager.setDoubleSpaceToPeriod(context, true)
        AutoSpaceTracker.clear()
        PunctuationAfterSpace.clear()
        controller = TextInputController(context, ModifierStateController(500L), 500L)
        input = BaseInputConnection(View(context), true)
        input.commitText("bonjour", 1)
    }

    private fun press(time: Long, key: Int = KeyEvent.KEYCODE_SPACE, disabled: Boolean = false): Boolean =
        controller.handleDoubleSpaceToPeriod(key, input, disabled, true, {}, eventTimeMs = time)

    @Test fun queuedRapidPhysicalSpacesRemainRapidAfterASlowDictionaryBoundary() {
        assertFalse(press(0))
        input.commitText(" ", 1)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        assertTrue(press(200))
        assertEquals("bonjour. ", input.editable.toString())
    }

    @Test fun queuedDistantPhysicalSpacesDoNotBecomeADoubleTap() {
        assertFalse(press(1000))
        input.commitText(" ", 1)
        assertFalse(press(1600))
        input.commitText(" ", 1)
        assertEquals("bonjour  ", input.editable.toString())
    }

    @Test fun interveningKeyBreaksTheDoubleTapEvenInsideTheThreshold() {
        assertFalse(press(1000))
        input.commitText(" ", 1)
        assertFalse(press(1100, KeyEvent.KEYCODE_SHIFT_LEFT))
        assertFalse(press(1200))
        assertEquals("bonjour ", input.editable.toString())
    }

    @Test fun disabledFieldCannotCarryAPendingTapIntoAnEnabledField() {
        assertFalse(press(1000))
        input.commitText(" ", 1)
        assertFalse(press(1100, disabled = true))
        assertFalse(press(1200))
        assertEquals("bonjour ", input.editable.toString())
    }

    @Test fun backwardOrReplayedTimestampCannotTriggerAPeriod() {
        assertFalse(press(1000))
        input.commitText(" ", 1)
        assertFalse(press(900))
        assertEquals("bonjour ", input.editable.toString())
    }

    @Test fun doubleSpacePeriodKeepsItsSpaceOutsideAClosingFrenchQuote() {
        input.setSelection(0, 0)
        input.commitText("«\u202f", 1)
        input.setSelection(input.editable!!.length, input.editable!!.length)
        assertFalse(press(1000)); input.commitText(" ", 1)
        assertTrue(press(1200))
        assertTrue(PunctuationAfterSpace.hasAutoSpaceForFormatting(input))
        assertTrue(ImmediateQuoteEditor.apply(input, java.util.Locale.FRENCH).consumed)
        assertEquals("«\u202fbonjour.\u202f» ", input.editable.toString())
    }
    @Test fun replayedSpaceWithIdenticalEventTimeDoesNotInsertAPeriod() {
        assertFalse(press(1000)); input.commitText(" ",1)
        assertFalse(press(1000))
        assertEquals("bonjour ",input.editable.toString())
    }

    @Test fun letterHandledBeforeTextPipelineBreaksThePair() {
        assertFalse(press(1000)); input.commitText(" ",1)
        controller.observeKeyDown(KeyEvent.KEYCODE_J)
        input.commitText("je ",1)
        assertFalse(press(1200))
        assertEquals("bonjour je ",input.editable.toString())
    }

    @Test fun holdingSpaceDoesNotCountAsASecondTap() {
        assertFalse(press(1000));input.commitText(" ",1)
        controller.observeKeyDown(KeyEvent.KEYCODE_SPACE,repeatCount=1)
        assertFalse(press(1200))
        assertEquals("bonjour ",input.editable.toString())
    }

    @Test fun aNewInputSessionCannotReuseThePreviousSpace() {
        assertFalse(press(1000));input.commitText(" ",1)
        controller.resetDoubleSpaceSequence()
        assertFalse(press(1200))
        assertEquals("bonjour ",input.editable.toString())
    }

    @Test fun twoManualSpacesAfterAnAcceptedSuggestionProduceOnePeriod() {
        input.commitText(" ",1)
        AutoSpaceTracker.markSuggestionAutoSpace(input)
        assertFalse(PunctuationAfterSpace.beforeTextCommit(input," "))
        assertFalse(press(1000));input.commitText(" ",1)
        assertFalse(PunctuationAfterSpace.beforeTextCommit(input," "))
        assertTrue(press(1170))
        assertEquals("bonjour. ",input.editable.toString())
        assertFalse(AutoSpaceTracker.isPending())
    }

    @Test fun letterAfterSuggestionEndsSpacePairAndCannotTrimManualSpaceAsFormatting() {
        input.commitText(" ",1);AutoSpaceTracker.markSuggestionAutoSpace(input)
        PunctuationAfterSpace.beforeTextCommit(input," ")
        assertFalse(press(1000));input.commitText(" ",1)
        assertFalse(AutoSpaceTracker.takeSuggestionSpaceForFormatting(input))
        controller.observeKeyDown(KeyEvent.KEYCODE_D)
        PunctuationAfterSpace.beforeTextCommit(input,"demain")
        input.commitText("demain",1)
        assertFalse(press(1170))
        assertEquals("bonjour  demain",input.editable.toString())
    }

    @Test fun suggestionDoubleSpaceBeforeClosingQuoteKeepsItalicsAndMovesOnlyAutoSpace() {
        input.setSelection(0,0);input.commitText("«\u202f",1)
        input.setSelection(input.editable!!.length,input.editable!!.length)
        val italic=android.text.style.StyleSpan(android.graphics.Typeface.ITALIC)
        input.editable!!.setSpan(italic,2,9,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        input.commitText(" ",1);AutoSpaceTracker.markSuggestionAutoSpace(input)
        PunctuationAfterSpace.beforeTextCommit(input," ")
        assertFalse(press(1000));input.commitText(" ",1)
        PunctuationAfterSpace.beforeTextCommit(input," ")
        assertTrue(press(1170))
        assertTrue(PunctuationAfterSpace.hasAutoSpaceForFormatting(input))
        assertTrue(ImmediateQuoteEditor.apply(input,java.util.Locale.FRENCH).consumed)
        assertEquals("«\u202fbonjour.\u202f» ",input.editable.toString())
        assertEquals(2,input.editable!!.getSpanStart(italic))
        assertEquals(9,input.editable!!.getSpanEnd(italic))
    }

}
