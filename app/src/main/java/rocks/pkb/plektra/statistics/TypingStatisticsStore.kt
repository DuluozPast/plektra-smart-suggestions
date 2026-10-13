package rocks.pkb.plektra.statistics

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import rocks.pkb.plektra.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TypingStatisticsSnapshot(val manualWords: Long = 0, val suggestedWords: Long = 0) {
    val totalWords: Long get() = manualWords + suggestedWords
}

/** Only aggregate counters are persisted. Writes are batched, never performed per letter. */
class TypingStatisticsStore internal constructor(private val preferences: SharedPreferences) {
    private val handler = Handler(Looper.getMainLooper())
    private val mutableSnapshot = MutableStateFlow(readSnapshot())
    val snapshot: StateFlow<TypingStatisticsSnapshot> = mutableSnapshot.asStateFlow()
    private var writeScheduled = false
    private val persist = Runnable { flush() }
    // Also reflect restored settings and changes made while this store is alive.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MANUAL_KEY || key == SUGGESTED_KEY) mutableSnapshot.value = readSnapshot()
    }

    init { preferences.registerOnSharedPreferenceChangeListener(listener) }

    @Synchronized
    fun record(manual: Int, suggested: Int) {
        require(manual >= 0 && suggested >= 0)
        if (manual == 0 && suggested == 0) return
        val current = mutableSnapshot.value
        mutableSnapshot.value = TypingStatisticsSnapshot(
            current.manualWords + manual, current.suggestedWords + suggested
        )
        if (!writeScheduled) {
            writeScheduled = true
            handler.postDelayed(persist, 1000L)
        }
    }

    @Synchronized
    fun flush() {
        if (!writeScheduled) return
        handler.removeCallbacks(persist)
        writeScheduled = false
        val current = mutableSnapshot.value
        preferences.edit().putLong(MANUAL_KEY, current.manualWords)
            .putLong(SUGGESTED_KEY, current.suggestedWords).apply()
    }

    private fun readSnapshot() = TypingStatisticsSnapshot(
        preferences.getLong(MANUAL_KEY, 0).coerceAtLeast(0),
        preferences.getLong(SUGGESTED_KEY, 0).coerceAtLeast(0)
    )

    companion object {
        internal const val MANUAL_KEY = "typing_statistics_manual_words"
        internal const val SUGGESTED_KEY = "typing_statistics_suggested_words"
        private var application: Context? = null
        private var instance: TypingStatisticsStore? = null

        @Synchronized
        fun get(context: Context): TypingStatisticsStore {
            val app = context.applicationContext
            if (instance == null || application !== app) {
                application = app
                instance = TypingStatisticsStore(SettingsManager.getPreferences(app))
            }
            return instance!!
        }
    }
}
