package rocks.pkb.plektra.core

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.AlternateCharacterManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SymShiftPortTest {
    @Test
    fun openSymbolPageUsesSameShiftedTextForDisplayAndHardwareAndConsumesOneShotAfterCommit() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.saveSymMappingsPage2(context, mapOf(KeyEvent.KEYCODE_A to "é", KeyEvent.KEYCODE_0 to "ß", KeyEvent.KEYCODE_GRAVE to "$"))
        SettingsManager.setSymAutoClose(context, false)
        val prefs = SettingsManager.getPreferences(context)
        val manager = AlternateCharacterManager(context.assets, prefs, context)
        manager.reloadSymMappings2()
        val controller = SymLayoutController(context, prefs, manager)
        controller.openSymbolsPage()
        val commits = mutableListOf<String>()
        val connection = Proxy.newProxyInstance(InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)) { _, method, args ->
            if (method.name == "commitText") commits.add(args!![0].toString())
            when (method.returnType) { java.lang.Boolean.TYPE -> true; java.lang.Integer.TYPE -> 0; else -> null }
        } as InputConnection
        var consumed = 0
        assertEquals("É", controller.currentSymMappings(true)!![KeyEvent.KEYCODE_A])
        controller.handleKeyWhenActive(KeyEvent.KEYCODE_A, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A), connection,
            false, false, {}, shiftPressed = true, onSymbolCommitted = { consumed++ })
        assertEquals(listOf("É"), commits)
        assertEquals(1, consumed)
        assertEquals("SS", controller.currentSymMappings(true)!![KeyEvent.KEYCODE_0])
        assertEquals("é", controller.currentSymMappings(false)!![KeyEvent.KEYCODE_A])
        assertEquals(mapOf(KeyEvent.KEYCODE_A to "é", KeyEvent.KEYCODE_0 to "ß", KeyEvent.KEYCODE_GRAVE to "$"), SettingsManager.getSymMappingsPage2(context))
    }
}
