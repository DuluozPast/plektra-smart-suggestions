package rocks.pkb.plektra.inputmethod

import android.app.Activity
import android.content.Context
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.EditText
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.commands.NavCommandSource
import rocks.pkb.plektra.core.ModifierStateController
import rocks.pkb.plektra.core.NavModeController
import rocks.pkb.plektra.data.mappings.KeyMappingLoader
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TextSelectionHelperVerticalTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    @After
    fun resetAnchor() {
        TextSelectionHelper::class.java.getDeclaredField("selectionAnchorState").apply {
            isAccessible = true
            set(null, null)
        }
    }

    @Test
    fun verticalActionsSendShiftArrowsWithBalancedKeyEvents() {
        val ic = connection(10, 10)
        assertTrue(TextSelectionHelper.expandSelectionUp(ic))
        assertTrue(TextSelectionHelper.expandSelectionDown(ic))
        val events = ArgumentCaptor.forClass(KeyEvent::class.java)
        verify(ic, times(4)).sendKeyEvent(events.capture())
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN), events.allValues.map { it.keyCode })
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP,
            KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), events.allValues.map { it.action })
        assertTrue(events.allValues.all { it.isShiftPressed && !it.isCtrlPressed && !it.isAltPressed })
    }

    @Test
    fun horizontalSelectionThenVerticalKeepsOriginalAnchor() {
        val ic = connection(10, 10)
        assertTrue(TextSelectionHelper.expandSelectionLeft(ic))
        verify(ic).setSelection(9, 10)
        assertTrue(TextSelectionHelper.expandSelectionUp(ic))
        // The editor must see the anchor first and the active edge second.
        verify(ic).setSelection(10, 9)
    }

    @Test
    fun rejectedKeyEventsReportFailureAndStillSendKeyUp() {
        val ic = connection(10, 10)
        `when`(ic.sendKeyEvent(any(KeyEvent::class.java))).thenReturn(false)
        assertFalse(TextSelectionHelper.expandSelectionDown(ic))
        verify(ic, times(2)).sendKeyEvent(any(KeyEvent::class.java))
    }

    @Test
    fun unavailableSelectionDoesNotSendNavigationKeys() {
        val ic = mock(InputConnection::class.java)
        assertFalse(TextSelectionHelper.expandSelectionUp(ic))
        verify(ic, never()).sendKeyEvent(any(KeyEvent::class.java))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun downThenUpInMultilineEditorShrinksBackToAnchor() {
        assumeFalse("This Robolectric version has no Windows native renderer", System.getProperty("os.name").startsWith("Windows"))
        val editor = editor("alpha beta\ngamma delta\nepsilon zeta")
        editor.setSelection(3)
        val ic = editorConnection(editor)
        assertTrue(TextSelectionHelper.expandSelectionDown(ic))
        assertEquals(3, editor.selectionStart)
        assertTrue(editor.selectionEnd > 3)
        assertTrue(TextSelectionHelper.expandSelectionUp(ic))
        assertEquals(3, editor.selectionStart)
        assertEquals(3, editor.selectionEnd)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun wrappedLinesAreSelectedUsingEditorLayout() {
        assumeFalse("This Robolectric version has no Windows native renderer", System.getProperty("os.name").startsWith("Windows"))
        val editor = editor("alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu")
        assertTrue(editor.layout.lineCount > 1)
        editor.setSelection(2)
        val ic = editorConnection(editor)
        assertTrue(TextSelectionHelper.expandSelectionDown(ic))
        assertEquals(2, editor.selectionStart)
        assertEquals(1, editor.layout.getLineForOffset(editor.selectionEnd))
    }

    @Test
    fun mappingsCanBeSavedAndReloadedOnEveryKeyInNavMenu() {
        val file = SettingsManager.getNavModeMappingsFile(context)
        val before = file.takeIf { it.exists() }?.readBytes()
        try {
            val keys = (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z).toList()
            val expected = keys.associateWith {
                KeyMappingLoader.CtrlMapping("action", if (it % 2 == 0) "expand_selection_up" else "expand_selection_down")
            }
            SettingsManager.saveNavModeKeyMappings(context, expected)
            assertTrue(file.exists())
            assertEquals(expected, KeyMappingLoader.loadCtrlKeyMappings(context.assets, context))
            // A second disk read represents reopening the settings screen/service.
            assertEquals(expected, KeyMappingLoader.loadCtrlKeyMappings(context.assets, context))
        } finally {
            if (before != null) file.writeBytes(before) else file.delete()
        }
    }

    @Test
    fun defaultArrowMappingsRemainUnchanged() {
        val defaults = KeyMappingLoader.loadCtrlKeyMappings(context.assets)
        assertEquals(KeyMappingLoader.CtrlMapping("keycode", "DPAD_UP"), defaults[KeyEvent.KEYCODE_E])
        assertEquals(KeyMappingLoader.CtrlMapping("keycode", "DPAD_DOWN"), defaults[KeyEvent.KEYCODE_D])
    }

    @Test
    fun commandRegistryExposesBothOptionalActions() {
        val ids = NavCommandSource().getCommands(context).map { it.id }
        assertTrue(ids.contains("nav.action.expand_selection_up"))
        assertTrue(ids.contains("nav.action.expand_selection_down"))
    }

    @Test
    fun navControllerExecutesBothSelectionActions() {
        val controller = NavModeController(context, ModifierStateController(300L))
        val ic = connection(10, 10)
        assertTrue(controller.executeMapping("action", "expand_selection_up", null, ic))
        assertTrue(controller.executeMapping("action", "expand_selection_down", null, ic))
        verify(ic, times(4)).sendKeyEvent(any(KeyEvent::class.java))
    }

    @Test
    fun routerExecutesSelectionActionOnChosenKey() {
        val controller = NavModeController(context, ModifierStateController(300L))
        val router = InputEventRouter(context, controller)
        val ic = connection(10, 10)
        assertTrue(router.handleCtrlModifiedKey(
            keyCode = KeyEvent.KEYCODE_G, event = null, inputConnection = ic,
            ctrlKeyMap = mapOf(KeyEvent.KEYCODE_G to KeyMappingLoader.CtrlMapping("action", "expand_selection_down")),
            ctrlLatchFromNavMode = true, ctrlOneShot = false, ctrlPhysicallyPressed = false,
            clearCtrlOneShot = {}, updateStatusBar = {}, callSuper = { false }, toggleMinimalUi = {}
        ))
        val events = ArgumentCaptor.forClass(KeyEvent::class.java)
        verify(ic, times(2)).sendKeyEvent(events.capture())
        assertTrue(events.allValues.all { it.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && it.isShiftPressed })
    }

    private fun connection(start: Int, end: Int): InputConnection {
        val ic = mock(InputConnection::class.java)
        var selectionStart = start
        var selectionEnd = end
        `when`(ic.getExtractedText(any(ExtractedTextRequest::class.java), anyInt())).thenAnswer {
            ExtractedText().apply {
                text = "alpha beta gamma delta"
                this.selectionStart = selectionStart
                this.selectionEnd = selectionEnd
            }
        }
        `when`(ic.setSelection(anyInt(), anyInt())).thenAnswer {
            selectionStart = it.getArgument(0)
            selectionEnd = it.getArgument(1)
            true
        }
        `when`(ic.sendKeyEvent(any(KeyEvent::class.java))).thenReturn(true)
        return ic
    }

    private fun editor(text: String): EditText {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return EditText(activity).apply {
            setText(text)
            setSingleLine(false)
            activity.setContentView(this)
            requestFocus()
            measure(View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            layout(0, 0, 150, 500)
        }
    }

    private fun editorConnection(editor: EditText): InputConnection {
        val ic = mock(InputConnection::class.java)
        `when`(ic.getExtractedText(any(ExtractedTextRequest::class.java), anyInt())).thenAnswer {
            ExtractedText().apply {
                text = editor.text.toString()
                selectionStart = editor.selectionStart
                selectionEnd = editor.selectionEnd
            }
        }
        `when`(ic.setSelection(anyInt(), anyInt())).thenAnswer {
            Selection.setSelection(editor.text, it.getArgument(0), it.getArgument(1))
            true
        }
        `when`(ic.sendKeyEvent(any(KeyEvent::class.java))).thenAnswer {
            val event = it.getArgument<KeyEvent>(0)
            if (event.action == KeyEvent.ACTION_DOWN) editor.onKeyDown(event.keyCode, event)
            else editor.onKeyUp(event.keyCode, event)
            true
        }
        return ic
    }
}
