package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Binder
import android.text.InputType
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.*
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.core.TextInputController
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class SpaceHardwareRepeatTest {
    private lateinit var service:PhysicalKeyboardInputMethodService
    private lateinit var input:Input
    @Before fun setup() {
        val context=RuntimeEnvironment.getApplication() as Context
        SettingsManager.getPreferences(context).edit().clear().commit()
        service=Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create().get()
        input=Input()
        val info=EditorInfo().apply {inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE;packageName="com.whatsapp"}
        field("mInputConnection",input);field("mStartedInputConnection",input);field("mInputEditorInfo",info)
        field("inputContextState",InputContextState.fromEditorInfo(info))
        field("isInputViewActive",true)
        service.window.window!!.attributes=service.window.window!!.attributes.apply{token=Binder()}
        input.commitText("bonjour ",1)
    }
    @After fun cleanup() { service.onDestroy() }
    private fun field(name:String,value:Any?) {
        var type:Class<*>?=service.javaClass
        while(type!=null) {try {type.getDeclaredField(name).apply{isAccessible=true}.set(service,value);return} catch(_:NoSuchFieldException){type=type.superclass}}
        error(name)
    }
    private fun controller():TextInputController=service.javaClass.getDeclaredField("textInputController").apply{isAccessible=true}.get(service) as TextInputController
    private fun repeatEvent()=KeyEvent(1000,1400,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SPACE,1,0,0,57)
    @Test fun repeatedSpaceIsConsumedBeforeNativeEditorEvenWhenDoubleSpaceIsDisabled() {
        SettingsManager.setDoubleSpaceToPeriod(service,false)
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE,repeatEvent()))
        assertEquals("bonjour ",input.buffer.toString())
    }
    @Test fun repeatedSpaceCannotTriggerAPeriodWhenDoubleSpaceIsEnabled() {
        SettingsManager.setDoubleSpaceToPeriod(service,true)
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE,repeatEvent()))
        assertEquals("bonjour ",input.buffer.toString())
    }
    @Test fun delayedRepeatDoesNotEraseTheFirstTapBeforeGenuineSecondTap() {
        SettingsManager.setDoubleSpaceToPeriod(service,true)
        input.deleteSurroundingText(1,0)
        val controller=controller()
        assertFalse(controller.handleDoubleSpaceToPeriod(KeyEvent.KEYCODE_SPACE,input,false,true,{},1000))
        input.commitText(" ",1)
        // Matches the phone trace: synthesized repeat at +400ms arrives before
        // key-up timestamped +66ms; the real second down can have timestamp +170ms.
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE,repeatEvent()))
        assertTrue(controller.handleDoubleSpaceToPeriod(KeyEvent.KEYCODE_SPACE,input,false,true,{},1170))
        assertEquals("bonjour. ",input.buffer.toString())
    }
    @Test fun genuineSingleSpaceAfterFullyTypedWordStillInsertsOnlyOneSeparator() {
        SettingsManager.setDoubleSpaceToPeriod(service,false)
        input.deleteSurroundingText(input.buffer.length,0);input.commitText("coucou",1)
        val handled=service.onKeyDown(KeyEvent.KEYCODE_SPACE,KeyEvent(1000,1000,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SPACE,0,0,0,57))
        // A false return is the original native event; a true return already consumed it.
        if(!handled) input.commitText(" ",1)
        assertEquals("coucou ",input.buffer.toString())
    }

    @Test fun explicitSecondSpaceWithFeatureDisabledDoesNotReachNativeAutoPeriod() {
        SettingsManager.setDoubleSpaceToPeriod(service,false)
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE,KeyEvent(1000,1000,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SPACE,0,0,0,57)))
        assertEquals("bonjour  ",input.buffer.toString())
    }
    @Test fun realDoubleTapAfterSuggestionUsesImePeriodAndNoNativeKeyFallback() {
        SettingsManager.setDoubleSpaceToPeriod(service,true)
        rocks.pkb.plektra.core.AutoSpaceTracker.markSuggestionAutoSpace(input)
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE,KeyEvent(1000,1000,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SPACE,0,0,0,57)))
        assertEquals("bonjour  ",input.buffer.toString())
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE,KeyEvent(1170,1170,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SPACE,0,0,0,57)))
        assertEquals("bonjour. ",input.buffer.toString())
    }
    @Test fun realDoubleTapSurvivesQueuedRepeatThroughTheWholeService() {
        SettingsManager.setDoubleSpaceToPeriod(service,true)
        input.deleteSurroundingText(1,0)
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE,KeyEvent(1000,1000,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SPACE,0,0,0,57)))
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE,repeatEvent()))
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE,KeyEvent(1170,1170,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SPACE,0,0,0,57)))
        assertEquals("bonjour. ",input.buffer.toString())
    }
    private class Input:BaseInputConnection(View(RuntimeEnvironment.getApplication()),true) {
        val buffer get()=requireNotNull(editable)
        init {Selection.setSelection(buffer,0)}
        override fun getExtractedText(request:ExtractedTextRequest?,flags:Int)=ExtractedText().apply {
            text=buffer.toString();startOffset=0;selectionStart=Selection.getSelectionStart(buffer);selectionEnd=Selection.getSelectionEnd(buffer)
        }
    }
}
