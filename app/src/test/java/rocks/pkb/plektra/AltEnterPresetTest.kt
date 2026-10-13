package rocks.pkb.plektra

import android.content.Context
import android.view.inputmethod.EditorInfo
import rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class AltEnterPresetTest {
    @Test fun altSendIsInBothTheMainPresetAndPerAppBehaviorMenus() {
        assertTrue(enterPresetOptions().contains(SettingsManager.ENTER_BEHAVIOR_PRESET_ENTER_NEWLINE_ALT_SEND))
        assertTrue(enterBehaviorOptions().contains(SettingsManager.ENTER_BEHAVIOR_ENTER_NEWLINE_ALT_SEND))
    }
    @Test fun altPresetAndAppOverrideAreSavedAndInferredWithoutFallingBack() {
        val context=RuntimeEnvironment.getApplication()
        SettingsManager.setAppEnterBehaviorPreset(context,SettingsManager.ENTER_BEHAVIOR_PRESET_ENTER_NEWLINE_ALT_SEND)
        assertEquals(SettingsManager.ENTER_BEHAVIOR_PRESET_ENTER_NEWLINE_ALT_SEND,SettingsManager.getAppEnterBehaviorPreset(context))
        val override=SettingsManager.AppEnterBehaviorOverride("com.whatsapp",SettingsManager.ENTER_BEHAVIOR_ENTER_NEWLINE_ALT_SEND)
        SettingsManager.setAppEnterBehaviorOverrides(context,listOf(override))
        assertEquals(listOf(override),SettingsManager.getAppEnterBehaviorOverrides(context))
        assertEquals(SettingsManager.ENTER_BEHAVIOR_PRESET_ENTER_NEWLINE_ALT_SEND,inferKnownAppEnterBehaviorPreset(listOf(override)))
    }
    @Test fun sendPriorityIsLimitedToConfiguredAppsAndDisappearsWhenDisabled() {
        val context=RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        val service=PhysicalKeyboardInputMethodService()
        ReflectionHelpers.callInstanceMethod<Void>(service,"attachBaseContext",ClassParameter.from(Context::class.java,context))
        SettingsManager.setAppEnterBehaviorEnabled(context,true)
        SettingsManager.setAppEnterBehaviorPreset(context,SettingsManager.ENTER_BEHAVIOR_PRESET_APP_DEFAULT)
        val info=EditorInfo().apply { packageName="com.whatsapp" }
        SettingsManager.setAppEnterBehaviorOverrides(context,listOf(SettingsManager.AppEnterBehaviorOverride("com.whatsapp",SettingsManager.ENTER_BEHAVIOR_ENTER_NEWLINE_ALT_SEND)))
        assertTrue(service.isAltEnterSendConfigured(info))
        assertFalse(service.isAltEnterSendConfigured(EditorInfo().apply { packageName="com.example.other" }))
        SettingsManager.setAppEnterBehaviorEnabled(context,false)
        assertFalse(service.isAltEnterSendConfigured(info))
        SettingsManager.setAppEnterBehaviorEnabled(context,true)
        SettingsManager.setAppEnterBehaviorOverrides(context,listOf(SettingsManager.AppEnterBehaviorOverride("com.whatsapp",SettingsManager.ENTER_BEHAVIOR_APP_DEFAULT,additionalSendShortcut=SettingsManager.ENTER_ADDITIONAL_SEND_SHORTCUT_ALT_ENTER)))
        assertTrue(service.isAltEnterSendConfigured(info))
    }
}
