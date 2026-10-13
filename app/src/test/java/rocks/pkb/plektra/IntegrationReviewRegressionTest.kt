package rocks.pkb.plektra

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.BaseInputConnection
import android.view.View
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.inputmethod.AutoCapitalizeHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class IntegrationReviewRegressionTest {
    @Test fun literalCaseRestrictionWinsOverRestrictedFieldPreference() {
        val info=EditorInfo().apply { inputType=InputType.TYPE_CLASS_TEXT; privateImeOptions="pastiera.literal_entry" }
        val state=InputContextState.fromEditorInfo(info)
        assertTrue(state.disablesAutoCapitalize(true))
        assertTrue(state.disablesAutoCapitalize(false))
        info.privateImeOptions=null
        assertFalse(InputContextState.fromEditorInfo(info).disablesAutoCapitalize(true))
        info.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        assertFalse(InputContextState.fromEditorInfo(info).disablesAutoCapitalize(true))
        assertTrue(InputContextState.fromEditorInfo(info).disablesAutoCapitalize(false))
    }
    @Test fun literalRestrictionAlsoWinsOverExplicitSentenceCapitalizationFlag() {
        val context=RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context,true)
        val info=EditorInfo().apply {
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            privateImeOptions="pastiera.literal_entry"
        }
        var enabled=0
        AutoCapitalizeHelper.checkAutoCapitalizeOnRestart(context, BaseInputConnection(View(context),true), true,
            enableShift={ enabled++; true }, disableShift={true}, onUpdateStatusBar={}, inputContextState=InputContextState.fromEditorInfo(info))
        assertEquals(0,enabled)
    }
    @Test fun altEnterIsOptionalAndSurvivesPerAppSerialization() {
        val context=RuntimeEnvironment.getApplication()
        val override=SettingsManager.AppEnterBehaviorOverride("com.whatsapp",SettingsManager.ENTER_BEHAVIOR_ENTER_NEWLINE_CTRL_SEND,
            additionalSendShortcut=SettingsManager.ENTER_ADDITIONAL_SEND_SHORTCUT_ALT_ENTER)
        SettingsManager.setAppEnterBehaviorOverrides(context,listOf(override))
        assertEquals(listOf(override),SettingsManager.getAppEnterBehaviorOverrides(context))
        assertEquals(SettingsManager.ENTER_ADDITIONAL_SEND_SHORTCUT_NONE,SettingsManager.AppEnterBehaviorOverride("com.example",SettingsManager.ENTER_BEHAVIOR_APP_DEFAULT).additionalSendShortcut)
    }
}
