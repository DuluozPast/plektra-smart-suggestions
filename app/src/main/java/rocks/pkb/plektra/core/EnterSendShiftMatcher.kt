package rocks.pkb.plektra.core

import android.view.KeyEvent
import rocks.pkb.plektra.SettingsManager

/** Only an explicitly pressed side sends; automatic capitalization never does. */
object EnterSendShiftMatcher {
    fun isActive(side: String, metaState: Int, heldKeys: Set<Int>, allowEventShift: Boolean = true): Boolean {
        val right = side == SettingsManager.ENTER_SEND_SHIFT_RIGHT
        val key = if (right) KeyEvent.KEYCODE_SHIFT_RIGHT else KeyEvent.KEYCODE_SHIFT_LEFT
        val mask = if (right) KeyEvent.META_SHIFT_RIGHT_ON else KeyEvent.META_SHIFT_LEFT_ON
        return (allowEventShift && metaState and mask != 0) || key in heldKeys
    }
}
