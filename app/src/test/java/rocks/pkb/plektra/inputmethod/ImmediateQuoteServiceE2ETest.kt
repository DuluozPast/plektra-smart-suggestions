package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Binder
import android.text.InputType
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.*
import rocks.pkb.plektra.AltModifierBinding
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.core.ModifierStateController
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Service lifecycle, actual Alt dispatch and French subtype, rather than calling the policy directly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class ImmediateQuoteServiceE2ETest {
    private lateinit var service: PhysicalKeyboardInputMethodService
    private lateinit var input: Input
    private lateinit var context: Context

    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        DeviceSpecific.clearTestOverrides()
        SettingsManager.setAltModifierBinding(context, AltModifierBinding.Emoji)
        SettingsManager.saveSymMappings(context, mapOf(KeyEvent.KEYCODE_L to "\""))
        SettingsManager.setSmartQuotesMode(context, SettingsManager.SMART_QUOTES_IMMEDIATE)
        val imm=context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        shadowOf(imm).setCurrentInputMethodSubtype(InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeLocale("fr_FR").setSubtypeMode("keyboard").setSubtypeExtraValue("KeyboardLayoutSet=azerty").build())
        service=Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create().get()
        input=Input()
        val info=EditorInfo().apply {
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            packageName="net.cozic.joplin"
        }
        field("mInputConnection",input);field("mStartedInputConnection",input);field("mInputEditorInfo",info)
        field("isInputViewActive",true);field("inputContextState",InputContextState.fromEditorInfo(info))
        service.window.window!!.attributes=service.window.window!!.attributes.apply { token=Binder() }
    }
    @After fun teardown() { service.onDestroy(); DeviceSpecific.clearTestOverrides() }
    private fun field(name: String,value: Any?) {
        var type: Class<*>?=service.javaClass
        while(type!=null) {
            try { type.getDeclaredField(name).apply { isAccessible=true }.set(service,value);return }
            catch (_:NoSuchFieldException) { type=type.superclass }
        }
        error(name)
    }
    private fun chord() {
        service.onKeyDown(KeyEvent.KEYCODE_L,KeyEvent(1000L,1100L,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_L,0,KeyEvent.META_ALT_ON))
        service.onKeyUp(KeyEvent.KEYCODE_L,KeyEvent(1000L,1101L,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_L,0,KeyEvent.META_ALT_ON))
    }
    @Test fun frenchAltLWorksThroughServiceDispatch() {
        assertEquals(SettingsManager.SMART_QUOTES_IMMEDIATE,SettingsManager.getSmartQuotesMode(service))
        chord();assertEquals("«\u202f",input.buffer.toString())
        input.commitText("Je ne sais pas.",1)
        chord();assertEquals("«\u202fJe ne sais pas.\u202f»",input.buffer.toString())
    }
    @Test fun latchedAltLWorksThroughServiceDispatch() {
        val modifiers=service.javaClass.getDeclaredField("modifierStateController").apply { isAccessible=true }.get(service) as ModifierStateController
        modifiers.altOneShot=true
        service.onKeyDown(KeyEvent.KEYCODE_L,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_L))
        assertEquals("«\u202f",input.buffer.toString())
    }
    @Test fun oldIncompleteEnglishCitationDoesNotBlockTheNextFrenchCitation() {
        val oldText="“bon. “ “Bon”.\n”Je ne sais quoi te dire. “\n\nBon, "
        input.commitText(oldText,1)
        chord()
        assertEquals(oldText+"«\u202f",input.buffer.toString())
        input.commitText("Je ne sais pas.",1)
        chord()
        assertEquals(oldText+"«\u202fJe ne sais pas.\u202f»",input.buffer.toString())
    }
    private class Input:BaseInputConnection(View(RuntimeEnvironment.getApplication()),true) {
        val buffer get()=requireNotNull(editable)
        init { Selection.setSelection(buffer,0) }
        override fun getExtractedText(request: ExtractedTextRequest?,flags:Int)=ExtractedText().apply {
            text=buffer.toString();startOffset=0
            selectionStart=Selection.getSelectionStart(buffer);selectionEnd=Selection.getSelectionEnd(buffer)
        }
    }
}
