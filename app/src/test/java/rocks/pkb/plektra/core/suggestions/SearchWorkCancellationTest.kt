package rocks.pkb.plektra.core.suggestions

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CancellationException

class SearchWorkCancellationTest {
    @Test fun cancelledSearchStopsInsideLargeSymSpellBucketAndDoesNotPoisonNextSearch() {
        val spell = SymSpell()
        val terms = (0 until 10000).associate { "abcdef$it" to it }
        spell.loadSerialized(terms, mapOf("abcdef" to terms.keys.toList()))
        var checks = 0
        try {
            SuggestionWorkCancellation.withCheckpoint({
                if (++checks == 25) throw CancellationException("newer input arrived")
            }) { spell.lookup("abcdef") }
            fail("Obsolete search must stop during the bucket, not after all candidates")
        } catch (_: CancellationException) { assertEquals(25, checks) }
        // Cleanup is also required for later searches on a reused worker thread.
        val actual = spell.lookup("abcdef")
        assertFalse(actual.isEmpty())
        assertEquals(actual, SuggestionWorkCancellation.withCheckpoint({}) { spell.lookup("abcdef") })
    }

    @Test fun cancelledEngineDoesNotScoreCandidatesReturnedAfterCancellation() {
        val job = Job()
        var scored = 0
        val repo = object : DictionaryRepository by FakeDictionaryRepository() {
            override val isReady = true
            override fun lookupByPrefixMerged(prefix: String, maxSize: Int): List<DictionaryEntry> {
                job.cancel()
                return listOf(DictionaryEntry("plage", 1000, SuggestionSource.MAIN))
            }
        }
        val engine = SuggestionEngine(repo, Locale.FRENCH, usageCountProvider = { _, _ -> ++scored })
        try {
            SuggestionWorkCancellation.withCheckpoint({ job.ensureActive() }) { engine.suggest("pla") }
            fail("Cancelled results must not be ranked")
        } catch (_: CancellationException) { assertEquals(0, scored) }
    }

    @Test fun checkpointsPreserveRankingNumbersApostrophesAndCompounds() {
        val repo = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
            addTestEntry("rendez-vous", 900, SuggestionSource.USER)
            addTestEntry("rendez", 1000)
            addTestEntry("plage", 1200)
            addTestEntry("plages", 800)
            addTestEntry("aller", 1500)
            addTestEntry("11", 200, SuggestionSource.USER)
        }
        val engine = SuggestionEngine(repo, Locale.FRENCH)
        for (input in listOf("rendez", "pla", "plagr", "j'al", "11")) {
            val expected = engine.suggest(input)
            var checks = 0
            val actual = SuggestionWorkCancellation.withCheckpoint({ checks++ }) { engine.suggest(input) }
            assertTrue(checks > 0)
            assertEquals(expected, actual)
        }
    }

    @Test fun nestedScopesRestorePreviousCheckpointAndSeparateThreadsDoNotShareIt() {
        var outer = 0
        var inner = 0
        SuggestionWorkCancellation.withCheckpoint({ outer++ }) {
            val before = outer
            SuggestionWorkCancellation.withCheckpoint({ inner++ }) { SuggestionWorkCancellation.check() }
            assertEquals(before, outer)
            SuggestionWorkCancellation.check()
            assertEquals(before + 1, outer)
            val thread = Thread { SuggestionWorkCancellation.check() }
            thread.start(); thread.join(5000)
            assertFalse(thread.isAlive)
            assertEquals(before + 1, outer)
        }
        SuggestionWorkCancellation.check()
        assertEquals(2, inner)
    }
}
