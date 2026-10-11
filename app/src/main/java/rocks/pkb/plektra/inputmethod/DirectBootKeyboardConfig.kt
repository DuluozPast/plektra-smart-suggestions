package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager

/**
 * Minimal BFU configuration, inspired by Mircea Horea IONICĂ (mionica),
 * https://github.com/mionica/pastiera/commit/5da24e7634.
 * The full IME still requires an unlocked user; this store does not enable it during Direct Boot.
 */
internal class DirectBootKeyboardConfig(private val context: Context) {
    private val storage: SharedPreferences
        get() = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val builtinLayouts: Set<String>
        get() = context.assets.list("common/layouts").orEmpty()
            .filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.toSet()

    /** Copy only explicit non-content settings after unlock; never migrate the source preferences. */
    fun refresh(source: SharedPreferences): Boolean {
        if (!isUserUnlocked(context)) return false
        val values = source.all
        val editor = storage.edit().clear()
        val layout = values["keyboard_layout"] as? String
        editor.putString("keyboard_layout", layout?.takeIf { it in builtinLayouts } ?: "qwerty")
        val profile = values["physical_keyboard_profile_override"] as? String
        editor.putString("physical_keyboard_profile_override", profile?.takeIf { it in PROFILES } ?: "auto")
        val currency = values["physical_keyboard_currency_symbol"] as? String
        editor.putString("physical_keyboard_currency_symbol", currency?.takeIf { it in CURRENCIES } ?: "€")
        BOOLEAN_KEYS.forEach { key ->
            editor.putBoolean(key, values[key] as? Boolean ?: (key == "clear_alt_on_space"))
        }
        editor.apply()
        return true
    }

    fun read(): Map<String, *> = storage.all.filterKeys { it in KEYS }

    companion object {
        const val PREFS_NAME = "direct_boot_keyboard_config"
        val BOOLEAN_KEYS = setOf("shift_tap_latches", "alt_tap_latches", "ctrl_tap_latches", "clear_alt_on_space")
        val KEYS = BOOLEAN_KEYS + setOf("keyboard_layout", "physical_keyboard_profile_override", "physical_keyboard_currency_symbol")
        private val CURRENCIES = setOf("$", "€", "£", "¥", "₹", "₽", "₩")
        private val PROFILES = setOf("auto", "key2", "Q25", "q25", "titan", "titan2", "titan2elite_qwerty", "mp01", "clicks_razr", "clicks_pixel", "clicks_power")

        fun isUserUnlocked(context: Context): Boolean =
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
    }
}
