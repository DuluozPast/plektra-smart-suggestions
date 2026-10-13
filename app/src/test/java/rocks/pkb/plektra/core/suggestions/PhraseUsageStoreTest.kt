package rocks.pkb.plektra.core.suggestions

import android.content.Context
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PhraseUsageStoreTest {

    private lateinit var context: Context
    private lateinit var store: PhraseUsageStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        context.deleteDatabase("phrase_usage.db")
        store = PhraseUsageStore(context)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("phrase_usage.db")
    }

    @Test
    fun doesNotLearnAdjacentNormalizedDuplicateWords() {
        val locale = Locale.FRENCH

        store.learn(
            locale,
            listOf("le", "château", "chateau")
        )

        assertNull(
            store.getUsage(
                locale,
                listOf("le", "château", "chateau")
            )
        )
    }

    @Test
    fun validRepeatedWordSeparatedByAnotherWordIsStillLearned() {
        val locale = Locale.FRENCH

        store.learn(
            locale,
            listOf("très", "bien", "très")
        )

        assertTrue(
            store.getUsage(
                locale,
                listOf("très", "bien", "très")
            ) != null
        )
    }

    @Test
    fun predictsSingleNextWordFromLongerLearnedPhrase() {
        val locale = Locale.FRENCH

        store.learn(
            locale,
            listOf("je", "visite", "le")
        )

        store.learn(
            locale,
            listOf("je", "visite", "le", "château")
        )

        val predictions = store.predictNext(
            locale = locale,
            contextWords = listOf("je", "visite"),
            limit = 3
        )

        assertTrue(
            predictions.any {
                it.word == "le" &&
                        it.words == listOf("le") &&
                        it.continuation == "le"
            }
        )
    }

    @Test
    fun predictsSingleWordContinuationFromPhraseUsage() {
        store.learn(
            Locale.FRENCH,
            listOf("je", "retrouve", "le", "mammouth"),
            nowMs = 1000L
        )

        store.learn(
            Locale.FRENCH,
            listOf("je", "retrouve", "le", "chantier"),
            nowMs = 2000L
        )

        val predictions = store.predictNext(
            Locale.FRENCH,
            listOf("je", "retrouve", "le"),
            limit = 3
        )

        assertTrue(
            predictions.any {
                it.word == "mammouth" &&
                        it.words == listOf("mammouth") &&
                        it.continuation == "mammouth" &&
                        it.contextLength == 3
            }
        )

        assertTrue(
            predictions.any {
                it.word == "chantier" &&
                        it.words == listOf("chantier") &&
                        it.continuation == "chantier" &&
                        it.contextLength == 3
            }
        )
    }

    @Test
    fun learnsAndIncrementsPhraseUsage() {
        val phrase = listOf("j'arrive", "à", "la", "maison")

        store.learn(Locale.FRENCH, phrase, nowMs = 1000L)
        store.learn(Locale.FRENCH, phrase, nowMs = 2000L)

        val usage = store.getUsage(Locale.FRENCH, phrase)

        assertEquals(2, usage?.count)
        assertEquals(2000L, usage?.lastUsed)
        assertEquals(phrase, usage?.words)
    }

    @Test
    fun keepsLanguagesSeparate() {
        val phrase = listOf("la", "casa")

        store.learn(Locale.FRENCH, phrase, nowMs = 1000L)

        assertEquals(1, store.getUsage(Locale.FRENCH, phrase)?.count)
        assertNull(store.getUsage(Locale("es", "ES"), phrase))
    }

    @Test
    fun ignoresSequencesOutsideTwoToFiveWords() {
        store.learn(Locale.FRENCH, listOf("bonjour"), nowMs = 1000L)

        store.learn(
            Locale.FRENCH,
            listOf("un", "deux", "trois", "quatre", "cinq", "six"),
            nowMs = 1000L
        )

        assertNull(store.getUsage(Locale.FRENCH, listOf("bonjour")))

        assertNull(
            store.getUsage(
                Locale.FRENCH,
                listOf("un", "deux", "trois", "quatre", "cinq", "six")
            )
        )
    }

    @Test
    fun normalizedVariantsShareUsage() {
        store.learn(
            Locale.FRENCH,
            listOf("derrière", "la", "maison"),
            nowMs = 1000L
        )

        store.learn(
            Locale.FRENCH,
            listOf("derriere", "la", "maison"),
            nowMs = 2000L
        )

        val usage = store.getUsage(
            Locale.FRENCH,
            listOf("derrière", "la", "maison")
        )

        assertEquals(2, usage?.count)
        assertEquals(2000L, usage?.lastUsed)
    }

    @Test
    fun predictsNextWordFromLearnedPhrase() {
        store.learn(
            Locale.FRENCH,
            listOf("j'arrive", "à", "la", "maison"),
            nowMs = 1000L
        )

        val predictions = store.predictNext(
            Locale.FRENCH,
            listOf("j'arrive", "à", "la"),
            limit = 3
        )

        assertEquals(1, predictions.size)
        assertEquals("maison", predictions.first().word)
        assertEquals(1, predictions.first().count)
        assertEquals(1000L, predictions.first().lastUsed)
    }

    @Test
    fun ranksNextWordPredictionsByUsageCount() {
        repeat(3) {
            store.learn(
                Locale.FRENCH,
                listOf("je", "vais", "à", "la", "maison"),
                nowMs = 1000L + it
            )
        }

        repeat(5) {
            store.learn(
                Locale.FRENCH,
                listOf("je", "vais", "à", "la", "gare"),
                nowMs = 2000L + it
            )
        }

        store.learn(
            Locale.FRENCH,
            listOf("je", "vais", "à", "la", "plage"),
            nowMs = 3000L
        )

        val predictions = store.predictNext(
            Locale.FRENCH,
            listOf("je", "vais", "à", "la"),
            limit = 3
        )

        assertEquals(
            listOf("gare", "maison", "plage"),
            predictions.map { it.word }
        )

        assertEquals(
            listOf(5, 3, 1),
            predictions.map { it.count }
        )
    }
    @Test
    fun predictsNextWordFromContextSuffix() {
        store.learn(
            Locale.FRENCH,
            listOf("je", "vais", "à", "la", "maison"),
            nowMs = 1000L
        )

        val predictions = store.predictNext(
            Locale.FRENCH,
            listOf("à", "la"),
            limit = 3
        )

        assertEquals(1, predictions.size)
        assertEquals("maison", predictions.first().word)
    }
    @Test
    fun longerContextTakesPriorityOverMoreFrequentShorterContext() {
        store.learn(
            Locale.FRENCH,
            listOf("la", "gare"),
            nowMs = 1000L
        )
        store.learn(
            Locale.FRENCH,
            listOf("la", "gare"),
            nowMs = 1001L
        )
        store.learn(
            Locale.FRENCH,
            listOf("la", "gare"),
            nowMs = 1002L
        )
        store.learn(
            Locale.FRENCH,
            listOf("la", "gare"),
            nowMs = 1003L
        )
        store.learn(
            Locale.FRENCH,
            listOf("la", "gare"),
            nowMs = 1004L
        )

        store.learn(
            Locale.FRENCH,
            listOf("à", "la", "maison"),
            nowMs = 2000L
        )

        val predictions = store.predictNext(
            Locale.FRENCH,
            listOf("à", "la"),
            limit = 3
        )

        assertEquals("maison", predictions.first().word)
        assertEquals(2, predictions.first().contextLength)

        assertEquals("gare", predictions[1].word)
        assertEquals(1, predictions[1].contextLength)
        assertEquals(5, predictions[1].count)
    }

    @Test
    fun prune_removesWeakStalePhrase() {
        val phrase = listOf("je", "vais", "partir")

        store.learn(
            Locale.FRENCH,
            phrase,
            nowMs = 100L
        )

        val deleted = store.prune(
            nowMs = 10_000L,
            staleAfterMs = 5_000L,
            weakCountThreshold = 2,
            maxEntriesPerLocale = 100
        )

        assertEquals(1, deleted)
        assertNull(store.getUsage(Locale.FRENCH, phrase))
    }

    @Test
    fun prune_keepsWeakRecentPhrase() {
        val phrase = listOf("je", "vais", "partir")

        store.learn(
            Locale.FRENCH,
            phrase,
            nowMs = 9_000L
        )

        val deleted = store.prune(
            nowMs = 10_000L,
            staleAfterMs = 5_000L,
            weakCountThreshold = 2,
            maxEntriesPerLocale = 100
        )

        assertEquals(0, deleted)
        assertEquals(
            1,
            store.getUsage(Locale.FRENCH, phrase)?.count
        )
    }

    @Test
    fun prune_keepsStrongStalePhrase() {
        val phrase = listOf("je", "vais", "partir")

        repeat(3) { index ->
            store.learn(
                Locale.FRENCH,
                phrase,
                nowMs = 100L + index
            )
        }

        val deleted = store.prune(
            nowMs = 10_000L,
            staleAfterMs = 5_000L,
            weakCountThreshold = 2,
            maxEntriesPerLocale = 100
        )

        assertEquals(0, deleted)
        assertEquals(
            3,
            store.getUsage(Locale.FRENCH, phrase)?.count
        )
    }

    @Test
    fun prune_enforcesCapPerLocale() {
        val weak = listOf("je", "vais", "partir")
        val medium = listOf("tu", "vas", "rester")
        val strong = listOf("nous", "allons", "manger")

        store.learn(
            Locale.FRENCH,
            weak,
            nowMs = 300L
        )

        repeat(2) {
            store.learn(
                Locale.FRENCH,
                medium,
                nowMs = 200L + it
            )
        }

        repeat(3) {
            store.learn(
                Locale.FRENCH,
                strong,
                nowMs = 100L + it
            )
        }

        val deleted = store.prune(
            nowMs = 1_000L,
            staleAfterMs = 10_000L,
            weakCountThreshold = 0,
            maxEntriesPerLocale = 2
        )

        assertEquals(1, deleted)
        assertNull(store.getUsage(Locale.FRENCH, weak))
        assertEquals(
            2,
            store.getUsage(Locale.FRENCH, medium)?.count
        )
        assertEquals(
            3,
            store.getUsage(Locale.FRENCH, strong)?.count
        )
    }

    @Test
    fun prune_capUsesRecencyWhenCountsAreEqual() {
        val older = listOf("je", "vais", "partir")
        val newer = listOf("tu", "vas", "rester")

        store.learn(
            Locale.FRENCH,
            older,
            nowMs = 100L
        )

        store.learn(
            Locale.FRENCH,
            newer,
            nowMs = 200L
        )

        val deleted = store.prune(
            nowMs = 1_000L,
            staleAfterMs = 10_000L,
            weakCountThreshold = 0,
            maxEntriesPerLocale = 1
        )

        assertEquals(1, deleted)
        assertNull(store.getUsage(Locale.FRENCH, older))
        assertEquals(
            1,
            store.getUsage(Locale.FRENCH, newer)?.count
        )
    }

    @Test
    fun prune_appliesCapIndependentlyPerLocale() {
        val frOlder = listOf("je", "vais", "partir")
        val frNewer = listOf("tu", "vas", "rester")

        val enOlder = listOf("i", "will", "leave")
        val enNewer = listOf("you", "will", "stay")

        store.learn(
            Locale.FRENCH,
            frOlder,
            nowMs = 100L
        )
        store.learn(
            Locale.FRENCH,
            frNewer,
            nowMs = 200L
        )

        store.learn(
            Locale.ENGLISH,
            enOlder,
            nowMs = 100L
        )
        store.learn(
            Locale.ENGLISH,
            enNewer,
            nowMs = 200L
        )

        val deleted = store.prune(
            nowMs = 1_000L,
            staleAfterMs = 10_000L,
            weakCountThreshold = 0,
            maxEntriesPerLocale = 1
        )

        assertEquals(2, deleted)

        assertNull(
            store.getUsage(Locale.FRENCH, frOlder)
        )
        assertEquals(
            1,
            store.getUsage(Locale.FRENCH, frNewer)?.count
        )

        assertNull(
            store.getUsage(Locale.ENGLISH, enOlder)
        )
        assertEquals(
            1,
            store.getUsage(Locale.ENGLISH, enNewer)?.count
        )
    }

    @Test
    fun prune_rebuildsPredictionIndexWithoutRemovedPhrase() {
        val removedPhrase =
            listOf("je", "vais", "maison")

        val retainedPhrase =
            listOf("je", "vais", "gare")

        store.learn(
            Locale.FRENCH,
            removedPhrase,
            nowMs = 100L
        )

        repeat(3) { index ->
            store.learn(
                Locale.FRENCH,
                retainedPhrase,
                nowMs = 200L + index
            )
        }

        val before = store.predictNext(
            locale = Locale.FRENCH,
            contextWords = listOf("je", "vais"),
            limit = 3
        )

        assertTrue(
            before.any { it.word == "maison" }
        )
        assertTrue(
            before.any { it.word == "gare" }
        )

        val deleted = store.prune(
            nowMs = 10_000L,
            staleAfterMs = 5_000L,
            weakCountThreshold = 1,
            maxEntriesPerLocale = 100
        )

        assertEquals(1, deleted)

        val after = store.predictNext(
            locale = Locale.FRENCH,
            contextWords = listOf("je", "vais"),
            limit = 3
        )

        assertFalse(
            after.any { it.word == "maison" }
        )

        assertTrue(
            after.any {
                it.word == "gare" &&
                        it.count == 3
            }
        )
    }
}