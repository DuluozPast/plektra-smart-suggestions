package rocks.pkb.plektra.core.suggestions

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

interface CorrectionUsageRepository {
    fun learn(
        locale: Locale,
        original: String,
        correction: String,
        nowMs: Long = System.currentTimeMillis()
    )

    fun getUsage(
        locale: Locale,
        original: String,
        correction: String
    ): CorrectionUsageStore.Usage?

    fun getAllUsages(): List<CorrectionUsageStore.Usage>

    fun restoreUsage(
        localeTag: String,
        original: String,
        correction: String,
        count: Int,
        lastUsed: Long
    )

    fun invalidateCache()

    fun clearAll()
}

class CorrectionUsageStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
), CorrectionUsageRepository {

    private val usageCache = ConcurrentHashMap<String, Usage>()

    @Volatile
    private var cacheFullyLoaded = false

    data class Usage(
        val localeTag: String,
        val original: String,
        val correction: String,
        val count: Int,
        val lastUsed: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_CORRECTION_USAGE (
                $COL_LOCALE TEXT NOT NULL,
                $COL_NORMALIZED_ORIGINAL TEXT NOT NULL,
                $COL_NORMALIZED_CORRECTION TEXT NOT NULL,
                $COL_ORIGINAL TEXT NOT NULL,
                $COL_CORRECTION TEXT NOT NULL,
                $COL_COUNT INTEGER NOT NULL,
                $COL_LAST_USED INTEGER NOT NULL,
                PRIMARY KEY (
                    $COL_LOCALE,
                    $COL_NORMALIZED_ORIGINAL,
                    $COL_NORMALIZED_CORRECTION
                )
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < DATABASE_VERSION) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CORRECTION_USAGE")
            onCreate(db)
        }
    }

    override fun learn(
        locale: Locale,
        original: String,
        correction: String,
        nowMs: Long
    ) {
        if (original.isBlank() || correction.isBlank()) return

        val localeKey = locale.language.lowercase(Locale.ROOT)
        val normalizedOriginal = normalize(original, locale)
        val normalizedCorrection = normalize(correction, locale)

        if (normalizedOriginal.isBlank() || normalizedCorrection.isBlank()) return

        // Selecting the same word is usage, not a correction.
        if (normalizedOriginal == normalizedCorrection) return

        val db = writableDatabase
        db.beginTransaction()

        try {
            db.insertWithOnConflict(
                TABLE_CORRECTION_USAGE,
                null,
                ContentValues().apply {
                    put(COL_LOCALE, localeKey)
                    put(COL_NORMALIZED_ORIGINAL, normalizedOriginal)
                    put(COL_NORMALIZED_CORRECTION, normalizedCorrection)
                    put(COL_ORIGINAL, original)
                    put(COL_CORRECTION, correction)
                    put(COL_COUNT, 0)
                    put(COL_LAST_USED, nowMs)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )

            db.execSQL(
                """
                UPDATE $TABLE_CORRECTION_USAGE
                SET $COL_COUNT = $COL_COUNT + 1,
                    $COL_LAST_USED = ?,
                    $COL_ORIGINAL = ?,
                    $COL_CORRECTION = ?
                WHERE $COL_LOCALE = ?
                    AND $COL_NORMALIZED_ORIGINAL = ?
                    AND $COL_NORMALIZED_CORRECTION = ?
                """.trimIndent(),
                arrayOf(
                    nowMs,
                    original,
                    correction,
                    localeKey,
                    normalizedOriginal,
                    normalizedCorrection
                )
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val key = cacheKey(locale, original, correction)

        val persistedUsage = readableDatabase.query(
            TABLE_CORRECTION_USAGE,
            arrayOf(
                COL_ORIGINAL,
                COL_CORRECTION,
                COL_COUNT,
                COL_LAST_USED
            ),
            """
            $COL_LOCALE = ?
            AND $COL_NORMALIZED_ORIGINAL = ?
            AND $COL_NORMALIZED_CORRECTION = ?
            """.trimIndent(),
            arrayOf(
                localeKey,
                normalizedOriginal,
                normalizedCorrection
            ),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                Usage(
                    localeTag = localeKey,
                    original = cursor.getString(
                        cursor.getColumnIndexOrThrow(COL_ORIGINAL)
                    ),
                    correction = cursor.getString(
                        cursor.getColumnIndexOrThrow(COL_CORRECTION)
                    ),
                    count = cursor.getInt(
                        cursor.getColumnIndexOrThrow(COL_COUNT)
                    ),
                    lastUsed = cursor.getLong(
                        cursor.getColumnIndexOrThrow(COL_LAST_USED)
                    )
                )
            }
        }

        if (persistedUsage != null) {
            usageCache[key] = persistedUsage
        }
    }

    @Synchronized
    private fun ensureCacheFullyLoaded() {
        if (cacheFullyLoaded) return

        readableDatabase.query(
            TABLE_CORRECTION_USAGE,
            arrayOf(
                COL_LOCALE,
                COL_NORMALIZED_ORIGINAL,
                COL_NORMALIZED_CORRECTION,
                COL_ORIGINAL,
                COL_CORRECTION,
                COL_COUNT,
                COL_LAST_USED
            ),
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val localeKey = cursor.getString(
                    cursor.getColumnIndexOrThrow(COL_LOCALE)
                )

                val normalizedOriginal = cursor.getString(
                    cursor.getColumnIndexOrThrow(COL_NORMALIZED_ORIGINAL)
                )

                val normalizedCorrection = cursor.getString(
                    cursor.getColumnIndexOrThrow(COL_NORMALIZED_CORRECTION)
                )

                val usage = Usage(
                    localeTag = localeKey,
                    original = cursor.getString(
                        cursor.getColumnIndexOrThrow(COL_ORIGINAL)
                    ),
                    correction = cursor.getString(
                        cursor.getColumnIndexOrThrow(COL_CORRECTION)
                    ),
                    count = cursor.getInt(
                        cursor.getColumnIndexOrThrow(COL_COUNT)
                    ),
                    lastUsed = cursor.getLong(
                        cursor.getColumnIndexOrThrow(COL_LAST_USED)
                    )
                )

                val key =
                    "$localeKey:$normalizedOriginal:$normalizedCorrection"

                usageCache[key] = usage
            }
        }

        cacheFullyLoaded = true
    }

    override fun getUsage(
        locale: Locale,
        original: String,
        correction: String
    ): Usage? {
        if (original.isBlank() || correction.isBlank()) return null

        val normalizedOriginal = normalize(original, locale)
        val normalizedCorrection = normalize(correction, locale)

        if (normalizedOriginal.isBlank() || normalizedCorrection.isBlank()) {
            return null
        }

        ensureCacheFullyLoaded()

        val key = cacheKey(
            locale = locale,
            original = original,
            correction = correction
        )

        return usageCache[key]
    }

    override fun getAllUsages(): List<Usage> {
        ensureCacheFullyLoaded()

        return usageCache.values
            .distinctBy { usage ->
                "${usage.localeTag}:${usage.original}:${usage.correction}"
            }
            .sortedWith(
                compareBy<Usage>(
                    { it.localeTag },
                    { it.original },
                    { it.correction }
                )
            )
    }

    @Synchronized
    override fun invalidateCache() {
        usageCache.clear()
        cacheFullyLoaded = false
    }

    override fun clearAll() {
        writableDatabase.delete(TABLE_CORRECTION_USAGE, null, null)
        usageCache.clear()
        cacheFullyLoaded = true
    }

    override fun restoreUsage(
        localeTag: String,
        original: String,
        correction: String,
        count: Int,
        lastUsed: Long
    ) {
        if (localeTag.isBlank()) return
        if (original.isBlank() || correction.isBlank()) return
        if (count <= 0) return
        if (lastUsed < 0L) return

        val locale = Locale.forLanguageTag(localeTag)
        val localeKey = locale.language.lowercase(Locale.ROOT)

        if (localeKey.isBlank()) return

        val normalizedOriginal = normalize(original, locale)
        val normalizedCorrection = normalize(correction, locale)

        if (normalizedOriginal.isBlank() || normalizedCorrection.isBlank()) return
        if (normalizedOriginal == normalizedCorrection) return

        writableDatabase.insertWithOnConflict(
            TABLE_CORRECTION_USAGE,
            null,
            ContentValues().apply {
                put(COL_LOCALE, localeKey)
                put(COL_NORMALIZED_ORIGINAL, normalizedOriginal)
                put(COL_NORMALIZED_CORRECTION, normalizedCorrection)
                put(COL_ORIGINAL, original)
                put(COL_CORRECTION, correction)
                put(COL_COUNT, count)
                put(COL_LAST_USED, lastUsed)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )

        val key = cacheKey(
            locale = locale,
            original = original,
            correction = correction
        )

        usageCache[key] = Usage(
            localeTag = localeKey,
            original = original,
            correction = correction,
            count = count,
            lastUsed = lastUsed
        )
    }

    private fun cacheKey(
        locale: Locale,
        original: String,
        correction: String
    ): String {
        val language = locale.language.lowercase(Locale.ROOT)
        val normalizedOriginal = normalize(original, locale)
        val normalizedCorrection = normalize(correction, locale)

        return "$language:$normalizedOriginal:$normalizedCorrection"
    }

    private fun normalize(word: String, locale: Locale): String {
        return word
            .replace('\u2019', '\'')
            .replace('\u2018', '\'')
            .replace('\u02BC', '\'')
            .lowercase(locale)
            .filter { it.isLetter() || it == '\'' }
    }

    companion object {
        private const val DATABASE_NAME = "correction_usage.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_CORRECTION_USAGE = "correction_usage"

        private const val COL_LOCALE = "locale"
        private const val COL_NORMALIZED_ORIGINAL = "normalized_original"
        private const val COL_NORMALIZED_CORRECTION = "normalized_correction"
        private const val COL_ORIGINAL = "original"
        private const val COL_CORRECTION = "correction"
        private const val COL_COUNT = "count"
        private const val COL_LAST_USED = "last_used"
    }
}