package rocks.pkb.plektra.core.suggestions

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.AutoCorrector
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.os.Looper
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SuggestionControllerReplacementReadinessTest {

    private lateinit var context: Context
    private val controllers = mutableListOf<SuggestionController>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setAutoCorrectEnabledLanguages(context, setOf("en"))
        AutoCorrector.loadCorrections(context.assets, context)
    }

    @After
    fun tearDown() {
        controllers.forEach(SuggestionController::destroy)
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun builtInReplacementDoesNotWaitForDictionaryWhenSuggestionsAreDisabled() {
        val repository = ControlledDictionaryRepository(ready = false, loadFails = false)
        val controller = newController(
            repository,
            experimentalSuggestionsEnabled = false,
            suggestionsEnabled = false
        )
        val input = FakeInputConnection(context, "im")

        val result = controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)

        assertTrue(result.replaced)
        assertEquals("I'm ", input.text)
    }

    @Test
    fun customReplacementDoesNotWaitForDictionaryWhenSuggestionsAreDisabled() {
        SettingsManager.saveCustomAutoCorrections(context, "en", mapOf("brb" to "be right back"))
        AutoCorrector.loadCorrections(context.assets, context)
        val controller = newController(
            ControlledDictionaryRepository(ready = false, loadFails = false),
            experimentalSuggestionsEnabled = false,
            suggestionsEnabled = false
        )
        val input = FakeInputConnection(context, "brb")

        val result = controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)

        assertTrue(result.replaced)
        assertEquals("be right back ", input.text)
    }

    @Test
    fun replacementSurvivesDictionaryLoadFailure() {
        val controller = newController(
            ControlledDictionaryRepository(ready = false, loadFails = true),
            experimentalSuggestionsEnabled = false,
            suggestionsEnabled = false
        )
        val input = FakeInputConnection(context, "ill")

        val result = controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)

        assertTrue(result.replaced)
        assertEquals("I'll ", input.text)
    }

    @Test
    fun warmDictionaryUsesTheSameExactReplacementPath() {
        val controller = newController(
            ControlledDictionaryRepository(ready = true, loadFails = false),
            experimentalSuggestionsEnabled = false,
            suggestionsEnabled = false
        )
        val input = FakeInputConnection(context, "didnt")

        val result = controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)

        assertTrue(result.replaced)
        assertEquals("didn't ", input.text)
    }

    @Test
    fun suggestionsEnabledOnlyAddsTrackerUpdatesAndDoesNotChangeReplacementSemantics() {
        val controller = newController(
            ControlledDictionaryRepository(ready = false, loadFails = false),
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true
        )
        val input = FakeInputConnection(context, "ill")
        "ill".forEach { controller.onCharacterCommitted(it.toString(), input) }

        val result = controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)

        assertTrue(result.replaced)
        assertEquals("I'll ", input.text)
    }

    @Test
    fun sentenceBoundaryPreventsPhraseLearningAcrossSuccessiveSentences() {
        val phraseUsageRepository = FakePhraseUsageRepository()

        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = true,
            phraseUsageRepository = phraseUsageRepository,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")

        fun completeWord(word: String) {
            input.appendFromEditor(word)
            controller.onBoundaryKey(
                KeyEvent.KEYCODE_SPACE,
                null,
                input
            )
        }

        // Première phrase : "je visite le château."
        completeWord("je")
        completeWord("visite")
        completeWord("le")

        input.appendFromEditor("château")
        controller.onBoundaryKey(
            KeyEvent.KEYCODE_PERIOD,
            null,
            input
        )

        // Deuxième phrase : "Je visite le château."
        completeWord("Je")
        completeWord("visite")
        completeWord("le")

        input.appendFromEditor("château")
        controller.onBoundaryKey(
            KeyEvent.KEYCODE_PERIOD,
            null,
            input
        )

        val learned = phraseUsageRepository.learnedPhrases.map { it.words }

        // Les associations internes aux phrases doivent exister.
        assertTrue(listOf("je", "visite") in learned)
        assertTrue(listOf("visite", "le") in learned)
        assertTrue(listOf("le", "château") in learned)

        // Mais aucune association ne doit traverser le point.
        assertFalse(listOf("château", "Je") in learned)
        assertFalse(listOf("le", "château", "Je") in learned)
        assertFalse(listOf("château", "Je", "visite") in learned)
    }

    @Test
    fun cursorMoveAfterHardBoundaryPreventsPhraseLearningAcrossSentences() {
        val phraseUsageRepository = FakePhraseUsageRepository()
        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }
        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = true,
            phraseUsageRepository = phraseUsageRepository,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")

        fun completeWord(word: String) {
            input.appendFromEditor(word)
            controller.onBoundaryKey(
                KeyEvent.KEYCODE_SPACE,
                null,
                input
            )
        }

        completeWord("je")
        completeWord("visite")
        completeWord("le")

        input.appendFromEditor("château")
        controller.onBoundaryKey(
            KeyEvent.KEYCODE_PERIOD,
            null,
            input
        )

        // Simule la mise à jour de curseur/sélection envoyée par l'éditeur
        // après la ponctuation.
        controller.onCursorMoved(input)

        completeWord("Je")
        completeWord("visite")

        val learned = phraseUsageRepository.learnedPhrases.map { it.words }

        assertTrue(listOf("je", "visite") in learned)
        assertTrue(listOf("visite", "le") in learned)
        assertTrue(listOf("le", "château") in learned)

        assertFalse(listOf("château", "Je") in learned)
        assertFalse(listOf("le", "château", "Je") in learned)
        assertFalse(listOf("château", "Je", "visite") in learned)
    }

    @Test
    fun contextResetPreventsPhraseLearningAcrossContexts() {
        val phraseUsageRepository = FakePhraseUsageRepository()

        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = true,
            phraseUsageRepository = phraseUsageRepository,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")

        fun completeWord(word: String) {
            input.appendFromEditor(word)
            controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)
        }

        completeWord("le")
        completeWord("château")

        controller.onContextReset()

        completeWord("je")
        completeWord("visite")

        val learned = phraseUsageRepository.learnedPhrases.map { it.words }

        assertTrue(listOf("le", "château") in learned)
        assertTrue(listOf("je", "visite") in learned)

        assertFalse(listOf("château", "je") in learned)
        assertFalse(listOf("le", "château", "je") in learned)
        assertFalse(listOf("château", "je", "visite") in learned)
    }

    @Test
    fun multipleBoundariesRemainIndependentWhileDictionaryIsLoading() {
        val controller = newController(
            ControlledDictionaryRepository(ready = false, loadFails = false),
            experimentalSuggestionsEnabled = false,
            suggestionsEnabled = false
        )
        val input = FakeInputConnection(context, "im")

        assertTrue(controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input).replaced)
        input.appendFromEditor("ill")
        assertTrue(controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input).replaced)

        assertEquals("I'm I'll ", input.text)
    }

    @Test
    fun readinessTransitionDoesNotChangeExactReplacementSemantics() {
        val repository = ControlledDictionaryRepository(ready = false, loadFails = false)
        val controller = newController(
            repository,
            experimentalSuggestionsEnabled = false,
            suggestionsEnabled = false
        )
        val input = FakeInputConnection(context, "im")

        assertTrue(controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input).replaced)
        repository.isReady = true
        input.appendFromEditor("didnt")
        assertTrue(controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input).replaced)

        assertEquals("I'm didn't ", input.text)
    }

    @Test
    fun learnsPhraseSequencesFromCompletedWords() {
        val phraseUsageRepository = FakePhraseUsageRepository()

        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = true,
            phraseUsageRepository = phraseUsageRepository,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")

        fun completeWord(word: String) {
            input.appendFromEditor(word)
            controller.onBoundaryKey(
                KeyEvent.KEYCODE_SPACE,
                null,
                input
            )
        }

        completeWord("j'arrive")
        completeWord("à")
        completeWord("la")
        completeWord("maison")

        val learned = phraseUsageRepository.learnedPhrases.map { it.words }

        assertEquals(
            listOf(
                listOf("j'arrive", "à"),
                listOf("à", "la"),
                listOf("j'arrive", "à", "la"),
                listOf("la", "maison"),
                listOf("à", "la", "maison"),
                listOf("j'arrive", "à", "la", "maison")
            ),
            learned
        )
    }

    @Test
    fun phrasePredictionUsesCompletedWordsAsContext() {
        val phraseUsageRepository = FakePhraseUsageRepository().apply {
            predictions = listOf(
                PhraseUsageStore.Prediction(
                    word = "maison",
                    count = 5,
                    lastUsed = 1000L,
                    contextLength = 3
                )
            )
        }

        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = true,
            phraseUsageRepository = phraseUsageRepository,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")

        fun completeWord(word: String) {
            input.appendFromEditor(word)
            controller.onBoundaryKey(
                KeyEvent.KEYCODE_SPACE,
                null,
                input
            )
        }

        completeWord("j'arrive")
        completeWord("à")
        completeWord("la")

        assertEquals(
            listOf("j'arrive", "à", "la"),
            phraseUsageRepository.predictionRequests.last().second
        )

        assertTrue(
            controller.currentSuggestions().any {
                it.candidate == "maison" &&
                        it.kind == SuggestionKind.NEXT_WORD &&
                        it.source == SuggestionSource.USER
            }
        )
    }

    @Test
    fun multiWordContinuationSuppressesRedundantSingleWordPrediction() {
        val phraseUsageRepository = FakePhraseUsageRepository().apply {
            predictions = listOf(
                PhraseUsageStore.Prediction(
                    word = "le",
                    count = 5,
                    lastUsed = 1000L,
                    contextLength = 2
                )
            )
        }

        val multiWordStore = MultiWordStore(context).apply {
            clearAll()
            promote(
                locale = Locale.FRENCH,
                words = listOf("le", "château"),
                phraseCount = 8,
                nowMs = 1000L
            )
        }

        val userNGramRepository = FakeUserNGramRepository().apply {
            predictions = listOf(
                UserNGramStore.Prediction(
                    word = "le",
                    count = 100,
                    lastUsed = 2000L
                )
            )
        }

        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = true,
            phraseUsageRepository = phraseUsageRepository,
            userNGramRepository = userNGramRepository,
            multiWordStore = multiWordStore,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")

        fun completeWord(word: String) {
            input.appendFromEditor(word)
            controller.onBoundaryKey(
                KeyEvent.KEYCODE_SPACE,
                null,
                input
            )
        }

        completeWord("je")
        completeWord("visite")

        val suggestions = controller.currentSuggestions()

        assertTrue(
            suggestions.any {
                it.candidate == "le château"
            }
        )

        assertFalse(
            suggestions.any {
                it.candidate == "le"
            }
        )
    }

    @Test
    fun singleWordPredictionRemainsWhenNoMultiWordContinuationSharesItsPrefix() {
        val phraseUsageRepository = FakePhraseUsageRepository().apply {
            predictions = listOf(
                PhraseUsageStore.Prediction(
                    word = "maison",
                    words = listOf("maison", "blanche"),
                    count = 5,
                    lastUsed = 1000L,
                    contextLength = 2
                )
            )
        }

        val userNGramRepository = FakeUserNGramRepository().apply {
            predictions = listOf(
                UserNGramStore.Prediction(
                    word = "le",
                    count = 100,
                    lastUsed = 2000L
                )
            )
        }

        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = true,
            phraseUsageRepository = phraseUsageRepository,
            userNGramRepository = userNGramRepository,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")

        fun completeWord(word: String) {
            input.appendFromEditor(word)
            controller.onBoundaryKey(
                KeyEvent.KEYCODE_SPACE,
                null,
                input
            )
        }

        completeWord("je")
        completeWord("visite")

        val suggestions = controller.currentSuggestions()

        assertTrue(
            suggestions.any {
                it.candidate == "le"
            }
        )
    }

    @Test
    fun phrasePredictionCanExposeMultiWordContinuation() {
        val phraseUsageRepository = FakePhraseUsageRepository().apply {
            predictions = listOf(
                PhraseUsageStore.Prediction(
                    word = "le",
                    count = 5,
                    lastUsed = 1000L,
                    contextLength = 2
                )
            )
        }

        val multiWordStore = MultiWordStore(context).apply {
            clearAll()
            promote(
                locale = Locale.FRENCH,
                words = listOf("le", "mammouth"),
                phraseCount = 8,
                nowMs = 1000L
            )
        }

        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = true,
            phraseUsageRepository = phraseUsageRepository,
            multiWordStore = multiWordStore,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")

        fun completeWord(word: String) {
            input.appendFromEditor(word)
            controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)
        }

        completeWord("je")
        completeWord("retrouve")

        assertEquals(
            listOf("je", "retrouve"),
            phraseUsageRepository.predictionRequests.last().second
        )

        assertTrue(
            controller.currentSuggestions().any {
                it.candidate == "le mammouth" &&
                        it.kind == SuggestionKind.NEXT_WORD &&
                        it.source == SuggestionSource.USER
            }
        )
    }

@Test
fun multiWordPredictionSuppressesRedundantSingleWordPrefixFromNextWordPredictor() {
    val phraseUsageRepository = FakePhraseUsageRepository().apply {
        predictions = listOf(
            PhraseUsageStore.Prediction(
                word = "le",
                words = listOf("le"),
                count = 1,
                lastUsed = 1000L,
                contextLength = 2
            )
        )
    }

    val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
        isReady = true
    }

    val userNGramRepository = FakeUserNGramRepository().apply {
        predictions = listOf(
            UserNGramStore.Prediction(
                word = "le",
                count = 100,
                lastUsed = 2000L
            ),
            UserNGramStore.Prediction(
                word = "les",
                count = 50,
                lastUsed = 1500L
            )
        )
    }

    val multiWordStore = MultiWordStore(context)
    multiWordStore.clearAll()
    multiWordStore.promote(
        locale = Locale.FRENCH,
        words = listOf("le", "château"),
        phraseCount = 8,
        nowMs = 1000L
    )

    val controller = newController(
        repository = repository,
        experimentalSuggestionsEnabled = true,
        suggestionsEnabled = true,
        multiWordSuggestionsEnabled = true,
        phraseUsageRepository = phraseUsageRepository,
        userNGramRepository = userNGramRepository,
        multiWordStore = multiWordStore,
        locale = Locale.FRENCH
    )

    val input = FakeInputConnection(context, "")

    fun completeWord(word: String) {
        input.appendFromEditor(word)
        controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )
    }

    completeWord("Je")
    completeWord("visite")

    val suggestions = controller.currentSuggestions()

    assertTrue(suggestions.any { it.candidate == "le château" })
    assertTrue(suggestions.any { it.candidate == "les" })
    assertFalse(suggestions.any { it.candidate == "le" })
}

    @Test
    fun multiWordSuggestionCommitPreservesPhraseContextForNextPrediction() {
        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val phraseUsageRepository = FakePhraseUsageRepository()

        val multiWordStore = MultiWordStore(context).apply {
            clearAll()
            promote(
                locale = Locale.FRENCH,
                words = listOf("demain", "matin"),
                phraseCount = 8,
                nowMs = 1000L
            )
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = true,
            phraseUsageRepository = phraseUsageRepository,
            multiWordStore = multiWordStore,
            locale = Locale.FRENCH
        )

        controller.onSuggestionCommitted("le château")

        assertTrue(
            phraseUsageRepository.learnedPhrases.any {
                it.locale == Locale.FRENCH &&
                        it.words == listOf("le", "château")
            }
        )

        phraseUsageRepository.predictions = listOf(
            PhraseUsageStore.Prediction(
                word = "demain",
                words = listOf("demain"),
                count = 10,
                lastUsed = 1000L,
                contextLength = 2
            )
        )

        val input = FakeInputConnection(
            context,
            "le château "
        )

        controller.readInitialContext(input)
        controller.onCursorMoved(input)

        shadowOf(Looper.getMainLooper()).idleFor(
            1,
            TimeUnit.SECONDS
        )

        assertTrue(
            phraseUsageRepository.predictionRequests.any { (_, contextWords) ->
                contextWords.takeLast(2) == listOf("le", "château")
            }
        )

        assertTrue(
            controller.currentSuggestions().any {
                it.candidate == "demain matin"
            }
        )
    }

    @Test
    fun nextWordPredictionsAreNotFilledWithStarterWords() {
        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val userNGramRepository = FakeUserNGramRepository().apply {
            predictions = listOf(
                UserNGramStore.Prediction(
                    word = "si",
                    count = 10,
                    lastUsed = 2000L
                ),
                UserNGramStore.Prediction(
                    word = "aussi",
                    count = 5,
                    lastUsed = 1000L
                )
            )
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            userNGramRepository = userNGramRepository,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")
        input.appendFromEditor("savoir")

        controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )

        val suggestions = controller.currentSuggestions()

        assertTrue(suggestions.any { it.candidate == "si" })
        assertTrue(suggestions.any { it.candidate == "aussi" })
        assertTrue(suggestions.all { it.kind == SuggestionKind.NEXT_WORD })
    }

    @Test
    fun noNextWordPredictionDoesNotFallBackToStarterWords() {
        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")
        input.appendFromEditor("ferait")

        controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )

        val suggestions = controller.currentSuggestions()

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun cursorMoveDoesNotClearSuggestionsWhenDictionaryIsTemporarilyNotReady() {
        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val phraseUsageRepository = FakePhraseUsageRepository().apply {
            predictions = listOf(
                PhraseUsageStore.Prediction(
                    word = "le",
                    words = listOf("le"),
                    count = 10,
                    lastUsed = 1000L,
                    contextLength = 2
                )
            )
        }

        val multiWordStore = MultiWordStore(context).apply {
            clearAll()
            promote(
                locale = Locale.FRENCH,
                words = listOf("le", "château"),
                phraseCount = 8,
                nowMs = 1000L
            )
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = true,
            phraseUsageRepository = phraseUsageRepository,
            multiWordStore = multiWordStore,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")

        fun completeWord(word: String) {
            input.appendFromEditor(word)
            controller.onBoundaryKey(
                KeyEvent.KEYCODE_SPACE,
                null,
                input
            )
        }

        completeWord("Je")
        completeWord("visite")

        assertTrue(
            controller.currentSuggestions().any {
                it.candidate == "le château"
            }
        )

        repository.isReady = false

        controller.onCursorMoved(input)

        shadowOf(Looper.getMainLooper()).idleFor(
            1,
            TimeUnit.SECONDS
        )

        assertTrue(
            controller.currentSuggestions().any {
                it.candidate == "le château"
            }
        )
    }

    @Test
    fun committedMultiWordSuggestionUpdatesPhraseContextWordByWord() {
        val phraseUsageRepository = FakePhraseUsageRepository()

        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = true,
            phraseUsageRepository = phraseUsageRepository,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")

        fun completeWord(word: String) {
            input.appendFromEditor(word)
            controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)
        }

        completeWord("je")
        completeWord("retrouve")

        controller.onSuggestionCommitted("le mammouth")

        assertEquals(
            listOf("je", "retrouve", "le", "mammouth"),
            phraseUsageRepository.learnedPhrases.last().words
        )
    }

    @Test
    fun committedMultiWordSuggestionIsUsedAsContextForNextPrediction() {
        val phraseUsageRepository = FakePhraseUsageRepository()

        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = true,
            phraseUsageRepository = phraseUsageRepository,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")

        fun completeWord(word: String) {
            input.appendFromEditor(word)
            controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)
        }

        completeWord("je")
        completeWord("retrouve")

        controller.onSuggestionCommitted("le mammouth")

        // Simule l'état réel après le clic :
        // la suggestion a été insérée et se termine par une espace.
        input.appendFromEditor("le mammouth ")

        controller.readInitialContext(input)

        // Une nouvelle frontière douce doit utiliser "mammouth"
        // comme dernier mot et toute la phrase comme contexte.
        controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )

        assertEquals(
            listOf("je", "retrouve", "le", "mammouth"),
            phraseUsageRepository.predictionRequests.last().second
        )
    }

    @Test
    fun longerPhraseContextOutranksMoreFrequentNextWordPrediction() {
        val phraseUsageRepository = FakePhraseUsageRepository().apply {
            predictions = listOf(
                PhraseUsageStore.Prediction(
                    word = "maison",
                    count = 1,
                    lastUsed = 1000L,
                    contextLength = 2
                )
            )
        }

        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val userNGramRepository = FakeUserNGramRepository().apply {
            predictions = listOf(
                UserNGramStore.Prediction(
                    word = "gare",
                    count = 100,
                    lastUsed = 2000L
                )
            )
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = true,
            phraseUsageRepository = phraseUsageRepository,
            userNGramRepository = userNGramRepository,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")

        fun completeWord(word: String) {
            input.appendFromEditor(word)
            controller.onBoundaryKey(
                KeyEvent.KEYCODE_SPACE,
                null,
                input
            )
        }

        completeWord("à")
        completeWord("la")

        val suggestions = controller.currentSuggestions()

        assertEquals("maison", suggestions[0].candidate)
        assertEquals("gare", suggestions[1].candidate)
        assertEquals(SuggestionKind.NEXT_WORD, suggestions[0].kind)
        assertEquals(SuggestionSource.USER, suggestions[0].source)
    }

    @Test
    fun sentenceBoundaryResetsPhraseLearningContext() {
        val phraseUsageRepository = FakePhraseUsageRepository()

        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = true,
            phraseUsageRepository = phraseUsageRepository,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")

        input.appendFromEditor("je")
        controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )

        input.appendFromEditor("rentre")
        controller.onBoundaryKey(
            KeyEvent.KEYCODE_PERIOD,
            null,
            input
        )

        input.appendFromEditor("demain")
        controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )

        input.appendFromEditor("matin")
        controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )

        val learned = phraseUsageRepository.learnedPhrases.map { it.words }

        assertTrue(listOf("je", "rentre") in learned)
        assertTrue(listOf("demain", "matin") in learned)

        assertFalse(listOf("rentre", "demain") in learned)
        assertFalse(listOf("rentre", "demain", "matin") in learned)
    }

    @Test
    fun replacementWorksImmediatelyAfterControllerRebind() {
        newController(
            ControlledDictionaryRepository(ready = true, loadFails = false),
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true
        ).destroy()
        val reboundController = newController(
            ControlledDictionaryRepository(ready = false, loadFails = false),
            experimentalSuggestionsEnabled = false,
            suggestionsEnabled = false
        )
        val input = FakeInputConnection(context, "ill")

        val result = reboundController.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)

        assertTrue(result.replaced)
        assertEquals("I'll ", input.text)
    }

    @Test
    fun enterBoundaryDoesNotWaitForDictionaryReadiness() {
        val controller = newController(
            ControlledDictionaryRepository(ready = false, loadFails = false),
            experimentalSuggestionsEnabled = false,
            suggestionsEnabled = false
        )
        val input = FakeInputConnection(context, "didnt")

        val result = controller.onBoundaryKey(KeyEvent.KEYCODE_ENTER, null, input)

        assertTrue(result.replaced)
        assertEquals("didn't\n", input.text)
    }

    @Test
    fun disabledLanguageDoesNotCreateAnEnglishReplacement() {
        SettingsManager.setAutoCorrectEnabledLanguages(context, setOf("de"))
        AutoCorrector.loadCorrections(context.assets, context)
        val controller = newController(
            ControlledDictionaryRepository(ready = false, loadFails = false),
            experimentalSuggestionsEnabled = false,
            suggestionsEnabled = false
        )
        val input = FakeInputConnection(context, "im")

        val result = controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)

        assertFalse(result.replaced)
        assertEquals("im ", input.text)
    }

    @Test
    fun readinessIndependentReplacementStillSupportsUndoWhenSuggestionsAreEnabled() {
        val controller = newController(
            ControlledDictionaryRepository(ready = false, loadFails = false),
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            autoReplaceOnSpaceEnter = true
        )
        val input = FakeInputConnection(context, "im")

        assertTrue(controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input).replaced)
        assertTrue(controller.handleBackspaceUndo(KeyEvent.KEYCODE_DEL, input))

        assertEquals("im", input.text)
    }

    @Test
    fun replacementWhileRepositoryIsUnreadyIsIndependentOfBothSuggestionToggles() {
        val combinations = listOf(
            false to false,
            false to true,
            true to false,
            true to true
        )

        combinations.forEach { (experimentalEnabled, normalEnabled) ->
            val repository = ControlledDictionaryRepository(ready = false, loadFails = false)
            val controller = newController(
                repository = repository,
                experimentalSuggestionsEnabled = experimentalEnabled,
                suggestionsEnabled = normalEnabled
            )
            val input = FakeInputConnection(context, "didnt")

            val result = controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)

            val combination =
                "experimental_suggestions_enabled=$experimentalEnabled, suggestions_enabled=$normalEnabled"
            assertTrue(combination, result.replaced)
            assertEquals(combination, "didn't ", input.text)
            assertFalse(combination, repository.isReady)
        }
    }

    @Test
    fun replacementIsImmediateWhileBlockedDictionaryLoadRemainsCancellable() {
        val repository = BlockingDictionaryRepository()
        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = false,
            suggestionsEnabled = true
        )
        val input = FakeInputConnection(context, "im")

        val result = controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)

        assertTrue(result.replaced)
        assertEquals("I'm ", input.text)
        assertTrue(repository.awaitLoadStarted())

        controller.destroy()

        assertTrue(repository.awaitLoadCancellation())
        assertFalse(repository.isReady)
    }
    @Test
    fun completedWordIsLearnedByWordUsageRepository() {
        val repository = FakeDictionaryRepository().apply {
            isReady = true
        }
        val wordUsageRepository = FakeWordUsageRepository()

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            wordUsageRepository = wordUsageRepository
        )

        val input = FakeInputConnection(context, "hello")

        controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )

        assertEquals("hello", wordUsageRepository.learnedWord)
        assertEquals(Locale.ENGLISH.language, wordUsageRepository.learnedLocale?.language)
    }
    @Test
    fun autoReplacedWordIsLearnedInsteadOfOriginalWord() {
        val repository = FakeDictionaryRepository().apply {
            isReady = true
        }
        val wordUsageRepository = FakeWordUsageRepository()

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            wordUsageRepository = wordUsageRepository
        )

        val input = FakeInputConnection(context, "im")

        val result = controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )

        assertTrue(result.replaced)
        assertEquals("I'm ", input.text)
        assertEquals("I'm", wordUsageRepository.learnedWord)
        assertEquals(Locale.ENGLISH.language, wordUsageRepository.learnedLocale?.language)
    }
    @Test
    fun shortFrenchWordIsNotAutoReplacedByAccentedVariant() {
        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
            addTestEntry("le", 200)
            addTestEntry("Lé", 200)
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            autoReplaceOnSpaceEnter = true,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "le")

        val result = controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )

        assertFalse(result.replaced)
        assertEquals("le ", input.text)
    }

    @Test
    fun shortFrenchWordIsNotAutoReplacedByHigherFrequencyAccentedVariant() {
        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
            addTestEntry("la", 213)
            addTestEntry("lâ", 620)
            addTestEntry("là", 145)
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            autoReplaceOnSpaceEnter = true,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "La")

        val result = controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )

        assertFalse(result.replaced)
        assertEquals("La ", input.text)
    }

    @Test
    fun unaccentedFrenchWordCanStillBeAutoReplacedByAccentedVariant() {
        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
            addTestEntry("état", 500)
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            autoReplaceOnSpaceEnter = true,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "etat")

        val result = controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )

        assertTrue(result.replaced)
        assertEquals("état ", input.text)
    }
    @Test
    fun multiWordSuggestionsDisabledPreventsPhraseLearning() {
        val phraseUsageRepository = FakePhraseUsageRepository()

        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = false,
            phraseUsageRepository = phraseUsageRepository,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")

        input.appendFromEditor("je")
        controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )

        input.appendFromEditor("visite")
        controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )

        assertTrue(phraseUsageRepository.learnedPhrases.isEmpty())
    }

    @Test
    fun multiWordSuggestionsDisabledPreventsPhrasePrediction() {
        val phraseUsageRepository = FakePhraseUsageRepository().apply {
            predictions = listOf(
                PhraseUsageStore.Prediction(
                    word = "le",
                    words = listOf("le", "mammouth"),
                    count = 100,
                    lastUsed = 1000L,
                    contextLength = 2
                )
            )
        }

        val repository = FakeDictionaryRepository(Locale.FRENCH).apply {
            isReady = true
        }

        val controller = newController(
            repository = repository,
            experimentalSuggestionsEnabled = true,
            suggestionsEnabled = true,
            multiWordSuggestionsEnabled = false,
            phraseUsageRepository = phraseUsageRepository,
            locale = Locale.FRENCH
        )

        val input = FakeInputConnection(context, "")

        input.appendFromEditor("je")
        controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )

        input.appendFromEditor("retrouve")
        controller.onBoundaryKey(
            KeyEvent.KEYCODE_SPACE,
            null,
            input
        )

        assertTrue(phraseUsageRepository.predictionRequests.isEmpty())

        assertFalse(
            controller.currentSuggestions().any {
                it.candidate == "le mammouth"
            }
        )
    }

    private fun newController(
        repository: DictionaryRepository,
        experimentalSuggestionsEnabled: Boolean,
        suggestionsEnabled: Boolean,
        multiWordSuggestionsEnabled: Boolean = false,
        autoReplaceOnSpaceEnter: Boolean = false,
        wordUsageRepository: WordUsageRepository = FakeWordUsageRepository(),
        phraseUsageRepository: PhraseUsageRepository = FakePhraseUsageRepository(),
        userNGramRepository: UserNGramRepository = FakeUserNGramRepository(),
        multiWordStore: MultiWordStore = MultiWordStore(context),
        locale: Locale = Locale.ENGLISH
    ): SuggestionController {
        return SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    textReplacementsEnabled = true,
                    suggestionsEnabled = suggestionsEnabled,
                    multiWordSuggestionsEnabled = multiWordSuggestionsEnabled,
                    autoReplaceOnSpaceEnter = autoReplaceOnSpaceEnter
                )
            },
            isEnabled = { experimentalSuggestionsEnabled },
            onSuggestionsUpdated = {},
            currentLocale = locale,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> repository },
            nextWordPredictorOverride = NextWordPredictor(
                userNGramRepository,
                asyncLearning = false
            ),
            wordUsageRepositoryOverride = wordUsageRepository,
            phraseUsageRepositoryOverride = phraseUsageRepository,
            multiWordStoreOverride = multiWordStore
        ).also { controllers += it }
    }

    private class ControlledDictionaryRepository(
        ready: Boolean,
        private val loadFails: Boolean
    ) : DictionaryRepository by FakeDictionaryRepository() {
        @Volatile
        override var isReady: Boolean = ready

        @Volatile
        override var isLoadStarted: Boolean = false

        override suspend fun loadIfNeeded() {
            isLoadStarted = true
            if (loadFails) throw IllegalStateException("controlled load failure")
        }
    }

    private class BlockingDictionaryRepository :
        DictionaryRepository by FakeDictionaryRepository() {
        private val loadStarted = CountDownLatch(1)
        private val loadCancelled = CountDownLatch(1)

        @Volatile
        override var isReady: Boolean = false

        @Volatile
        override var isLoadStarted: Boolean = false

        override suspend fun loadIfNeeded() {
            isLoadStarted = true
            loadStarted.countDown()
            try {
                awaitCancellation()
            } finally {
                loadCancelled.countDown()
            }
        }

        fun awaitLoadStarted(): Boolean = loadStarted.await(2, TimeUnit.SECONDS)

        fun awaitLoadCancellation(): Boolean = loadCancelled.await(2, TimeUnit.SECONDS)
    }

    private class FakeInputConnection(
        context: Context,
        initialText: String
    ) : BaseInputConnection(View(context), true) {
        private val buffer = StringBuilder(initialText)

        val text: String
            get() = buffer.toString()

        fun appendFromEditor(text: String) {
            buffer.append(text)
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = buffer.takeLast(n)

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val start = (buffer.length - beforeLength).coerceAtLeast(0)
            buffer.delete(start, buffer.length)
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            buffer.append(text ?: "")
            return true
        }

        override fun beginBatchEdit(): Boolean = true

        override fun endBatchEdit(): Boolean = true
    }
}

private class FakeWordUsageRepository : WordUsageRepository {
    var learnedLocale: Locale? = null
    var learnedWord: String? = null

    override fun learn(
        locale: Locale,
        word: String,
        nowMs: Long
    ) {
        learnedLocale = locale
        learnedWord = word
    }

    override fun getUsage(
        locale: Locale,
        word: String
    ): WordUsageStore.Usage? = null

    override fun getExactUsage(
        locale: Locale,
        word: String
    ): WordUsageStore.Usage? = null

    override fun delete(
        locale: Locale,
        word: String
    ): Int = 0

    override fun prune(
        nowMs: Long,
        staleAfterMs: Long,
        weakCountThreshold: Int,
        maxEntriesPerLocale: Int
    ): Int = 0

    override fun clearAll() {
        // No-op.
    }
}
private class FakeUserNGramRepository : UserNGramRepository {

    var predictions: List<UserNGramStore.Prediction> = emptyList()

    override fun learn(
        locale: String,
        prefix: String,
        nextWord: String,
        nowMs: Long
    ) {
        // No-op for these tests.
    }

    override fun predict(
        locale: String,
        prefix: String,
        limit: Int
    ): List<UserNGramStore.Prediction> {
        return predictions.take(limit)
    }

    override fun getTotalCount(
        locale: String,
        prefix: String
    ): Int {
        return 0
    }

    override fun delete(
        locale: String,
        prefix: String,
        nextWord: String
    ): Int = 0

    override fun deleteNextWord(
        locale: String,
        nextWord: String
    ): Int = 0

    override fun getUsage(
        locale: String,
        prefix: String,
        nextWord: String
    ): UserNGramStore.Prediction? {
        return null
    }

    override fun prune(
        nowMs: Long,
        staleAfterMs: Long,
        weakCountThreshold: Int,
        maxEntriesPerLocale: Int
    ): Int = 0

    override fun clearAll() {
        // No-op.
    }
}
