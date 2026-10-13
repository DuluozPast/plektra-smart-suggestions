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

    private data class Key(val locale: String, val words: List<String>)
    private data class Prefix(val locale: String, val words: List<String>)
    private val entries = LinkedHashMap<Key, Entry>()
    private val ordinals = HashMap<Key, Long>()
    private val byLocale = HashMap<String, LinkedHashSet<Key>>()
    private val byPrefix = HashMap<Prefix, LinkedHashSet<Key>>()
    private var loaded = false
    private var nextOrdinal = 0L
    private var batchDepth = 0
    private var dirty = false
    internal var persistenceCountForTests = 0
        private set

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** *Restoration invalidates both the in-memory data and its lookup indexes.* */
    @Synchronized
    fun invalidateCache() {
        check(batchDepth == 0) { "Cannot invalidate during learning" }
        resetIndexes()
        loaded = false
        dirty = false
    }

    @Synchronized
    fun getAll(): List<Entry> {
        ensureLoaded()
        return entries.values.toList()
    }

    @Synchronized
    fun getForLocale(locale: Locale): List<Entry> {
        ensureLoaded()
        return byLocale[localeKey(locale)].orEmpty().mapNotNull { entries[it] }
    }

    /** *Return only groups matching a suffix of the normalized context, in original store order.* */
    @Synchronized
    fun getForContext(locale: Locale, normalizedContext: List<String>): List<Entry> {
        ensureLoaded()
        val keys = LinkedHashSet<Key>()
        val tag = localeKey(locale)
        for (length in 1..minOf(normalizedContext.size, MAX_WORDS - 1)) {
            byPrefix[Prefix(tag, normalizedContext.takeLast(length))]?.let { keys.addAll(it) }
        }
        return orderedEntries(keys)
    }

    /** *Chain only groups beginning with a predicted word; keep tie-breaking order unchanged.* */
    @Synchronized
    fun getStartingWith(locale: Locale, normalizedFirstWords: Set<String>): List<Entry> {
        ensureLoaded()
        val keys = LinkedHashSet<Key>()
        val tag = localeKey(locale)
        normalizedFirstWords.forEach { word ->
            byPrefix[Prefix(tag, listOf(word))]?.let { keys.addAll(it) }
        }
        return orderedEntries(keys)
    }

    private fun orderedEntries(keys: Set<Key>): List<Entry> =
        keys.sortedBy { ordinals[it] }.mapNotNull { entries[it] }

    @Synchronized
    fun find(locale: Locale, words: List<String>): Entry? {
        val normalized = normalizeWords(locale, words)
        if (normalized.size < MIN_WORDS) return null
        ensureLoaded()
        return entries[Key(localeKey(locale), normalized)]
    }

    /** *Batch one logical learning operation into one atomic write, with no deferred-loss window.* */
    @Synchronized
    fun <T> batchUpdates(block: () -> T): T {
        // *An empty/disabled learning batch must not load the store.*
        batchDepth++
        try {
            return block()
        } finally {
            batchDepth--
            if (batchDepth == 0) persistIfDirty()
        }
    }

    @Synchronized
    fun promote(locale: Locale, words: List<String>, phraseCount: Int,
                nowMs: Long = System.currentTimeMillis()): Entry? {
        val cleanWords = words.map { it.trim() }.filter { it.isNotBlank() }
        if (cleanWords.size !in MIN_WORDS..MAX_WORDS || phraseCount <= 0) return null
        val normalized = normalizeWords(locale, cleanWords)
        if (normalized.size != cleanWords.size || normalized.any { it.isBlank() }) return null
        ensureLoaded()
        val key = Key(localeKey(locale), normalized)
        val existing = entries[key]
        if (existing != null && existing.words == cleanWords && existing.phraseCount == phraseCount) return existing
        val promoted = Entry(key.locale, normalized, cleanWords,
            existing?.promotedAt ?: nowMs, nowMs, phraseCount,
            existing?.lastUsedAt?.takeIf { it > 0L } ?: existing?.updatedAt ?: nowMs)
        // The legacy store moves a changed promotion to the end. Keep that exact ordering.
        removeIndexed(key)
        addIndexed(key, promoted)
        changed()
        return promoted
    }

    @Synchronized
    fun markUsed(locale: Locale, words: List<String>, nowMs: Long = System.currentTimeMillis()): Boolean {
        val normalized = normalizeWords(locale, words)
        if (normalized.size < MIN_WORDS) return false
        ensureLoaded()
        val key = Key(localeKey(locale), normalized)
        val existing = entries[key] ?: return false
        entries[key] = existing.copy(lastUsedAt = nowMs)
        changed()
        return true
    }

    @Synchronized
    fun remove(locale: Locale, words: List<String>): Boolean {
        val normalized = normalizeWords(locale, words)
        if (normalized.size < MIN_WORDS) return false
        ensureLoaded()
        val key = Key(localeKey(locale), normalized)
        if (!entries.containsKey(key)) return false
        removeIndexed(key)
        changed()
        return true
    }

    @Synchronized
    fun clearAll() {
        AtomicFile(storeFile).delete()
        resetIndexes()
        loaded = true
        dirty = false
    }

    private fun changed() {
        dirty = true
        if (batchDepth == 0) persistIfDirty()
    }

    private fun addIndexed(key: Key, entry: Entry) {
        entries[key] = entry
        ordinals[key] = nextOrdinal++
        byLocale.getOrPut(key.locale) { LinkedHashSet() }.add(key)
        for (length in 1 until key.words.size) {
            byPrefix.getOrPut(Prefix(key.locale, key.words.take(length))) { LinkedHashSet() }.add(key)
        }
    }

    private fun removeIndexed(key: Key) {
        if (entries.remove(key) == null) return
        ordinals.remove(key)
        byLocale[key.locale]?.let { bucket ->
            bucket.remove(key)
            if (bucket.isEmpty()) byLocale.remove(key.locale)
        }
        for (length in 1 until key.words.size) {
            val prefix = Prefix(key.locale, key.words.take(length))
            byPrefix[prefix]?.let { bucket ->
                bucket.remove(key)
                if (bucket.isEmpty()) byPrefix.remove(prefix)
            }
        }
    }

    private fun resetIndexes() {
        entries.clear(); ordinals.clear(); byLocale.clear(); byPrefix.clear()
        nextOrdinal = 0L
    }

    private fun ensureLoaded() {
        if (loaded) return
        val data = try {
            val text = AtomicFile(storeFile).openRead().bufferedReader().use { it.readText() }
            if (text.isBlank()) StoreData() else json.decodeFromString<StoreData>(text)
                .takeIf { it.version == CURRENT_VERSION } ?: StoreData()
        } catch (_: Exception) { StoreData() }
        resetIndexes()
        data.entries.forEach { entry -> addIndexed(Key(entry.locale, entry.normalizedWords), entry) }
        loaded = true
    }

    private fun persistIfDirty() {
        if (!dirty) return
        val atomicFile = AtomicFile(storeFile)
        val bytes = json.encodeToString(StoreData(entries = entries.values.toList())).toByteArray(Charsets.UTF_8)
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
            dirty = false
            persistenceCountForTests++
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
