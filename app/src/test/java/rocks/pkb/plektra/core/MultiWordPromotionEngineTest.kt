package rocks.pkb.plektra.core.suggestions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MultiWordPromotionEngineTest {

    private lateinit var context: Context
    private lateinit var phraseStore: PhraseUsageStore
    private lateinit var nGramStore: UserNGramStore
    private lateinit var nextWordPredictor: NextWordPredictor
    private lateinit var multiWordStore: MultiWordStore
    private lateinit var engine: MultiWordPromotionEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        context.deleteDatabase("phrase_usage.db")
        context.deleteDatabase("user_ngrams.db")

        File(context.filesDir, "multi_word_sequences.json").delete()
        File(context.filesDir, "multi_word_sequences.json.tmp").delete()

        phraseStore = PhraseUsageStore(context)
        nGramStore = UserNGramStore(context)

        nextWordPredictor = NextWordPredictor(
            store = nGramStore,
            asyncLearning = false
        )

        multiWordStore = MultiWordStore(context)

        engine = MultiWordPromotionEngine(
            phraseUsageRepository = phraseStore,
            nextWordPredictor = nextWordPredictor,
            multiWordStore = multiWordStore
        )
    }

    @After
    fun tearDown() {
        if (::nextWordPredictor.isInitialized) {
            nextWordPredictor.destroy()
        }

        if (::phraseStore.isInitialized) {
            phraseStore.close()
        }

        if (::nGramStore.isInitialized) {
            nGramStore.close()
        }

        if (::multiWordStore.isInitialized) {
            multiWordStore.clearAll()
        }

        if (::context.isInitialized) {
            context.deleteDatabase("phrase_usage.db")
            context.deleteDatabase("user_ngrams.db")
            File(context.filesDir, "multi_word_sequences.json.tmp").delete()
        }
    }

    @Test
    fun phraseEvidenceAloneDoesNotPromote() {
        val locale = Locale.FRENCH
        val words = listOf("à", "la", "maison")

        repeat(8) {
            phraseStore.learn(locale, words)
        }

        val result = engine.promoteEligible(locale)

        assertEquals(1, result.examined)
        assertEquals(0, result.promoted)
        assertNull(multiWordStore.find(locale, words))
    }

    @Test
    fun insufficientNGramEvidenceDoesNotPromote() {
        val locale = Locale.FRENCH
        val words = listOf("à", "la", "maison")

        repeat(8) {
            phraseStore.learn(locale, words)
        }

        repeat(5) {
            nextWordPredictor.learn(locale, "à", "la")
        }

        repeat(4) {
            nextWordPredictor.learn(locale, "la", "maison")
        }

        val result = engine.promoteEligible(locale)

        assertEquals(1, result.examined)
        assertEquals(0, result.promoted)
        assertNull(multiWordStore.find(locale, words))
    }

    @Test
    fun strongPhraseAndNGramEvidencePromotes() {
        val locale = Locale.FRENCH
        val words = listOf("à", "la", "maison")

        repeat(8) {
            phraseStore.learn(locale, words)
        }

        repeat(5) {
            nextWordPredictor.learn(locale, "à", "la")
            nextWordPredictor.learn(locale, "la", "maison")
        }

        val result = engine.promoteEligible(
            locale = locale,
            nowMs = 1234L
        )

        assertEquals(1, result.examined)
        assertEquals(1, result.promoted)

        val promoted = multiWordStore.find(locale, words)

        assertNotNull(promoted)
        assertEquals(words, promoted!!.words)
        assertEquals(8, promoted.phraseCount)
        assertEquals(1234L, promoted.promotedAt)
    }

    @Test
    fun phraseBelowThresholdIsNotEvenCandidate() {
        val locale = Locale.FRENCH
        val words = listOf("à", "la", "maison")

        repeat(7) {
            phraseStore.learn(locale, words)
        }

        repeat(10) {
            nextWordPredictor.learn(locale, "à", "la")
            nextWordPredictor.learn(locale, "la", "maison")
        }

        val result = engine.promoteEligible(locale)

        assertEquals(0, result.examined)
        assertEquals(0, result.promoted)
        assertNull(multiWordStore.find(locale, words))
    }

    @Test
    fun ambiguousNGramEvidenceDoesNotPromote() {
        val locale = Locale.FRENCH
        val words = listOf("à", "la", "maison")

        repeat(8) {
            phraseStore.learn(locale, words)
        }

        // Transition "à → la" : assez fréquente en valeur absolue...
        repeat(5) {
            nextWordPredictor.learn(locale, "à", "la")
        }

        // ...mais seulement 5 occurrences sur 10 = 50 %.
        repeat(5) {
            nextWordPredictor.learn(locale, "à", "mon")
        }

        // La deuxième transition est, elle, très solide.
        repeat(5) {
            nextWordPredictor.learn(locale, "la", "maison")
        }

        val result = engine.promoteEligible(locale)

        assertEquals(1, result.examined)
        assertEquals(0, result.promoted)
        assertNull(multiWordStore.find(locale, words))
    }

    @Test
    fun repeatedPersonalSequencePromotesDespiteLowRelativeNGramConfidence() {
        val locale = Locale.FRENCH
        val words = listOf("je", "voudrais", "visiter")

        // La séquence complète a été répétée suffisamment souvent.
        repeat(8) {
            phraseStore.learn(locale, words)
        }

        // Les deux transitions de la séquence ont elles aussi
        // été observées 8 fois.
        repeat(8) {
            nextWordPredictor.learn(locale, "je", "voudrais")
            nextWordPredictor.learn(locale, "voudrais", "visiter")
        }

        // Mais "je" et "voudrais" ont beaucoup d'autres continuations :
        // la confiance relative de nos transitions reste donc < 60 %.
        repeat(32) {
            nextWordPredictor.learn(locale, "je", "suis")
            nextWordPredictor.learn(locale, "voudrais", "savoir")
        }

        val result = engine.promoteEligible(
            locale = locale,
            nowMs = 1234L
        )

        assertEquals(1, result.examined)
        assertEquals(1, result.promoted)

        val promoted = multiWordStore.find(locale, words)

        assertNotNull(promoted)
        assertEquals(words, promoted!!.words)
        assertEquals(8, promoted.phraseCount)
    }

    @Test
    fun newlyEligiblePhraseIsNotStarvedByOlderMoreFrequentCandidates() {
        val locale = Locale.FRENCH
        val targetWords = listOf("je", "voudrais", "visiter")

        val letters = ('a'..'z').toList()

        // 50 séquences distinctes, entièrement alphabétiques,
        // chacune plus fréquente que la cible.
        repeat(50) { index ->
            val first =
                "ancien" +
                        letters[index / 26] +
                        letters[index % 26]

            val second =
                "mot" +
                        letters[index / 26] +
                        letters[index % 26]

            val words = listOf(first, second)

            repeat(9) {
                phraseStore.learn(locale, words)
            }
        }

        // La cible atteint exactement le seuil.
        repeat(8) {
            phraseStore.learn(locale, targetWords)

            nextWordPredictor.learn(
                locale,
                "je",
                "voudrais"
            )

            nextWordPredictor.learn(
                locale,
                "voudrais",
                "visiter"
            )
        }

        val result = engine.promoteEligible(
            locale = locale,
            nowMs = 1234L
        )

        assertEquals(50, result.examined)

        assertNotNull(
            multiWordStore.find(locale, targetWords)
        )
    }

    @Test
    fun specificEligibleSequenceCanBePromotedOutsideGlobalCandidateLimit() {
        val locale = Locale.FRENCH
        val words = listOf("je", "voudrais", "visiter")

        repeat(8) {
            phraseStore.learn(locale, words)
            nextWordPredictor.learn(locale, "je", "voudrais")
            nextWordPredictor.learn(locale, "voudrais", "visiter")
        }

        val promoted = engine.promoteIfEligible(
            locale = locale,
            words = words,
            nowMs = 1234L
        )

        assertEquals(true, promoted)

        val stored = multiWordStore.find(locale, words)

        assertNotNull(stored)
        assertEquals(8, stored!!.phraseCount)
        assertEquals(1234L, stored.promotedAt)
    }

    @Test
    fun promotedSequenceIsDemotedWhenPhraseEvidenceDisappears() {
        val locale = Locale.FRENCH
        val words = listOf("à", "la", "maison")

        repeat(5) {
            nextWordPredictor.learn(locale, "à", "la")
            nextWordPredictor.learn(locale, "la", "maison")
        }

        multiWordStore.promote(
            locale = locale,
            words = words,
            phraseCount = 8,
            nowMs = 1234L
        )

        assertNotNull(multiWordStore.find(locale, words))

        val result = engine.demoteIneligible(locale)

        assertEquals(1, result.examined)
        assertEquals(1, result.demoted)
        assertNull(multiWordStore.find(locale, words))
    }

    @Test
    fun promotedSequenceRemainsWhenEvidenceIsStillStrong() {
        val locale = Locale.FRENCH
        val words = listOf("à", "la", "maison")

        repeat(8) {
            phraseStore.learn(locale, words)
        }

        repeat(5) {
            nextWordPredictor.learn(locale, "à", "la")
            nextWordPredictor.learn(locale, "la", "maison")
        }

        multiWordStore.promote(
            locale = locale,
            words = words,
            phraseCount = 8,
            nowMs = 1234L
        )

        val result = engine.demoteIneligible(locale)

        assertEquals(1, result.examined)
        assertEquals(0, result.demoted)
        assertNotNull(multiWordStore.find(locale, words))
    }

    @Test
    fun promotedSequenceIsDemotedWhenNGramEvidenceDisappears() {
        val locale = Locale.FRENCH
        val words = listOf("à", "la", "maison")

        repeat(8) {
            phraseStore.learn(locale, words)
        }

        multiWordStore.promote(
            locale = locale,
            words = words,
            phraseCount = 8,
            nowMs = 1234L
        )

        assertNotNull(multiWordStore.find(locale, words))

        val result = engine.demoteIneligible(locale)

        assertEquals(1, result.examined)
        assertEquals(1, result.demoted)
        assertNull(multiWordStore.find(locale, words))
    }

    @Test
    fun demoteIneligibleAllDemotesSequencesAcrossLocales() {
        val french = Locale.FRENCH
        val german = Locale.GERMANY

        val frenchWords = listOf("à", "la", "maison")
        val germanWords = listOf("ich", "bin", "hier")

        multiWordStore.promote(
            locale = french,
            words = frenchWords,
            phraseCount = 8,
            nowMs = 1234L
        )

        multiWordStore.promote(
            locale = german,
            words = germanWords,
            phraseCount = 8,
            nowMs = 1234L
        )

        assertNotNull(
            multiWordStore.find(french, frenchWords)
        )
        assertNotNull(
            multiWordStore.find(german, germanWords)
        )

        val result = engine.demoteIneligibleAll()

        assertEquals(2, result.examined)
        assertEquals(2, result.demoted)

        assertNull(
            multiWordStore.find(french, frenchWords)
        )
        assertNull(
            multiWordStore.find(german, germanWords)
        )
    }
}