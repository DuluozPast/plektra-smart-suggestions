package rocks.pkb.plektra.inputmethod

import android.os.Binder
import android.os.Looper
import android.text.InputType
import android.text.Selection
import android.view.View
import android.view.inputmethod.*
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.core.ModifierStateController
import rocks.pkb.plektra.core.suggestions.*
import rocks.pkb.plektra.inputmethod.suggestions.SuggestionButtonHandler
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class SuggestionCapsLockTest {
    private lateinit var service: PhysicalKeyboardInputMethodService
    private lateinit var input: BaseInputConnection
    private lateinit var modifiers: ModifierStateController
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create().get()
        input = BaseInputConnection(View(context), true)
        Selection.setSelection(input.editable!!, 0)
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "com.whatsapp" }
        field("mInputConnection", input); field("mStartedInputConnection", input); field("mInputEditorInfo", info)
        field("inputContextState", InputContextState.fromEditorInfo(info)); field("isInputViewActive", true)
        service.window.window!!.attributes = service.window.window!!.attributes.apply { token = Binder() }
        modifiers = service.javaClass.getDeclaredField("modifierStateController").apply { isAccessible=true }.get(service) as ModifierStateController
    }
    @After fun cleanup() { service.onDestroy() }
    private fun field(name:String, value:Any?) {
        var type:Class<*>?=service.javaClass
        while(type!=null) { try {type.getDeclaredField(name).apply{isAccessible=true}.set(service,value);return} catch(_:NoSuchFieldException){type=type.superclass} }
        error(name)
    }
    private fun results() = listOf(
        SuggestionResult("à l'état, demain",0,1.0,SuggestionSource.MAIN,SuggestionKind.CURRENT_WORD),
        SuggestionResult("Emmanuel Castel",0,1.0,SuggestionSource.MAIN,SuggestionKind.NEXT_WORD),
        SuggestionResult("bonjour",0,1.0,SuggestionSource.MAIN,SuggestionKind.STARTER_WORD))
    @Suppress("UNCHECKED_CAST") private fun visible() = service.javaClass.getDeclaredMethod("visibleSuggestionStrings").apply{isAccessible=true}.invoke(service) as List<String>
    @Test fun capsLockDisplaysEverySuggestionKindInUppercaseAndUnlockRestoresOriginals() {
        field("latestSuggestionResults", results())
        modifiers.capsLockEnabled=true
        assertEquals(listOf("À L'ÉTAT, DEMAIN","EMMANUEL CASTEL","BONJOUR"), visible())
        modifiers.capsLockEnabled=false
        assertEquals("à l'état, demain", visible().first())
        assertEquals("Emmanuel Castel", visible()[1])
    }
    @Test fun consecutiveSwipeSelectionsKeepCapsLockAndCommitUppercase() {
        modifiers.capsLockEnabled=true
        repeat(2) {
            field("latestSuggestionResults", results())
            service.javaClass.getDeclaredMethod("acceptSuggestionAtIndex", Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(service,1)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(modifiers.capsLockEnabled)
        }
        assertEquals("À L'ÉTAT, DEMAIN À L'ÉTAT, DEMAIN ", input.editable.toString())
    }
    @Test fun tappedSuggestionUsesTheCurrentCapsLockStateWithoutTypedPrefix() {
        var locked=true
        val context=RuntimeEnvironment.getApplication()
        val click=SuggestionButtonHandler.createSuggestionClickListener("à demain",input,
            shouldDisableAutoCapitalize=true,capsLockActive={locked})
        click.onClick(View(context))
        click.onClick(View(context))
        assertEquals("À DEMAIN À DEMAIN ",input.editable.toString())
        locked=false
        click.onClick(View(context))
        assertEquals("À DEMAIN À DEMAIN à demain ",input.editable.toString())
    }
    private fun lockWithAnOlderTemporaryLayerSnapshot() {
        val beforeLock = modifiers.captureLogicalState()
        modifiers.capsLockEnabled = true
        field("shiftLayerLatched", true)
        field("modifierStateBeforeHold", beforeLock)
    }
    @Test fun swipeCannotRestoreThePreLockStateFromTheTemporaryLayer() {
        lockWithAnOlderTemporaryLayerSnapshot()
        field("latestSuggestionResults", results())
        service.javaClass.getDeclaredMethod("acceptSuggestionAtIndex", Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(service,1)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Accepting a suggestion must not undo explicit Caps Lock", modifiers.capsLockEnabled)
        assertEquals("À L'ÉTAT, DEMAIN ", input.editable.toString())
    }
    @Test fun tapCallbackCannotRestoreThePreLockStateFromTheTemporaryLayer() {
        lockWithAnOlderTemporaryLayerSnapshot()
        val bar=service.javaClass.getDeclaredField("candidatesBarController").apply{isAccessible=true}.get(service) as CandidatesBarController
        bar.onSuggestionCommitted!!.invoke("", "DEMAIN")
        assertTrue(modifiers.capsLockEnabled)
    }
    @Test fun hidingTheKeyboardClearsTransientModifiersButKeepsExplicitCapsLock() {
        modifiers.capsLockEnabled=true
        service.javaClass.getDeclaredMethod("resetModifierStates",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(service,false)
        assertTrue(modifiers.capsLockEnabled)
        assertFalse(modifiers.shiftPhysicallyPressed)
    }
    @Test fun learnedLowercaseNextWordIsAvailableAfterAnUppercasePrefix() {
        UserNGramStore(RuntimeEnvironment.getApplication()).use { store ->
            val predictor=NextWordPredictor(store,asyncLearning=false)
            predictor.learn(java.util.Locale.FRANCE,"bonjour","demain")
            field("latestSuggestionResults",predictor.predict(java.util.Locale.FRANCE,"BONJOUR"))
            modifiers.capsLockEnabled=true
            assertTrue(visible().contains("DEMAIN"))
        }
    }

}
