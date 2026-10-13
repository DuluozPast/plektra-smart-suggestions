package rocks.pkb.plektra.core.suggestions

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import android.util.AtomicFile

class MultiWordStore(context: Context) {

    @Serializable
    data class Entry(
        val locale: String,
        val normalizedWords: List<String>,
        val words: List<String>,
        val promotedAt: Long,
        val updatedAt: Long,
        val phraseCount: Int,
        val lastUsedAt: Long = 0L
    )

    @Serializable
    private data class StoreData(
        val version: Int = CURRENT_VERSION,
        val entries: List<Entry> = emptyList()
    )

    private val storeFile =
        File(context.applicationContext.filesDir, FILE_NAME)

    private var cachedStore: StoreData? = null

    @Synchronized
    fun invalidateCache() {
        cachedStore = null
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun getAll(): List<Entry> =
        readStore().entries

    @Synchronized
    fun getForLocale(locale: Locale): List<Entry> {
        val localeKey = localeKey(locale)

        return readStore().entries.filter {
            it.locale == localeKey
        }
    }

    @Synchronized
    fun find(
        locale: Locale,
        words: List<String>
    ): Entry? {
        val normalizedWords = normalizeWords(locale, words)
        if (normalizedWords.size < MIN_WORDS) return null

        val localeKey = localeKey(locale)

        return readStore().entries.firstOrNull {
            it.locale == localeKey &&
                    it.normalizedWords == normalizedWords
        }
    }

    @Synchronized
    fun promote(
        locale: Locale,
        words: List<String>,
        phraseCount: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Entry? {
        val cleanWords = words
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (cleanWords.size !in MIN_WORDS..MAX_WORDS) return null
        if (phraseCount <= 0) return null

        val normalizedWords = normalizeWords(locale, cleanWords)
        if (normalizedWords.size != cleanWords.size) return null
        if (normalizedWords.any { it.isBlank() }) return null

        val localeKey = localeKey(locale)
        val current = readStore()

        val existing = current.entries.firstOrNull {
            it.locale == localeKey &&
                    it.normalizedWords == normalizedWords
        }

        // Rechecking eligibility must not rewrite the whole file or refresh
        // timestamps when the learned words and their count have not changed.
        if (existing != null && existing.words == cleanWords &&
            existing.phraseCount == phraseCount) {
            return existing
        }

        val promotedEntry = Entry(
            locale = localeKey,
            normalizedWords = normalizedWords,
            words = cleanWords,
            promotedAt = existing?.promotedAt ?: nowMs,
            updatedAt = nowMs,
            phraseCount = phraseCount,
            lastUsedAt = existing?.lastUsedAt?.takeIf { it > 0L }
                ?: existing?.updatedAt
                ?: nowMs
        )

        val updatedEntries = current.entries
            .filterNot {
                it.locale == localeKey &&
                        it.normalizedWords == normalizedWords
            } + promotedEntry

        writeStore(
            StoreData(
                version = CURRENT_VERSION,
                entries = updatedEntries
            )
        )

        return promotedEntry
    }

    @Synchronized
    fun markUsed(
        locale: Locale,
        words: List<String>,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val normalizedWords = normalizeWords(locale, words)
        if (normalizedWords.size < MIN_WORDS) return false

        val localeKey = localeKey(locale)
        val current = readStore()
        var found = false

        val updatedEntries = current.entries.map { entry ->
            if (
                entry.locale == localeKey &&
                entry.normalizedWords == normalizedWords
            ) {
                found = true
                entry.copy(lastUsedAt = nowMs)
            } else {
                entry
            }
        }

        if (!found) return false

        writeStore(
            StoreData(
                version = CURRENT_VERSION,
                entries = updatedEntries
            )
        )

        return true
    }

    @Synchronized
    fun remove(
        locale: Locale,
        words: List<String>
    ): Boolean {
        val normalizedWords = normalizeWords(locale, words)
        if (normalizedWords.size < MIN_WORDS) return false

        val localeKey = localeKey(locale)
        val current = readStore()

        val updatedEntries = current.entries.filterNot {
            it.locale == localeKey &&
                    it.normalizedWords == normalizedWords
        }

        if (updatedEntries.size == current.entries.size) {
            return false
        }

        writeStore(
            StoreData(
                version = CURRENT_VERSION,
                entries = updatedEntries
            )
        )

        return true
    }

    @Synchronized
    fun clearAll() {
        AtomicFile(storeFile).delete()
        cachedStore = StoreData()
    }

    private fun readStore(): StoreData {
        cachedStore?.let { return it }

        val loaded = try {
            // openRead also recovers an interrupted AtomicFile write.
            val text = AtomicFile(storeFile).openRead().bufferedReader().use { it.readText() }

            if (text.isBlank()) {
                StoreData()
            } else {
                val data = json.decodeFromString<StoreData>(text)

                if (data.version != CURRENT_VERSION) {
                    StoreData()
                } else {
                    data
                }
            }
        } catch (_: Exception) {
            StoreData()
        }
        cachedStore = loaded
        return loaded
    }

    private fun writeStore(data: StoreData) {
        val atomicFile = AtomicFile(storeFile)
        val bytes = json.encodeToString(data).toByteArray(Charsets.UTF_8)

        val output = atomicFile.startWrite()

        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
            cachedStore = data
        } catch (e: Exception) {
            atomicFile.failWrite(output)
            throw e
        }
    }

    private fun normalizeWords(
        locale: Locale,
        words: List<String>
    ): List<String> =
        words
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map {
                WordNormalization.normalizeForSuggestion(
                    it,
                    locale
                )
            }

    private fun localeKey(locale: Locale): String =
        locale.toLanguageTag().lowercase(Locale.ROOT)

    companion object {
        private const val FILE_NAME =
            "multi_word_sequences.json"

        private const val CURRENT_VERSION = 1

        private const val MIN_WORDS = 2
        private const val MAX_WORDS =
            PhraseUsageStore.MAX_SEQUENCE_LENGTH
    }
}
