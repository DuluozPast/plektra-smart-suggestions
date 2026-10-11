package rocks.pkb.plektra.inputmethod

import android.app.Application
import android.content.Context
import android.os.UserManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DirectBootKeyboardConfigTest {
    @Test
    fun snapshotCopiesOnlyApprovedConfigurationAndPreservesCredentialData() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        val source = context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
        source.edit().clear().putString("keyboard_layout", "azerty")
            .putString("physical_keyboard_profile_override", "Q25")
            .putString("snippets", "private text").putString("speech_api_key", "secret")
            .putString("clipboard_history", "private clipboard").putString("user_dictionary", "private words")
            .putBoolean("shift_tap_latches", true).commit()
        val before = source.all.toMap()
        val store = DirectBootKeyboardConfig(context)
        // Purge obsolete/unapproved keys even when the previous snapshot was not empty.
        context.createDeviceProtectedStorageContext().getSharedPreferences(DirectBootKeyboardConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("snippets", "stale secret").commit()
        assertTrue(store.refresh(source))
        assertEquals("azerty", store.read()["keyboard_layout"])
        assertEquals("Q25", store.read()["physical_keyboard_profile_override"])
        assertEquals(true, store.read()["shift_tap_latches"])
        assertEquals(DirectBootKeyboardConfig.KEYS, store.read().keys)
        val raw = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootKeyboardConfig.PREFS_NAME, Context.MODE_PRIVATE).all
        assertFalse(raw.values.any { it == "secret" || it == "stale secret" || it == "private text" })
        assertEquals(before, source.all)
        assertFalse(context.createDeviceProtectedStorageContext().getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE).contains("snippets"))
    }

    @Test
    fun lockedRefreshCannotOverwriteSnapshotAndCustomTextIsNotCopied() {
        val context = RuntimeEnvironment.getApplication()
        val user = shadowOf(context.getSystemService(UserManager::class.java))
        user.setUserUnlocked(true)
        val source = context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
        source.edit().clear().putString("keyboard_layout", "private_custom_layout")
            .putString("physical_keyboard_currency_symbol", "arbitrary private content").commit()
        val store = DirectBootKeyboardConfig(context)
        assertTrue(store.refresh(source))
        assertEquals("qwerty", store.read()["keyboard_layout"])
        assertEquals("€", store.read()["physical_keyboard_currency_symbol"])
        val before = store.read().toMap()
        user.setUserUnlocked(false)
        assertFalse(store.refresh(source))
        assertEquals(before, store.read())
    }
}
