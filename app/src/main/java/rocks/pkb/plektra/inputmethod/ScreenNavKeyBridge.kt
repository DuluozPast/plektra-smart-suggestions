package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import rocks.pkb.plektra.data.mappings.KeyMappingLoader

/** The accessibility filter only forwards keys; the selected IME sends native events. */
internal object ScreenNavKeyBridge {
    interface Target {
        val screenNavLocked: Boolean
        fun dispatchScreenNavKey(event: KeyEvent): Boolean
        fun canRepeatScreenNavKey(event: KeyEvent): Boolean
        fun resetScreenNavKeys()
        fun breakScreenNavTapSequence()
    }
    @Volatile private var target: Target? = null
    fun register(value: Target) { target=value }
    fun unregister(value: Target) { if(target===value) target=null }
    val locked: Boolean get()=target?.screenNavLocked==true
    fun dispatch(event: KeyEvent)=target?.dispatchScreenNavKey(event)==true
    fun canRepeat(event: KeyEvent)=target?.canRepeatScreenNavKey(event)==true
    fun reset() { target?.resetScreenNavKeys() }
    fun breakTap() { target?.breakScreenNavTapSequence() }
}

/** Only navigation, selection and explicitly requested copy; never cut, paste or text insertion. */
internal object ScreenNavKeyPolicy {
    data class Chord(val keyCode: Int, val meta: Int)
    fun chord(mapping: KeyMappingLoader.CtrlMapping?, physicalShift: Boolean): Chord? {
        mapping ?: return null
        val shift=KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val ctrl=KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if(mapping.type=="action") return when(mapping.value) {
            "expand_selection_left" -> Chord(KeyEvent.KEYCODE_DPAD_LEFT,shift)
            "expand_selection_right" -> Chord(KeyEvent.KEYCODE_DPAD_RIGHT,shift)
            "expand_selection_up" -> Chord(KeyEvent.KEYCODE_DPAD_UP,shift)
            "expand_selection_down" -> Chord(KeyEvent.KEYCODE_DPAD_DOWN,shift)
            "expand_selection_word_left" -> Chord(KeyEvent.KEYCODE_DPAD_LEFT,shift or ctrl)
            "expand_selection_word_right" -> Chord(KeyEvent.KEYCODE_DPAD_RIGHT,shift or ctrl)
            "move_word_left" -> Chord(KeyEvent.KEYCODE_DPAD_LEFT,ctrl)
            "move_word_right" -> Chord(KeyEvent.KEYCODE_DPAD_RIGHT,ctrl)
            "copy" -> Chord(KeyEvent.KEYCODE_C,ctrl)
            "select_all" -> Chord(KeyEvent.KEYCODE_A,ctrl)
            else -> null
        }
        if(mapping.type!="keycode") return null
        val key=when(mapping.value) {
            "DPAD_LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT; "DPAD_RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "DPAD_UP" -> KeyEvent.KEYCODE_DPAD_UP; "DPAD_DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
            "MOVE_HOME" -> KeyEvent.KEYCODE_MOVE_HOME; "MOVE_END" -> KeyEvent.KEYCODE_MOVE_END
            "PAGE_UP" -> KeyEvent.KEYCODE_PAGE_UP; "PAGE_DOWN" -> KeyEvent.KEYCODE_PAGE_DOWN
            "ESCAPE" -> KeyEvent.KEYCODE_ESCAPE
            else -> return null
        }
        return Chord(key,if(physicalShift) shift else 0)
    }
}
