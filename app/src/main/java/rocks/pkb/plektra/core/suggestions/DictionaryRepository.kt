package rocks.pkb.plektra.core.suggestions

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import android.os.Looper
import org.json.JSONArray
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.pow

interface DictionaryRepository {
    val isReady: Boolean
    val isLoadStarted: Boolean
    suspend fun loadIfNeeded()
    suspend fun refreshUserEntries()
    fun addUserEntryQuick(word: String)
    fun removeAutoLearnedUserEntry(word: String): Boolean
    fun isAutoLearnedUserEntry(word: String): Boolean
    fun addAutoLearnedUserEntryQuick(word: String)
    fun indexStoredUserEntryQuick(word: String)
    fun removeStoredUserEntryQuick(word: String)
    fun removeUserEntry(word: String)
    fun markUsed(word: String)
    fun effectiveFrequency(entry: DictionaryEntry): Int
    fun getExactWordFrequency(word: String): Int
    fun lookupByPrefixMerged(prefix: String, maxSize: Int): List<DictionaryEntry>
    fun symSpellLookup(term: String, maxSuggestions: Int): List<SymSpell.SuggestItem>
    fun bestEntryForNormalized(normalized: String): DictionaryEntry?
    fun topByNormalized(normalized: String, limit: Int = 5): List<DictionaryEntry>
    fun topCommonEntries(limit: Int = 16, includeUserEntries: Boolean = true): List<DictionaryEntry>
    fun isKnownWord(word: String): Boolean
    fun ensureLoadScheduled(background: () -> Unit)
}

/**
 * Loads and indexes lightweight dictionaries from assets and merges them with the user dictionary.
 */
class AndroidDictionaryRepository(
    private val context: Context,
    private val assets: AssetManager,
    private val userDictionaryStore: UserDictionaryStore,
    private val baseLocale: Locale = Locale.ITALIAN,
    private val cachePrefixLength: Int = 4,
    debugLogging: Boolean = false
) : DictionaryRepository {

    companion object {
        // Avoid concurrent heavy loads across repositories to reduce memory spikes.
        private val loadMutex = Mutex()
        
        /**
         * Checks if a dictionary file exists for the given locale.
         * Returns true if a dictionary is found (serialized format) in assets or custom folder.
         * This is a lightweight check that doesn't require loading the dictionary.
         */
        fun hasDictionaryForLocale(context: Context, locale: Locale): Boolean {
            return hasDictionaryForLocale(context, locale.language)
        }
        
        /**
         * Checks if a dictionary file exists for the given language code.
         * Returns true if a dictionary is found (serialized format) in assets or custom folder.
         */
        fun hasDictionaryForLocale(context: Context, languageCode: String): Boolean {
            return try {
                val langCode = languageCode.lowercase()
                val assets = context.assets
                
                // Check serialized dictionaries from assets
                try {
                    val serializedFiles = assets.list("common/dictionaries_serialized")
                    serializedFiles?.forEach { fileName ->
                        if (fileName == "${langCode}_base.dict") {
                            return true
                        }
                    }
                } catch (e: Exception) {
                    // If serialized directory doesn't exist, continue
                }

                // Check custom/imported serialized dictionaries in app storage
                try {
                    val localDir = File(context.filesDir, "dictionaries_serialized/custom")
                    val localFiles = localDir.listFiles { file ->
                        file.isFile && file.name == "${langCode}_base.dict"
                    }
                    if (!localFiles.isNullOrEmpty()) {
                        return true
                    }
                } catch (e: Exception) {
                    // Ignore errors
                }
                
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    private val prefixCache: MutableMap<String, MutableList<DictionaryEntry>> = mutableMapOf()
    private val normalizedIndex: MutableMap<String, MutableList<DictionaryEntry>> = mutableMapOf()
    @Volatile
    private var compactV31Reader: CompactDictionaryV31Reader? = null
    @Volatile private var symSpell: SymSpell? = null
    @Volatile private var symSpellBuilt: Boolean = false
    @Volatile private var topCommonEntriesCache: List<DictionaryEntry>? = null
    @Volatile private var topCommonEntriesWithoutUserCache: List<DictionaryEntry>? = null
    @Volatile override var isReady: Boolean = false
        internal set
    @Volatile private var loadStarted: Boolean = false
    
    override val isLoadStarted: Boolean
        get() = loadStarted
    private val tag = "DictionaryRepo"
    private val debugLogging: Boolean = debugLogging
    private val maxRawFrequency = 255.0
    private val scaledFrequencyMax = 1600.0

    override suspend fun loadIfNeeded() {
        if (isReady) return
        // Must not run on main thread
        if (Looper.myLooper() == Looper.getMainLooper()) return
        coroutineContext.ensureActive()
        loadMutex.withLock {
            if (isReady) return
            synchronized(this) {
                if (isReady || loadStarted) return
                loadStarted = true
            }
            try {
                val startTime = System.currentTimeMillis()

                // Move legacy local dictionaries into the custom folder to isolate imports.
                migrateLegacyLocalDictionaries()

                // Determine paths for custom and bundled dictionaries
                val customDir = File(context.filesDir, "dictionaries_serialized/custom").apply { mkdirs() }

                val activeDictionaryPrefs =
                    context.getSharedPreferences("active_dictionaries", Context.MODE_PRIVATE)

                val activeFilename = activeDictionaryPrefs.getString(
                    baseLocale.language,
                    "${baseLocale.language}_base.dict"
                ) ?: "${baseLocale.language}_base.dict"

                val compactV31Filename =
                    activeFilename.removeSuffix(".dict") + "_v31.dict"

                val compactV31File =
                    File(customDir, compactV31Filename)

                if (compactV31File.exists() && compactV31Reader == null) {
                    compactV31Reader =
                        CompactDictionaryV31Reader(compactV31File)

                    Log.i(
                        tag,
                        "V3.1 reader ready for locale=${baseLocale.language} " +
                                "dictionary=$activeFilename " +
                                "words=${compactV31Reader?.wordCount} " +
                                "normalized=${compactV31Reader?.normalizedCount} " +
                                "prefixes=${compactV31Reader?.prefixCount}"
                    )
                }

                /*
 * Legacy V1 dictionary is only required when no V3.1
 * memory-mapped dictionary exists.
 */
                if (compactV31Reader == null) {
                    val customFile =
                        File(customDir, activeFilename)

                    coroutineContext.ensureActive()

                    val serializedPath =
                        "common/dictionaries_serialized/${baseLocale.language}_base.dict"

                    val loadedSerialized =
                        when {
                            customFile.exists() -> {
                                Log.i(
                                    tag,
                                    "Loading CUSTOM dictionary from ${customFile.absolutePath}"
                                )

                                loadSerializedFromFile(customFile)
                            }

                            else -> {
                                Log.i(
                                    tag,
                                    "Loading BUNDLED dictionary from assets: $serializedPath"
                                )

                                loadSerializedFromAssets(serializedPath)
                            }
                        }

                    if (!loadedSerialized) {
                        Log.i(tag, "No usable base dictionary for ${baseLocale.language}; loading user words only")
                        prefixCache.clear(); normalizedIndex.clear()
                        symSpell = null; symSpellBuilt = false
                        topCommonEntriesCache = null; topCommonEntriesWithoutUserCache = null
                    }

                    val loadTime =
                        System.currentTimeMillis() - startTime

                    Log.i(
                        tag,
                        "Loaded legacy dictionary (.dict) in ${loadTime}ms - " +
                                "normalizedIndex=${normalizedIndex.size} " +
                                "prefixCache=${prefixCache.size}"
                    )
                } else {
                    Log.i(
                        tag,
                        "V3.1 active: skipping legacy CBOR dictionary load " +
                                "for locale=${baseLocale.language}"
                    )
                }
                coroutineContext.ensureActive()
                // Optional default user entries (editable copy stored in app files dir, fallback to asset)
                val defaultUserEntries = loadUserDefaults()
                if (defaultUserEntries.isNotEmpty()) {
                    index(defaultUserEntries, keepExisting = true)
                }

                // Always add user entries
                val userEntries = userDictionaryStore.loadUserEntries(context)
                if (userEntries.isNotEmpty()) {
                    index(userEntries, keepExisting = true)
                }

                coroutineContext.ensureActive()

                if (compactV31Reader == null) {
                    buildSymSpell()
                }

                isReady = true

            } catch (ce: CancellationException) {
                synchronized(this) { loadStarted = false }
                throw ce
            } catch (oom: OutOfMemoryError) {
                Log.e(tag, "OutOfMemory while loading dictionary; clearing and falling back", oom)
                synchronized(this) { loadStarted = false }
                prefixCache.clear()
                normalizedIndex.clear()
                topCommonEntriesCache = null
                topCommonEntriesWithoutUserCache = null
                symSpell = null
                symSpellBuilt = false
                isReady = false
                // Rethrow to let caller handle failure
                throw oom
            }
        }
    }

    override fun ensureLoadScheduled(background: () -> Unit) {
        if (isReady || loadStarted) return
        background()
    }

    override suspend fun refreshUserEntries() {
        coroutineContext.ensureActive()
        // Ensure dictionary base is loaded first (if not already)
        if (!isReady && !loadStarted) {
            loadIfNeeded()
            if (!isReady) return
        }
        coroutineContext.ensureActive()
        // Refresh defaults and user entries - this works even if base dictionary is still loading
        // because index() with keepExisting=true will merge with existing entries
        val defaultUserEntries = loadUserDefaults()
        val userEntries = userDictionaryStore.loadUserEntries(context)
        // Remove stale USER entries no longer present, then re-add current defaults/users
        purgeUserEntries(userEntries)
        if (defaultUserEntries.isNotEmpty()) {
            index(defaultUserEntries, keepExisting = true)
        }
        index(userEntries, keepExisting = true)
        // Rebuild SymSpell to drop removed entries
        /*
         * V3.1 reads USER / DEFAULT_USER entries directly from normalizedIndex.
         * Only legacy dictionaries need their SymSpell rebuilt.
         */
        if (compactV31Reader == null) {
            coroutineContext.ensureActive()
            symSpellBuilt = false
            buildSymSpell()
        }
    }

    fun addUserEntry(word: String) {
        addUserEntryQuick(word)
    }

    /**
     * Lightweight add: updates persistent store, then merges a single USER entry
     * into the in-memory indices and SymSpell without rebuilding everything.
     */
    override fun addUserEntryQuick(word: String) {
        userDictionaryStore.addWord(context, word)

        // Find latest frequency from snapshot; default to 1
        val freq = userDictionaryStore.getSnapshot()
            .firstOrNull { it.word.equals(word, ignoreCase = true) }
            ?.frequency ?: 1

        val entry = DictionaryEntry(
            word,
            freq,
            SuggestionSource.USER
        )

        index(
            listOf(entry),
            keepExisting = true
        )

        /*
         * V3.1 reads dynamic USER entries directly from normalizedIndex.
         * Legacy SymSpell only needs updating for dictionaries without V3.1.
         */
        if (compactV31Reader == null) {
            addToSymSpell(listOf(entry))
        }
    }

    override fun removeAutoLearnedUserEntry(word: String): Boolean {
        val removed = userDictionaryStore.removeAutoLearnedWord(
            context,
            word
        )

        if (!removed) return false

        removeStoredUserEntryQuick(word)

        return true
    }

    override fun removeStoredUserEntryQuick(word: String) {
        val normalized = normalize(word)

        normalizedIndex[normalized]?.removeAll { entry ->
            entry.source == SuggestionSource.USER &&
                    entry.word.equals(word, ignoreCase = true)
        }

        prefixCache.values.forEach { entries ->
            entries.removeAll { entry ->
                entry.source == SuggestionSource.USER &&
                        entry.word.equals(word, ignoreCase = true)
            }
        }

        if (compactV31Reader == null) {
            symSpell?.removeWord(normalized)
        }

        topCommonEntriesCache = null
        topCommonEntriesWithoutUserCache = null
    }

    override fun addAutoLearnedUserEntryQuick(word: String) {
        userDictionaryStore.addAutoLearnedWord(context, word)

        val freq = userDictionaryStore.getSnapshot()
            .firstOrNull { it.word.equals(word, ignoreCase = true) }
            ?.frequency ?: 1

        val entry = DictionaryEntry(
            word,
            freq,
            SuggestionSource.USER
        )

        index(
            listOf(entry),
            keepExisting = true
        )

        /*
         * V3.1 reads dynamic USER entries directly from normalizedIndex.
         * Legacy SymSpell only needs updating for dictionaries without V3.1.
         */
        if (compactV31Reader == null) {
            addToSymSpell(listOf(entry))
        }
    }

    override fun indexStoredUserEntryQuick(word: String) {
        val freq = userDictionaryStore.getSnapshot()
            .firstOrNull { it.word.equals(word, ignoreCase = true) }
            ?.frequency ?: return

        val entry = DictionaryEntry(
            word,
            freq,
            SuggestionSource.USER
        )

        index(
            listOf(entry),
            keepExisting = true
        )

        /*
         * V3.1 reads dynamic USER entries directly from normalizedIndex.
         * Legacy SymSpell only needs updating for dictionaries without V3.1.
         */
        if (compactV31Reader == null) {
            addToSymSpell(listOf(entry))
        }
    }

    override fun isAutoLearnedUserEntry(word: String): Boolean {
        return userDictionaryStore.isAutoLearnedWord(word)
    }

    override fun removeUserEntry(word: String) {
        userDictionaryStore.removeWord(context, word)
        val normalized = normalize(word)
        normalizedIndex[normalized]?.removeAll { entry ->
            entry.source == SuggestionSource.USER && entry.word.equals(word, ignoreCase = true)
        }
        prefixCache.values.forEach { entries ->
            entries.removeAll { entry ->
                entry.source == SuggestionSource.USER && entry.word.equals(word, ignoreCase = true)
            }
        }
        topCommonEntriesCache = null
        topCommonEntriesWithoutUserCache = null
    }

    override fun markUsed(word: String) {
        userDictionaryStore.markUsed(context, word)
    }

    override fun isKnownWord(word: String): Boolean {
        if (!isReady) return false

        val normalized = normalize(word)

        if (
            normalizedIndex[normalized]
                ?.any { it.source != SuggestionSource.MAIN } == true
        ) {
            return true
        }

        val reader = compactV31Reader

        if (reader != null) {
            return reader
                .normalizedEntries(normalized)
                .isNotEmpty()
        }

        return normalizedIndex[normalized]
            ?.isNotEmpty() == true
    }

    private fun topCommonEntriesV31(limit: Int): List<DictionaryEntry> {
        val reader = compactV31Reader ?: return emptyList()

        if (limit <= 0) {
            return emptyList()
        }

        /*
         * V3.1 prefix buckets are already sorted by descending raw frequency.
         *
         * Instead of scanning all 150,000 static words, inspect only the
         * one-letter prefix buckets. Taking a small number of leading entries
         * from each bucket is sufficient to recover the globally common words
         * while keeping this path very small.
         */
        val candidateLimitPerPrefix = maxOf(limit, 8)

        val candidates = ArrayList<DictionaryEntry>(
            26 * candidateLimitPerPrefix
        )

        for (letter in 'a'..'z') {
            reader.prefixEntries(
                letter.toString(),
                candidateLimitPerPrefix
            ).mapTo(candidates) {
                it.toDictionaryEntry()
            }
        }

        return candidates
            .asSequence()
            .filter { entry ->
                entry.word.length > 1 &&
                        entry.word.any { it.isLetter() } &&
                        entry.word.all { it.isLetter() || it == '\'' }
            }
            .distinctBy { entry ->
                entry.word.lowercase(Locale.ROOT)
            }
            .sortedWith(
                compareByDescending<DictionaryEntry> {
                    effectiveFrequency(it)
                }.thenBy {
                    it.word.length
                }
            )
            .take(limit)
            .toList()
    }

    override fun topCommonEntries(
        limit: Int,
        includeUserEntries: Boolean
    ): List<DictionaryEntry> {
        if (!isReady || limit <= 0) {
            return emptyList()
        }

        val cached =
            if (includeUserEntries) {
                topCommonEntriesCache
            } else {
                topCommonEntriesWithoutUserCache
            }

        if (cached != null) {
            return cached.take(limit)
        }

        val candidates =
            mutableListOf<DictionaryEntry>()

        /*
         * Dynamic USER entries remain in normalizedIndex.
         */
        if (includeUserEntries) {
            normalizedIndex.values
                .asSequence()
                .flatten()
                .filter {
                    it.source == SuggestionSource.USER
                }
                .forEach {
                    candidates.add(it)
                }
        }

        /*
         * MAIN dictionary.
         */
        if (compactV31Reader != null) {
            candidates.addAll(
                topCommonEntriesV31(48)
            )
        } else {
            normalizedIndex.values
                .asSequence()
                .mapNotNull { entries ->
                    entries
                        .filter {
                            it.source == SuggestionSource.MAIN
                        }
                        .maxByOrNull {
                            effectiveFrequency(it)
                        }
                }
                .forEach {
                    candidates.add(it)
                }
        }

        val computed =
            candidates
                .asSequence()
                .filter {
                    it.word.length > 1 &&
                            it.source != SuggestionSource.DEFAULT_USER &&
                            (
                                    includeUserEntries ||
                                            it.source != SuggestionSource.USER
                                    ) &&
                            it.word.any { char ->
                                char.isLetter()
                            } &&
                            it.word.none { char ->
                                !char.isLetter() &&
                                        char != '\''
                            }
                }
                .distinctBy {
                    it.word.lowercase(baseLocale)
                }
                .sortedWith(
                    compareByDescending<DictionaryEntry> {
                        it.source == SuggestionSource.USER
                    }
                        .thenByDescending {
                            effectiveFrequency(it)
                        }
                        .thenBy {
                            it.word.length
                        }
                )
                .take(48)
                .toList()

        if (includeUserEntries) {
            topCommonEntriesCache = computed
        } else {
            topCommonEntriesWithoutUserCache = computed
        }

        return computed.take(limit)
    }

    /**
     * Applies a non-linear scaling to compact dictionary frequencies (0–255) to
     * restore a meaningful range for ranking and SymSpell. The exponent (<1)
     * boosts mid/high values without making low values explode.
     */
    override fun effectiveFrequency(entry: DictionaryEntry): Int {
        val raw = entry.frequency.coerceAtLeast(0).coerceAtMost(maxRawFrequency.toInt())
        val normalized = raw / maxRawFrequency
        val scaled = (normalized.pow(0.75) * scaledFrequencyMax).toInt()
        return scaled.coerceAtLeast(1)
    }

    /**
     * Gets the frequency of an exact word (case-insensitive match).
     * Returns the maximum frequency if multiple entries exist (e.g., different sources).
     * Returns 0 if the word doesn't exist.
     */
    override fun getExactWordFrequency(word: String): Int {
        if (!isReady) return 0

        val normalized = normalize(word)

        var maxFrequency = 0

        normalizedIndex[normalized]
            ?.asSequence()
            ?.filter { it.source != SuggestionSource.MAIN }
            ?.filter { it.word.equals(word, ignoreCase = true) }
            ?.forEach { entry ->
                if (entry.frequency > maxFrequency) {
                    maxFrequency = entry.frequency
                }
            }

        val reader = compactV31Reader

        if (reader != null) {
            reader
                .normalizedEntries(normalized)
                .filter {
                    it.word.equals(
                        word,
                        ignoreCase = true
                    )
                }
                .forEach { entry ->
                    if (entry.frequency > maxFrequency) {
                        maxFrequency = entry.frequency
                    }
                }
        } else {
            normalizedIndex[normalized]
                ?.filter {
                    it.word.equals(
                        word,
                        ignoreCase = true
                    )
                }
                ?.forEach { entry ->
                    if (entry.frequency > maxFrequency) {
                        maxFrequency = entry.frequency
                    }
                }
        }

        return maxFrequency
    }

    private fun CompactDictionaryV31Reader.WordEntry.toDictionaryEntry(): DictionaryEntry {
        return DictionaryEntry(
            word = word,
            frequency = frequency,
            source = SuggestionSource.MAIN
        )
    }

    private fun lookupByPrefixMergedV31(
        prefix: String,
        maxSize: Int
    ): List<DictionaryEntry> {
        val reader = compactV31Reader ?: return emptyList()
        if (prefix.isBlank() || maxSize <= 0) return emptyList()

        val normalizedPrefix = normalize(prefix)
        if (normalizedPrefix.isBlank()) return emptyList()

        val maxPrefixLength =
            normalizedPrefix.length.coerceAtMost(cachePrefixLength)

        val seen = LinkedHashMap<String, DictionaryEntry>()

        for (length in maxPrefixLength downTo 1) {
            SuggestionWorkCancellation.check()
            val entries =
                reader.prefixEntries(normalizedPrefix.take(length))

            for (compactEntry in entries) {
            SuggestionWorkCancellation.check()
                val entry = compactEntry.toDictionaryEntry()
                val key = entry.word.lowercase(baseLocale)

                if (!seen.containsKey(key)) {
                    seen[key] = entry

                    if (seen.size >= maxSize) {
                        return seen.values.toList()
                    }
                }
            }
        }

        return seen.values.toList()
    }

    private fun topByNormalizedV31(
        normalized: String,
        limit: Int
    ): List<DictionaryEntry> {
        val reader = compactV31Reader ?: return emptyList()
        if (limit <= 0) return emptyList()

        return reader
            .normalizedEntries(normalized)
            .map { it.toDictionaryEntry() }
            .sortedByDescending { effectiveFrequency(it) }
            .take(limit)
    }

    fun lookupByPrefix(prefix: String): List<DictionaryEntry> {
        if (!isReady || prefix.isBlank()) return emptyList()
        val normalizedPrefix = normalize(prefix)
        val maxPrefixLength = normalizedPrefix.length.coerceAtMost(cachePrefixLength)

        for (length in maxPrefixLength downTo 1) {
            SuggestionWorkCancellation.check()
            val bucket = prefixCache[normalizedPrefix.take(length)]
            if (!bucket.isNullOrEmpty()) {
                return bucket
            }
        }
        return emptyList()
    }

    /**
     * Returns a merged list of candidates from the most specific prefix bucket down to the
     * single-letter bucket, stopping when maxSize is reached. This helps capture common
     * transpositions (e.g., "teh" -> "the", "caio" -> "ciao") that would otherwise live
     * under a different prefix.
     */
    override fun lookupByPrefixMerged(
        prefix: String,
        maxSize: Int
    ): List<DictionaryEntry> {
        if (!isReady || prefix.isBlank() || maxSize <= 0) {
            return emptyList()
        }

        val normalizedPrefix = normalize(prefix)

        if (normalizedPrefix.isBlank()) {
            return emptyList()
        }

        val maxPrefixLength =
            normalizedPrefix.length.coerceAtMost(cachePrefixLength)

        val seen =
            LinkedHashMap<String, DictionaryEntry>()

        /*
         * 1. Dynamic entries first:
         * USER / DEFAULT_USER.
         */
        for (length in maxPrefixLength downTo 1) {
            SuggestionWorkCancellation.check()
            val bucket =
                prefixCache[normalizedPrefix.take(length)] ?: continue

            for (entry in bucket) {
            SuggestionWorkCancellation.check()
                if (entry.source == SuggestionSource.MAIN) {
                    continue
                }

                val key =
                    entry.word.lowercase(baseLocale)

                if (!seen.containsKey(key)) {
                    seen[key] = entry
                }
            }
        }

        /*
         * 2. Static MAIN dictionary from V3.1.
         */
        if (compactV31Reader != null) {
            val compactEntries =
                lookupByPrefixMergedV31(prefix, maxSize)

            for (entry in compactEntries) {
            SuggestionWorkCancellation.check()
                val key =
                    entry.word.lowercase(baseLocale)

                if (!seen.containsKey(key)) {
                    seen[key] = entry
                }
            }
        } else {
            /*
             * Legacy V1 fallback for dictionaries
             * which do not yet have a V3.1 file.
             */
            for (length in maxPrefixLength downTo 1) {
            SuggestionWorkCancellation.check()
                val bucket =
                    prefixCache[normalizedPrefix.take(length)] ?: continue

                for (entry in bucket) {
            SuggestionWorkCancellation.check()
                    val key =
                        entry.word.lowercase(baseLocale)

                    if (!seen.containsKey(key)) {
                        seen[key] = entry
                    }
                }
            }
        }

        return seen.values
            .sortedByDescending { effectiveFrequency(it) }
            .take(maxSize)
    }

    private fun editDistanceAtMostTwo(
        left: String,
        right: String
    ): Int {
        if (left == right) return 0

        val lengthDifference =
            kotlin.math.abs(left.length - right.length)

        if (lengthDifference > 2) return 3

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (i in 1..left.length) {
            SuggestionWorkCancellation.check()
            current[0] = i
            var rowMinimum = current[0]

            for (j in 1..right.length) {
            SuggestionWorkCancellation.check()
                val substitutionCost =
                    if (left[i - 1] == right[j - 1]) 0 else 1

                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + substitutionCost
                )

                rowMinimum = minOf(
                    rowMinimum,
                    current[j]
                )
            }

            if (rowMinimum > 2) {
                return 3
            }

            val swap = previous
            previous = current
            current = swap
        }

        return previous[right.length]
    }

    private fun symSpellLookupV31(
        term: String,
        maxSuggestions: Int
    ): List<SymSpell.SuggestItem> {
        val reader = compactV31Reader ?: return emptyList()

        if (term.isBlank() || maxSuggestions <= 0) {
            return emptyList()
        }

        val normalizedTerm = normalize(term)

        if (normalizedTerm.isBlank()) {
            return emptyList()
        }

        val candidates =
            LinkedHashMap<String, CompactDictionaryV31Reader.WordEntry>()

        fun addEntries(
            entries: List<CompactDictionaryV31Reader.WordEntry>
        ) {
            for (entry in entries) {
            SuggestionWorkCancellation.check()
                val current = candidates[entry.normalized]

                if (
                    current == null ||
                    entry.frequency > current.frequency
                ) {
                    candidates[entry.normalized] = entry
                }
            }
        }

        val dynamicCandidates =
            LinkedHashMap<String, DictionaryEntry>()

        fun addDynamicEntry(entry: DictionaryEntry) {
            if (entry.source == SuggestionSource.MAIN) {
                return
            }

            val normalized = normalize(entry.word)

            if (normalized.isBlank()) {
                return
            }

            val current = dynamicCandidates[normalized]

            if (
                current == null ||
                effectiveFrequency(entry) > effectiveFrequency(current)
            ) {
                dynamicCandidates[normalized] = entry
            }
        }

        // Exact normalized candidate.
        addEntries(
            reader.normalizedEntries(normalizedTerm)
        )

        /*
         * V3.1 SymSpell index was generated from the first
         * cachePrefixLength characters with edit distance <= 2.
         */
        val prefix =
            normalizedTerm.take(cachePrefixLength)

        val deletes = LinkedHashSet<String>()
        deletes.add(prefix)

        var currentLevel: Set<String> =
            setOf(prefix)

        repeat(2) {
            val nextLevel =
                LinkedHashSet<String>()

            for (value in currentLevel) {
            SuggestionWorkCancellation.check()
                for (index in value.indices) {
            SuggestionWorkCancellation.check()
                    val deleted =
                        value.removeRange(index, index + 1)

                    if (deletes.add(deleted)) {
                        nextLevel.add(deleted)
                    }
                }
            }

            currentLevel = nextLevel
        }

        for (deleteKey in deletes) {
            SuggestionWorkCancellation.check()
            addEntries(
                reader.symDeleteEntries(deleteKey)
            )
        }

        /*
 * Dynamic USER / DEFAULT_USER entries.
 *
 * This set is expected to remain small, so scanning only
 * non-MAIN entries is cheap and avoids building a second
 * large SymSpell structure.
 */
        normalizedIndex.forEach { (_, entries) ->
            for (entry in entries) {
            SuggestionWorkCancellation.check()
                if (entry.source == SuggestionSource.MAIN) {
                    continue
                }

                val entryNormalized = normalize(entry.word)

                val distance =
                    editDistanceAtMostTwo(
                        normalizedTerm,
                        entryNormalized
                    )

                if (distance <= 2) {
                    addDynamicEntry(entry)
                }
            }
        }

        val merged =
            LinkedHashMap<String, SymSpell.SuggestItem>()

        fun addSuggestion(item: SymSpell.SuggestItem) {
            val current = merged[item.term]

            if (
                current == null ||
                item.distance < current.distance ||
                (
                        item.distance == current.distance &&
                                item.frequency > current.frequency
                        )
            ) {
                merged[item.term] = item
            }
        }

        /*
         * Static MAIN candidates from V3.1.
         */
        candidates.values.forEach { entry ->
            SuggestionWorkCancellation.check()
            val distance =
                editDistanceAtMostTwo(
                    normalizedTerm,
                    entry.normalized
                )

            if (distance <= 2) {
                addSuggestion(
                    SymSpell.SuggestItem(
                        term = entry.normalized,
                        distance = distance,
                        frequency = effectiveFrequency(
                            entry.toDictionaryEntry()
                        )
                    )
                )
            }
        }

        /*
         * Dynamic USER / DEFAULT_USER candidates.
         */
        dynamicCandidates.forEach { (normalized, entry) ->
            SuggestionWorkCancellation.check()
            val distance =
                editDistanceAtMostTwo(
                    normalizedTerm,
                    normalized
                )

            if (distance <= 2) {
                addSuggestion(
                    SymSpell.SuggestItem(
                        term = normalized,
                        distance = distance,
                        frequency = effectiveFrequency(entry)
                    )
                )
            }
        }

        return merged.values
            .sortedWith(
                Comparator { a, b ->
                    val distanceComparison =
                        a.distance.compareTo(b.distance)

                    if (distanceComparison != 0) {
                        distanceComparison
                    } else {
                        b.frequency.compareTo(a.frequency)
                    }
                }
            )
            .take(maxSuggestions)
    }

    override fun symSpellLookup(
        term: String,
        maxSuggestions: Int
    ): List<SymSpell.SuggestItem> {
        return if (compactV31Reader != null) {
            symSpellLookupV31(term, maxSuggestions)
        } else {
            val engine = symSpell ?: return emptyList()
            engine.lookup(term, maxSuggestions)
        }
    }

    override fun bestEntryForNormalized(
        normalized: String
    ): DictionaryEntry? {
        val reader = compactV31Reader

        /*
         * Legacy repository: MAIN + USER + DEFAULT_USER
         * are all still in normalizedIndex.
         */
        if (reader == null) {
            return normalizedIndex[normalized]
                ?.maxByOrNull {
                    effectiveFrequency(it)
                }
        }

        /*
         * V3.1 repository:
         * MAIN comes from the mapped compact dictionary,
         * dynamic entries remain in normalizedIndex.
         */
        val candidates =
            mutableListOf<DictionaryEntry>()

        reader
            .normalizedEntries(normalized)
            .mapTo(candidates) {
                it.toDictionaryEntry()
            }

        normalizedIndex[normalized]
            ?.asSequence()
            ?.filter {
                it.source != SuggestionSource.MAIN
            }
            ?.forEach {
                candidates.add(it)
            }

        return candidates.maxByOrNull {
            effectiveFrequency(it)
        }
    }

    fun allCandidates(): List<DictionaryEntry> {
        if (!isReady) return emptyList()
        return normalizedIndex.values.flatten()
    }

    /**
     * Returns top entries for a normalized term, sorted by frequency.
     * Useful for single-character inputs to surface multiple variants (e.g., accented).
     */
    override fun topByNormalized(
        normalized: String,
        limit: Int
    ): List<DictionaryEntry> {
        if (!isReady || limit <= 0) {
            return emptyList()
        }

        val merged =
            LinkedHashMap<String, DictionaryEntry>()

        normalizedIndex[normalized]
            ?.asSequence()
            ?.filter { it.source != SuggestionSource.MAIN }
            ?.forEach { entry ->
                merged[entry.word.lowercase(baseLocale)] = entry
            }

        compactV31Reader
            ?.normalizedEntries(normalized)
            ?.forEach { compactEntry ->
                val entry = compactEntry.toDictionaryEntry()
                val key = entry.word.lowercase(baseLocale)

                if (!merged.containsKey(key)) {
                    merged[key] = entry
                }
            }

        /*
         * If no V3.1 dictionary exists for this repository,
         * retain the V1 MAIN entries as fallback.
         */
        if (compactV31Reader == null) {
            normalizedIndex[normalized]
                ?.forEach { entry ->
                    val key = entry.word.lowercase(baseLocale)

                    if (!merged.containsKey(key)) {
                        merged[key] = entry
                    }
                }
        }

        return merged.values
            .sortedByDescending { effectiveFrequency(it) }
            .take(limit)
    }

    /**
     * Attempts to load dictionary from serialized format (.dict file).
     * Returns true if successful, false otherwise (fallback to JSON).
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun loadSerializedFromAssets(path: String): Boolean {
        Log.i(tag, "Attempting to load serialized dictionary from assets: $path")
        return try {
            coroutineContext.ensureActive()
            assets.open(path).use { input ->
                decodeSerializedDictionary(input)
            }
            true
        } catch (e: java.io.FileNotFoundException) {
            Log.w(tag, "Serialized dictionary file not found: $path - ${e.message}")
            false
        } catch (e: SerializationException) {
            Log.e(tag, "Failed to deserialize dictionary from $path: ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(tag, "Error loading serialized dictionary from $path: ${e.javaClass.simpleName} - ${e.message}", e)
            false
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun loadSerializedFromFile(file: File): Boolean {
        Log.i(tag, "Attempting to load serialized dictionary from file: ${file.absolutePath}")
        return try {
            coroutineContext.ensureActive()
            FileInputStream(file).use { input ->
                decodeSerializedDictionary(input)
            }
            true
        } catch (e: SerializationException) {
            Log.e(tag, "Failed to deserialize dictionary from file: ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(tag, "Error loading serialized dictionary from file: ${e.javaClass.simpleName} - ${e.message}", e)
            false
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeSerializedDictionary(input: InputStream) {
        // 1. Read serialized file into memory
        val bytes = input.readBytes()
        val sizeKb = bytes.size / 1024

        // Auto-detect format: JSON starts with '{' (0x7B), CBOR with other bytes
        val isJson = bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()

        Log.i(
            tag,
            "Dictionary format detected: ${if (isJson) "JSON (legacy)" else "CBOR"} " +
                    "- size: ${sizeKb} KB"
        )

        // 2. Deserialize
        val index = if (isJson) {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<DictionaryIndex>(bytes.decodeToString())
        } else {
            Cbor.decodeFromByteArray<DictionaryIndex>(bytes)
        }

        Log.i(
            tag,
            "Deserialized dictionary: " +
                    "normalizedIndex=${index.normalizedIndex.size}, " +
                    "prefixCache=${index.prefixCache.size}"
        )

        normalizedIndex.clear()
        prefixCache.clear()

        // 3. Convert/copy normalized index
        index.normalizedIndex.forEach { (normalized, entries) ->
            normalizedIndex[normalized] =
                entries.map { it.toDictionaryEntry() }.toMutableList()
        }

        // 4. Convert/copy prefix cache
        index.prefixCache.forEach { (prefix, entries) ->
            prefixCache[prefix] =
                entries.map { it.toDictionaryEntry() }.toMutableList()
        }

        // 5. Compatibility aliases
        val aliasTerms = addCompatibilityAliasesFromExistingIndex()

        if (index.symDeletes != null && index.symMeta != null) {
            val engine = SymSpell(
                maxEditDistance = index.symMeta.maxEditDistance,
                prefixLength = index.symMeta.prefixLength
            )

            // 6. Build term -> frequency map
            val termFrequencies = normalizedIndex.mapValues { (_, entries) ->
                entries.maxOfOrNull { effectiveFrequency(it) } ?: 0
            }

            // 7. Build prefix -> terms temporary map
            val prefixToTerms = mutableMapOf<String, MutableList<String>>()

            termFrequencies.keys.forEach { term ->
                val prefix =
                    term.take(index.symMeta.prefixLength.coerceAtMost(term.length))

                val list =
                    prefixToTerms.getOrPut(prefix) { mutableListOf() }

                list.add(term)
            }

            // 8. Expand serialized SymSpell deletes
            val expandedDeletes =
                mutableMapOf<String, MutableList<String>>()

            index.symDeletes.forEach { (deleteKey, terms) ->
                val targets = LinkedHashSet<String>()

                terms.forEach { t ->
                    if (termFrequencies.containsKey(t)) {
                        targets.add(t)
                    } else {
                        prefixToTerms[t]?.let { targets.addAll(it) }
                    }
                }

                if (targets.isNotEmpty()) {
                    expandedDeletes[deleteKey] =
                        targets.toMutableList()
                }
            }

            // 9. Load SymSpell structures
            engine.loadSerialized(
                termFrequencies,
                expandedDeletes
            )

            // 10. Add normalization aliases to SymSpell
            if (aliasTerms.isNotEmpty()) {
                aliasTerms.forEach { alias ->
                    val freq =
                        termFrequencies[alias] ?: return@forEach

                    engine.addWord(alias, freq)
                }
            }

            symSpell = engine
            symSpellBuilt = true

            Log.i(
                tag,
                "Loaded precomputed SymSpell deletes: ${expandedDeletes.size} keys"
            )
        }

        // 11. Sort runtime caches
        sortCachesByEffectiveFrequency()

        Log.i(
            tag,
            "Successfully populated indices from serialized format"
        )
    }

    /**
     * Loads optional default user entries from a writable file, falling back to the asset.
     * Format: [{ "w": "word", "f": 10 }, ...]
     */
    private fun loadUserDefaults(): List<DictionaryEntry> {
        val fileName = "user_defaults.json"
        val file = context.getFileStreamPath(fileName)
        
        // Ensure we have a local editable copy if asset exists
        if (!file.exists()) {
            try {
                assets.open("common/dictionaries/$fileName").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                // No asset; leave file absent
            }
        }
        
        return try {
            val jsonString = if (file.exists()) {
                file.readText()
            } else {
                assets.open("common/dictionaries/$fileName").bufferedReader().use { it.readText() }
            }
            val jsonArray = JSONArray(jsonString)
            buildList {
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val word = obj.getString("w")
                    val freq = obj.optInt("f", 1)
                    add(DictionaryEntry(word, freq, SuggestionSource.DEFAULT_USER))
                }
            }
        } catch (e: Exception) {
            if (debugLogging) Log.d(tag, "No default user dictionary at $fileName (${e.javaClass.simpleName}: ${e.message})")
            emptyList()
        }
    }

    internal fun index(entries: List<DictionaryEntry>, keepExisting: Boolean = false) {
        if (!keepExisting) {
            prefixCache.clear()
            normalizedIndex.clear()
            symSpellBuilt = false
        }

        entries.forEach { entry ->
            val normalized = normalize(entry.word)
            val bucket = normalizedIndex.getOrPut(normalized) { mutableListOf() }
            bucket.removeAll { it.word.equals(entry.word, ignoreCase = true) && it.source == entry.source }
            bucket.add(entry)

            val maxPrefixLength = normalized.length.coerceAtMost(cachePrefixLength)
            for (length in 1..maxPrefixLength) {
                val prefix = normalized.take(length)
                val prefixList = prefixCache.getOrPut(prefix) { mutableListOf() }
                prefixList.add(entry)
            }
        }

        sortCachesByEffectiveFrequency()
        if (debugLogging) {
            Log.d(tag, "index built: normalizedIndex=${normalizedIndex.size} prefixCache=${prefixCache.size}")
        }
    }

    private fun purgeUserEntries(currentUserEntries: List<DictionaryEntry>) {
        if (!isReady && !loadStarted) return
        val keepSet = currentUserEntries.map { it.word.lowercase(baseLocale) }.toSet()
        // Remove USER entries not in keepSet
        normalizedIndex.forEach { (_, list) ->
            val iterator = list.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.source == SuggestionSource.USER &&
                    !keepSet.contains(entry.word.lowercase(baseLocale))
                ) {
                    iterator.remove()
                }
            }
        }
        // Rebuild prefixCache from current normalizedIndex
        prefixCache.clear()
        normalizedIndex.forEach { (normalized, list) ->
            val maxPrefixLength = normalized.length.coerceAtMost(cachePrefixLength)
            for (length in 1..maxPrefixLength) {
                val prefix = normalized.take(length)
                val prefixList = prefixCache.getOrPut(prefix) { mutableListOf() }
                prefixList.addAll(list)
            }
        }
        sortCachesByEffectiveFrequency()
    }

    private fun buildSymSpell() {
        if (symSpellBuilt && symSpell != null) return
        val engine = SymSpell(maxEditDistance = 2, prefixLength = cachePrefixLength)
        normalizedIndex.forEach { (normalized, entries) ->
            val best = entries.maxByOrNull { effectiveFrequency(it) } ?: return@forEach
            engine.addWord(normalized, effectiveFrequency(best))
        }
        symSpell = engine
        symSpellBuilt = true
    }

    internal fun buildSymSpellForTests() {
        buildSymSpell()
    }

    /**
     * Move legacy dictionaries from the root local directory into the custom folder.
     * This isolates user-imported dictionaries from bundled assets.
     */
    private fun migrateLegacyLocalDictionaries() {
        val rootDir = File(context.filesDir, "dictionaries_serialized")
        val customDir = File(rootDir, "custom").apply { mkdirs() }
        if (!rootDir.exists() || rootDir == customDir) return

        rootDir.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name == "custom") return@forEach
            if (file.isFile && file.extension == "dict") {
                val dest = File(customDir, file.name)
                if (!dest.exists()) {
                    val moved = file.renameTo(dest)
                    Log.i(tag, "Migrating legacy dictionary ${file.name} to custom folder: success=$moved")
                    if (!moved) {
                        file.copyTo(dest, overwrite = false)
                        file.delete()
                    }
                } else {
                    Log.i(tag, "Removing duplicate legacy dictionary ${file.name}")
                    file.delete()
                }
            }
        }
    }

    private fun addToSymSpell(entries: List<DictionaryEntry>) {
        val engine = symSpell ?: run {
            buildSymSpell()
            symSpell ?: return
        }
        entries.forEach { entry ->
            val normalized = normalize(entry.word)
            engine.addWord(normalized, effectiveFrequency(entry))
        }
    }

    internal fun normalize(word: String): String {
        return WordNormalization.normalizeForDictionary(word, baseLocale)
    }

    /**
     * Adds normalization aliases (e.g. œil -> oeil) for dictionaries generated
     * with older normalization rules.
     *
     * Returns the set of normalized alias terms that were added.
     */
    private fun addCompatibilityAliasesFromExistingIndex(): Set<String> {
        if (normalizedIndex.isEmpty()) return emptySet()

        val aliasTerms = linkedSetOf<String>()
        val snapshot = normalizedIndex.entries.toList()

        snapshot.forEach { (term, entries) ->
            val alias = normalize(term)
            if (alias.isBlank() || alias == term) return@forEach

            val aliasBucket = normalizedIndex.getOrPut(alias) { mutableListOf() }

            entries.forEach { entry ->
                val exists = aliasBucket.any {
                    it.source == entry.source &&
                            it.word.equals(entry.word, ignoreCase = true)
                }

                if (!exists) {
                    aliasBucket.add(entry)
                }
            }

            aliasTerms.add(alias)
        }

        if (aliasTerms.isNotEmpty()) {
            rebuildPrefixCacheFromNormalizedIndex()
            Log.i(
                tag,
                "Added ${aliasTerms.size} normalization alias terms for compatibility"
            )
        }

        return aliasTerms
    }

    private fun rebuildPrefixCacheFromNormalizedIndex() {
        prefixCache.clear()
        normalizedIndex.forEach { (normalized, list) ->
            val maxPrefixLength = normalized.length.coerceAtMost(cachePrefixLength)
            for (length in 1..maxPrefixLength) {
                val prefix = normalized.take(length)
                val prefixList = prefixCache.getOrPut(prefix) { mutableListOf() }
                prefixList.addAll(list)
            }
        }
    }

    internal fun sortCachesByEffectiveFrequency() {
        prefixCache.values.forEach { list ->
            list.sortByDescending { effectiveFrequency(it) }
        }
        normalizedIndex.values.forEach { list ->
            list.sortByDescending { effectiveFrequency(it) }
        }
        topCommonEntriesCache = null
        topCommonEntriesWithoutUserCache = null
    }
}
