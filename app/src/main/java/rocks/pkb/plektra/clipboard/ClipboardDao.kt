package rocks.pkb.plektra.clipboard

import android.content.ContentValues
import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * Data Access Object for clipboard history.
 * Provides cached access to clipboard entries stored in SQLite.
 */
class ClipboardDao private constructor(private val db: ClipboardDatabase) {

    interface Listener {
        fun onClipInserted(position: Int)
        fun onClipsRemoved(position: Int, count: Int)
        fun onClipMoved(oldPosition: Int, newPosition: Int)
    }

    var listener: Listener? = null

    // Track when we last cleaned old clips
    private var lastClearOldClips = 0L
    private var nextExpiry = 0L
    private var lastRetention: Pair<Long, Long>? = null

    // In-memory cache loaded at start
    private val cache = mutableListOf<ClipboardHistoryEntry>().apply {
        db.readableDatabase.query(
            TABLE,
            arrayOf(COLUMN_ID, COLUMN_TIMESTAMP, COLUMN_PINNED, COLUMN_TEXT, COLUMN_SENSITIVE),
            null,
            null,
            null,
            null,
            "$COLUMN_PINNED DESC, $COLUMN_TIMESTAMP DESC"
        ).use {
            while (it.moveToNext()) {
                add(ClipboardHistoryEntry(
                    it.getLong(0),
                    it.getLong(1),
                    it.getInt(2) != 0,
                    it.getString(3),
                    it.getInt(4) != 0
                ))
            }
        }
        sort()
    }

    /**
     * Add a new clipboard entry or update existing one with same text.
     * @param retentionMinutes Normal retention in minutes; zero keeps entries until deleted.
     * @param sensitiveRetentionMinutes Sensitive entries expire independently and cannot be pinned.
     */
    fun addClip(timestamp: Long, pinned: Boolean, text: String, retentionMinutes: Long = 0,
        sensitive: Boolean = false, sensitiveRetentionMinutes: Long = 30) {
        clearOldClips(now = true, retentionMinutes = retentionMinutes,
            sensitiveRetentionMinutes = sensitiveRetentionMinutes)
        if (sensitive && timestamp <= System.currentTimeMillis() - sensitiveRetentionMinutes.coerceIn(1, 120) * 60_000L) return

        nextExpiry = 0L
        val existingIndex = cache.indexOfFirst { it.text == text }
        if (existingIndex >= 0) {
            val entry = cache[existingIndex]
            entry.timeStamp = timestamp
            // Copying the same secret without metadata must not downgrade its protection.
            entry.isSensitive = entry.isSensitive || sensitive
            if (entry.isSensitive) entry.isPinned = false
            val cv = ContentValues().apply {
                put(COLUMN_TIMESTAMP, entry.timeStamp)
                put(COLUMN_SENSITIVE, entry.isSensitive)
                put(COLUMN_PINNED, entry.isPinned)
            }
            db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ?", arrayOf(entry.id.toString()))
            cache.sort()
            listener?.onClipMoved(existingIndex, cache.indexOf(entry))
            return
        }

        val cv = ContentValues().apply {
            put(COLUMN_TIMESTAMP, timestamp)
            put(COLUMN_PINNED, pinned && !sensitive)
            put(COLUMN_TEXT, text)
            put(COLUMN_SENSITIVE, sensitive)
        }
        val rowId = db.writableDatabase.insert(TABLE, null, cv)
        val entry = ClipboardHistoryEntry(rowId, timestamp, pinned && !sensitive, text, sensitive)
        cache.add(entry)
        cache.sort()
        listener?.onClipInserted(cache.indexOf(entry))
    }

    fun isPinned(index: Int) = cache.getOrNull(index)?.isPinned ?: false

    fun getAt(index: Int) = cache.getOrNull(index)

    fun get(id: Long) = cache.firstOrNull { it.id == id }

    fun count() = cache.size

    fun sort() = cache.sort()

    fun togglePinned(id: Long) {
        val entry = cache.firstOrNull { it.id == id } ?: return
        if (entry.isSensitive) return
        nextExpiry = 0L
        entry.isPinned = !entry.isPinned
        entry.timeStamp = System.currentTimeMillis()

        if (listener != null) {
            val oldPos = cache.indexOf(entry)
            cache.sort()
            val newPos = cache.indexOf(entry)
            listener?.onClipMoved(oldPos, newPos)
        } else {
            cache.sort()
        }

        val cv = ContentValues(2).apply {
            put(COLUMN_PINNED, entry.isPinned)
            put(COLUMN_TIMESTAMP, entry.timeStamp)
        }
        db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ${entry.id}", null)
    }

    fun deleteClipAt(index: Int) {
        val entry = cache.getOrNull(index) ?: return
        cache.removeAt(index)
        db.writableDatabase.delete(TABLE, "$COLUMN_ID = ${entry.id}", null)
    }

    /**
     * Remove old clipboard entries based on retention time setting.
     * @param now If true, force clear immediately; otherwise respect debounce
     * @param retentionMinutes Normal retention in minutes; zero keeps normal entries indefinitely.
     * @param sensitiveRetentionMinutes Sensitive retention, clamped to 1–120 minutes.
     */
    fun clearOldClips(now: Boolean = false, retentionMinutes: Long = 0,
        sensitiveRetentionMinutes: Long = 30) {
        if (!now && lastClearOldClips > SystemClock.elapsedRealtime() - 5_000) return
        lastClearOldClips = SystemClock.elapsedRealtime()
        val currentTime = System.currentTimeMillis()
        val retention = retentionMinutes to sensitiveRetentionMinutes
        if (lastRetention == retention && currentTime < nextExpiry) return
        lastRetention = retention
        val normalCutoff = if (retentionMinutes > 0) currentTime - retentionMinutes * 60_000L else Long.MIN_VALUE
        val sensitiveCutoff = currentTime - sensitiveRetentionMinutes.coerceIn(1, 120) * 60_000L
        val expired = cache.indices.filter { index ->
            val clip = cache[index]
            if (clip.isSensitive) clip.timeStamp <= sensitiveCutoff
            else !clip.isPinned && clip.timeStamp <= normalCutoff
        }
        // Each removal may be separated by a pinned or long-lived normal entry.
        expired.asReversed().forEach { index ->
            val entry = cache.removeAt(index)
            db.writableDatabase.delete(TABLE, "$COLUMN_ID = ?", arrayOf(entry.id.toString()))
        }
        nextExpiry = cache.minOfOrNull { clip ->
            when {
                clip.isSensitive -> clip.timeStamp + sensitiveRetentionMinutes.coerceIn(1, 120) * 60_000L
                !clip.isPinned && retentionMinutes > 0 -> clip.timeStamp + retentionMinutes * 60_000L
                else -> Long.MAX_VALUE
            }
        } ?: Long.MAX_VALUE
        expired.asReversed().forEach { listener?.onClipsRemoved(it, 1) }
    }

    fun clearNonPinned() {
        if (listener != null) {
            val indicesToRemove = mutableListOf<Int>()
            cache.forEachIndexed { idx, clip ->
                if (!clip.isPinned) indicesToRemove.add(idx)
            }
            if (indicesToRemove.isEmpty()) return

            cache.removeAll { !it.isPinned }
            listener?.onClipsRemoved(indicesToRemove[0], indicesToRemove.size)
        } else if (!cache.removeAll { !it.isPinned }) {
            return // Nothing to remove
        }

        db.writableDatabase.delete(TABLE, "$COLUMN_PINNED = 0", null)
    }

    fun clear() {
        if (count() == 0) return
        val count = count()
        cache.clear()
        listener?.onClipsRemoved(0, count)
        db.writableDatabase.delete(TABLE, null, null)
    }

    companion object {
        private const val TAG = "ClipboardDao"

        const val TABLE = "CLIPBOARD"
        private const val COLUMN_ID = "ID"
        private const val COLUMN_TIMESTAMP = "TIMESTAMP"
        private const val COLUMN_PINNED = "PINNED"
        private const val COLUMN_TEXT = "TEXT"
        private const val COLUMN_SENSITIVE = "SENSITIVE"

        const val CREATE_TABLE = """
            CREATE TABLE $TABLE (
                $COLUMN_ID INTEGER PRIMARY KEY,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_PINNED TINYINT NOT NULL,
                $COLUMN_TEXT TEXT,
                $COLUMN_SENSITIVE TINYINT NOT NULL DEFAULT 0
            )
        """

        @Volatile
        private var instance: ClipboardDao? = null

        /**
         * Get the singleton instance, or create it if needed.
         * Returns null if instance can't be created (e.g. device locked).
         */
        fun getInstance(context: Context): ClipboardDao? {
            return instance ?: synchronized(this) {
                instance ?: try {
                    ClipboardDao(ClipboardDatabase.getInstance(context)).also {
                        instance = it
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to create ClipboardDao", e)
                    null
                }
            }
        }
    }
}
