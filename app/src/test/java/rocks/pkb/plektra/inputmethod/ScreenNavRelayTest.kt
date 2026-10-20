package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Binder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.data.mappings.KeyMappingLoader
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
class ScreenNavRelayTest {
    private lateinit var service: PhysicalKeyboardInputMethodService
    private lateinit var input: NativeInput
    @Before fun setup() {
        val context=RuntimeEnvironment.getApplication() as Context
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setNavModeEnabled(context,true)
        service=Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create().get()
        input=NativeInput()
        field("mInputConnection",input);field("mStartedInputConnection",null)
        field("mInputEditorInfo",EditorInfo().apply { inputType=0;packageName="com.duckduckgo.mobile.android" })
        field("ctrlKeyMap",mapOf(
            KeyEvent.KEYCODE_W to KeyMappingLoader.CtrlMapping("action","expand_selection_left"),
            KeyEvent.KEYCODE_R to KeyMappingLoader.CtrlMapping("action","expand_selection_right"),
            KeyEvent.KEYCODE_E to KeyMappingLoader.CtrlMapping("action","expand_selection_up"),
            KeyEvent.KEYCODE_D to KeyMappingLoader.CtrlMapping("action","expand_selection_down")))
        service.window.window!!.attributes=service.window.window!!.attributes.apply { token=Binder() }
    }
    @After fun cleanup() { service.onDestroy() }
    private fun field(name:String,value:Any?) {
        var type:Class<*>?=service.javaClass
        while(type!=null) { try { type.getDeclaredField(name).apply{isAccessible=true}.set(service,value);return } catch(_:NoSuchFieldException){type=type.superclass} }
        error(name)
    }
    private fun tapFn() {
        service.dispatchScreenNavKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_CTRL_LEFT))
        service.dispatchScreenNavKey(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_CTRL_LEFT))
    }
    @Test fun doubleFnAndUserMappingsDispatchNativeSelectionWithoutReadingText() {
        tapFn();assertFalse(service.screenNavLocked);tapFn();assertTrue(service.screenNavLocked)
        for(key in listOf(KeyEvent.KEYCODE_W,KeyEvent.KEYCODE_R,KeyEvent.KEYCODE_E,KeyEvent.KEYCODE_D)) assertTrue(service.dispatchScreenNavKey(KeyEvent(KeyEvent.ACTION_DOWN,key)))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN),input.events.filter{it.action==KeyEvent.ACTION_DOWN}.map{it.keyCode})
        assertEquals(8,input.events.size);assertTrue(input.events.all{it.isShiftPressed && !it.isCtrlPressed})
    }
    @Test fun absentConnectionFailsOpenAndCannotActivateLock() {
        field("mInputConnection",null);tapFn();tapFn();assertFalse(service.screenNavLocked);assertTrue(input.events.isEmpty())
    }
    @Test fun explicitResetReleasesLockAndStopsRouting() {
        tapFn();tapFn();service.resetScreenNavKeys();assertFalse(service.screenNavLocked)
        assertFalse(service.dispatchScreenNavKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_R)));assertTrue(input.events.isEmpty())
    }
    @Test fun mappingPolicyPreservesWordSelectionAndRejectsEditingCommands() {
        val word=ScreenNavKeyPolicy.chord(KeyMappingLoader.CtrlMapping("action","expand_selection_word_right"),false)!!
        assertTrue((word.meta and KeyEvent.META_CTRL_ON)!=0 && (word.meta and KeyEvent.META_SHIFT_ON)!=0)
        for(action in listOf("cut","paste","undo","format_bold")) assertNull(ScreenNavKeyPolicy.chord(KeyMappingLoader.CtrlMapping("action",action),false))
    }
    @Test fun repeatsOnlyNavigationAndNeverCopy() {
        tapFn();tapFn()
        assertTrue(service.canRepeatScreenNavKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_R)))
        field("ctrlKeyMap",mapOf(KeyEvent.KEYCODE_C to KeyMappingLoader.CtrlMapping("action","copy")))
        assertFalse(service.canRepeatScreenNavKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_C)))
    }
    @Test fun heldKeyRepeatsAfterDelayAndStopsOnRelease() {
        val events=mutableListOf<KeyEvent>()
        val repeater=ScreenNavKeyRepeater { events.add(it);true }
        val down=KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_R)
        val looper=org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        repeater.start(down);looper.idleFor(java.time.Duration.ofMillis(399));assertTrue(events.isEmpty())
        looper.idleFor(java.time.Duration.ofMillis(131));assertEquals(3,events.size)
        assertTrue(events.all{it.repeatCount==0 && it.action==KeyEvent.ACTION_DOWN})
        repeater.release(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_R))
        looper.idleFor(java.time.Duration.ofSeconds(1));assertEquals(3,events.size)
    }
    @Test fun interruptedRepeatStopsAndDoesNotLeakIntoNextApp() {
        var calls=0
        val repeater=ScreenNavKeyRepeater { calls++;false }
        val looper=org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        repeater.start(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_E))
        looper.idleFor(java.time.Duration.ofMillis(600));assertEquals(1,calls)
        repeater.start(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_D));repeater.stop()
        looper.idleFor(java.time.Duration.ofSeconds(1));assertEquals(1,calls)
    }
    @Test fun explicitSelectorUsesLocalNavigationAndWorksWithoutEditorConnection() {
        val host=object:ScreenTextSelectorBridge.Host {
            override var active=false
            var opened=0
            val chords=mutableListOf<ScreenNavKeyPolicy.Chord>()
            override fun open():Boolean { opened++;active=true;return true }
            override fun dispatch(chord:ScreenNavKeyPolicy.Chord):Boolean { chords.add(chord);return true }
            override fun close() { active=false }
        }
        ScreenTextSelectorBridge.register(host)
        try {
            field("mInputConnection",null)
            field("ctrlKeyMap",mapOf(
                KeyEvent.KEYCODE_P to KeyMappingLoader.CtrlMapping("action","select_screen_text"),
                KeyEvent.KEYCODE_R to KeyMappingLoader.CtrlMapping("action","expand_selection_right")))
            tapFn();tapFn();assertTrue(service.screenNavLocked)
            assertEquals(0,host.opened)
            assertTrue(service.dispatchScreenNavKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_P)))
            assertEquals(1,host.opened)
            assertTrue(service.dispatchScreenNavKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_R)))
            assertEquals(1,host.chords.size);assertTrue(input.events.isEmpty())
        } finally { ScreenTextSelectorBridge.unregister(host) }
        assertFalse(ScreenTextSelectorBridge.available)
    }
    private class NativeInput:BaseInputConnection(View(RuntimeEnvironment.getApplication()),false) {
        val events=mutableListOf<KeyEvent>()
        override fun sendKeyEvent(event:KeyEvent):Boolean { events.add(event);return true }
        override fun getExtractedText(request:ExtractedTextRequest?,flags:Int):android.view.inputmethod.ExtractedText?=error("Native relay must not request text")
    }
}
