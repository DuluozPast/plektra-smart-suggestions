package rocks.pkb.plektra.core

import android.view.KeyEvent
import rocks.pkb.plektra.SettingsManager
import org.junit.Assert.*
import org.junit.Test

class EnterSendShiftMatcherTest {
    @Test fun selectedSideMatchesOnlyThatHeldKey() {
        assertTrue(EnterSendShiftMatcher.isActive("left", 0, setOf(KeyEvent.KEYCODE_SHIFT_LEFT)))
        assertFalse(EnterSendShiftMatcher.isActive("left", 0, setOf(KeyEvent.KEYCODE_SHIFT_RIGHT)))
        assertTrue(EnterSendShiftMatcher.isActive("right", 0, setOf(KeyEvent.KEYCODE_SHIFT_RIGHT)))
        assertFalse(EnterSendShiftMatcher.isActive("right", 0, setOf(KeyEvent.KEYCODE_SHIFT_LEFT)))
    }
    @Test fun genericShiftOrCapsLockDoesNotSend() {
        assertFalse(EnterSendShiftMatcher.isActive("left", KeyEvent.META_SHIFT_ON, emptySet()))
        assertFalse(EnterSendShiftMatcher.isActive("right", KeyEvent.META_CAPS_LOCK_ON, emptySet()))
    }
    @Test fun sideSpecificPhysicalMetaIsRecognized() {
        assertTrue(EnterSendShiftMatcher.isActive("right", KeyEvent.META_SHIFT_RIGHT_ON, emptySet()))
        assertFalse(EnterSendShiftMatcher.isActive("left", KeyEvent.META_SHIFT_RIGHT_ON, emptySet()))
    }
    @Test fun automaticSoftwareShiftCannotSend() {
        assertFalse(EnterSendShiftMatcher.isActive("left", KeyEvent.META_SHIFT_LEFT_ON, emptySet(), allowEventShift = false))
        assertTrue(EnterSendShiftMatcher.isActive("left", KeyEvent.META_SHIFT_LEFT_ON,
            setOf(KeyEvent.KEYCODE_SHIFT_LEFT), allowEventShift = false))
    }
}
