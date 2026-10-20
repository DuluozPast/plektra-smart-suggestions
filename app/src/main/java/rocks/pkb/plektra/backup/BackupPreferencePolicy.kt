package rocks.pkb.plektra.backup

internal object BackupPreferencePolicy {
    private const val PLEKTRA_PREFS = "plektra_prefs"

    // Installation-local aggregates must never be replaced by an imported archive.
    val localOnlyKeys = setOf(
        "plektra_prefs:typing_statistics_manual_words",
        "plektra_prefs:typing_statistics_suggested_words"
    )

    val runtimeDerivedKeys = setOf(
        "$PLEKTRA_PREFS:slim_mode_active",
        "$PLEKTRA_PREFS:software_keyboard_mode_runtime_override"
    )

    val targetDeviceDerivedKeys = setOf(
        "$PLEKTRA_PREFS:physical_keyboard_profile_override",
        "$PLEKTRA_PREFS:titan2_layout_enabled",
        "$PLEKTRA_PREFS:titan2_elite_rounded_corner_insets"
    )
}
