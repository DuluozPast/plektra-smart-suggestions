package rocks.pkb.plektra.core.suggestions

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores per-language word usage independently from dictionary frequencies.
 *
 * DictionaryEntry.frequency represents general corpus frequency, while this
 * store represents the individual user's usage of a word.
 *
 * Words are indexed with the same normalization used by SuggestionEngine so
 * spelling variants handled as equivalent by the engine share one usage count.
 */
interface WordUsageRepository {
    fun learn(
        locale: Locale,
        word: String,
        nowMs: Long = System.currentTimeMillis()
    )

    fun getUsage(locale: Locale, word: String): WordUsageStore.Usage?

    fun getExactUsage(locale: Locale, word: String): WordUsageStore.Usage?
    fun delete(locale: Locale, word: String): Int
    fun prune(nowMs: Long = System.currentTimeMillis(), staleAfterMs: Long, weakCountThreshold: Int, maxEntriesPerLocale: Int): Int

    fun clearAll()
}

class WordUsageStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
), WordUsageRepository {

    private val usageCache = ConcurrentHashMap<String, Usage>()
    private val exactUsageCache = ConcurrentHashMap<String, Usage>()
    private val usageMissCache = ConcurrentHashMap.newKeySet<String>()
    private val exactUsageMissCache = ConcurrentHashMap.newKeySet<String>()
    private val maxMissCacheEntries = 4096

    private fun rememberMiss(cache: MutableSet<String>, key: String) {
        if (cache.size >= maxMissCacheEntries) {
            cache.clear()
        }
        cache.add(key)
    }
    data class Usage(
        val word: String,
        val count: Int,
        val lastUsed: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_WORD_USAGE (
                $COL_LOCALE TEXT NOT NULL,
                $COL_NORMALIZED_WORD TEXT NOT NULL,
                $COL_WORD TEXT NOT NULL,
                $COL_COUNT INTEGER NOT NULL,
                $COL_LAST_USED INTEGER NOT NULL,
                PRIMARY KEY ($COL_LOCALE, $COL_NORMALIZED_WORD)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
    CREATE TABLE $TABLE_EXACT_WORD_USAGE (
        $COL_LOCALE TEXT NOT NULL,
        $COL_EXACT_WORD TEXT NOT NULL,
        $COL_WORD TEXT NOT NULL,
        $COL_COUNT INTEGER NOT NULL,
        $COL_LAST_USED INTEGER NOT NULL,
        PRIMARY KEY ($COL_LOCALE, $COL_EXACT_WORD)
    )
    """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 4) {
            db.execSQL(
                """
            CREATE TABLE IF NOT EXISTS $TABLE_EXACT_WORD_USAGE (
                $COL_LOCALE TEXT NOT NULL,
                $COL_EXACT_WORD TEXT NOT NULL,
                $COL_WORD TEXT NOT NULL,
                $COL_COUNT INTEGER NOT NULL,
                $COL_LAST_USED INTEGER NOT NULL,
                PRIMARY KEY ($COL_LOCALE, $COL_EXACT_WORD)
            )
            """.trimIndent()
            )
        }
    }
    override fun learn(
        locale: Locale,
        word: String,
        nowMs: Long
    ) {
        if (word.isBlank()) return

        val localeKey = locale.language.lowercase(Locale.ROOT)
        val normalizedWord = normalize(word, locale)
        if (normalizedWord.isBlank()) return

        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                TABLE_WORD_USAGE,
                null,
                ContentValues().apply {
                    put(COL_LOCALE, localeKey)
                    put(COL_NORMALIZED_WORD, normalizedWord)
                    put(COL_WORD, word)
                    put(COL_COUNT, 0)
                    put(COL_LAST_USED, nowMs)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )

            db.execSQL(
                """
            UPDATE $TABLE_WORD_USAGE
            SET $COL_COUNT = $COL_COUNT + 1,
                $COL_LAST_USED = ?,
                $COL_WORD = ?
            WHERE $COL_LOCALE = ?
                AND $COL_NORMALIZED_WORD = ?
            """.trimIndent(),
                arrayOf(nowMs, word, localeKey, normalizedWord)
            )

            val exactWord = exactKey(word, locale)

            db.insertWithOnConflict(
                TABLE_EXACT_WORD_USAGE,
                null,
                ContentValues().apply {
                    put(COL_LOCALE, localeKey)
                    put(COL_EXACT_WORD, exactWord)
                    put(COL_WORD, word)
                    put(COL_COUNT, 0)
                    put(COL_LAST_USED, nowMs)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )

            db.execSQL(
                """
    UPDATE $TABLE_EXACT_WORD_USAGE
    SET $COL_COUNT = $COL_COUNT + 1,
        $COL_LAST_USED = ?,
        $COL_WORD = ?
    WHERE $COL_LOCALE = ?
        AND $COL_EXACT_WORD = ?
    """.trimIndent(),
                arrayOf(nowMs, word, localeKey, exactWord)
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // Keep the in-memory cache synchronized with the persisted usage count.
        val key = cacheKey(locale, word)
        usageMissCache.remove(key)
        val persistedUsage = readableDatabase.query(
            TABLE_WORD_USAGE,
            arrayOf(COL_WORD, COL_COUNT, COL_LAST_USED),
            "$COL_LOCALE = ? AND $COL_NORMALIZED_WORD = ?",
            arrayOf(localeKey, normalizedWord),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                rememberMiss(exactUsageMissCache, key)
                null
            } else {
                Usage(
                    word = cursor.getString(cursor.getColumnIndexOrThrow(COL_WORD)),
                    count = cursor.getInt(cursor.getColumnIndexOrThrow(COL_COUNT)),
                    lastUsed = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_USED))
                )
            }
        }

        if (persistedUsage != null) {
            usageCache[key] = persistedUsage
        }
        exactUsageCache.remove(exactCacheKey(locale, word))
        usageMissCache.remove(cacheKey(locale, word))
        exactUsageMissCache.remove(exactCacheKey(locale, word))
    }

        override fun getUsage(
            locale: Locale,
            word: String
        ): Usage? {
            if (word.isBlank()) return null

            val localeKey = locale.language.lowercase(Locale.ROOT)
            val normalizedWord = normalize(word, locale)
            if (normalizedWord.isBlank()) return null

            val key = cacheKey(locale, word)

            usageCache[key]?.let { cached ->
                return cached
            }

            val cursor = readableDatabase.query(
                TABLE_WORD_USAGE,
                arrayOf(COL_WORD, COL_COUNT, COL_LAST_USED),
                "$COL_LOCALE = ? AND $COL_NORMALIZED_WORD = ?",
                arrayOf(localeKey, normalizedWord),
                null,
                null,
                null,
                "1"
            )

            cursor.use {
                if (!it.moveToFirst()) return null

                val usage = Usage(
                    word = it.getString(it.getColumnIndexOrThrow(COL_WORD)),
                    count = it.getInt(it.getColumnIndexOrThrow(COL_COUNT)),
                    lastUsed = it.getLong(it.getColumnIndexOrThrow(COL_LAST_USED))
                )

                usageCache[key] = usage
                return usage
            }
        }

    override fun getExactUsage(
        locale: Locale,
        word: String
    ): Usage? {
        if (word.isBlank()) return null

        val localeKey = locale.language.lowercase(Locale.ROOT)
        val exactWord = exactKey(word, locale)
        val key = exactCacheKey(locale, word)

        exactUsageCache[key]?.let { cached ->
            return cached
        }

        if (exactUsageMissCache.contains(key)) {
            return null
        }

        readableDatabase.query(
            TABLE_EXACT_WORD_USAGE,
            arrayOf(COL_WORD, COL_COUNT, COL_LAST_USED),
            "$COL_LOCALE = ? AND $COL_EXACT_WORD = ?",
            arrayOf(localeKey, exactWord),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null

            val usage = Usage(
                word = cursor.getString(cursor.getColumnIndexOrThrow(COL_WORD)),
                count = cursor.getInt(cursor.getColumnIndexOrThrow(COL_COUNT)),
                lastUsed = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_USED))
            )

            exactUsageCache[key] = usage
            return usage
        }
    }

    override fun delete(locale: Locale, word: String): Int {
        if (word.isBlank()) return 0

        val localeKey = locale.language.lowercase(Locale.ROOT)
        val normalizedWord = normalize(word, locale)
        if (normalizedWord.isBlank()) return 0

        val exactWord = exactKey(word, locale)
        val db = writableDatabase

        val deleted: Int

        db.beginTransaction()
        try {
            deleted = db.delete(
                TABLE_WORD_USAGE,
                "$COL_LOCALE = ? AND $COL_NORMALIZED_WORD = ?",
                arrayOf(localeKey, normalizedWord)
            )

            db.delete(
                TABLE_EXACT_WORD_USAGE,
                "$COL_LOCALE = ? AND $COL_EXACT_WORD = ?",
                arrayOf(localeKey, exactWord)
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        exactUsageCache.remove(exactCacheKey(locale, word))
        usageMissCache.remove(cacheKey(locale, word))
        exactUsageMissCache.remove(exactCacheKey(locale, word))

        if (deleted > 0) {
            usageCache.remove(cacheKey(locale, word))
        }

        return deleted
    }

    override fun prune(
        nowMs: Long,
        staleAfterMs: Long,
        weakCountThreshold: Int,
        maxEntriesPerLocale: Int
    ): Int {
        require(staleAfterMs >= 0L)
        require(weakCountThreshold >= 0)
        require(maxEntriesPerLocale > 0)

        val cutoff = nowMs - staleAfterMs
        val db = writableDatabase

        /*
         * Collect the normalized entries to remove first.
         *
         * We deliberately keep the decision in terms of the normalized usage
         * table because that is the canonical personal-frequency store.
         */
        val entriesToDelete =
            linkedSetOf<Pair<String, String>>()

        // 1. Weak + stale entries.
        db.query(
            TABLE_WORD_USAGE,
            arrayOf(
                COL_LOCALE,
                COL_NORMALIZED_WORD
            ),
            "$COL_COUNT <= ? AND $COL_LAST_USED < ?",
            arrayOf(
                weakCountThreshold.toString(),
                cutoff.toString()
            ),
            null,
            null,
            null
        ).use { cursor ->
            val localeIndex =
                cursor.getColumnIndexOrThrow(COL_LOCALE)
            val wordIndex =
                cursor.getColumnIndexOrThrow(COL_NORMALIZED_WORD)

            while (cursor.moveToNext()) {
                entriesToDelete +=
                    cursor.getString(localeIndex) to
                            cursor.getString(wordIndex)
            }
        }

// 2. Safety cap per locale.
        val locales = mutableListOf<String>()

        db.rawQuery(
            "SELECT DISTINCT $COL_LOCALE FROM $TABLE_WORD_USAGE",
            null
        ).use { cursor ->
            val localeIndex =
                cursor.getColumnIndexOrThrow(COL_LOCALE)

            while (cursor.moveToNext()) {
                locales += cursor.getString(localeIndex)
            }
        }
        for (localeKey in locales) {
            val retainedEntries =
                mutableListOf<String>()

            db.query(
                TABLE_WORD_USAGE,
                arrayOf(COL_NORMALIZED_WORD),
                "$COL_LOCALE = ?",
                arrayOf(localeKey),
                null,
                null,
                "$COL_COUNT DESC, $COL_LAST_USED DESC, $COL_NORMALIZED_WORD ASC"
            ).use { cursor ->
                val wordIndex =
                    cursor.getColumnIndexOrThrow(COL_NORMALIZED_WORD)

                while (cursor.moveToNext()) {
                    val normalizedWord =
                        cursor.getString(wordIndex)

                    if (
                        (localeKey to normalizedWord) !in
                        entriesToDelete
                    ) {
                        retainedEntries += normalizedWord
                    }
                }
            }

            if (retainedEntries.size > maxEntriesPerLocale) {
                retainedEntries
                    .drop(maxEntriesPerLocale)
                    .forEach { normalizedWord ->
                        entriesToDelete +=
                            localeKey to normalizedWord
                    }
            }
        }

        if (entriesToDelete.isEmpty()) {
            return 0
        }

        var deletedCount = 0

        db.beginTransaction()
        try {
            /*
             * 3. Remove exact-form rows belonging to each normalized entry.
             *
             * exact_word cannot safely be compared with normalized_word in SQL:
             * normalizeForSuggestion() may collapse accents or other spelling
             * distinctions. Therefore normalize the stored surface word using
             * the same Kotlin normalization as the suggestion engine.
             */
            for ((localeKey, normalizedWord) in entriesToDelete) {
                val locale =
                    Locale.forLanguageTag(localeKey)

                val exactWordsToDelete =
                    mutableListOf<String>()

                db.query(
                    TABLE_EXACT_WORD_USAGE,
                    arrayOf(
                        COL_EXACT_WORD,
                        COL_WORD
                    ),
                    "$COL_LOCALE = ?",
                    arrayOf(localeKey),
                    null,
                    null,
                    null
                ).use { cursor ->
                    val exactWordIndex =
                        cursor.getColumnIndexOrThrow(COL_EXACT_WORD)
                    val wordIndex =
                        cursor.getColumnIndexOrThrow(COL_WORD)

                    while (cursor.moveToNext()) {
                        val surfaceWord =
                            cursor.getString(wordIndex)

                        if (
                            normalize(surfaceWord, locale) ==
                            normalizedWord
                        ) {
                            exactWordsToDelete +=
                                cursor.getString(exactWordIndex)
                        }
                    }
                }

                for (exactWord in exactWordsToDelete) {
                    db.delete(
                        TABLE_EXACT_WORD_USAGE,
                        "$COL_LOCALE = ? AND $COL_EXACT_WORD = ?",
                        arrayOf(localeKey, exactWord)
                    )
                }

                deletedCount += db.delete(
                    TABLE_WORD_USAGE,
                    "$COL_LOCALE = ? AND $COL_NORMALIZED_WORD = ?",
                    arrayOf(localeKey, normalizedWord)
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        /*
         * Some cached entries may now refer to deleted rows.
         * Maintenance is rare, so clearing these small caches is safer and
         * simpler than trying to invalidate individual aliases.
         */
        usageCache.clear()
        exactUsageCache.clear()
        usageMissCache.clear()
        exactUsageMissCache.clear()

        return deletedCount
    }

    override fun clearAll() {
        val db = writableDatabase

        db.beginTransaction()
        try {
            db.delete(TABLE_WORD_USAGE, null, null)
            db.delete(TABLE_EXACT_WORD_USAGE, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        usageCache.clear()
        exactUsageCache.clear()
        usageMissCache.clear()
        exactUsageMissCache.clear()
    }

    fun invalidateCache() {
        usageCache.clear()
        exactUsageCache.clear()
        usageMissCache.clear()
        exactUsageMissCache.clear()
    }

    private fun cacheKey(locale: Locale, word: String): String {
        val language = locale.language.lowercase(Locale.ROOT)
        val normalizedWord = normalize(word, locale)
        return "$language:$normalizedWord"
    }
    private fun exactCacheKey(locale: Locale, word: String): String {
        val language = locale.language.lowercase(Locale.ROOT)
        val exactWord = exactKey(word, locale)
        return "$language:$exactWord"
    }
    private fun exactKey(word: String, locale: Locale): String {
        return word.lowercase(locale)
    }

    private fun normalize(word: String, locale: Locale): String {
        return WordNormalization.normalizeForSuggestion(word, locale)
    }


    companion object {
        private const val DATABASE_NAME = "word_usage.db"
        private const val DATABASE_VERSION = 4

        private const val TABLE_WORD_USAGE = "word_usage"
        private const val TABLE_EXACT_WORD_USAGE = "exact_word_usage"

        private const val COL_LOCALE = "locale"
        private const val COL_NORMALIZED_WORD = "normalized_word"
        private const val COL_EXACT_WORD = "exact_word"
        private const val COL_WORD = "word"
        private const val COL_COUNT = "count"
        private const val COL_LAST_USED = "last_used"
    }
}