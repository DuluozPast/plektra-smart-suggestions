package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.core.ModifierStateController
import rocks.pkb.plektra.inputmethod.subtype.AdditionalSubtypeUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputMethodManager
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [LanguageSwitchServiceE2ETest.LanguageImeShadow::class])
class LanguageSwitchServiceE2ETest {
    @Implements(InputMethodManager::class)
    class LanguageImeShadow : ShadowInputMethodManager() {
        companion object {
            var enabled = emptyList<InputMethodSubtype>()
            val switches = mutableListOf<InputMethodSubtype>()
            val explicitlyEnabled = mutableListOf<List<Int>>()
        }
        @Implementation protected fun getEnabledInputMethodSubtypeList(info: InputMethodInfo, implicit: Boolean): List<InputMethodSubtype> = enabled
        @Implementation protected fun setExplicitlyEnabledInputMethodSubtypes(id: String, hashes: IntArray) {
            explicitlyEnabled += hashes.toList()
        }
        @Implementation protected fun setInputMethodAndSubtype(token: IBinder, id: String, subtype: InputMethodSubtype) {
            switches += subtype
            setCurrentInputMethodSubtype(subtype)
        }
    }
    private lateinit var context: Context
    private lateinit var service: PhysicalKeyboardInputMethodService
    private lateinit var connection: InputConnection
    private lateinit var imm: InputMethodManager
    private lateinit var english: InputMethodSubtype
    private lateinit var german: InputMethodSubtype
    private lateinit var italian: InputMethodSubtype

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        DeviceSpecific.clearTestOverrides()
        SettingsManager.setAltShiftLayoutSwitchEnabled(context, false)
        SettingsManager.setAltSpacePreviousLanguageEnabled(context, true)
        SettingsManager.setCtrlSpaceLayoutSwitchEnabled(context, true)
        SettingsManager.setToastOnLayoutSwitchEnabled(context, false)
        service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create().get()
        connection = mock(InputConnection::class.java)
        `when`(connection.sendKeyEvent(any(KeyEvent::class.java))).thenReturn(true)
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "plektra.test" }
        field("mInputConnection", connection)
        field("mStartedInputConnection", connection)
        field("mInputEditorInfo", info)
        field("isInputViewActive", true)
        field("inputContextState", InputContextState.fromEditorInfo(info))
        service.window.window!!.attributes = service.window.window!!.attributes.apply { token = Binder() }
        imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val shadow = shadowOf(imm)
        shadow.setInputMethodInfoList(listOf(InputMethodInfo(context.packageName, PhysicalKeyboardInputMethodService::class.java.name, "Plektra", null)))
        english = subtype("en_US", "qwerty")
        german = subtype("de_DE", "qwertz")
        italian = subtype("it_IT", "qwerty")
        LanguageImeShadow.enabled = listOf(english, german, italian)
        LanguageImeShadow.switches.clear()
        LanguageImeShadow.explicitlyEnabled.clear()
        shadow.setCurrentInputMethodSubtype(english)
    }
    @After fun tearDown() { service.onDestroy(); DeviceSpecific.clearTestOverrides() }
    private fun subtype(locale: String, layout: String, additional: Boolean = false) = InputMethodSubtype.InputMethodSubtypeBuilder()
        .setSubtypeLocale(locale).setSubtypeMode("keyboard")
        .setSubtypeExtraValue("KeyboardLayoutSet=$layout" + if (additional) ",isAdditionalSubtype" else "").build()
    private fun field(name: String, value: Any?) {
        var cls: Class<*>? = service.javaClass
        while (cls != null) {
            try { cls.getDeclaredField(name).apply { isAccessible = true }.set(service, value); return }
            catch (_: NoSuchFieldException) { cls = cls.superclass }
        }
        error(name)
    }
    private fun modifiers(): ModifierStateController = service.javaClass.getDeclaredField("modifierStateController")
        .apply { isAccessible = true }.get(service) as ModifierStateController
    private fun event(action: Int, key: Int, meta: Int = 0, repeat: Int = 0) = KeyEvent(1000L, 1100L, action, key, repeat, meta)
    private fun altSpace(meta: Int = KeyEvent.META_ALT_ON) {
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, meta)))
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, meta)))
        shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
    }

    @Test fun altSpaceCyclesBackwardsWrapsAndDoesNotInsertSpace() {
        altSpace()
        assertEquals(italian, imm.currentInputMethodSubtype)
        altSpace()
        assertEquals(german, imm.currentInputMethodSubtype)
        assertEquals(listOf(italian, german), LanguageImeShadow.switches)
        verify(connection, never()).commitText(any(CharSequence::class.java), anyInt())
        verify(connection, never()).sendKeyEvent(any(KeyEvent::class.java))
    }
    @Test fun repeatsAndReleaseAfterAltReleaseDoNotSwitchTwiceOrLeakToEditor() {
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT))
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.META_ALT_ON)))
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT))
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, repeat = 1)))
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE)))
        assertEquals(listOf(italian), LanguageImeShadow.switches)
        assertFalse(modifiers().altOneShot)
        verify(connection, never()).commitText(any(CharSequence::class.java), anyInt())
    }
    @Test fun altSpaceMarksNonModifierInteractionSoNextAltTapDoesNotDoubleLatch() {
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT))
        service.onKeyDown(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.META_ALT_ON))
        service.onKeyUp(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, KeyEvent.META_ALT_ON))
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT))
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT))
        assertFalse(modifiers().altLatchActive)
    }

    @Test fun autoShiftDoesNotChangeDirection() {
        modifiers().shiftOneShot = true
        altSpace(KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON)
        assertEquals(italian, imm.currentInputMethodSubtype)
    }
    @Test fun ctrlSpaceKeepsForwardDirectionEvenWithAutoShiftAndConsumesRepeats() {
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE,
            KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)))
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, repeat = 1)))
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE)))
        assertEquals(listOf(german), LanguageImeShadow.switches)
        verify(connection, never()).commitText(any(CharSequence::class.java), anyInt())
    }
    @Test fun hiddenRegionalSystemLanguageAndDuplicateDisappearFromBothDirectionsAndAvailableList() {
        SettingsManager.hideSystemInputStyle(context, "en_GB", "qwerty")
        val duplicate = subtype("en_GB", "qwerty", additional = true)
        LanguageImeShadow.enabled = listOf(english, duplicate, german, italian)
        assertEquals(listOf(german, italian), SubtypeCycler.getAvailableSubtypes(service, service.javaClass))
        shadowOf(imm).setCurrentInputMethodSubtype(german)
        altSpace()
        assertEquals(italian, imm.currentInputMethodSubtype)
        assertTrue(SubtypeCycler.cycleToNextSubtype(service, service.javaClass, service.assets, false))
        assertEquals(german, imm.currentInputMethodSubtype)
        assertFalse(AdditionalSubtypeUtils.shouldKeepSubtype(context, context.assets, english, setOf("en_GB"), setOf("en")))
        assertFalse(AdditionalSubtypeUtils.shouldKeepSubtype(context, context.assets, duplicate, setOf("en_GB"), setOf("en")))
        assertTrue(AdditionalSubtypeUtils.createAdditionalSubtypesArray("en_GB:qwerty", context.assets, context).isEmpty())
        SettingsManager.showSystemInputStyle(context, "en_GB", "qwerty")
        assertTrue(SubtypeCycler.getAvailableSubtypes(service, service.javaClass).contains(english))
    }
    @Test
    @Config(sdk = [34])
    fun hidingSystemLanguageUpdatesAndroidPickerRegistrationWhileImeIsRunning() {
        val visibleGerman = subtype("de_DE", "qwertz", additional = true)
        val visibleItalian = subtype("it_IT", "qwerty", additional = true)
        val info = imm.inputMethodList.single()
        val arrayClass = Class.forName("android.view.inputmethod.InputMethodSubtypeArray")
        val subtypeArray = arrayClass.getConstructor(List::class.java)
            .newInstance(listOf(english, visibleGerman, visibleItalian))
        info.javaClass.getDeclaredField("mSubtypes").apply { isAccessible = true }.set(info, subtypeArray)
        LanguageImeShadow.enabled = listOf(english, visibleGerman, visibleItalian)
        SettingsManager.hideSystemInputStyle(context, "en_GB", "qwerty")
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        assertTrue(LanguageImeShadow.explicitlyEnabled.isNotEmpty())
        assertEquals(setOf(visibleGerman.hashCode(), visibleItalian.hashCode()),
            LanguageImeShadow.explicitlyEnabled.last().toSet())
        assertFalse(LanguageImeShadow.explicitlyEnabled.last().contains(english.hashCode()))
    }

    @Test fun quickLauncherAndLanguageShortcutAreMutuallyExclusiveInBothDirections() {
        SettingsManager.setQuickLauncherAltSpaceInTextFields(context, true)
        assertFalse(SettingsManager.isAltSpacePreviousLanguageEnabled(context))
        assertTrue(SettingsManager.getQuickLauncherAltSpaceInTextFields(context))
        service.onKeyDown(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.META_ALT_ON))
        assertTrue(LanguageImeShadow.switches.isEmpty())
        SettingsManager.setAltSpacePreviousLanguageEnabled(context, true)
        assertFalse(SettingsManager.getQuickLauncherAltSpaceInTextFields(context))
        altSpace()
        assertEquals(listOf(italian), LanguageImeShadow.switches)
    }
    @Test fun enablingLauncherAltSpaceRoutesToLauncherAndEnablingLanguageRoutesBackToCycler() {
        SettingsManager.setQuickLauncherShortcut(context, KeyEvent.KEYCODE_SPACE)
        SettingsManager.setQuickLauncherAltShortcutsOutsideTextFields(context, true)
        SettingsManager.setQuickLauncherAltSpaceInTextFields(context, true)
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.META_ALT_ON)))
        assertTrue(LanguageImeShadow.switches.isEmpty())
        assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        SettingsManager.setAltSpacePreviousLanguageEnabled(context, true)
        altSpace()
        assertEquals(listOf(italian), LanguageImeShadow.switches)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }
    @Test fun disabledSoftwareReverseOptionKeepsForwardCycle() {
        SettingsManager.setCtrlLanguagePreviousEnabled(context, false)
        modifiers().ctrlOneShot = true
        val controller = service.javaClass.getDeclaredField("candidatesBarController").apply { isAccessible = true }
            .get(service) as CandidatesBarController
        controller.onLanguageSwitchRequested!!.invoke()
        assertEquals(german, imm.currentInputMethodSubtype)
    }

    @Test fun reverseLanguageShortcutPreservesOtherAssignedAltShortcuts() {
        SettingsManager.setQuickLauncherAltShortcutsOutsideTextFields(context, true)
        SettingsManager.setQuickLauncherAltShortcutsInTextFields(context, true)
        SettingsManager.setQuickLauncherShortcut(context, KeyEvent.KEYCODE_A)
        SettingsManager.setAltSpacePreviousLanguageEnabled(context, true)
        assertTrue(SettingsManager.getQuickLauncherAltShortcutsInTextFields(context))
        assertFalse(SettingsManager.getQuickLauncherAltSpaceInTextFields(context))
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_A, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, KeyEvent.META_ALT_ON)))
        assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertTrue(LanguageImeShadow.switches.isEmpty())
    }
    @Test fun missingVisibleLanguagesConsumesChordWithoutEditorInput() {
        LanguageImeShadow.enabled = emptyList()
        altSpace()
        assertTrue(LanguageImeShadow.switches.isEmpty())
        verify(connection, never()).commitText(any(CharSequence::class.java), anyInt())
        verify(connection, never()).sendKeyEvent(any(KeyEvent::class.java))
    }

    @Test fun unrelatedCtrlAltAndMetaChordsDoNotSwitchLanguage() {
        listOf(KeyEvent.META_ALT_ON or KeyEvent.META_CTRL_ON, KeyEvent.META_ALT_ON or KeyEvent.META_META_ON, KeyEvent.META_ALT_ON or KeyEvent.META_SYM_ON).forEach { meta ->
            service.onKeyDown(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, meta))
            service.onKeyUp(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, meta))
        }
        assertTrue(LanguageImeShadow.switches.isEmpty())
    }
    @Test fun softwareLanguageCallbackWithCtrlUsesSameFilteredReverseCycle() {
        SettingsManager.hideSystemInputStyle(context, "en_US", "qwerty")
        shadowOf(imm).setCurrentInputMethodSubtype(german)
        modifiers().ctrlOneShot = true
        val controller = service.javaClass.getDeclaredField("candidatesBarController").apply { isAccessible = true }
            .get(service) as CandidatesBarController
        controller.onLanguageSwitchRequested!!.invoke()
        assertEquals(italian, imm.currentInputMethodSubtype)
        assertFalse(modifiers().ctrlOneShot)
    }
}
