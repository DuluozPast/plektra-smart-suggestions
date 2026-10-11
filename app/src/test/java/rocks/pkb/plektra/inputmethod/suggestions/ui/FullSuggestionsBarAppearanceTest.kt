package rocks.pkb.plektra.inputmethod.suggestions.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class FullSuggestionsBarAppearanceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test fun togglingWeightRendersTheSameCandidatesImmediately() {
        val bar = FullSuggestionsBar(context)
        val view = bar.ensureView()
        bar.setContentEnabled(suggestions = true, menuButtons = false)
        fun render() = bar.update(listOf("LongCandidateWithAccentsÄÖÜ", "Second", "Third"), true,
            null, null, false, "OwnWord", null, null, null, null, null, null)
        fun candidates() = descendants(view).filterIsInstance<TextView>().filter {
            it.text.toString() in listOf("LongCandidateWithAccentsÄÖÜ", "Second", "Third", "OwnWord")
        }
        render()
        assertTrue(candidates().isNotEmpty())
        assertTrue(candidates().all { it.typeface?.isBold != true })
        SettingsManager.setSuggestionsBold(context, true)
        render()
        assertTrue(candidates().all { it.typeface?.isBold == true })
        SettingsManager.setSuggestionsBold(context, false)
        render()
        assertTrue(candidates().all { it.typeface?.isBold != true })
    }

    @Test fun suppliedUserCandidatesRemainVisibleWithoutSubtypeDictionary() {
        val bar = FullSuggestionsBar(context)
        val view = bar.ensureView()
        bar.setContentEnabled(suggestions = true, menuButtons = false)
        bar.update(listOf("PersonalWord"), true, null, null, false, null,
            null, null, null, null, null, null)
        assertEquals(View.VISIBLE, view.visibility)
        assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.toString() == "PersonalWord" })
    }

    @Test fun restrictedFieldClearsPreviouslyRenderedSecretsAndAddButton() {
        val bar = FullSuggestionsBar(context)
        val view = bar.ensureView()
        bar.setContentEnabled(suggestions = true, menuButtons = false)
        bar.update(listOf("TestSecret"), true, null, null, false, "TestSecret",
            null, null, null, null, null, null)
        bar.update(listOf("TestSecret"), true, null, null, true, "TestSecret",
            null, null, null, null, null, null, reserveEmptyRow = true)
        assertEquals(View.VISIBLE, view.visibility)
        assertFalse(descendants(view).filterIsInstance<TextView>().any { it.text.toString() == "TestSecret" })
    }

    @Test fun reservedSoftwareRowKeepsItsHeightWhenSuggestionsDisappear() {
        val bar = FullSuggestionsBar(context)
        val view = bar.ensureView()
        bar.setContentEnabled(suggestions = true, menuButtons = false)
        fun measure() {
            view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
        }
        bar.update(listOf("Candidate"), true, null, null, false, null,
            null, null, null, null, null, null, reserveEmptyRow = true)
        measure()
        val height = view.measuredHeight
        bar.update(emptyList(), true, null, null, false, null,
            null, null, null, null, null, null, reserveEmptyRow = true)
        measure()
        assertEquals(View.VISIBLE, view.visibility)
        assertEquals(height, view.measuredHeight)
        assertTrue(height > 0)
    }

    @Test fun changingCandidatesAndEmptyResultsKeepsTheSameVisibleSlots() {
        val bar = FullSuggestionsBar(context)
        val view = bar.ensureView()
        bar.setContentEnabled(suggestions = true, menuButtons = false)
        fun render(words: List<String>) = bar.update(words, true, null, null, false, null,
            null, null, null, null, null, null, reserveEmptyRow = true)
        render(listOf("First", "Second", "Third"))
        val originalSlots = descendants(view).filterIsInstance<TextView>().filter {
            it.text.toString() in listOf("First", "Second", "Third")
        }
        assertEquals(3, originalSlots.size)
        render(listOf("Fourth", "Fifth", "Sixth"))
        assertEquals(setOf("Fourth", "Fifth", "Sixth"), originalSlots.map { it.text.toString() }.toSet())
        render(emptyList())
        assertTrue(originalSlots.all { it.text.isEmpty() && it.visibility == View.VISIBLE })
        assertTrue(originalSlots.all { it.parent != null && !it.isClickable })
        render(listOf("Seventh"))
        assertTrue(originalSlots.any { it.text.toString() == "Seventh" })
        assertEquals(View.VISIBLE, view.visibility)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()
}
