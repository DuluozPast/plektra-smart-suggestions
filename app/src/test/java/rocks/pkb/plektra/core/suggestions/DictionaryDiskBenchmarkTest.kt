package rocks.pkb.plektra.core.suggestions

import android.content.ContextWrapper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Opt-in desktop measurements, never represented as Titan measurements. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DictionaryDiskBenchmarkTest {
    @Test fun profileRealDictionariesAndRepeatedSwitches() = runBlocking {
        val sourceRoot = System.getProperty("dictionary.profile.sources")
        assumeTrue(sourceRoot != null)
        val report = File(System.getProperty("dictionary.profile.output"))
        val cache = File(System.getProperty("java.io.tmpdir"), "disk-profile-" + java.util.UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) { override fun getCacheDir() = cache }
        val root = File(sourceRoot!!)
        val sources = listOf(
            Locale.FRENCH to File(root, "fr_extended_150k.dict"),
            Locale.ITALIAN to File(root, "app/src/main/assets/common/dictionaries_serialized/it_base.dict"),
            Locale.ENGLISH to File(root, "app/src/main/assets/common/dictionaries_serialized/en_base.dict"))
        assertTrue(sources.all { it.second.isFile })
        fun heap() = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val startHeap = heap()
        val sampling = AtomicBoolean(true)
        val peak = AtomicLong(startHeap)
        val sampler = Thread {
            while (sampling.get()) { peak.accumulateAndGet(heap(), ::maxOf); Thread.sleep(2) }
        }.apply { isDaemon = true; start() }
        val records = org.json.JSONArray()
        val readers = mutableListOf<DiskDictionaryReader>()
        val beforeFds = File("/proc/self/fd").list()?.size
        try {
            for ((locale, file) in sources) {
                val started = System.nanoTime()
                val reader = DiskDictionaryReader.open(context, locale) { file.inputStream() }
                readers.add(reader)
                val opened = System.nanoTime()
                val prefix = if (locale.language == "fr") "eta" else if (locale.language == "it") "buo" else "the"
                val durations = mutableListOf<Long>()
                repeat(40) {
                    val queryStart = System.nanoTime()
                    assertTrue(reader.prefixEntries(prefix, 12).isNotEmpty())
                    durations.add(System.nanoTime() - queryStart)
                }
                val repository = AndroidDictionaryRepository(context, app.assets, UserDictionaryStore(), locale)
                AndroidDictionaryRepository::class.java.getDeclaredField("compactV31Reader").apply { isAccessible = true }.set(repository, reader)
                repository.isReady = true
                val engine = SuggestionEngine(repository, locale)
                val engineDurations = mutableListOf<Long>()
                for (word in if (locale.language == "fr") listOf("e", "et", "etat", "l'etat", "bonjor", "bonjour") else listOf(prefix, prefix + "a")) {
                    val queryStart = System.nanoTime()
                    engine.suggest(word, 3)
                    engineDurations.add(System.nanoTime() - queryStart)
                }
                records.put(JSONObject().put("language", locale.language).put("words", reader.wordCount)
                    .put("sourceBytes", file.length()).put("openMs", (opened - started) / 1e6)
                    .put("prefixMedianMs", durations.sorted()[durations.size / 2] / 1e6)
                    .put("prefixMaxMs", durations.max() / 1e6).put("engineQueryMs", org.json.JSONArray(engineDurations.map { it / 1e6 })).put("heapUsed", heap()))
            }
            assertEquals(150000, readers.first().wordCount)
            readers.forEach { it.close() }; readers.clear()
            val switches = org.json.JSONArray()
            repeat(4) {
                for ((locale, source) in sources) {
                    val start = System.nanoTime()
                    DiskDictionaryReader.open(context, locale) { source.inputStream() }.use { reader ->
                        assertFalse(reader.commonEntries(3).isEmpty())
                    }
                    switches.put((System.nanoTime() - start) / 1e6)
                }
            }
            // A separate memory stress test: three copies of the real French
            // 150k lexicon, not a claim about the unavailable IT/EN Extended files.
            val stress = org.json.JSONArray()
            for (locale in listOf(Locale.FRENCH, Locale.ITALIAN, Locale.ENGLISH)) {
                val started = System.nanoTime()
                val reader = DiskDictionaryReader.open(context, locale) { sources.first().second.inputStream() }
                readers.add(reader)
                assertEquals(150000, reader.wordCount)
                stress.put(JSONObject().put("locale", locale.language).put("words", reader.wordCount).put("openMs", (System.nanoTime() - started) / 1e6))
            }
            readers.forEach { it.close() }; readers.clear()
            val mappedFile = File(root, "en_extended_150k_v31_phone.dict")
            CompactDictionaryV31Reader(mappedFile).use { mapped ->
                assertEquals(150000, mapped.wordCount)
                assertTrue(mapped.prefixEntries("th", 3).isNotEmpty())
                mapped.visitDeleteEntries("th", 2, 6) { assertTrue(it.normalized.length in 2..6) }
            }
            System.gc()
            report.writeText(JSONObject().put("platform", "Windows JVM + Robolectric; not Titan; native SQLite memory excluded from Java heap")
                .put("maxHeap", Runtime.getRuntime().maxMemory()).put("heapAtStart", startHeap)
                .put("heapAfterClose", heap()).put("sampledHeapPeakBytes", peak.get())
                .put("fileDescriptorsBefore", beforeFds).put("fileDescriptorsAfter", File("/proc/self/fd").list()?.size)
                .put("cacheBytes", cache.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                .put("languages", records).put("cachedSwitchesMs", switches).put("stressThreeCopiesOfFrench150k", stress).toString(2))
        } finally { sampling.set(false); sampler.join(); readers.forEach { it.close() }; cache.deleteRecursively() }
    }
}
