package rocks.pkb.plektra.core.suggestions

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

/**
 * Stores per-language usage of short word sequences.
 *
 * A sequence contains between 2 and 5 words. The stored count represents
 * how often the individual user has produced that sequence.
 *
 * This data is intentionally kept separate from corpus frequencies and
 * individual WordUsageStore statistics.
 */
interface PhraseUsageRepository {

    fun learn(
        locale: Locale,
        words: List<String>,
        nowMs: Long = System.currentTimeMillis()
    )

    fun getUsage(
        locale: Locale,
        words: List<String>
    ): PhraseUsageStore.Usage?

    fun getPromotionCandidates(
        locale: Locale,
        minimumCount: Int,
        limit: Int = 50
    ): List<PhraseUsageStore.Usage>

    fun predictNext(
        locale: Locale,
        contextWords: List<String>,
        limit: Int = 3
    ): List<PhraseUsageStore.Prediction>

    fun prune(
        nowMs: Long = System.currentTimeMillis(),
        staleAfterMs: Long,
        weakCountThreshold: Int,
        maxEntriesPerLocale: Int
    ): Int

    fun clearAll()
}

class PhraseUsageStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
), PhraseUsageRepository {

    data class Usage(
        val words: List<String>,
        val count: Int,
        val lastUsed: Long
    )

    data class Prediction(
        val word: String,
        val words: List<String> = listOf(word),
        val count: Int,
        val lastUsed: Long,
        val contextLength: Int
    ) {
        val continuation: String
            get() = words.joinToString(" ")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
        CREATE TABLE $TABLE_PHRASE_USAGE (
            $COL_LOCALE TEXT NOT NULL,
            $COL_NORMALIZED_SEQUENCE TEXT NOT NULL,
            $COL_SEQUENCE TEXT NOT NULL,
            $COL_COUNT INTEGER NOT NULL,
            $COL_LAST_USED INTEGER NOT NULL,
            PRIMARY KEY ($COL_LOCALE, $COL_NORMALIZED_SEQUENCE)
        )
        """.trimIndent()
        )

        db.execSQL(
            """
        CREATE TABLE $TABLE_PHRASE_NEXT_WORD (
            $COL_LOCALE TEXT NOT NULL,
            $COL_CONTEXT TEXT NOT NULL,
            $COL_NEXT_WORD_NORMALIZED TEXT NOT NULL,
            $COL_NEXT_WORD TEXT NOT NULL,
            $COL_COUNT INTEGER NOT NULL,
            $COL_LAST_USED INTEGER NOT NULL,
            PRIMARY KEY (
                $COL_LOCALE,
                $COL_CONTEXT,
                $COL_NEXT_WORD_NORMALIZED
            )
        )
        """.trimIndent()
        )

        db.execSQL(
            """
        CREATE INDEX idx_phrase_next_word_lookup
        ON $TABLE_PHRASE_NEXT_WORD (
            $COL_LOCALE,
            $COL_CONTEXT,
            $COL_COUNT DESC,
            $COL_LAST_USED DESC
        )
        """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2) {
            db.execSQL(
                """
            CREATE TABLE IF NOT EXISTS $TABLE_PHRASE_NEXT_WORD (
                $COL_LOCALE TEXT NOT NULL,
                $COL_CONTEXT TEXT NOT NULL,
                $COL_NEXT_WORD_NORMALIZED TEXT NOT NULL,
                $COL_NEXT_WORD TEXT NOT NULL,
                $COL_COUNT INTEGER NOT NULL,
                $COL_LAST_USED INTEGER NOT NULL,
                PRIMARY KEY (
                    $COL_LOCALE,
                    $COL_CONTEXT,
                    $COL_NEXT_WORD_NORMALIZED
                )
            )
            """.trimIndent()
            )

            db.execSQL(
                """
            CREATE INDEX IF NOT EXISTS idx_phrase_next_word_lookup
            ON $TABLE_PHRASE_NEXT_WORD (
                $COL_LOCALE,
                $COL_CONTEXT,
                $COL_COUNT DESC,
                $COL_LAST_USED DESC
            )
            """.trimIndent()
            )

            rebuildNextWordIndex(db)
        }
    }

    private fun rebuildNextWordIndex(db: SQLiteDatabase) {
        db.delete(TABLE_PHRASE_NEXT_WORD, null, null)

        db.query(
            TABLE_PHRASE_USAGE,
            arrayOf(
                COL_LOCALE,
                COL_SEQUENCE,
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
                val localeKey =
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_LOCALE))

                val words =
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_SEQUENCE))
                        .split(SEPARATOR)
                        .filter { it.isNotBlank() }

                val count =
                    cursor.getInt(cursor.getColumnIndexOrThrow(COL_COUNT))

                val lastUsed =
                    cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_USED))

                if (words.size < MIN_SEQUENCE_LENGTH) continue

                val locale = Locale.forLanguageTag(localeKey)

                val normalizedWords = words.map {
                    WordNormalization.normalizeForSuggestion(it, locale)
                }

                if (normalizedWords.any { it.isBlank() }) continue

                for (nextIndex in 1 until words.size) {
                    val maxContextLength =
                        minOf(nextIndex, MAX_SEQUENCE_LENGTH - 1)

                    for (contextLength in 1..maxContextLength) {
                        val contextStart = nextIndex - contextLength

                        val context = normalizedWords
                            .subList(contextStart, nextIndex)
                            .joinToString(SEPARATOR)

                        val nextWord = words[nextIndex]
                        val nextWordNormalized = normalizedWords[nextIndex]

                        upsertNextWordIndex(
                            db = db,
                            localeKey = localeKey,
                            context = context,
                            nextWordNormalized = nextWordNormalized,
                            nextWord = nextWord,
                            countIncrement = count,
                            lastUsed = lastUsed
                        )
                    }
                }
            }
        }
    }

    private fun upsertNextWordIndex(
        db: SQLiteDatabase,
        localeKey: String,
        context: String,
        nextWordNormalized: String,
        nextWord: String,
        countIncrement: Int,
        lastUsed: Long
    ) {
        db.insertWithOnConflict(
            TABLE_PHRASE_NEXT_WORD,
            null,
            ContentValues().apply {
                put(COL_LOCALE, localeKey)
                put(COL_CONTEXT, context)
                put(COL_NEXT_WORD_NORMALIZED, nextWordNormalized)
                put(COL_NEXT_WORD, nextWord)
                put(COL_COUNT, 0)
                put(COL_LAST_USED, lastUsed)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )

        db.execSQL(
            """
        UPDATE $TABLE_PHRASE_NEXT_WORD
        SET $COL_COUNT = $COL_COUNT + ?,
            $COL_LAST_USED = MAX($COL_LAST_USED, ?),
            $COL_NEXT_WORD = ?
        WHERE $COL_LOCALE = ?
          AND $COL_CONTEXT = ?
          AND $COL_NEXT_WORD_NORMALIZED = ?
        """.trimIndent(),
            arrayOf(
                countIncrement,
                lastUsed,
                nextWord,
                localeKey,
                context,
                nextWordNormalized
            )
        )
    }

    override fun learn(
        locale: Locale,
        words: List<String>,
        nowMs: Long
    ) {
        val cleanWords = words
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (cleanWords.size !in MIN_SEQUENCE_LENGTH..MAX_SEQUENCE_LENGTH) return

        val normalizedWords = cleanWords.map {
            WordNormalization.normalizeForSuggestion(it, locale)
        }

        if (normalizedWords.any { it.isBlank() }) return

        if (
            normalizedWords.zipWithNext().any { (previous, current) ->
                previous == current
            }
        ) {
            return
        }

        val localeKey = locale.language.lowercase(Locale.ROOT)
        val sequence = cleanWords.joinToString(SEPARATOR)
        val normalizedSequence = normalizedWords.joinToString(SEPARATOR)

        val db = writableDatabase
        db.beginTransaction()

        try {
            db.insertWithOnConflict(
                TABLE_PHRASE_USAGE,
                null,
                ContentValues().apply {
                    put(COL_LOCALE, localeKey)
                    put(COL_NORMALIZED_SEQUENCE, normalizedSequence)
                    put(COL_SEQUENCE, sequence)
                    put(COL_COUNT, 0)
                    put(COL_LAST_USED, nowMs)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )

            db.execSQL(
                """
                UPDATE $TABLE_PHRASE_USAGE
                SET $COL_COUNT = $COL_COUNT + 1,
                    $COL_LAST_USED = ?,
                    $COL_SEQUENCE = ?
                WHERE $COL_LOCALE = ?
                    AND $COL_NORMALIZED_SEQUENCE = ?
                """.trimIndent(),
                arrayOf(
                    nowMs,
                    sequence,
                    localeKey,
                    normalizedSequence
                )
            )

            // Keep the fast context -> next-word index in sync.
            val nextWord = cleanWords.last()
            val nextWordNormalized = normalizedWords.last()

            for (contextLength in 1 until cleanWords.size) {
                val context = normalizedWords
                    .takeLast(contextLength + 1)
                    .dropLast(1)
                    .joinToString(SEPARATOR)

                upsertNextWordIndex(
                    db = db,
                    localeKey = localeKey,
                    context = context,
                    nextWordNormalized = nextWordNormalized,
                    nextWord = nextWord,
                    countIncrement = 1,
                    lastUsed = nowMs
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun getUsage(
        locale: Locale,
        words: List<String>
    ): Usage? {
        val cleanWords = words
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (cleanWords.size !in MIN_SEQUENCE_LENGTH..MAX_SEQUENCE_LENGTH) return null

        val normalizedWords = cleanWords.map {
            WordNormalization.normalizeForSuggestion(it, locale)
        }

        if (normalizedWords.any { it.isBlank() }) return null

        val localeKey = locale.language.lowercase(Locale.ROOT)
        val normalizedSequence = normalizedWords.joinToString(SEPARATOR)

        return readableDatabase.query(
            TABLE_PHRASE_USAGE,
            arrayOf(
                COL_SEQUENCE,
                COL_COUNT,
                COL_LAST_USED
            ),
            "$COL_LOCALE = ? AND $COL_NORMALIZED_SEQUENCE = ?",
            arrayOf(localeKey, normalizedSequence),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                Usage(
                    words = cursor
                        .getString(cursor.getColumnIndexOrThrow(COL_SEQUENCE))
                        .split(SEPARATOR),
                    count = cursor.getInt(
                        cursor.getColumnIndexOrThrow(COL_COUNT)
                    ),
                    lastUsed = cursor.getLong(
                        cursor.getColumnIndexOrThrow(COL_LAST_USED)
                    )
                )
            }
        }
    }

    override fun getPromotionCandidates(
        locale: Locale,
        minimumCount: Int,
        limit: Int
    ): List<Usage> {
        if (minimumCount <= 0 || limit <= 0) return emptyList()

        val localeKey = locale.language.lowercase(Locale.ROOT)

        return readableDatabase.query(
            TABLE_PHRASE_USAGE,
            arrayOf(
                COL_SEQUENCE,
                COL_COUNT,
                COL_LAST_USED
            ),
            "$COL_LOCALE = ? AND $COL_COUNT >= ?",
            arrayOf(
                localeKey,
                minimumCount.toString()
            ),
            null,
            null,
            "$COL_LAST_USED DESC, $COL_COUNT DESC",
            limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val words = cursor
                        .getString(cursor.getColumnIndexOrThrow(COL_SEQUENCE))
                        .split(SEPARATOR)
                        .filter { it.isNotBlank() }

                    if (words.size !in MIN_SEQUENCE_LENGTH..MAX_SEQUENCE_LENGTH) {
                        continue
                    }

                    add(
                        Usage(
                            words = words,
                            count = cursor.getInt(
                                cursor.getColumnIndexOrThrow(COL_COUNT)
                            ),
                            lastUsed = cursor.getLong(
                                cursor.getColumnIndexOrThrow(COL_LAST_USED)
                            )
                        )
                    )
                }
            }
        }
    }
    override fun predictNext(
        locale: Locale,
        contextWords: List<String>,
        limit: Int
    ): List<Prediction> {
        if (limit <= 0) return emptyList()

        val cleanContext = contextWords
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .takeLast(MAX_SEQUENCE_LENGTH - 1)

        if (cleanContext.isEmpty()) return emptyList()

        val normalizedContext = cleanContext.map {
            WordNormalization.normalizeForSuggestion(it, locale)
        }

        if (normalizedContext.any { it.isBlank() }) return emptyList()

        val localeKey = locale.language.lowercase(Locale.ROOT)
        val predictions = LinkedHashMap<String, Prediction>()

        for (contextLength in normalizedContext.size downTo 1) {
            val context = normalizedContext
                .takeLast(contextLength)
                .joinToString(SEPARATOR)

            readableDatabase.query(
                TABLE_PHRASE_NEXT_WORD,
                arrayOf(
                    COL_NEXT_WORD,
                    COL_NEXT_WORD_NORMALIZED,
                    COL_COUNT,
                    COL_LAST_USED
                ),
                "$COL_LOCALE = ? AND $COL_CONTEXT = ?",
                arrayOf(localeKey, context),
                null,
                null,
                "$COL_COUNT DESC, $COL_LAST_USED DESC",
                limit.toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val word = cursor.getString(
                        cursor.getColumnIndexOrThrow(COL_NEXT_WORD)
                    )

                    val normalizedWord = cursor.getString(
                        cursor.getColumnIndexOrThrow(COL_NEXT_WORD_NORMALIZED)
                    )

                    if (normalizedWord.isBlank()) continue
                    if (predictions.containsKey(normalizedWord)) continue

                    predictions[normalizedWord] = Prediction(
                        word = word,
                        words = listOf(word),
                        count = cursor.getInt(
                            cursor.getColumnIndexOrThrow(COL_COUNT)
                        ),
                        lastUsed = cursor.getLong(
                            cursor.getColumnIndexOrThrow(COL_LAST_USED)
                        ),
                        contextLength = contextLength
                    )

                    if (predictions.size >= limit) {
                        return predictions.values.toList()
                    }
                }
            }
        }

        return predictions.values.toList()
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
        var deletedCount = 0

        db.beginTransaction()
        try {
            // 1. Remove weak phrases that have not been used recently.
            deletedCount += db.delete(
                TABLE_PHRASE_USAGE,
                "$COL_COUNT <= ? AND $COL_LAST_USED < ?",
                arrayOf(
                    weakCountThreshold.toString(),
                    cutoff.toString()
                )
            )

            // 2. Apply a hard safety cap independently to each locale.
            val locales = mutableListOf<String>()

            db.rawQuery(
                "SELECT DISTINCT $COL_LOCALE FROM $TABLE_PHRASE_USAGE",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    locales += cursor.getString(0)
                }
            }

            for (locale in locales) {
                val sequencesToDelete = mutableListOf<String>()

                db.query(
                    TABLE_PHRASE_USAGE,
                    arrayOf(COL_NORMALIZED_SEQUENCE),
                    "$COL_LOCALE = ?",
                    arrayOf(locale),
                    null,
                    null,
                    "$COL_COUNT DESC, $COL_LAST_USED DESC, $COL_NORMALIZED_SEQUENCE ASC"
                ).use { cursor ->
                    val sequenceIndex =
                        cursor.getColumnIndexOrThrow(COL_NORMALIZED_SEQUENCE)

                    var position = 0

                    while (cursor.moveToNext()) {
                        if (position >= maxEntriesPerLocale) {
                            sequencesToDelete += cursor.getString(sequenceIndex)
                        }

                        position++
                    }
                }

                for (normalizedSequence in sequencesToDelete) {
                    deletedCount += db.delete(
                        TABLE_PHRASE_USAGE,
                        "$COL_LOCALE = ? AND $COL_NORMALIZED_SEQUENCE = ?",
                        arrayOf(
                            locale,
                            normalizedSequence
                        )
                    )
                }
            }

            // phrase_next_word is derived entirely from phrase_usage.
            // Rebuild it so removed phrases cannot keep influencing predictions.
            rebuildNextWordIndex(db)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return deletedCount
    }

    override fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()

        try {
            db.delete(TABLE_PHRASE_NEXT_WORD, null, null)
            db.delete(TABLE_PHRASE_USAGE, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun rebuildPredictionIndex() {
        val db = writableDatabase

        db.beginTransaction()
        try {
            rebuildNextWordIndex(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private const val DATABASE_NAME = "phrase_usage.db"
        private const val DATABASE_VERSION = 2

        private const val TABLE_PHRASE_USAGE = "phrase_usage"
        private const val TABLE_PHRASE_NEXT_WORD = "phrase_next_word"

        private const val COL_LOCALE = "locale"
        private const val COL_NORMALIZED_SEQUENCE = "normalized_sequence"
        private const val COL_SEQUENCE = "sequence"
        private const val COL_COUNT = "count"
        private const val COL_LAST_USED = "last_used"
        private const val COL_CONTEXT = "context"
        private const val COL_NEXT_WORD_NORMALIZED = "next_word_normalized"
        private const val COL_NEXT_WORD = "next_word"

        private const val SEPARATOR = "\u001F"

        const val MIN_SEQUENCE_LENGTH = 2
        const val MAX_SEQUENCE_LENGTH = 5
    }
}