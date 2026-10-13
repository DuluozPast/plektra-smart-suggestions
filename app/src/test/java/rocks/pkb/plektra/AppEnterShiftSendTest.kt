package rocks.pkb.plektra

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppEnterShiftSendTest {
    @Test fun shiftPresetAndSideSurviveReload() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAppEnterBehaviorPreset(context, SettingsManager.ENTER_BEHAVIOR_PRESET_ENTER_NEWLINE_SHIFT_SEND)
        SettingsManager.setAppEnterSendShiftSide(context, SettingsManager.ENTER_SEND_SHIFT_RIGHT)
        assertEquals(SettingsManager.ENTER_BEHAVIOR_PRESET_ENTER_NEWLINE_SHIFT_SEND, SettingsManager.getAppEnterBehaviorPreset(context))
        assertEquals(SettingsManager.ENTER_SEND_SHIFT_RIGHT, SettingsManager.getAppEnterSendShiftSide(context))
        SettingsManager.setAppEnterSendShiftSide(context, SettingsManager.ENTER_SEND_SHIFT_LEFT)
        assertEquals(SettingsManager.ENTER_SEND_SHIFT_LEFT, SettingsManager.getAppEnterSendShiftSide(context))
    }
    @Test fun perAppShiftBehaviorSurvivesSerializationAndPresetInference() {
        val context = RuntimeEnvironment.getApplication()
        val override = SettingsManager.AppEnterBehaviorOverride("com.whatsapp", SettingsManager.ENTER_BEHAVIOR_ENTER_NEWLINE_SHIFT_SEND)
        SettingsManager.setAppEnterBehaviorOverrides(context, listOf(override))
        val restored = SettingsManager.getAppEnterBehaviorOverrides(context)
        assertEquals(listOf(override), restored)
        assertEquals(SettingsManager.ENTER_BEHAVIOR_PRESET_ENTER_NEWLINE_SHIFT_SEND, inferKnownAppEnterBehaviorPreset(restored))
    }
}
