package rocks.pkb.plektra.core.suggestions

import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rocks.pkb.plektra.backup.LearningOperationGate
import kotlinx.coroutines.runBlocking

class NextWordPredictorAsyncLearningTest {

    @Test
    fun learnDoesNotBlockOnStoreWrite_andPredictsPendingBigramImmediately() {
        val store = BlockingNGramRepository()
        val predictor = NextWordPredictor(store)

        try {
            val elapsedMs = measureTimeMillis {
                predictor.learn(Locale.GERMANY, "ich", "bin")
            }

            assertTrue("learn should only enqueue work", elapsedMs < 100)
            assertEquals(
                listOf("bin"),
                predictor.predict(Locale.GERMANY, "ich").map { it.candidate }
            )
            assertTrue(store.writeStarted.await(1, TimeUnit.SECONDS))

            store.releaseWrites()
            predictor.flushLearningForTests()

            assertEquals(listOf("bin"), store.predict("de-DE", "ich", 3).map { it.word })
        } finally {
            store.releaseWrites()
            predictor.destroy()
        }
    }

    @Test
    fun forgetCancelsQueuedWriteBeforeItReachesStore() {
        val store = BlockingNGramRepository()
        val predictor = NextWordPredictor(store)

        try {
            predictor.learn(Locale.GERMANY, "block", "first")
            assertTrue(store.writeStarted.await(1, TimeUnit.SECONDS))

            predictor.learn(Locale.GERMANY, "ich", "bin")
            assertEquals(
                listOf("bin"),
                predictor.predict(Locale.GERMANY, "ich").map { it.candidate }
            )

            predictor.forget(Locale.GERMANY, "ich", "bin")
            assertTrue(predictor.predict(Locale.GERMANY, "ich").isEmpty())

            store.releaseWrites()
            predictor.flushLearningForTests()

            assertEquals(listOf("first"), store.predict("de-DE", "block", 3).map { it.word })
            assertFalse(store.learnedWords().contains("bin"))
        } finally {
            store.releaseWrites()
            predictor.destroy()
        }
    }

    @Test
    fun prune_flushesPendingLearningBeforePruning() {
        val store = BlockingNGramRepository()
        val predictor = NextWordPredictor(store)

        try {
            predictor.learn(
                Locale.FRANCE,
                "je",
                "vais"
            )

            assertTrue(
                store.writeStarted.await(
                    1,
                    TimeUnit.SECONDS
                )
            )

            val pruneThread = Thread {
                predictor.prune(
                    nowMs = Long.MAX_VALUE,
                    staleAfterMs = 0L,
                    weakCountThreshold = 1,
                    maxEntriesPerLocale = 100
                )
            }

            pruneThread.start()

            assertEquals(0, store.pruneCallCount)

            store.releaseWrites()
            pruneThread.join(2_000L)

            assertFalse(pruneThread.isAlive)
            assertEquals(1, store.pruneCallCount)
            assertTrue(store.learnCompletedBeforePrune)
        } finally {
            store.releaseWrites()
            predictor.destroy()
        }
    }


    @Test
    fun flushWaitsForPendingLearningToFinish() {
        val store = BlockingNGramRepository()
        val predictor = NextWordPredictor(store)

        try {
            predictor.learn(Locale.GERMANY, "ich", "bin")

            assertTrue(
                "The asynchronous write should have started",
                store.writeStarted.await(1, TimeUnit.SECONDS)
            )

            val flushFinished = CountDownLatch(1)

            val flushThread = Thread {
                try {
                    predictor.flushLearningForTests()
                } finally {
                    flushFinished.countDown()
                }
            }

            flushThread.start()

            assertFalse(
                "Flush must wait while the database write is blocked",
                flushFinished.await(100, TimeUnit.MILLISECONDS)
            )

            store.releaseWrites()

            assertTrue(
                "Flush should finish after the write is released",
                flushFinished.await(3, TimeUnit.SECONDS)
            )

            assertEquals(
                listOf("bin"),
                store.predict("de-DE", "ich", 3).map { it.word }
            )
        } finally {
            store.releaseWrites()
            predictor.destroy()
        }
    }

    @Test
    fun exclusiveOperationPreventsNewLearning() = runBlocking {
        val store = BlockingNGramRepository()
        val predictor = NextWordPredictor(store)

        try {
            LearningOperationGate.withExclusiveOperation {
                assertTrue(LearningOperationGate.isLearningSuspended)

                predictor.learn(Locale.GERMANY, "ich", "bin")

                assertTrue(
                    "No write should be started while learning is suspended",
                    !store.writeStarted.await(100, TimeUnit.MILLISECONDS)
                )

                assertTrue(
                    predictor.predict(Locale.GERMANY, "ich").isEmpty()
                )
            }

            predictor.flushLearningForTests()

            assertTrue(store.learnedWords().isEmpty())
            assertFalse(LearningOperationGate.isLearningSuspended)
        } finally {
            store.releaseWrites()
            predictor.destroy()
        }
    }


    private class BlockingNGramRepository : UserNGramRepository {
        val writeStarted = CountDownLatch(1)
        private val releaseWrites = CountDownLatch(1)
        private val lock = Any()
        private val learned = mutableListOf<LearnedBigram>()

        override fun getTotalCount(
            locale: String,
            prefix: String
        ): Int {
            return 0
        }

        override fun learn(locale: String, prefix: String, nextWord: String, nowMs: Long) {
            writeStarted.countDown()
            releaseWrites.await(2, TimeUnit.SECONDS)
            synchronized(lock) {
                learned.add(LearnedBigram(locale, prefix, nextWord, nowMs))
            }
        }

        override fun predict(locale: String, prefix: String, limit: Int): List<UserNGramStore.Prediction> {
            synchronized(lock) {
                return learned
                    .filter { it.locale == locale && it.prefix == prefix }
                    .groupBy { it.nextWord }
                    .map { (word, rows) ->
                        UserNGramStore.Prediction(
                            word = word,
                            count = rows.size,
                            lastUsed = rows.maxOf { it.nowMs }
                        )
                    }
                    .sortedWith(
                        compareByDescending<UserNGramStore.Prediction> { it.count }
                            .thenByDescending { it.lastUsed }
                    )
                    .take(limit)
            }
        }

        override fun getUsage(
            locale: String,
            prefix: String,
            nextWord: String
        ): UserNGramStore.Prediction? {
            return null
        }

        override fun delete(locale: String, prefix: String, nextWord: String): Int {
            synchronized(lock) {
                val before = learned.size
                learned.removeAll {
                    it.locale == locale &&
                        it.prefix == prefix &&
                        it.nextWord.equals(nextWord, ignoreCase = true)
                }
                return before - learned.size
            }
        }

        override fun deleteNextWord(locale: String, nextWord: String): Int {
            synchronized(lock) {
                val before = learned.size
                learned.removeAll {
                    it.locale == locale && it.nextWord.equals(nextWord, ignoreCase = true)
                }
                return before - learned.size
            }
        }

        var pruneCallCount = 0
            private set

        var learnCompletedBeforePrune = false
            private set

        override fun prune(
            nowMs: Long,
            staleAfterMs: Long,
            weakCountThreshold: Int,
            maxEntriesPerLocale: Int
        ): Int {
            synchronized(lock) {
                pruneCallCount++
                learnCompletedBeforePrune = learned.isNotEmpty()
            }
            return 0
        }

        override fun clearAll() {
            synchronized(lock) {
                learned.clear()
            }
        }

        fun releaseWrites() {
            releaseWrites.countDown()
        }

        fun learnedWords(): List<String> {
            synchronized(lock) {
                return learned.map { it.nextWord }
            }
        }

        private data class LearnedBigram(
            val locale: String,
            val prefix: String,
            val nextWord: String,
            val nowMs: Long
        )
    }
}
