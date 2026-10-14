package rocks.pkb.plektra

import android.content.Context
import org.json.JSONArray
import java.time.LocalDate

/** *Optional calendar-day rotation, independent of restarts, timezone offsets and DST.* */
object DailyThemeRotation {
    private fun suffix(target: SettingsManager.KeyboardThemeTarget) = target.name.lowercase(java.util.Locale.ROOT)
    fun namesKey(target: SettingsManager.KeyboardThemeTarget) = "keyboard_theme_rotation_names_${suffix(target)}"
    fun anchorKey(target: SettingsManager.KeyboardThemeTarget) = "keyboard_theme_rotation_anchor_${suffix(target)}"
    fun selectedNames(context: Context, target: SettingsManager.KeyboardThemeTarget): List<String> {
        val raw = SettingsManager.getPreferences(context).getString(namesKey(target), "[]") ?: "[]"
        return try { val array = JSONArray(raw); (0 until array.length()).map { array.getString(it) }.distinct() }
        catch (_: Exception) { emptyList() }
    }
    fun select(context: Context, target: SettingsManager.KeyboardThemeTarget, names: List<String>) {
        SettingsManager.getPreferences(context).edit()
            .putString(namesKey(target), JSONArray(names.distinct()).toString())
            .putLong(anchorKey(target), LocalDate.now().toEpochDay()).apply()
    }
    fun index(day: Long, anchor: Long, count: Int): Int =
        if (count <= 0) -1 else Math.floorMod(day - anchor, count.toLong()).toInt()

    private data class Cache(val prefs: android.content.SharedPreferences, val saved: String?, val names: String?,
        val day: Long, val anchor: Long, val theme: SettingsManager.KeyboardThemeSettings?)
    private val caches = mutableMapOf<SettingsManager.KeyboardThemeTarget, Cache>()
    @Synchronized
    fun theme(context: Context, target: SettingsManager.KeyboardThemeTarget, day: Long = LocalDate.now().toEpochDay()): SettingsManager.KeyboardThemeSettings? {
        val prefs = SettingsManager.getPreferences(context)
        val saved = prefs.getString("keyboard_theme_saved_themes", null)
        val names = prefs.getString(namesKey(target), null)
        val anchor = prefs.getLong(anchorKey(target), day)
        caches[target]?.let { if (it.prefs === prefs && it.saved == saved && it.names == names && it.day == day && it.anchor == anchor) return it.theme }
        val available = SettingsManager.getSavedKeyboardThemes(context)
        val themes = selectedNames(context, target).mapNotNull { name -> available.firstOrNull { it.name.equals(name, true) }?.theme }
        val result = themes.getOrNull(index(day, anchor, themes.size))
        caches[target] = Cache(prefs, saved, names, day, anchor, result)
        return result
    }

    private data class OverrideCache(val prefs: android.content.SharedPreferences, val saved: String?,
        val day: Long, val assignment: SettingsManager.KeyboardThemeLayoutOverride,
        val theme: SettingsManager.KeyboardThemeSettings?)
    // *Keep at most the last resolved assignment per keyboard target, not an ever-growing locale cache.*
    private val overrideCaches = mutableMapOf<SettingsManager.KeyboardThemeTarget, OverrideCache>()
    @Synchronized
    fun themeForOverride(context: Context, target: SettingsManager.KeyboardThemeTarget,
        assignment: SettingsManager.KeyboardThemeLayoutOverride,
        day: Long = LocalDate.now().toEpochDay()): SettingsManager.KeyboardThemeSettings? {
        val prefs = SettingsManager.getPreferences(context)
        val saved = prefs.getString("keyboard_theme_saved_themes", null)
        overrideCaches[target]?.let {
            if (it.prefs === prefs && it.saved == saved && it.day == day && it.assignment == assignment) return it.theme
        }
        val available = SettingsManager.getSavedKeyboardThemes(context)
        val themes = assignment.rotationThemeNames.mapNotNull { name ->
            available.firstOrNull { it.name.equals(name, true) }?.theme
        }
        val result = themes.getOrNull(index(day, assignment.rotationAnchorDay, themes.size))
        overrideCaches[target] = OverrideCache(prefs, saved, day, assignment, result)
        return result
    }

    /** *A local rotation also needs the session-only midnight refresh when the global mode is fixed.* */
    fun hasDailyRotation(context: Context): Boolean = SettingsManager.KeyboardThemeTarget.entries.any { target ->
        SettingsManager.getKeyboardThemeAssignmentMode(context, target) == SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY ||
            SettingsManager.getKeyboardThemeLayoutOverrides(context, target).any {
                it.mode == SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY
            }
    }

}
