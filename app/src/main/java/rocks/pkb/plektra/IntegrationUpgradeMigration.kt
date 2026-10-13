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
        check(marker.edit().putBoolean("preferences_v1", true).commit())
        return result.appliedKeys.size
    }
}
