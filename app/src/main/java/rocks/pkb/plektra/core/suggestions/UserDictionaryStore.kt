package rocks.pkb.plektra.core.suggestions

import android.content.Context
import android.content.SharedPreferences
import rocks.pkb.plektra.SettingsManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import android.util.Log

class UserDictionaryStore {

    enum class EntryOrigin {
        EXPLICIT,
        AUTO_LEARNED
    }

    data class UserEntry(
        val word: String,
        val frequency: Int,
        val lastUsed: Long,
        val origin: EntryOrigin = EntryOrigin.EXPLICIT
    )
    /**
     * Personal entry temporarily hidden because one or more installed base
     * dictionaries already provide the same normalized word.
     *
     * coveredBy contains the language codes whose active Extended dictionaries
     * currently justify keeping this entry out of the personal dictionary.
     */
    private data class SuppressedEntry(
        val entry: UserEntry,
        val coveredBy: MutableSet<String>
    )

    private fun prefs(context: Context): SharedPreferences =
        SettingsManager.getPreferences(context)

    fun loadUserEntries(context: Context): List<DictionaryEntry> {
        return try {
            val json = prefs(context).getString(KEY_USER_DICTIONARY, "[]") ?: "[]"
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val word = obj.getString(KEY_WORD)
                    val frequency = obj.optInt(KEY_FREQ, 1)
                    val lastUsed = obj.optLong(KEY_LAST_USED, 0L)

                    add(
                        DictionaryEntry(
                            word = word,
                            frequency = frequency,
                            source = SuggestionSource.USER
                        )
                    )

                    // Cache keys are case-insensitive. DictionaryRepository applies
                    // locale-aware dictionary normalization when indexing entries.
                    cache[word.lowercase()] = UserEntry(
                        word = word,
                        frequency = frequency,
                        lastUsed = lastUsed,
                        origin = runCatching {
                            EntryOrigin.valueOf(
                                obj.optString(KEY_ORIGIN, EntryOrigin.EXPLICIT.name)
                            )
                        }.getOrDefault(EntryOrigin.EXPLICIT)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user dictionary", e)
            emptyList()
        }
    }

    fun addWord(context: Context, word: String) {
        val cacheKey = word.lowercase()
        val entry = cache[cacheKey]

        val updated = if (entry != null) {
            entry.copy(
                frequency = entry.frequency + 1,
                lastUsed = System.currentTimeMillis()
            )
        } else {
            UserEntry(
                word = word,
                frequency = 1,
                lastUsed = System.currentTimeMillis()
            )
        }

        cache[cacheKey] = updated
        persist(context)
    }

    fun addAutoLearnedWord(context: Context, word: String) {
        val cacheKey = word.lowercase()
        val entry = cache[cacheKey]

        val updated = if (entry != null) {
            entry.copy(
                frequency = entry.frequency + 1,
                lastUsed = System.currentTimeMillis()
            )
        } else {
            UserEntry(
                word = word,
                frequency = 1,
                lastUsed = System.currentTimeMillis(),
                origin = EntryOrigin.AUTO_LEARNED
            )
        }

        cache[cacheKey] = updated
        persist(context)
    }

    fun removeWord(context: Context, word: String) {
        cache.remove(word.lowercase())
        persist(context)
    }

    fun isAutoLearnedWord(word: String): Boolean {
        val cacheKey = word.lowercase()
        return cache[cacheKey]?.origin == EntryOrigin.AUTO_LEARNED
    }

    fun removeAutoLearnedWord(context: Context, word: String): Boolean {
        val cacheKey = word.lowercase()
        val entry = cache[cacheKey]

        if (entry == null) {

            return false
        }

        if (entry.origin != EntryOrigin.AUTO_LEARNED) {

            return false
        }

        cache.remove(cacheKey)
        persist(context)

        return true
    }

    fun pruneAutoLearnedWords(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        staleAfterMs: Long,
        weakFrequencyThreshold: Int,
        maxEntries: Int
    ): List<String> {
        require(staleAfterMs >= 0L)
        require(weakFrequencyThreshold >= 0)
        require(maxEntries > 0)

        val cutoff = nowMs - staleAfterMs

        val activeEntries = readActiveEntries(context)
        val suppressedEntries = loadSuppressedEntries(context)

        var removedCount = 0
        val removedWords = mutableListOf<String>()

        val remainingActive = activeEntries
            .filterNot { entry ->
                val shouldRemove =
                    entry.origin == EntryOrigin.AUTO_LEARNED &&
                            entry.frequency <= weakFrequencyThreshold &&
                            entry.lastUsed < cutoff

                if (shouldRemove) {
                    removedCount++
                    removedWords.add(entry.word)
                }

                shouldRemove
            }
            .toMutableList()

        val remainingSuppressed = suppressedEntries
            .filterNot { suppressed ->
                val entry = suppressed.entry

                val shouldRemove =
                    entry.origin == EntryOrigin.AUTO_LEARNED &&
                            entry.frequency <= weakFrequencyThreshold &&
                            entry.lastUsed < cutoff

                if (shouldRemove) {
                    removedCount++
                    removedWords.add(entry.word)
                }

                shouldRemove
            }
            .toMutableList()

        data class AutoLearnedCandidate(
            val entry: UserEntry,
            val isSuppressed: Boolean
        )

        val candidates = buildList {
            remainingActive
                .filter { it.origin == EntryOrigin.AUTO_LEARNED }
                .forEach { entry ->
                    add(
                        AutoLearnedCandidate(
                            entry = entry,
                            isSuppressed = false
                        )
                    )
                }

            remainingSuppressed
                .filter {
                    it.entry.origin == EntryOrigin.AUTO_LEARNED
                }
                .forEach { suppressed ->
                    add(
                        AutoLearnedCandidate(
                            entry = suppressed.entry,
                            isSuppressed = true
                        )
                    )
                }
        }.sortedWith(
            compareByDescending<AutoLearnedCandidate> {
                it.entry.frequency
            }.thenByDescending {
                it.entry.lastUsed
            }.thenBy {
                entryKey(it.entry.word)
            }
        )

        if (candidates.size > maxEntries) {
            candidates
                .drop(maxEntries)
                .forEach { candidate ->
                    val key = entryKey(candidate.entry.word)

                    val removed = if (candidate.isSuppressed) {
                        remainingSuppressed.removeAll { suppressed ->
                            entryKey(suppressed.entry.word) == key &&
                                    suppressed.entry.origin == EntryOrigin.AUTO_LEARNED
                        }
                    } else {
                        remainingActive.removeAll { entry ->
                            entryKey(entry.word) == key &&
                                    entry.origin == EntryOrigin.AUTO_LEARNED
                        }
                    }

                    if (removed) {
                        removedCount++
                        removedWords.add(candidate.entry.word)
                    }
                    }
                }

    if (removedCount > 0) {
        persistDictionaryState(
            context = context,
            activeEntries = remainingActive,
            suppressedEntries = remainingSuppressed
        )
    }

    return removedWords
    }

    fun pruneAutoLearned(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        staleAfterMs: Long,
        weakFrequencyThreshold: Int,
        maxEntries: Int
    ): Int {
        return pruneAutoLearnedWords(
            context = context,
            nowMs = nowMs,
            staleAfterMs = staleAfterMs,
            weakFrequencyThreshold = weakFrequencyThreshold,
            maxEntries = maxEntries
        ).size
    }

    fun updateWord(context: Context, oldWord: String, newWord: String) {
        val oldKey = oldWord.lowercase()
        val newKey = newWord.lowercase()
        val existing = cache[oldKey] ?: return

        cache.remove(oldKey)
        cache[newKey] = existing.copy(
            word = newWord,
            lastUsed = System.currentTimeMillis()
        )

        persist(context)
    }

    fun markUsed(context: Context, word: String) {
        val cacheKey = word.lowercase()

        cache[cacheKey]?.let { entry ->
            cache[cacheKey] = entry.copy(
                lastUsed = System.currentTimeMillis(),
                frequency = entry.frequency + 1
            )
            persist(context)
        }
    }

    /**
     * Removes personal entries already covered by the newly installed base
     * dictionary and stores them temporarily for this language.
     *
     * The saved entries can later be restored if the custom/Extended base
     * dictionary is removed, making base-dictionary deduplication reversible.
     */
    /**
     * Reconciles the personal dictionary with the newly installed base dictionary.
     *
     * Personal entries covered by the new base are temporarily suppressed.
     * Entries suppressed by an older version of the same language dictionary are
     * restored automatically if the new base no longer contains them.
     */

    /** Include suppressed entries when rechecking coverage against a replacement base. */
    internal fun personalKeysForBase(context: Context, locale: Locale): Set<String> =
        (readActiveEntries(context).asSequence().map { it.word } +
            loadSuppressedEntries(context).asSequence().map { it.entry.word })
            .map { WordNormalization.normalizeForDictionary(it, locale) }.toSet()

    fun removeWordsAlreadyInBase(
        context: Context,
        baseNormalizedWords: Set<String>,
        locale: Locale
    ): Int {
        return try {
            val language = languageKey(locale)
            val activeEntries = readActiveEntries(context)
            val suppressedEntries = loadSuppressedEntries(context)

            /*
             * Build a single view of every personal entry, whether currently
             * active or already suppressed by another base dictionary.
             */
            val entriesByKey = linkedMapOf<String, UserEntry>()
            val coveredByKey = mutableMapOf<String, MutableSet<String>>()

            activeEntries.forEach { entry ->
                val key = entryKey(entry.word)
                entriesByKey[key] = choosePreferredEntry(
                    entriesByKey[key],
                    entry
                )
                coveredByKey.putIfAbsent(key, mutableSetOf())
            }

            suppressedEntries.forEach { suppressed ->
                val key = entryKey(suppressed.entry.word)
                entriesByKey[key] = choosePreferredEntry(
                    entriesByKey[key],
                    suppressed.entry
                )
                coveredByKey
                    .getOrPut(key) { mutableSetOf() }
                    .addAll(suppressed.coveredBy)
            }

            val activeKeysBefore = activeEntries
                .map { entryKey(it.word) }
                .toSet()

            /*
             * Re-evaluate this language for every personal entry. Existing
             * suppression reasons belonging to other languages are preserved.
             */
            entriesByKey.forEach { (key, entry) ->
                val normalizedWord =
                    WordNormalization.normalizeForDictionary(
                        entry.word,
                        locale
                    )

                val coveredBy = coveredByKey
                    .getOrPut(key) { mutableSetOf() }

                if (normalizedWord in baseNormalizedWords) {
                    coveredBy.add(language)
                } else {
                    coveredBy.remove(language)
                }
            }

            val newActiveEntries = mutableListOf<UserEntry>()
            val newSuppressedEntries = mutableListOf<SuppressedEntry>()
            var newlySuppressedCount = 0

            entriesByKey.forEach { (key, entry) ->
                val coveredBy = coveredByKey[key].orEmpty()

                if (coveredBy.isEmpty()) {
                    newActiveEntries.add(entry)
                } else {
                    newSuppressedEntries.add(
                        SuppressedEntry(
                            entry = entry,
                            coveredBy = coveredBy.toMutableSet()
                        )
                    )

                    if (key in activeKeysBefore && language in coveredBy) {
                        newlySuppressedCount++
                    }
                }
            }

            persistDictionaryState(
                context = context,
                activeEntries = newActiveEntries,
                suppressedEntries = newSuppressedEntries
            )

            newlySuppressedCount
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Unable to reconcile personal dictionary with base dictionary",
                e
            )
            0
        }
    }

    /**
     * Restores personal entries that were removed during base-dictionary
     * deduplication for this language.
     *
     * Existing personal entries win if the same normalized word was added
     * again while the Extended dictionary was installed.
     */

    /**
     * Removes this language as a suppression reason after its Extended base
     * dictionary is uninstalled.
     *
     * An entry is restored only when no other installed base dictionary still
     * covers it.
     */
    fun restoreWordsRemovedForBase(
        context: Context,
        locale: Locale
    ): Int {
        return try {
            val language = languageKey(locale)
            val activeEntries = readActiveEntries(context)
            val suppressedEntries = loadSuppressedEntries(context)

            if (suppressedEntries.isEmpty()) return 0

            val restoredEntries = mutableListOf<UserEntry>()
            val remainingSuppressedEntries = mutableListOf<SuppressedEntry>()

            suppressedEntries.forEach { suppressed ->
                val coveredBy = suppressed.coveredBy.toMutableSet()

                if (language in coveredBy) {
                    coveredBy.remove(language)
                }

                if (coveredBy.isEmpty()) {
                    restoredEntries.add(suppressed.entry)
                } else {
                    remainingSuppressedEntries.add(
                        SuppressedEntry(
                            entry = suppressed.entry,
                            coveredBy = coveredBy
                        )
                    )
                }
            }

            /*
             * Merge restored entries with the active personal dictionary without
             * creating duplicate storage entries.
             */
            val activeByKey = linkedMapOf<String, UserEntry>()

            activeEntries.forEach { entry ->
                val key = entryKey(entry.word)
                activeByKey[key] = choosePreferredEntry(
                    activeByKey[key],
                    entry
                )
            }

            restoredEntries.forEach { entry ->
                val key = entryKey(entry.word)
                activeByKey[key] = choosePreferredEntry(
                    activeByKey[key],
                    entry
                )
            }

            persistDictionaryState(
                context = context,
                activeEntries = activeByKey.values.toList(),
                suppressedEntries = remainingSuppressedEntries
            )

            restoredEntries.size
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Unable to restore personal dictionary entries",
                e
            )
            0
        }
    }

    fun getSnapshot(): List<UserEntry> =
        cache.values.sortedByDescending { it.lastUsed }

    private fun languageKey(locale: Locale): String {
        return locale.language
            .takeIf { it.isNotBlank() }
            ?.lowercase(Locale.ROOT)
            ?: locale.toLanguageTag().lowercase(Locale.ROOT)
    }

    /**
     * Stable identity used to match the same personal entry across active and
     * suppressed storage without applying language-specific normalization.
     */
    private fun entryKey(word: String): String {
        return word.lowercase(Locale.ROOT)
    }

    /**
     * Keeps the most recently used version when the same personal entry exists
     * in both active and suppressed storage.
     */
    private fun choosePreferredEntry(
        current: UserEntry?,
        candidate: UserEntry
    ): UserEntry {
        if (current == null) return candidate

        return if (candidate.lastUsed > current.lastUsed) {
            candidate
        } else {
            current
        }
    }

    private fun readActiveEntries(context: Context): MutableList<UserEntry> {
        val json = prefs(context)
            .getString(KEY_USER_DICTIONARY, "[]")
            ?: "[]"

        val array = JSONArray(json)
        val entries = mutableListOf<UserEntry>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            entries.add(
                UserEntry(
                    word = obj.getString(KEY_WORD),
                    frequency = obj.optInt(KEY_FREQ, 1),
                    lastUsed = obj.optLong(KEY_LAST_USED, 0L),
                    origin = runCatching {
                        EntryOrigin.valueOf(
                            obj.optString(KEY_ORIGIN, EntryOrigin.EXPLICIT.name)
                        )
                    }.getOrDefault(EntryOrigin.EXPLICIT)
                )
            )
        }

        return entries
    }

    private fun loadSuppressedEntries(
        context: Context
    ): MutableList<SuppressedEntry> {
        val json = prefs(context)
            .getString(KEY_SUPPRESSED_ENTRIES, "[]")
            ?: "[]"

        val array = JSONArray(json)
        val entries = mutableListOf<SuppressedEntry>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val coveredByArray = obj.optJSONArray(KEY_COVERED_BY)
            val coveredBy = mutableSetOf<String>()

            if (coveredByArray != null) {
                for (j in 0 until coveredByArray.length()) {
                    coveredBy.add(coveredByArray.getString(j))
                }
            }

            entries.add(
                SuppressedEntry(
                    entry = UserEntry(
                        word = obj.getString(KEY_WORD),
                        frequency = obj.optInt(KEY_FREQ, 1),
                        lastUsed = obj.optLong(KEY_LAST_USED, 0L),
                        origin = runCatching {
                            EntryOrigin.valueOf(
                                obj.optString(KEY_ORIGIN, EntryOrigin.EXPLICIT.name)
                            )
                        }.getOrDefault(EntryOrigin.EXPLICIT)
                    ),
                    coveredBy = coveredBy
                )
            )
        }

        return entries
    }

    /**
     * Rebuilds the in-memory personal dictionary cache using the same stable,
     * language-independent key as the suppression store.
     */
    private fun rebuildCache(
        entries: List<UserEntry>
    ) {
        cache.clear()

        entries.forEach { entry ->
            cache[entryKey(entry.word)] = entry
        }
    }


    private fun UserEntry.toJson(): JSONObject {
        return JSONObject().apply {
            put(KEY_WORD, word)
            put(KEY_FREQ, frequency)
            put(KEY_LAST_USED, lastUsed)
            put(KEY_ORIGIN, origin.name)
        }
    }

    /**
     * Persists active and suppressed personal entries together so the two stores
     * always describe the same dictionary state.
     */
    private fun persistDictionaryState(
        context: Context,
        activeEntries: List<UserEntry>,
        suppressedEntries: List<SuppressedEntry>
    ) {
        val activeArray = JSONArray()
        activeEntries.forEach { entry ->
            activeArray.put(entry.toJson())
        }

        val suppressedArray = JSONArray()
        suppressedEntries.forEach { suppressed ->
            val obj = suppressed.entry.toJson()

            val coveredByArray = JSONArray()
            suppressed.coveredBy
                .sorted()
                .forEach { language ->
                    coveredByArray.put(language)
                }

            obj.put(KEY_COVERED_BY, coveredByArray)
            suppressedArray.put(obj)
        }

        rebuildCache(activeEntries)

        /*
         * Write both parts of the personal dictionary with the same editor.
         * This avoids leaving active and suppressed storage out of sync.
         */
        prefs(context)
            .edit()
            .putString(KEY_USER_DICTIONARY, activeArray.toString())
            .putString(KEY_SUPPRESSED_ENTRIES, suppressedArray.toString())
            .apply()
    }

    private fun persist(context: Context) {
        try {
            val array = JSONArray()

            cache.values.forEach { entry ->
                array.put(entry.toJson())
            }

            prefs(context)
                .edit()
                .putString(KEY_USER_DICTIONARY, array.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to persist user dictionary", e)
        }
    }

    companion object {
        private const val TAG = "UserDictionaryStore"

        private const val KEY_USER_DICTIONARY =
            "user_dictionary_entries"

        /*
 * Global storage for temporarily suppressed personal entries.
 * Each saved entry records every language whose active Extended dictionary
 * currently covers it.
 */
        private const val KEY_SUPPRESSED_ENTRIES =
            "user_dictionary_suppressed_entries"

        private const val KEY_COVERED_BY = "coveredBy"
        private const val KEY_WORD = "w"
        private const val KEY_FREQ = "f"
        private const val KEY_LAST_USED = "u"
        private const val KEY_ORIGIN = "o"

        private val cache: MutableMap<String, UserEntry> =
            mutableMapOf()
    }
}