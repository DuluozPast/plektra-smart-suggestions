package rocks.pkb.plektra.core.suggestions

import java.util.Locale
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import android.content.Context
import android.content.ContextWrapper
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalSerializationApi::class)
class DiskDictionaryReaderTest {
    private lateinit var context: Context
    private lateinit var testCache: File
    @Before fun setup() {
        testCache = File(System.getProperty("java.io.tmpdir"), "disk-" + java.util.UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getCacheDir(): File = testCache }
    }
    @After fun cleanup() { testCache.deleteRecursively() }

    private fun sample() = DictionaryIndex(mapOf(
        "etat" to listOf(SerializableDictionaryEntry("état", 240, 0)),
        "etats" to listOf(SerializableDictionaryEntry("états", 200, 0)),
        "œil" to listOf(SerializableDictionaryEntry("œil", 120, 0)),
        "καλημερα" to listOf(SerializableDictionaryEntry("καλημέρα", 250, 0)),
        "bonjour" to listOf(SerializableDictionaryEntry("Bonjour", 210, 2))), emptyMap())

    @Test fun jsonAndCborRetainAccentsCaseSourceAndCompatibilityAliases() = runBlocking {
        for (bytes in listOf(Cbor.encodeToByteArray(sample()), Json.encodeToString(sample()).toByteArray())) {
            DiskDictionaryReader.open(context, Locale.FRENCH) { bytes.inputStream() }.use { reader ->
                assertEquals("état", reader.normalizedEntries("etat").single().word)
                assertEquals("œil", reader.normalizedEntries("oeil").single().word)
                assertEquals(2, reader.normalizedEntries("bonjour").single().source)
                assertEquals(listOf("état"), reader.prefixEntries("eta", 1).map { it.word })
                assertEquals("καλημέρα", reader.commonEntries(1).single().word)
                val bucket = mutableListOf<String>()
                reader.visitDeleteEntries("eta") { bucket.add(it.word) }
                assertTrue(bucket.contains("état"))
                assertTrue(bucket.contains("états"))
            }
        }
    }

    @Test fun contentReplacementCannotReuseAnIndexForTheOldImport() = runBlocking {
        val old = Cbor.encodeToByteArray(sample())
        val next = Cbor.encodeToByteArray(DictionaryIndex(mapOf("nouveau" to listOf(SerializableDictionaryEntry("nouveau", 255, 0))), emptyMap()))
        DiskDictionaryReader.open(context, Locale.FRENCH) { old.inputStream() }.use { assertTrue(it.normalizedEntries("etat").isNotEmpty()) }
        DiskDictionaryReader.open(context, Locale.FRENCH) { next.inputStream() }.use {
            assertTrue(it.normalizedEntries("etat").isEmpty()); assertEquals("nouveau", it.prefixEntries("nou", 1).single().word)
        }
    }

    @Test fun cancellationWhileIndexingRemovesPartialIndexAndAllowsRetry() = runBlocking {
        val bytes = Cbor.encodeToByteArray(sample())
        var opens = 0
        try {
            DiskDictionaryReader.open(context, Locale.JAPANESE) {
                opens++
                if (opens == 2) throw CancellationException("cancel index build")
                bytes.inputStream()
            }
            fail("expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(File(context.cacheDir, "dictionary-index-v2").listFiles().orEmpty().any { it.name.startsWith("building-") })
        DiskDictionaryReader.open(context, Locale.JAPANESE) { bytes.inputStream() }.use { assertEquals(5, it.wordCount) }
    }

    @Test fun repeatedCloseAndReopenDoesNotReuseAClosedDatabase() = runBlocking {
        val bytes = Cbor.encodeToByteArray(sample())
        repeat(12) {
            val reader = DiskDictionaryReader.open(context, Locale.FRENCH) { bytes.inputStream() }
            assertEquals(1, reader.prefixEntries("et", 1).size)
            reader.close(); reader.close()
            val db = DiskDictionaryReader::class.java.getDeclaredField("db").apply { isAccessible = true }.get(reader) as android.database.sqlite.SQLiteDatabase
            assertFalse(db.isOpen)
            assertTrue(reader.normalizedEntries("etat").isEmpty())
        }
    }

    @Test fun diskRepositoryCorrectsAnElisionWithoutReplacingItByACompletion() = runBlocking {
        val bytes = Cbor.encodeToByteArray(sample())
        val reader = DiskDictionaryReader.open(context, Locale.FRENCH) { bytes.inputStream() }
        val repository = AndroidDictionaryRepository(context, context.assets, UserDictionaryStore(), Locale.FRENCH)
        AndroidDictionaryRepository::class.java.getDeclaredField("compactV31Reader").apply { isAccessible = true }.set(repository, reader)
        repository.isReady = true
        try {
            assertTrue(repository.symSpellLookup("etad", 3).any { it.term == "etat" })
            val tracker = CurrentWordTracker(onWordChanged = {}, onWordReset = {})
            tracker.setWord("l'etat")
            val connection = object : android.view.inputmethod.BaseInputConnection(android.view.View(context), true) {
                init { val text = requireNotNull(editable); text.append("je parle de l'etat"); android.text.Selection.setSelection(text, text.length) }
            }
            val controller = AutoReplaceController(repository, SuggestionEngine(repository, Locale.FRENCH), {
                SuggestionSettings(autoReplaceOnSpaceEnter = true, accentMatching = true, maxAutoReplaceDistance = 1)
            })
            controller.handleBoundary(android.view.KeyEvent.KEYCODE_SPACE,
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_SPACE), tracker, connection)
            assertEquals("je parle de l'état ", connection.editable.toString())
        } finally { repository.releaseResources() }
    }
    @Test fun anExactLookupDoesNotWaitForAnotherSearchVisitor() = runBlocking {
        val reader = DiskDictionaryReader.open(context, Locale.FRENCH) { Cbor.encodeToByteArray(sample()).inputStream() }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val visitor = executor.submit {
            reader.visitDeleteEntries("eta") {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val lookup = executor.submit<List<CompactDictionaryV31Reader.WordEntry>> { reader.normalizedEntries("bonjour") }
            // The visitor is deliberately paused: this checks independence, not throughput.
            assertEquals("Bonjour", lookup.get(1, java.util.concurrent.TimeUnit.SECONDS).single().word)
        } finally {
            release.countDown()
            visitor.get(3, java.util.concurrent.TimeUnit.SECONDS)
            reader.close()
            executor.shutdownNow()
        }
    }

    @Test fun closeRetiresTheReaderWithoutInterruptingAnActiveVisitor() = runBlocking {
        val reader = DiskDictionaryReader.open(context, Locale.FRENCH) { Cbor.encodeToByteArray(sample()).inputStream() }
        val db = DiskDictionaryReader::class.java.getDeclaredField("db").apply { isAccessible = true }.get(reader) as android.database.sqlite.SQLiteDatabase
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val visitor = executor.submit {
            reader.visitDeleteEntries("eta") {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val closing = executor.submit { reader.close() }
            closing.get(1, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("The active visitor retains the database until it finishes", db.isOpen)
            assertTrue("A retired reader rejects new work", reader.normalizedEntries("bonjour").isEmpty())
            release.countDown()
            visitor.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(db.isOpen)
        } finally {
            release.countDown()
            visitor.get(3, java.util.concurrent.TimeUnit.SECONDS)
            reader.close()
            executor.shutdownNow()
        }
    }


}
