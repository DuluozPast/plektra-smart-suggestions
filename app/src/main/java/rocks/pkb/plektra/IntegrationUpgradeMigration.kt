package rocks.pkb.plektra

import android.content.Context
import rocks.pkb.plektra.backup.PreferenceSchemas
import rocks.pkb.plektra.backup.PreferenceValue
import rocks.pkb.plektra.backup.PreferencesBackupHelper
import rocks.pkb.plektra.backup.FileBackupHelper

/** One-time preference migration for the local Integration test variant. Learning files retain their names. */
object IntegrationUpgradeMigration {
    fun migrate(context: Context): Int {
        if (context.packageName != "it.palsoftware.pastiera.custom.multimotstest") return 0
        val marker = context.getSharedPreferences("integration_upgrade_migration", Context.MODE_PRIVATE)
        if (marker.getBoolean("preferences_v1", false)) return 0
        val legacy = context.getSharedPreferences("pastiera_prefs", Context.MODE_PRIVATE).all
        if (legacy.isEmpty()) return 0
        val existing = SettingsManager.getPreferences(context).all
        val values = legacy.mapNotNull { (key, value) -> PreferenceValue.fromAny(value)?.let { key to it } }.toMap()
        val normalized = PreferenceSchemas.normalizeForRestore(mapOf("pastiera_prefs" to values))
            .mapValues { (_, entries) -> entries.filterKeys { it !in existing } }
        val result = PreferencesBackupHelper.restorePreferences(context, normalized,
            hasRestoredTypingSoundPack = FileBackupHelper.hasValidTypingSoundPack(context.filesDir))
        // This is an in-place upgrade of the same installation, not an archive import.
        // Adopt its legacy local totals once, only where no current value exists.
        val localCounters = normalized["plektra_prefs"].orEmpty().filterKeys {
            "plektra_prefs:$it" in rocks.pkb.plektra.backup.BackupPreferencePolicy.localOnlyKeys
        }
        val editor = SettingsManager.getPreferences(context).edit()
        var migratedCounters = 0
        localCounters.forEach { (key, value) ->
            val coerced = value.coerceTo(rocks.pkb.plektra.backup.PreferenceValueType.LONG)
            val count = (coerced?.value as? Number)?.toLong()
            if (count != null) {
                editor.putLong(key, count.coerceAtLeast(0))
                migratedCounters++
            }
        }
        check(editor.commit())
        check(marker.edit().putBoolean("preferences_v1", true).commit())
        return result.appliedKeys.size + migratedCounters
    }
}
