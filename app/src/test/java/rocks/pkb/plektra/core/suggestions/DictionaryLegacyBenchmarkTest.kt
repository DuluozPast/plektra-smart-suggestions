package rocks.pkb.plektra.core.suggestions

import java.io.File
import java.util.Locale
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Replays Friday's private eager decoder in a disposable desktop test process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DictionaryLegacyBenchmarkTest {
    @Test fun profileFridayDecoderAtTheSameHeapLimit() {
        val source = System.getProperty("dictionary.profile.sources")
        assumeTrue(source != null)
        val context = RuntimeEnvironment.getApplication()
        val repository = AndroidDictionaryRepository(context, context.assets, UserDictionaryStore(), Locale.FRENCH)
        val decoder = AndroidDictionaryRepository::class.java.getDeclaredMethod("decodeSerializedDictionary", java.io.InputStream::class.java).apply { isAccessible = true }
        val clear = AndroidDictionaryRepository::class.java.getDeclaredMethod("clearAfterMemoryFailure").apply { isAccessible = true }
        fun heap() = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        System.gc()
        val before = heap()
        val start = System.nanoTime()
        var outcome = "completed"
        try {
            File(source!!, "fr_extended_150k.dict").inputStream().use { decoder.invoke(repository, it) }
        } catch (error: java.lang.reflect.InvocationTargetException) {
            if (error.targetException !is OutOfMemoryError) throw error
            outcome = "OutOfMemoryError"
        }
        val duration = (System.nanoTime() - start) / 1e6
        val after = heap()
        clear.invoke(repository)
        System.gc()
        File(System.getProperty("dictionary.profile.output").replace("desktop-memory-profile", "desktop-legacy-profile")).writeText(
            JSONObject().put("platform", "Windows JVM + Robolectric; not Titan")
                .put("dictionary", "French real 150000-entry legacy CBOR")
                .put("maxHeap", Runtime.getRuntime().maxMemory()).put("heapBefore", before)
                .put("heapBeforeClear", after).put("heapAfterClear", heap()).put("loadMs", duration).put("outcome", outcome).toString(2))
    }
}
