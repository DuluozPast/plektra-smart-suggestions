package rocks.pkb.plektra

/**
 * App-internal broadcast actions shared across UI, IME and restore flows.
 * Keep action strings stable for backwards compatibility with existing receivers/senders.
 */
object AppBroadcastActions {
    const val USER_DICTIONARY_UPDATED =
        "rocks.pkb.plektra.ACTION_USER_DICTIONARY_UPDATED"
    const val CORRECTION_USAGE_UPDATED =
        "rocks.pkb.plektra.CORRECTION_USAGE_UPDATED"

    const val LEARNING_RESTORED =
        "rocks.pkb.plektra.action.LEARNING_RESTORED"

    /**
     * Sent when the active base dictionary changes, for example after
     * installing or removing an Extended dictionary.
     */
    const val BASE_DICTIONARY_UPDATED =
        "rocks.pkb.plektra.ACTION_BASE_DICTIONARY_UPDATED"
}
