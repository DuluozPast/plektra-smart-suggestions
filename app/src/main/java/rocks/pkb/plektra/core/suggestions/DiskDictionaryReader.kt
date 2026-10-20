package rocks.pkb.plektra.core.suggestions

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import rocks.pkb.plektra.dictionaries.StreamingDictionaryValidator
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A disposable derived index. Original dictionaries, backups and personal stores
 * are never rewritten. SQLite's page cache is bounded independently of word count.
 * Construction reads one legacy entry at a time, including on the first import.
 */
internal class DiskDictionaryReader private constructor(private val db: SQLiteDatabase) : StaticDictionaryReader {
    private var closed = false
    private var activeVisits = 0
    override val wordCount = db.rawQuery("SELECT count(*) FROM words", null).use { it.moveToFirst(); it.getInt(0) }
    override val normalizedCount = db.rawQuery("SELECT count(DISTINCT normalized) FROM words", null).use { it.moveToFirst(); it.getInt(0) }
    override val prefixCount = 0 // Not a materialized prefix-to-words map.

    private fun visit(sql: String, args: Array<String>?, action: (CompactDictionaryV31Reader.WordEntry) -> Unit) {
        // Lease the database, but never hold the lifecycle lock while a caller
        // ranks candidates. An exact UI-thread lookup must not wait for that work.
        synchronized(this) {
            if (closed) return
            activeVisits++
        }
        try {
            db.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    SuggestionWorkCancellation.check()
                    action(CompactDictionaryV31Reader.WordEntry(cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getInt(3)))
                }
            }
        } finally {
            synchronized(this) {
                activeVisits--
                if (closed && activeVisits == 0) db.close()
            }
        }
    }

    override fun normalizedEntries(normalized: String) = buildList {
        visit("SELECT word,normalized,frequency,source FROM words WHERE normalized=? ORDER BY frequency DESC", arrayOf(normalized)) { add(it) }
    }

    override fun prefixEntries(prefix: String, limit: Int): List<CompactDictionaryV31Reader.WordEntry> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        return buildList {
            // Unicode binary range, with a generous upper sentinel (dictionary keys are letters).
            visit("SELECT word,normalized,frequency,source FROM words WHERE normalized>=? AND normalized<? ORDER BY frequency DESC,word LIMIT ?",
                arrayOf(prefix, prefix + '\uffff', limit.toString())) { add(it) }
        }
    }

    override fun visitDeleteEntries(deleteKey: String, minLength: Int, maxLength: Int, visit: (CompactDictionaryV31Reader.WordEntry) -> Unit) {
        // Cursor iteration avoids materializing a large deletion bucket. The caller
        // keeps only its best matching candidates after checking edit distance.
        this.visit("SELECT w.word,w.normalized,w.frequency,w.source FROM deletes d JOIN words w ON w.prefix=d.prefix WHERE d.key=? AND w.units>=? AND w.units<=?",
            arrayOf(deleteKey, minLength.toString(), maxLength.toString()), visit)
    }

    override fun commonEntries(limit: Int): List<CompactDictionaryV31Reader.WordEntry> = buildList {
        visit("SELECT word,normalized,frequency,source FROM words WHERE starter=1 ORDER BY frequency DESC,word LIMIT ?", arrayOf(limit.toString())) { add(it) }
    }

    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            // A dictionary switch rejects new visits immediately; existing cursors
            // finish safely and release the last lease before the database closes.
            if (activeVisits == 0) db.close()
        }
    }

    companion object {
        private const val SCHEMA = 2
        private fun configure(db: SQLiteDatabase) {
            db.execSQL("PRAGMA cache_size=-1024") // 1 MiB per connection; no mmap heap/index expansion.
            db.execSQL("PRAGMA temp_store=FILE")
            db.setMaxSqlCacheSize(8)
        }

        suspend fun open(context: Context, locale: Locale, source: () -> InputStream): DiskDictionaryReader {
            val taskContext = coroutineContext
            // Hash content, not timestamps: replacing an import with the same name
            // must not reuse stale words. The hash pass uses a fixed 16 KiB buffer.
            val digest = MessageDigest.getInstance("SHA-256")
            source().use { input ->
                val bytes = ByteArray(16384)
                while (true) { taskContext.ensureActive(); val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val dir = File(context.cacheDir, "dictionary-index-v$SCHEMA").apply { mkdirs() }
            val target = File(dir, "${locale.toLanguageTag()}-$hash.sqlite")
            if (!target.exists()) {
                val staging = File.createTempFile("building-", ".sqlite", dir)
                try {
                    SQLiteDatabase.openOrCreateDatabase(staging, null).use { db ->
                        configure(db)
                        db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
                        db.execSQL("CREATE TABLE words(normalized TEXT NOT NULL,word TEXT NOT NULL,frequency INTEGER NOT NULL,source INTEGER NOT NULL,prefix TEXT NOT NULL,starter INTEGER NOT NULL,units INTEGER NOT NULL,PRIMARY KEY(normalized,word,source)) WITHOUT ROWID")
                        db.execSQL("CREATE TABLE prefixes(prefix TEXT PRIMARY KEY) WITHOUT ROWID")
                        db.execSQL("CREATE TABLE deletes(key TEXT NOT NULL,prefix TEXT NOT NULL,PRIMARY KEY(key,prefix)) WITHOUT ROWID")
                        db.beginTransaction()
                        try {
                            db.compileStatement("INSERT OR REPLACE INTO words VALUES(?,?,?,?,?,?,?)").use { word ->
                                db.compileStatement("INSERT OR IGNORE INTO prefixes VALUES(?)").use { prefix ->
                                    db.compileStatement("INSERT OR IGNORE INTO deletes VALUES(?,?)").use { deletion ->
                                        source().use { input ->
                                            StreamingDictionaryValidator.validate(input, emptySet(), onEntry = { entry ->
                                                val normalized = WordNormalization.normalizeForDictionary(entry.key, locale)
                                                if (normalized.isNotEmpty()) {
                                                    val start = normalized.take(4)
                                                    word.bindString(1, normalized); word.bindString(2, entry.word)
                                                    word.bindLong(3, entry.frequency.toLong()); word.bindLong(4, entry.source.toLong()); word.bindString(5, start)
                                                    val starter = entry.word.length > 1 && entry.word.any { it.isLetter() } && entry.word.all { it.isLetter() || it == '\'' }
                                                    word.bindLong(6, if (starter) 1 else 0); word.bindLong(7, normalized.length.toLong()); word.executeInsert()
                                                    prefix.bindString(1, start)
                                                    if (prefix.executeUpdateDelete() == 1) {
                                                        val deletes = linkedSetOf(start)
                                                        var level: Set<String> = setOf(start)
                                                        repeat(2) {
                                                            val next = linkedSetOf<String>()
                                                            for (value in level) for (i in value.indices) {
                                                                val key = value.removeRange(i, i + 1)
                                                                if (deletes.add(key)) next.add(key)
                                                            }
                                                            level = next
                                                        }
                                                        for (key in deletes) { deletion.bindString(1, key); deletion.bindString(2, start); deletion.executeInsert() }
                                                    }
                                                }
                                            }, checkpoint = { taskContext.ensureActive() })
                                        }
                                    }
                                }
                            }
                            taskContext.ensureActive()
                            db.execSQL("CREATE INDEX words_prefix ON words(prefix,units)")
                            db.execSQL("CREATE INDEX words_frequency ON words(starter,frequency DESC,word)")
                            db.execSQL("DROP TABLE prefixes")
                            db.version = SCHEMA
                            db.setTransactionSuccessful()
                        } finally { db.endTransaction() }
                    }
                    taskContext.ensureActive()
                    Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    staging.delete(); File(staging.path + "-journal").delete()
                }
            }
            taskContext.ensureActive()
            val db = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            try {
                configure(db)
                val reader = DiskDictionaryReader(db)
                // Keep the latest derived cache per locale; this does not touch imports.
                dir.listFiles()?.filter { it != target && it.name.startsWith(locale.toLanguageTag() + "-") && it.extension == "sqlite" }?.forEach { it.delete() }
                return reader
            } catch (error: Throwable) { db.close(); throw error }
        }
    }
}
