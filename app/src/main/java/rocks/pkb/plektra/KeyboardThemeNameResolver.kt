package rocks.pkb.plektra

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import java.util.Locale

/** *The displayed title follows the same language/layout and daily-cycle priority as its colours.* */
object KeyboardThemeNameResolver {
    private data class Key(val prefs: SharedPreferences, val locale: String?, val layout: String?,
        val day: Long, val saved: String?, val overrides: String?, val names: String?,
        val anchor: Long, val mode: String, val theme: SettingsManager.KeyboardThemeSettings,
        val uiLocale: String)
    private data class Entry(val key: Key, val name: String)
    // *Only one entry per keyboard target: repeated renders do not reparse saved-theme JSON.*
    private val cache = mutableMapOf<SettingsManager.KeyboardThemeTarget, Entry>()

    @Synchronized
    fun resolve(context: Context, target: SettingsManager.KeyboardThemeTarget, locale: String?,
        layout: String?, effectiveTheme: SettingsManager.KeyboardThemeSettings,
        day: Long = LocalDate.now().toEpochDay()): String {
        val prefs = SettingsManager.getPreferences(context)
        val suffix = target.name.lowercase(Locale.ROOT)
        val mode = SettingsManager.getKeyboardThemeAssignmentMode(context, target)
        val key = Key(prefs, locale, layout, day, prefs.getString("keyboard_theme_saved_themes", null),
            prefs.getString("keyboard_theme_layout_overrides_$suffix", null),
            prefs.getString(DailyThemeRotation.namesKey(target), null),
            prefs.getLong(DailyThemeRotation.anchorKey(target), day), mode, effectiveTheme,
            context.resources.configuration.locales.toLanguageTags())
        cache[target]?.let { if (it.key == key) return it.name }
        val saved = SettingsManager.getSavedKeyboardThemes(context)
        val assignment = SettingsManager.keyboardThemeAssignmentForDisplay(context, target, locale, layout)
        val names = when {
            assignment?.mode == SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY -> assignment.rotationThemeNames
            assignment == null && mode == SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY -> DailyThemeRotation.selectedNames(context, target)
            else -> emptyList()
        }
        val anchor = assignment?.rotationAnchorDay ?: key.anchor
        val cycle = names.mapNotNull { name -> saved.firstOrNull { it.name.equals(name, true) } }
        // *Read the chosen cycle entry's name directly; equal-colour themes may have different titles.*
        val cycleName = cycle.getOrNull(DailyThemeRotation.index(day, anchor, cycle.size))?.name
        val builtIns = keyboardThemePresets().map {
            if (target == SettingsManager.KeyboardThemeTarget.SOFTWARE) it.withSoftwareKeyboardDefaults() else it
        }
        val name = cycleName ?: saved.firstOrNull { it.theme == effectiveTheme }?.name
            ?: builtIns.firstOrNull { it.toSettingsTheme() == effectiveTheme }?.name
            ?: context.getString(R.string.keyboard_theme_label_custom)
        cache[target] = Entry(key, name)
        return name
    }
}
