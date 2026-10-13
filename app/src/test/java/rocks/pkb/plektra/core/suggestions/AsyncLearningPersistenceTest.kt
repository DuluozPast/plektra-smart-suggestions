package rocks.pkb.plektra.core.suggestions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import rocks.pkb.plektra.backup.LearningOperationGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [AsyncLearningPersistenceTest.PosixAtomicFile::class])
class AsyncLearningPersistenceTest {
    // *Android rename replaces an existing file atomically. File.renameTo on Windows does not.*
    // *Keep real stream writes and use the host's equivalent atomic replacement for this test.*
    @Implements(AtomicFile::class)
    class PosixAtomicFile {
        @RealObject lateinit var real: AtomicFile
        @Implementation fun finishWrite(stream: FileOutputStream?) {
            if (stream == null) return
            stream.fd.sync()
            stream.close()
            Files.move(File(real.baseFile.path + ".new").toPath(), real.baseFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun blockedDiskDoesNotBlockEnqueueAndBackupWaitsForAllAcceptedWrites() {
        val queue = OrderedPersistenceQueue("blocked-test")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val barrier = Executors.newSingleThreadExecutor()
        try {
            queue.enqueue { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); order.add(1) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queue.enqueue { order.add(2) }
            barrier.submit { runBlocking { LearningOperationGate.awaitPendingLearning() }; completed.countDown() }
            assertFalse(completed.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2), order.toList())
        } finally { release.countDown(); queue.flush(); queue.closeWhenDrained(); barrier.shutdownNow() }
    }

    @Test fun failedWriteIsRetriedBeforeLaterOperationsAndFailureIsReported() {
        val queue = OrderedPersistenceQueue("failure-test")
        val attempts = AtomicInteger()
        val failed = CountDownLatch(1)
        val available = java.util.concurrent.atomic.AtomicBoolean(false)
        val order = java.util.Collections.synchronizedList(mutableListOf<Int>())
        try {
            queue.enqueue {
                attempts.incrementAndGet()
                if (!available.get()) { failed.countDown(); throw IOException("simulated disk failure") }
                order.add(1)
            }
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            queue.enqueue { order.add(2) }
            try { queue.flush(); fail("A failed persistence barrier must report failure") }
            catch (expected: IllegalStateException) { assertTrue(expected.cause is IOException) }
            assertTrue(order.isEmpty())
            available.set(true)
            queue.flush()
            assertTrue(attempts.get() >= 2)
            assertEquals(listOf(1, 2), order.toList())
        } finally { available.set(true); queue.flush(); queue.closeWhenDrained() }
    }

    @Test fun wordAndCorrectionCountersAreImmediateAndDurableWithoutDoubleCounting() {
        val words = WordUsageStore(context, asyncPersistence = true)
        val corrections = CorrectionUsageStore(context, asyncPersistence = true)
        try {
            words.clearAll(); corrections.clearAll()
            repeat(40) { i ->
                words.learn(Locale.FRENCH, "rendez-vous", i.toLong())
                corrections.learn(Locale.FRENCH, "randez-vous", "rendez-vous", i.toLong())
                assertEquals(i + 1, words.getUsage(Locale.FRENCH, "rendez-vous")!!.count)
                assertEquals(i + 1, words.getExactUsage(Locale.FRENCH, "rendez-vous")!!.count)
                assertEquals(i + 1, corrections.getUsage(Locale.FRENCH, "randez-vous", "rendez-vous")!!.count)
            }
            words.flushPendingWrites(); corrections.flushPendingWrites()
            WordUsageStore(context).use { assertEquals(40, it.getUsage(Locale.FRENCH, "rendez-vous")!!.count) }
            CorrectionUsageStore(context).use { assertEquals(40, it.getUsage(Locale.FRENCH, "randez-vous", "rendez-vous")!!.count) }
        } finally { words.clearAll(); corrections.clearAll(); words.close(); corrections.close() }
    }

    @Test fun phrasePredictionsMatchPersistedRankingForNumbersCompoundsAndContexts() {
        val raw = PhraseUsageStore(context)
        raw.clearAll()
        val phrases = AsyncPhraseUsageRepository(raw)
        try {
            val sequences = listOf(listOf("j'ai", "rendez-vous", "à", "11", "heures"),
                listOf("à", "11", "minutes"), listOf("à", "12", "heures"), listOf("11", "heures"))
            repeat(12) { i -> sequences.forEach { phrases.learn(Locale.FRENCH, it, (100 - i).toLong()) } }
            phrases.learn(Locale.GERMAN, listOf("11", "Uhr"), 200L)
            val contexts = listOf(listOf("j'ai"), listOf("à", "11"), listOf("11"), listOf("à"))
            val immediate = contexts.map { phrases.predictNext(Locale.FRENCH, it, 3) }
            assertEquals(12, phrases.getUsage(Locale.FRENCH, sequences.first())!!.count)
            assertTrue(immediate[1].any { it.word == "heures" })
            phrases.flushPendingWrites()
            contexts.forEachIndexed { i, history -> assertEquals(raw.predictNext(Locale.FRENCH, history, 3), immediate[i]) }
            assertEquals(raw.getPromotionCandidates(Locale.FRENCH, 2, 3), phrases.getPromotionCandidates(Locale.FRENCH, 2, 3))
        } finally { phrases.clearAll(); phrases.closeWhenDrained(); raw.close() }
    }

    @Test fun rapidJsonUpdatesAreVisibleImmediatelyAndSurviveReload() {
        val store = MultiWordStore(context, asyncPersistence = true)
        try {
            store.clearAll()
            repeat(150) { i ->
                store.promote(Locale.FRENCH, listOf("11", "heures"), i + 3, i.toLong())
                store.markUsed(Locale.FRENCH, listOf("11", "heures"), i.toLong())
            }
            val expected = store.getAll()
            assertEquals(1, expected.size)
            store.flushPendingWrites()
            assertEquals(expected, MultiWordStore(context).getAll())
            store.invalidateCache()
            assertEquals(expected, store.getAll())
        } finally { store.clearAll(); store.closeWhenDrained() }
    }

    @Test fun serviceRestartSharesImmediateModelsAndRestoreInvalidatesInactiveCaches() {
        val first = SharedLearningStores.get(context)
        first.words.clearAll(); first.multiwords.clearAll()
        try {
            first.words.learn(Locale.FRENCH, "plage", 100L)
            first.multiwords.promote(Locale.FRENCH, listOf("la", "plage"), 5, 100L)
            val restarted = SharedLearningStores.get(context)
            assertSame(first, restarted)
            assertEquals(1, restarted.words.getUsage(Locale.FRENCH, "plage")!!.count)
            runBlocking { LearningOperationGate.awaitPendingLearning() }
            // Simulate a restore replacing stored data while there is no new service instance.
            WordUsageStore(context).use { it.clearAll(); it.learn(Locale.FRENCH, "mer", 200L) }
            MultiWordStore(context).clearAll()
            SharedLearningStores.invalidate(context)
            assertNull(restarted.words.getUsage(Locale.FRENCH, "plage"))
            assertEquals(1, restarted.words.getUsage(Locale.FRENCH, "mer")!!.count)
            assertTrue(restarted.multiwords.getAll().isEmpty())
        } finally { first.words.clearAll(); first.multiwords.clearAll() }
    }
}
