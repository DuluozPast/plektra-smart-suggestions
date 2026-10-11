package rocks.pkb.plektra

import android.content.res.Configuration
import android.widget.EditText
import android.widget.TextView
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotesActivityTest {

    @Test fun externalAccessibilityDictationUpdatesEditorAndCanBeEditedThroughIme() {
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        val activity = controller.get()
        val editor = activity.findViewById<EditText>(R.id.notes_body)
        editor.setText("Vorher")
        editor.requestFocus()
        val arguments = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                "Externes Diktat")
        }
        // Overlay dictation apps insert through Android accessibility while Plektra stays active.
        assertTrue(editor.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        assertEquals("Externes Diktat", editor.text.toString())
        val connection = editor.onCreateInputConnection(EditorInfo())
        connection.setSelection(editor.length(), editor.length())
        connection.commitText(" ergänzt", 1)
        assertEquals("Externes Diktat ergänzt", editor.text.toString())
        assertTrue(editor.hasFocus())
        controller.pause().stop().destroy()
        val reopened = Robolectric.buildActivity(NotesActivity::class.java).setup()
        assertEquals("Externes Diktat ergänzt", reopened.get().findViewById<EditText>(R.id.notes_body).text.toString())
        reopened.pause().stop().destroy()
    }

    @Test fun previewCheckboxClickUpdatesAndPersistsMarkdown() {
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        val activity = controller.get()
        val editor = activity.findViewById<EditText>(R.id.notes_body)
        editor.setText("- [ ] One\n- [x] Two")
        activity.findViewById<TextView>(R.id.notes_preview_toggle).performClick()
        val preview = activity.findViewById<TextView>(R.id.notes_preview_body)
        preview.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        preview.layout(0, 0, 600, 600)
        val y = preview.totalPaddingTop + preview.layout.getLineBottom(0) / 2f
        val x = preview.totalPaddingLeft + 3f
        preview.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        preview.dispatchTouchEvent(MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, x, y, 0))
        assertEquals("- [x] One\n- [x] Two", editor.text.toString())
        controller.pause().stop().destroy()
        val reopened = Robolectric.buildActivity(NotesActivity::class.java).setup()
        assertEquals("- [x] One\n- [x] Two", reopened.get().findViewById<EditText>(R.id.notes_body).text.toString())
        reopened.pause().stop().destroy()
    }

    @Test fun imeEnterContinuesChecklistWithAnUncheckedItem() {
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        val editor = controller.get().findViewById<EditText>(R.id.notes_body)
        editor.setText("- [x] One")
        editor.setSelection(editor.length())
        editor.onCreateInputConnection(EditorInfo()).commitText("\n", 1)
        assertEquals("- [x] One\n- [ ] ", editor.text.toString())
        assertEquals(editor.length(), editor.selectionStart)
        controller.pause().stop().destroy()
    }

    @Test fun hardwareChangesKeepEditorFocusAndSelection() {
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        val activity = controller.get()
        val editor = activity.findViewById<EditText>(R.id.notes_body)
        editor.setText("Testyyy")
        editor.requestFocus()
        editor.setSelection(2, 5)
        for (keyboard in listOf(Configuration.KEYBOARD_QWERTY, Configuration.KEYBOARD_NOKEYS)) {
            activity.onConfigurationChanged(Configuration(activity.resources.configuration).apply { this.keyboard = keyboard })
            assertTrue(editor.hasFocus())
            assertEquals(2, editor.selectionStart)
            assertEquals(5, editor.selectionEnd)
            assertEquals("Testyyy", editor.text.toString())
        }
        assertFalse(activity.findViewById<EditText>(R.id.notes_heading).hasFocus())
        controller.pause().stop().destroy()
    }


    @Test fun reopeningRestoresNoteAndPreviewRendersMarkdownWithoutChangingSource() {
        val first = Robolectric.buildActivity(NotesActivity::class.java).setup()
        first.get().findViewById<EditText>(R.id.notes_body).setText("## Heading\n\n**bold**")
        first.get().findViewById<EditText>(R.id.notes_heading).setText("Saved title")
        first.pause().stop().destroy()
        val second = Robolectric.buildActivity(NotesActivity::class.java).setup()
        second.get().findViewById<TextView>(R.id.notes_preview_toggle).performClick()
        val preview = second.get().findViewById<TextView>(R.id.notes_preview_body).text.toString()
        assertFalse(preview.contains("**"))
        assertEquals("## Heading\n\n**bold**", second.get().findViewById<EditText>(R.id.notes_body).text.toString())
        assertEquals("Saved title", second.get().findViewById<EditText>(R.id.notes_heading).text.toString())
        second.pause().stop().destroy()
    }

    @Test fun splitViewUpdatesPreviewWhileTypingAndPreservesSource() {
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<TextView>(R.id.notes_both_toggle).performClick()
        activity.findViewById<EditText>(R.id.notes_body).setText("**updated**")
        assertEquals("updated", activity.findViewById<TextView>(R.id.notes_preview_body).text.toString())
        assertEquals("**updated**", activity.findViewById<EditText>(R.id.notes_body).text.toString())
        controller.pause().stop().destroy()
    }
    @Test fun modeTilesKeepEditorSelectionAcrossPreviewAndSplit() {
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        val activity = controller.get()
        val editor = activity.findViewById<EditText>(R.id.notes_body)
        editor.setText("Selection survives")
        editor.requestFocus()
        editor.setSelection(2, 7)
        val markdown = activity.findViewById<TextView>(R.id.notes_markdown_toggle)
        val both = activity.findViewById<TextView>(R.id.notes_both_toggle)
        val preview = activity.findViewById<TextView>(R.id.notes_preview_toggle)
        val group = both.parent as android.view.ViewGroup
        assertEquals(group, markdown.parent)
        assertEquals(markdown, group.getChildAt(0))
        assertEquals(both, group.getChildAt(2))
        assertEquals(preview, group.getChildAt(4))
        assertTrue(markdown.isSelected)
        preview.performClick()
        assertTrue(preview.isSelected)
        assertFalse(markdown.isSelected)
        both.performClick()
        assertTrue(both.isSelected)
        assertTrue(editor.hasFocus())
        assertEquals(2, editor.selectionStart)
        assertEquals(7, editor.selectionEnd)
        assertEquals("Selection survives", editor.text.toString())
        markdown.performClick()
        assertEquals(2, editor.selectionStart)
        assertEquals(7, editor.selectionEnd)
        controller.pause().stop().destroy()
    }

    @Test fun modeTilesRestoreHeadingFocusAndSelection() {
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        val activity = controller.get()
        val heading = activity.findViewById<EditText>(R.id.notes_heading)
        heading.setText("My heading")
        heading.requestFocus()
        heading.setSelection(1, 4)
        activity.findViewById<TextView>(R.id.notes_preview_toggle).performClick()
        activity.findViewById<TextView>(R.id.notes_markdown_toggle).performClick()
        assertTrue(heading.hasFocus())
        assertEquals(1, heading.selectionStart)
        assertEquals(4, heading.selectionEnd)
        controller.pause().stop().destroy()
    }

    @Test fun undoAndRedoButtonsFollowHistoryAndClearRedoAfterNewEdit() {
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        val activity = controller.get()
        val editor = activity.findViewById<EditText>(R.id.notes_body)
        val undo = activity.findViewById<TextView>(R.id.notes_undo_button)
        val redo = activity.findViewById<TextView>(R.id.notes_redo_button)
        assertFalse(undo.isEnabled)
        assertFalse(redo.isEnabled)
        val original = editor.text.toString()
        editor.setText("First edit")
        assertTrue(undo.isEnabled)
        assertFalse(redo.isEnabled)
        undo.performClick()
        assertEquals(original, editor.text.toString())
        assertFalse(undo.isEnabled)
        assertTrue(redo.isEnabled)
        redo.performClick()
        assertEquals("First edit", editor.text.toString())
        assertTrue(undo.isEnabled)
        assertFalse(redo.isEnabled)
        undo.performClick()
        editor.setText("New edit")
        assertFalse(redo.isEnabled)
        controller.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "w485dp-h1078dp")
    fun previewRecreationRestoresBodyFocusAndSelectionRange() {
        verifyPreviewRecreationPreservesSelection(R.id.notes_body)
    }

    @Test
    @Config(qualifiers = "w485dp-h1078dp")
    fun previewRecreationRestoresHeadingFocusAndSelectionRange() {
        verifyPreviewRecreationPreservesSelection(R.id.notes_heading)
    }

    private fun verifyPreviewRecreationPreservesSelection(targetId: Int) {
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        val activity = controller.get()
        layoutActivity(activity)
        val target = activity.findViewById<EditText>(targetId)
        target.setText("Selection survives recreation")
        target.requestFocus()
        target.setSelection(2, 5)
        assertTrue("Initial editor must be focused", target.hasFocus())
        activity.findViewById<TextView>(R.id.notes_preview_toggle).performClick()
        controller.recreate()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        val reopened = controller.get()
        layoutActivity(reopened)
        assertTrue(reopened.findViewById<TextView>(R.id.notes_preview_toggle).isSelected)
        reopened.findViewById<TextView>(R.id.notes_markdown_toggle).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        layoutActivity(reopened)
        val restoredTarget = reopened.findViewById<EditText>(targetId)
        assertTrue("Expected focus $targetId, actual ${reopened.currentFocus?.id}", restoredTarget.hasFocus())
        assertEquals("Selection survives recreation", restoredTarget.text.toString())
        assertEquals(2, restoredTarget.selectionStart)
        assertEquals(5, restoredTarget.selectionEnd)
        controller.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "w320dp-h800dp")
    fun narrowHeaderKeepsTitleAndModeControlsUsable() {
        verifyHeaderLayout(separateTitle = true)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun regularHeaderKeepsTitleInNavigation() {
        verifyHeaderLayout(separateTitle = false)
    }

    @Test
    @Config(qualifiers = "w485dp-h1078dp")
    fun pixelHeaderKeepsTitleAndModeControlsUsableWithLargeFont() {
        val configuration = android.content.res.Configuration(org.robolectric.RuntimeEnvironment.getApplication().resources.configuration)
        configuration.fontScale = 1.5f
        org.robolectric.RuntimeEnvironment.getApplication().resources.updateConfiguration(configuration, null)
        verifyHeaderLayout(separateTitle = false)
    }

    private fun verifyHeaderLayout(separateTitle: Boolean) {
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        val activity = controller.get()
        val heading = activity.findViewById<EditText>(R.id.notes_heading)
        val tile = activity.findViewById<TextView>(R.id.notes_both_toggle)
        val navigation = (tile.parent as View).parent
        assertTrue("Title must remain visible", heading.width > 0)
        if (separateTitle) assertFalse(heading.parent == navigation)
        else assertTrue(heading.parent == navigation)
        heading.requestFocus()
        assertTrue(heading.hasFocus())
        val modeGroup = tile.parent as View
        val header = navigation as View
        assertTrue("All mode tiles must fit inside header", modeGroup.right <= header.width)
        controller.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "w485dp-h1078dp")
    fun headingKeepsSelectionWhenAvailableWidthChangesWithoutConfigurationChange() {
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        val activity = controller.get()
        val heading = activity.findViewById<EditText>(R.id.notes_heading)
        val tile = activity.findViewById<TextView>(R.id.notes_both_toggle)
        val navigation = (tile.parent as View).parent
        heading.setText("Selection survives resizing")
        heading.requestFocus()
        heading.setSelection(2, 5)
        layoutActivity(activity, widthDp = 320)
        assertFalse(heading.parent == navigation)
        assertTrue(heading.hasFocus())
        assertEquals(2, heading.selectionStart)
        assertEquals(5, heading.selectionEnd)
        layoutActivity(activity, widthDp = 485)
        assertTrue(heading.parent == navigation)
        assertTrue(heading.hasFocus())
        assertEquals(2, heading.selectionStart)
        assertEquals(5, heading.selectionEnd)
        controller.pause().stop().destroy()
    }

    private fun layoutActivity(activity: NotesActivity, widthDp: Int = activity.resources.configuration.screenWidthDp) {
        val decor = activity.window.decorView
        val configuration = activity.resources.configuration
        val density = activity.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (configuration.screenHeightDp * density).toInt()
        repeat(2) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, width, height)
        }
    }

    @Test
    @Config(qualifiers = "w485dp-h1078dp")
    fun editingHeadingInPreviewRestoresCurrentHeadingSelectionOnPencil() {
        verifyHeadingEditedInPreview(recreate = false)
    }

    @Test
    @Config(qualifiers = "w485dp-h1078dp")
    fun headingEditedInPreviewSurvivesRecreationAndRestoresCurrentSelection() {
        verifyHeadingEditedInPreview(recreate = true)
    }

    private fun verifyHeadingEditedInPreview(recreate: Boolean) {
        val controller = Robolectric.buildActivity(NotesActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<EditText>(R.id.notes_body).apply {
            setText("Body remains intact")
            requestFocus()
            setSelection(0, 4)
        }
        activity.findViewById<TextView>(R.id.notes_preview_toggle).performClick()
        activity.findViewById<EditText>(R.id.notes_heading).apply {
            requestFocus()
            setText("Title edited in preview")
            setSelection(2, 5)
        }
        if (recreate) controller.recreate()
        val reopened = controller.get()
        layoutActivity(reopened)
        reopened.findViewById<TextView>(R.id.notes_markdown_toggle).performClick()
        layoutActivity(reopened)
        val heading = reopened.findViewById<EditText>(R.id.notes_heading)
        assertTrue(heading.hasFocus())
        assertEquals("Title edited in preview", heading.text.toString())
        assertEquals(2, heading.selectionStart)
        assertEquals(5, heading.selectionEnd)
        assertEquals("Body remains intact", reopened.findViewById<EditText>(R.id.notes_body).text.toString())
        controller.pause().stop().destroy()
    }

}
