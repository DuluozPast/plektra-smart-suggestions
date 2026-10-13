package rocks.pkb.plektra.core.suggestions

import java.util.Locale

/** *Immediate phrase counters and predictions with ordered SQL persistence off the input thread.* */
internal class AsyncPhraseUsageRepository(private val store: PhraseUsageStore) : PhraseUsageRepository {
    private data class Key(val language: String, val words: List<String>)
    private val usages = LinkedHashMap<Key, PhraseUsageStore.Usage>()
    private val models = HashMap<Key, LinkedHashMap<String, PhraseUsageStore.Prediction>>()
    private val writer = OrderedPersistenceQueue("phrase-learning")

    init { store.setWriteAheadLoggingEnabled(true) }

    private fun normalized(words: List<String>, locale: Locale) =
        words.map { WordNormalization.normalizeForSuggestion(it, locale) }
    private fun key(locale: Locale, words: List<String>) = Key(locale.language.lowercase(Locale.ROOT), words)

    private fun model(locale: Locale, context: List<String>): LinkedHashMap<String, PhraseUsageStore.Prediction> =
        models.getOrPut(key(locale, context)) {
            LinkedHashMap<String, PhraseUsageStore.Prediction>().apply {
                store.predictionRows(locale, context).forEach { row ->
                    put(WordNormalization.normalizeForSuggestion(row.word, locale), row)
                }
            }
        }

    @Synchronized
    override fun learn(locale: Locale, words: List<String>, nowMs: Long) {
        val clean = words.map { it.trim() }.filter { it.isNotBlank() }
        if (clean.size !in PhraseUsageStore.MIN_SEQUENCE_LENGTH..PhraseUsageStore.MAX_SEQUENCE_LENGTH) return
        val normalized = normalized(clean, locale)
        if (normalized.any { it.isBlank() } || normalized.zipWithNext().any { it.first == it.second }) return
        val usageKey = key(locale, normalized)
        val prior = usages[usageKey] ?: store.getUsage(locale, clean)
        // Seed every affected context before enqueuing its first SQL mutation.
        val affected = (1 until clean.size).map { length ->
            model(locale, normalized.takeLast(length + 1).dropLast(1))
        }
        usages[usageKey] = PhraseUsageStore.Usage(clean, (prior?.count ?: 0) + 1, nowMs)
        affected.forEachIndexed { index, rows ->
            val next = normalized.last()
            val old = rows[next]
            rows[next] = PhraseUsageStore.Prediction(clean.last(), count = (old?.count ?: 0) + 1,
                lastUsed = maxOf(old?.lastUsed ?: 0L, nowMs), contextLength = index + 1)
        }
        writer.enqueue { store.learn(locale, clean, nowMs) }
    }

    @Synchronized
    override fun getUsage(locale: Locale, words: List<String>): PhraseUsageStore.Usage? {
        val clean = words.map { it.trim() }.filter { it.isNotBlank() }
        val usageKey = key(locale, normalized(clean, locale))
        return usages[usageKey] ?: store.getUsage(locale, clean)?.also { usages[usageKey] = it }
    }

    @Synchronized
    override fun predictNext(locale: Locale, contextWords: List<String>, limit: Int): List<PhraseUsageStore.Prediction> {
        if (limit <= 0) return emptyList()
        val context = normalized(contextWords.map { it.trim() }.filter { it.isNotBlank() }
            .takeLast(PhraseUsageStore.MAX_SEQUENCE_LENGTH - 1), locale)
        if (context.isEmpty() || context.any { it.isBlank() }) return emptyList()
        val result = LinkedHashMap<String, PhraseUsageStore.Prediction>()
        for (length in context.size downTo 1) {
            val rows = model(locale, context.takeLast(length)).entries.sortedWith(
                compareByDescending<Map.Entry<String, PhraseUsageStore.Prediction>> { it.value.count }
                    .thenByDescending { it.value.lastUsed })
            for ((word, row) in rows.take(limit)) {
                if (word !in result) result[word] = row.copy(contextLength = length)
                if (result.size >= limit) return result.values.toList()
            }
        }
        return result.values.toList()
    }

    @Synchronized
    override fun getPromotionCandidates(locale: Locale, minimumCount: Int, limit: Int): List<PhraseUsageStore.Usage> {
        if (minimumCount <= 0 || limit <= 0) return emptyList()
        val combined = LinkedHashMap<Key, PhraseUsageStore.Usage>()
        store.getPromotionCandidates(locale, minimumCount, Int.MAX_VALUE).forEach { usage ->
            combined[key(locale, normalized(usage.words, locale))] = usage
        }
        usages.filterKeys { it.language == locale.language.lowercase(Locale.ROOT) }.forEach { (k, usage) ->
            combined[k] = usage
        }
        return combined.values.filter { it.count >= minimumCount }
            .sortedWith(compareByDescending<PhraseUsageStore.Usage> { it.lastUsed }.thenByDescending { it.count })
            .take(limit)
    }

    @Synchronized
    override fun prune(nowMs: Long, staleAfterMs: Long, weakCountThreshold: Int, maxEntriesPerLocale: Int): Int {
        writer.flush()
        return store.prune(nowMs, staleAfterMs, weakCountThreshold, maxEntriesPerLocale).also {
            usages.clear(); models.clear()
        }
    }

    @Synchronized
    override fun clearAll() { writer.flush(); store.clearAll(); usages.clear(); models.clear() }

    @Synchronized
    fun invalidateCache() { writer.flush(); usages.clear(); models.clear() }
    fun flushPendingWrites() { writer.flush() }
    fun closeWhenDrained() { writer.closeWhenDrained() }
}
