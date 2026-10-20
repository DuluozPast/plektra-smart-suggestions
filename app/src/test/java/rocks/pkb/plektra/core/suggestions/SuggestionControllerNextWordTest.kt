package rocks.pkb.plektra.core.suggestions

import android.os.Looper
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import java.util.Locale
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SuggestionControllerNextWordTest {

    private lateinit var store: UserNGramStore
    private lateinit var fakeRepository: FakeDictionaryRepository
    private val snapshots = mutableListOf<List<SuggestionResult>>()
    private val controllers = mutableListOf<SuggestionController>()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        store = UserNGramStore(context).apply { clearAll() }
        fakeRepository = FakeDictionaryRepository().apply {
            isReady = true
            addTestEntry("bar", 200)
        }
        snapshots.clear()
        controllers.clear()
    }

    @After
    fun tearDown() {
        controllers.forEach { controller ->
            controller.flushNextWordLearningForTests()
            controller.destroy()
        }
        store.clearAll()
        store.close()
    }

    @Test
    fun learningCandidateUpdatesImmediatelyDuringTypingAndDeletion() {
        val controller = newController()
        typeWord(controller, "unknown")
        assertEquals("unknown", controller.pendingAddWord())
        controller.onCharacterCommitted("x", null)
        assertEquals("unknownx", controller.pendingAddWord())
        controller.refreshFromInputConnection(null)
        assertEquals("unknown", controller.pendingAddWord())
        controller.onCharacterCommitted("x".repeat(100), null)
        assertEquals(null, controller.pendingAddWord())
    }

    @Test
    fun learnsBigramAndPredictsAfterSpace() {
        val controller = newController()

        typeWord(controller, "ich")
        pressSpace(controller)
        typeWord(controller, "bin")
        pressSpace(controller)
        typeWord(controller, "ich")
        pressSpace(controller)

        val latest = snapshots.last()

        assertEquals(
            listOf("bin"),
            latest.map { it.candidate }
        )
        assertEquals(
            SuggestionKind.NEXT_WORD,
            latest.first().kind
        )
    }

    @Test
    fun dictionaryReloadAtWordBoundaryKeepsNextWordPredictions() {
        val controller = newController()
        typeWord(controller, "ich")
        pressSpace(controller)
        typeWord(controller, "bin")
        pressSpace(controller)
        typeWord(controller, "ich")
        pressSpace(controller)
        assertEquals(SuggestionKind.NEXT_WORD, snapshots.last().first().kind)

        controller.reloadDictionaries()
        // *Reload preserves the current context; its callback may already have restored the prediction.*
        val restored = waitForSuggestionCandidates("bin")
        assertEquals(listOf("bin"), restored.map { it.candidate })
        assertEquals(SuggestionKind.NEXT_WORD, restored.first().kind)
    }

    @Test
    fun semicolonKeepsContextForLearning() {
        val controller = newController()

        typeWord(controller, "teste")
        pressSemicolon(controller)
        pressSpace(controller)
        typeWord(controller, "das")
        pressSpace(controller)
        controller.flushNextWordLearningForTests()

        val predictions = store.predict("de-DE", "teste", limit = 3)
        assertEquals(listOf("das"), predictions.map { it.word })
    }

    @Test
    fun periodLearnsCurrentPairThenResetsContext() {
        val controller = newController()

        typeWord(controller, "ich")
        pressSpace(controller)
        typeWord(controller, "bin")
        pressPeriod(controller)
        typeWord(controller, "morgen")
        pressSpace(controller)
        controller.flushNextWordLearningForTests()

        assertEquals(listOf("bin"), store.predict("de-DE", "ich", limit = 3).map { it.word })
        assertTrue(store.predict("de-DE", "bin", limit = 3).isEmpty())
        assertTrue(snapshots.last().isEmpty())
    }

    @Test
    fun typingLetterOverridesNextWordPredictions() {
        val controller = newController()

        typeWord(controller, "ich")
        pressSpace(controller)
        typeWord(controller, "bin")
        pressSpace(controller)
        typeWord(controller, "ich")
        pressSpace(controller)
        typeWord(controller, "b")

        val latest = waitForSuggestionCandidates("bar")
        assertEquals(listOf("bar"), latest.map { it.candidate })
        assertEquals(SuggestionKind.CURRENT_WORD, latest.first().kind)
    }

    @Test
    fun currentWordSuggestionsIncludeAdditionalLocales() {
        val deRepository = FakeDictionaryRepository().apply {
            isReady = true
            addTestEntry("deutsch", 200)
        }
        val enRepository = FakeDictionaryRepository().apply {
            isReady = true
            addTestEntry("english", 200)
        }
        val context = RuntimeEnvironment.getApplication()
        val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = { SuggestionSettings(suggestionsEnabled = true, maxSuggestions = 3) },
            onSuggestionsUpdated = { snapshots.add(it) },
            currentLocale = Locale.GERMANY,
            dictionaryRepositoryFactory = { _, _, _, locale, _ ->
                if (locale.language == "en") enRepository else deRepository
            },
            nextWordPredictorOverride = NextWordPredictor(store),
            activeSuggestionLocalesProvider = { listOf(Locale.ENGLISH) }
        )
        controllers.add(controller)

        typeWord(controller, "eng")

        assertEquals(listOf("english"), waitForSuggestionCandidates("english").map { it.candidate })
    }

    @Test
    fun updateLocaleClearsOldSuggestionsAndUsesNewLocale() {
        val frRepository = FakeDictionaryRepository().apply {
            isReady = true
            addTestEntry("français", 200)
        }
        val enRepository = FakeDictionaryRepository().apply {
            isReady = true
            addTestEntry("english", 200)
        }

        val context = RuntimeEnvironment.getApplication()
        val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    maxSuggestions = 3
                )
            },
            onSuggestionsUpdated = { snapshots.add(it) },
            currentLocale = Locale.FRENCH,
            dictionaryRepositoryFactory = { _, _, _, locale, _ ->
                if (locale.language == "en") enRepository else frRepository
            },
            nextWordPredictorOverride = NextWordPredictor(store),
            activeSuggestionLocalesProvider = { emptyList() }
        )
        controllers.add(controller)

        // Produce a real French suggestion first.
        typeWord(controller, "fran")

        assertTrue(
            waitForSuggestionCandidates("français")
                .any { it.candidate == "français" }
        )

        // Switching locale must immediately remove the old French state.
        controller.updateLocale(Locale.ENGLISH)

        assertTrue(snapshots.last().isEmpty())

        // New typing must now be handled by the English engine.
        typeWord(controller, "eng")

        assertEquals(
            listOf("english"),
            waitForSuggestionCandidates("english")
                .map { it.candidate }
        )
    }

    @Test
    fun emptyInitialContextShowsStarterSuggestions() {
        fakeRepository.addTestEntry("Plektra", 255, SuggestionSource.DEFAULT_USER)
        fakeRepository.addTestEntry("BlackBerry", 255, SuggestionSource.DEFAULT_USER)
        fakeRepository.addTestEntry("Parenzo", 255, SuggestionSource.DEFAULT_USER)
        fakeRepository.addTestEntry("ich", 220)
        fakeRepository.addTestEntry("dann", 210)
        val controller = newController()

        controller.readInitialContext(emptyInputConnection())

        val latest = snapshots.last()
        assertEquals(listOf("ich", "dann", "bar"), latest.map { it.candidate })
        assertEquals(SuggestionKind.STARTER_WORD, latest.first().kind)
    }

    @Test
    fun typingLetterOverridesStarterSuggestions() {
        fakeRepository.addTestEntry("ich", 220)
        fakeRepository.addTestEntry("dann", 210)
        val controller = newController()

        controller.readInitialContext(emptyInputConnection())
        typeWord(controller, "b")

        val latest = waitForSuggestionCandidates("bar")
        assertEquals(listOf("bar"), latest.map { it.candidate })
        assertEquals(SuggestionKind.CURRENT_WORD, latest.first().kind)
    }

    @Test
    fun learnedBigramIsShownWithoutStarterFallback() {
        fakeRepository.addTestEntry("ich", 220)
        fakeRepository.addTestEntry("dann", 210)
        val controller = newController()

        typeWord(controller, "ich")
        pressSpace(controller)
        typeWord(controller, "bin")
        pressSpace(controller)
        typeWord(controller, "ich")
        pressSpace(controller)

        val latest = snapshots.last()
        assertEquals(listOf("bin"), latest.map { it.candidate })
        assertEquals(SuggestionKind.NEXT_WORD, latest.first().kind)
    }

    @Test
    fun dismissSuggestionForgetsBigramAndRefillsVisibleSuggestions() {
        fakeRepository.addTestEntry("ich", 220)
        fakeRepository.addTestEntry("dann", 210)
        val controller = newController()

        typeWord(controller, "ich")
        pressSpace(controller)
        typeWord(controller, "bin")
        pressSpace(controller)
        typeWord(controller, "ich")
        pressSpace(controller)

        controller.dismissSuggestion("bin")
        controller.flushNextWordLearningForTests()

        val latest = snapshots.last()
        assertEquals(listOf("ich", "dann", "bar"), latest.map { it.candidate })
        assertEquals(SuggestionKind.STARTER_WORD, latest.first().kind)
        assertTrue(store.predict("de-DE", "ich", limit = 3).isEmpty())
    }

    @Test
    fun learnsSentenceStartAndPredictsItBeforeStarterFallback() {
        fakeRepository.addTestEntry("ich", 220)
        fakeRepository.addTestEntry("dann", 210)
        val controller = newController()

        typeWord(controller, "Hallo")
        pressPeriod(controller)
        typeWord(controller, "Morgen")
        pressSpace(controller)
        controller.onContextReset()
        controller.readInitialContext(emptyInputConnection())

        val latest = snapshots.last()
        assertEquals(listOf("Hallo", "Morgen", "ich"), latest.map { it.candidate })
        assertEquals(SuggestionKind.NEXT_WORD, latest.first().kind)
        assertEquals(SuggestionKind.STARTER_WORD, latest[2].kind)
    }

    @Test
    fun autoLearnedWordIsPersistedOnlyOnceWithMultipleRepositories() {
        val deRepository = FakeDictionaryRepository().apply {
            isReady = true
        }

        val enRepository = FakeDictionaryRepository().apply {
            isReady = true
        }

        val context = RuntimeEnvironment.getApplication()

        val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    maxSuggestions = 3
                )
            },
            onSuggestionsUpdated = { snapshots.add(it) },
            currentLocale = Locale.GERMANY,
            dictionaryRepositoryFactory = { _, _, _, locale, _ ->
                if (locale.language == "en") {
                    enRepository
                } else {
                    deRepository
                }
            },
            nextWordPredictorOverride = NextWordPredictor(store),
            activeSuggestionLocalesProvider = {
                listOf(Locale.ENGLISH)
            }
        )

        controllers.add(controller)

        repeat(3) {
            typeWord(controller, "monpetitloup")
            pressSpace(controller)
        }

        assertEquals(
            0,
            deRepository.autoLearnedAddCount +
                    enRepository.autoLearnedAddCount
        )

        assertEquals(
            2,
            deRepository.storedUserIndexCount +
                    enRepository.storedUserIndexCount
        )
    }

    @Test
    fun learningMaintenanceRunsAfterFiveHundredCompletedWords() {
        val controller = newController()

        repeat(499) {
            controller.noteCompletedWordForMaintenanceForTests()
        }

        controller.waitForMaintenanceForTests()

        assertEquals(
            0,
            controller.maintenanceRunCountForTests
        )

        controller.noteCompletedWordForMaintenanceForTests()
        controller.waitForMaintenanceForTests()

        assertEquals(
            1,
            controller.maintenanceRunCountForTests
        )
    }
    @Test
    fun learningMaintenanceRemovesPrunedAutoLearnedWordFromLoadedRepository() {
        val context = RuntimeEnvironment.getApplication()

        val userDictionaryStore = UserDictionaryStore()

        userDictionaryStore.getSnapshot()
            .map { it.word }
            .forEach { word ->
                userDictionaryStore.removeWord(context, word)
            }

        userDictionaryStore.addAutoLearnedWord(
            context,
            "monpetitloup"
        )

        fakeRepository.addTestEntry(
            "monpetitloup",
            1,
            SuggestionSource.USER
        )

        assertTrue(
            fakeRepository.isKnownWord("monpetitloup")
        )

        val controller = newController()
        controller.setMaintenanceNowMsForTests(
            System.currentTimeMillis() +
                    91L * 24L * 60L * 60L * 1000L
        )

        repeat(500) {
            controller.noteCompletedWordForMaintenanceForTests()
        }

        controller.waitForMaintenanceForTests()

        assertEquals(
            1,
            fakeRepository.storedUserRemoveCount
        )

        assertTrue(
            !fakeRepository.isKnownWord("monpetitloup")
        )
    }

    @Test
    fun learningMaintenanceDemotesMultiWordSequenceAfterNGramEvidenceIsPruned() {
        val context = RuntimeEnvironment.getApplication()
        val locale = Locale.GERMANY

        val phraseStore = PhraseUsageStore(context).apply {
            clearAll()
        }

        val multiWordStore = MultiWordStore(context).apply {
            clearAll()
        }

        val words = listOf("ich", "bin", "hier")
        val oldNow = 1_000L

        // PhraseUsage reste volontairement forte :
        // elle ne doit PAS être supprimée par la maintenance.
        repeat(8) {
            phraseStore.learn(
                locale = locale,
                words = words,
                nowMs = oldNow
            )
        }

        // Les NGram sont anciens et faibles.
        // Avec le seuil de pruning actuel (count <= 1),
        // ils doivent disparaître pendant la maintenance.
        store.learn(
            locale.toLanguageTag(),
            "ich",
            "bin",
            oldNow
        )

        store.learn(
            locale.toLanguageTag(),
            "bin",
            "hier",
            oldNow
        )

        multiWordStore.promote(
            locale = locale,
            words = words,
            phraseCount = 8,
            nowMs = oldNow
        )

        assertTrue(
            multiWordStore.find(locale, words) != null
        )

        val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    maxSuggestions = 3,
                    multiWordSuggestionsEnabled = true
                )
            },
            onSuggestionsUpdated = { snapshots.add(it) },
            currentLocale = locale,
            dictionaryRepositoryFactory = { _, _, _, _, _ ->
                fakeRepository
            },
            nextWordPredictorOverride = NextWordPredictor(
                store = store,
                asyncLearning = false
            ),
            phraseUsageRepositoryOverride = phraseStore,
            multiWordStoreOverride = multiWordStore
        )

        controllers.add(controller)

        controller.setMaintenanceNowMsForTests(
            oldNow +
                    365L * 24L * 60L * 60L * 1000L
        )

        repeat(500) {
            controller.noteCompletedWordForMaintenanceForTests()
        }

        controller.waitForMaintenanceForTests()

        assertEquals(
            1,
            controller.maintenanceRunCountForTests
        )

        // PhraseUsage forte : elle doit avoir survécu.
        assertTrue(
            phraseStore.getUsage(locale, words) != null
        )

        // NGram faibles et anciens : ils doivent avoir été supprimés.
        assertTrue(
            store.predict(
                locale.toLanguageTag(),
                "ich",
                limit = 3
            ).isEmpty()
        )

        // Le MultiWord promu n'a donc plus les preuves NGram requises :
        // la maintenance doit l'avoir démoté.
        assertTrue(
            multiWordStore.find(locale, words) == null
        )

        phraseStore.close()
    }

    @Test
    fun learningMaintenanceDemotesMultiWordSequenceFromInactiveLocale() {
        val context = RuntimeEnvironment.getApplication()

        val activeLocale = Locale.GERMANY
        val inactiveLocale = Locale.FRENCH

        val phraseStore = PhraseUsageStore(context).apply {
            clearAll()
        }

        val multiWordStore = MultiWordStore(context).apply {
            clearAll()
        }

        val words = listOf("à", "la", "maison")

        multiWordStore.promote(
            locale = inactiveLocale,
            words = words,
            phraseCount = 8,
            nowMs = 1_000L
        )

        assertTrue(
            multiWordStore.find(inactiveLocale, words) != null
        )

        val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    maxSuggestions = 3,
                    multiWordSuggestionsEnabled = true
                )
            },
            onSuggestionsUpdated = { snapshots.add(it) },

            // La langue ACTIVE est volontairement l'allemand.
            currentLocale = activeLocale,

            dictionaryRepositoryFactory = { _, _, _, _, _ ->
                fakeRepository
            },
            nextWordPredictorOverride = NextWordPredictor(
                store = store,
                asyncLearning = false
            ),
            phraseUsageRepositoryOverride = phraseStore,
            multiWordStoreOverride = multiWordStore
        )

        controllers.add(controller)

        repeat(500) {
            controller.noteCompletedWordForMaintenanceForTests()
        }

        controller.waitForMaintenanceForTests()

        assertEquals(
            1,
            controller.maintenanceRunCountForTests
        )

        assertTrue(
            multiWordStore.find(inactiveLocale, words) == null
        )

        phraseStore.close()
    }

    @Test
    fun destroyCancelsRunningLearningMaintenanceBeforeItCompletes() {
        val context = RuntimeEnvironment.getApplication()
        val blockingWordUsageStore = BlockingWordUsageRepository()
        val countingPhraseUsageStore = CountingPhraseUsageRepository()

        val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    maxSuggestions = 3
                )
            },
            onSuggestionsUpdated = { snapshots.add(it) },
            currentLocale = Locale.GERMANY,
            dictionaryRepositoryFactory = { _, _, _, _, _ ->
                fakeRepository
            },
            nextWordPredictorOverride = NextWordPredictor(
                store = store,
                asyncLearning = false
            ),
            wordUsageRepositoryOverride = blockingWordUsageStore,
            phraseUsageRepositoryOverride = countingPhraseUsageStore
        )

        controllers.add(controller)

        // Déclenche la maintenance.
        repeat(500) {
            controller.noteCompletedWordForMaintenanceForTests()
        }

        // Attend qu'elle soit réellement entrée dans WordUsage.prune().
        assertTrue(
            blockingWordUsageStore.pruneStarted.await(
                5,
                TimeUnit.SECONDS
            )
        )

        assertEquals(
            1,
            controller.maintenanceRunCountForTests
        )

        // Le contrôleur disparaît pendant que prune() est encore bloqué.
        controller.destroy()

        // Autorise le prune synchrone déjà commencé à revenir.
        blockingWordUsageStore.allowPruneToFinish.countDown()
        assertTrue(
            blockingWordUsageStore.pruneFinished.await(
                5,
                TimeUnit.SECONDS
            )
        )

        controller.waitForMaintenanceForTests()

        assertEquals(
            0,
            controller.maintenanceCompletedCountForTests
        )
        assertEquals(
            0,
            countingPhraseUsageStore.pruneCallCount
        )
    }

    @Test
    fun committedSuggestionLearnsBigramFromPreviousCompletedWord() {
        val controller = newController()

        // Establish "mon" as the current completed-word context.
        typeWord(controller, "mon")
        pressSpace(controller)

        // Simulate selecting BlackBerry from the suggestion bar.
        controller.onSuggestionCommitted("BlackBerry")
        controller.flushNextWordLearningForTests()

        val predictions = store.predict(
            "de-DE",
            "mon",
            limit = 3
        )

        assertEquals(
            listOf("BlackBerry"),
            predictions.map { it.word }
        )
    }

    @Test fun acceptingAnAccentCorrectionClearsCurrentWordCandidates() {
        fakeRepository.addTestEntry("état", 200)
        val controller = newController()
        typeWord(controller, "l'etat")
        controller.onSuggestionCommitted("l'état")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.currentSuggestions().none { it.kind == SuggestionKind.CURRENT_WORD })
        assertEquals(null, controller.pendingAddWord())
    }

    @Test fun repeatedCorrectionTrialsCanLegitimatelyPredictTheSameWordAgain() {
        val controller = newController()
        controller.onSuggestionCommitted("l'état")
        controller.onSuggestionCommitted("l'état")
        controller.flushNextWordLearningForTests()
        val predictions = controller.currentSuggestions()
        assertTrue(predictions.any { it.candidate == "l'état" && it.kind == SuggestionKind.NEXT_WORD })
        assertTrue(predictions.none { it.kind == SuggestionKind.CURRENT_WORD })
    }

    @Test
    fun committedMultiWordSuggestionLearnsEachBigramInSequence() {
        val controller = newController()

        typeWord(controller, "je")
        pressSpace(controller)

        controller.onSuggestionCommitted("voudrais savoir")
        controller.flushNextWordLearningForTests()

        assertEquals(
            listOf("voudrais"),
            store.predict(
                "de-DE",
                "je",
                limit = 3
            ).map { it.word }
        )

        assertEquals(
            listOf("savoir"),
            store.predict(
                "de-DE",
                "voudrais",
                limit = 3
            ).map { it.word }
        )
    }

    @Test
    fun nextWordPredictionUsesCanonicalDictionaryCasing() {
        fakeRepository.addTestEntry(
            "BlackBerry",
            255,
            SuggestionSource.DEFAULT_USER
        )

        val controller = newController()

        // Learn the casing exactly as it may have been typed by the user.
        typeWord(controller, "mon")
        pressSpace(controller)

        typeWord(controller, "blackBerry")
        pressSpace(controller)

        // Ask for predictions after "mon" again.
        typeWord(controller, "mon")
        pressSpace(controller)

        val latest = snapshots.last()

        assertEquals(
            listOf("BlackBerry"),
            latest.map { it.candidate }
        )
    }

    @Test
    fun frenchNextWordPredictionLowercasesSentenceInitialCasingMidSentence() {
        fakeRepository.addTestEntry(
            "souviens-toi",
            255,
            SuggestionSource.DEFAULT_USER
        )

        val context = RuntimeEnvironment.getApplication()

        val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    maxSuggestions = 3
                )
            },
            onSuggestionsUpdated = { snapshots.add(it) },
            currentLocale = Locale.FRENCH,
            dictionaryRepositoryFactory = { _, _, _, _, _ ->
                fakeRepository
            },
            nextWordPredictorOverride = NextWordPredictor(store)
        )

        controllers.add(controller)

        // Reproduit une forme historiquement apprise en début de phrase.
        store.learn(
            Locale.FRENCH.toLanguageTag(),
            "je",
            "Souviens-toi"
        )

        // En milieu de phrase, la graphie canonique française doit être utilisée.
        typeWord(controller, "je")
        pressSpace(controller)

        val latest = snapshots.last()

        assertTrue(
            latest.any { it.candidate == "souviens-toi" }
        )

        assertTrue(
            latest.none { it.candidate == "Souviens-toi" }
        )
    }

    @Test
    fun extraLocaleNextWordPredictionUsesCanonicalDictionaryCasing() {
        val deRepository = FakeDictionaryRepository().apply {
            isReady = true
        }

        val enRepository = FakeDictionaryRepository().apply {
            isReady = true
            addTestEntry(
                "BlackBerry",
                255,
                SuggestionSource.DEFAULT_USER
            )
        }

        val context = RuntimeEnvironment.getApplication()

        val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    maxSuggestions = 3
                )
            },
            onSuggestionsUpdated = { snapshots.add(it) },
            currentLocale = Locale.GERMANY,
            dictionaryRepositoryFactory = { _, _, _, locale, _ ->
                if (locale.language == "en") {
                    enRepository
                } else {
                    deRepository
                }
            },
            nextWordPredictorOverride = NextWordPredictor(store),
            activeSuggestionLocalesProvider = {
                listOf(Locale.ENGLISH)
            }
        )

        controllers.add(controller)

        // Create an English NGram whose stored casing is non-canonical.
        store.learn(
            Locale.ENGLISH.toLanguageTag(),
            "mon",
            "blackBerry"
        )

        // Force creation/loading of the extra English repository.
        typeWord(controller, "bla")
        waitForSuggestionCandidates("BlackBerry")

        // Return to a clean context and request predictions after "mon".
        controller.onContextReset()

        typeWord(controller, "mon")
        pressSpace(controller)

        val latest = snapshots.last()

        assertTrue(
            latest.any { it.candidate == "BlackBerry" }
        )

        assertTrue(
            latest.none { it.candidate == "blackBerry" }
        )
    }

    @Test
    fun phraseUsageNextWordUsesCanonicalDictionaryCasing() {
        val context = RuntimeEnvironment.getApplication()
        val locale = Locale.GERMANY

        fakeRepository.addTestEntry(
            "BlackBerry",
            255,
            SuggestionSource.DEFAULT_USER
        )

        val phraseStore = PhraseUsageStore(context).apply {
            clearAll()
        }

        // Reproduce the historical casing seen on the physical phone.
        repeat(4) {
            phraseStore.learn(
                locale = locale,
                words = listOf("mon", "blackBerry")
            )
        }

        val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    maxSuggestions = 3,
                    multiWordSuggestionsEnabled = true
                )
            },
            onSuggestionsUpdated = { snapshots.add(it) },
            currentLocale = locale,
            dictionaryRepositoryFactory = { _, _, _, _, _ ->
                fakeRepository
            },
            nextWordPredictorOverride = NextWordPredictor(store),
            phraseUsageRepositoryOverride = phraseStore
        )

        controllers.add(controller)

        typeWord(controller, "mon")
        pressSpace(controller)

        val latest = snapshots.last()

        println(
            "PHRASE_CANON latest=" +
                    latest.joinToString { result ->
                        "'${result.candidate}'[${result.score}/${result.source}/${result.kind}]"
                    }
        )

        println(
            "PHRASE_CANON normalized=" +
                    WordNormalization.normalizeForSuggestion(
                        "blackBerry",
                        locale
                    )
        )

        println(
            "PHRASE_CANON repository=" +
                    fakeRepository.topByNormalized(
                        WordNormalization.normalizeForSuggestion(
                            "blackBerry",
                            locale
                        ),
                        limit = 16
                    ).joinToString { "'${it.word}'" }
        )

        assertTrue(
            latest.any { it.candidate == "BlackBerry" }
        )

        assertTrue(
            latest.none { it.candidate == "blackBerry" }
        )

        phraseStore.close()
    }

    @Test
    fun promotedMultiWordUsesCanonicalDictionaryCasing() {
        val context = RuntimeEnvironment.getApplication()
        val locale = Locale.FRENCH

        fakeRepository.addTestEntry(
            "je",
            255,
            SuggestionSource.MAIN
        )
        fakeRepository.addTestEntry(
            "voudrais",
            255,
            SuggestionSource.MAIN
        )

        val phraseStore = PhraseUsageStore(context).apply {
            clearAll()
        }

        val multiWordStore = MultiWordStore(context).apply {
            clearAll()
        }

        // Forme historiquement enregistrée avec une majuscule
        // provenant d'un début de phrase.
        multiWordStore.promote(
            locale = locale,
            words = listOf("Je", "voudrais"),
            phraseCount = 8,
            nowMs = 1_000L
        )

        // *The phrase index learns the final transition of each supplied sequence.*
        // Rend "Je voudrais" éligible après "ici".
        repeat(4) {
            phraseStore.learn(locale, listOf("ici", "Je"))
            phraseStore.learn(
                locale = locale,
                words = listOf("ici", "Je", "voudrais")
            )
        }

        val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    maxSuggestions = 3,
                    multiWordSuggestionsEnabled = true
                )
            },
            onSuggestionsUpdated = { snapshots.add(it) },
            currentLocale = locale,
            dictionaryRepositoryFactory = { _, _, _, _, _ ->
                fakeRepository
            },
            nextWordPredictorOverride = NextWordPredictor(
                store = store,
                asyncLearning = false
            ),
            phraseUsageRepositoryOverride = phraseStore,
            multiWordStoreOverride = multiWordStore
        )

        controllers.add(controller)

        typeWord(controller, "ici")
        pressSpace(controller)

        val latest = snapshots.last()

        println(
            "CASE_TEST latest=" +
                    latest.joinToString(" | ") {
                        "'${it.candidate}'"
                    }
        )

        assertTrue(latest.any { it.candidate == "je voudrais" })
        assertTrue(latest.none { it.candidate == "Je voudrais" })

        phraseStore.close()
    }


    @Test
    fun numericAltLockedSpaceLearnsRepeatedNumberAheadOfSingleTypo() {
        val controller = newController()
        val context = RuntimeEnvironment.getApplication()
        val router = rocks.pkb.plektra.inputmethod.InputEventRouter(
            context,
            rocks.pkb.plektra.core.NavModeController(context,
                rocks.pkb.plektra.core.ModifierStateController(300L))
        ).apply { suggestionController = controller }
        val alternate = rocks.pkb.plektra.inputmethod.AlternateCharacterManager(
            context.assets, rocks.pkb.plektra.SettingsManager.getPreferences(context), context
        )
        val input = object : android.view.inputmethod.BaseInputConnection(android.view.View(context), true) {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        }
        fun altSpace() {
            assertTrue(router.handleAltModifiedKey(KeyEvent.KEYCODE_SPACE, null, input, alternate,
                updateStatusBar = {}, callSuperWithKey = { _, _ -> false }))
        }
        repeat(3) {
            controller.onContextReset()
            typeWord(controller, "à"); altSpace()
            typeWord(controller, "11"); altSpace()
            typeWord(controller, "heures"); altSpace()
        }
        controller.onContextReset()
        typeWord(controller, "à"); altSpace()
        typeWord(controller, "ZZ"); altSpace()
        controller.onContextReset()
        typeWord(controller, "à"); altSpace()
        assertEquals("11", snapshots.last().first().candidate)
        typeWord(controller, "11"); altSpace()
        assertEquals("heures", snapshots.last().first().candidate)
    }

    @Test
    fun compoundAltHyphenPreservesElisionContextAndCompletesPrefix() {
        val controller = newController()
        val context = RuntimeEnvironment.getApplication()
        val router = rocks.pkb.plektra.inputmethod.InputEventRouter(context,
            rocks.pkb.plektra.core.NavModeController(context,
                rocks.pkb.plektra.core.ModifierStateController(300L)))
            .apply { suggestionController = controller }
        val input = object : android.view.inputmethod.BaseInputConnection(android.view.View(context), true) {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = "rendez-".takeLast(n)
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        }
        typeWord(controller, "J'ai"); pressSpace(controller)
        typeWord(controller, "rendez")
        router.handleCommittedAltCharacter('-', input)
        typeWord(controller, "vous"); pressSpace(controller)
        controller.onContextReset()
        controller.onSuggestionCommitted("J'ai")
        assertEquals("rendez-vous", snapshots.last().first().candidate)
        snapshots.clear()
        typeWord(controller, "rendez")
        assertEquals(SuggestionKind.CURRENT_WORD, waitForSuggestionCandidates("rendez-vous", kind = SuggestionKind.CURRENT_WORD).first().kind)
        snapshots.clear()
        typeWord(controller, "-")
        assertEquals(SuggestionKind.CURRENT_WORD, waitForSuggestionCandidates("rendez-vous", kind = SuggestionKind.CURRENT_WORD).first().kind)
    }

    @Test
    fun compoundStandaloneAltHyphenResetsContext() {
        val controller = newController()
        val context = RuntimeEnvironment.getApplication()
        val router = rocks.pkb.plektra.inputmethod.InputEventRouter(context,
            rocks.pkb.plektra.core.NavModeController(context,
                rocks.pkb.plektra.core.ModifierStateController(300L)))
            .apply { suggestionController = controller }
        typeWord(controller, "J'ai"); pressSpace(controller)
        router.handleCommittedAltCharacter('-', null)
        typeWord(controller, "vous"); pressSpace(controller)
        controller.flushNextWordLearningForTests()
        assertTrue(store.predict("de-DE", "jai", 3).isEmpty())
    }

    @Test
    fun backupNumericCountOutranksSingleLetterOnlyPhrase() {
        val context = RuntimeEnvironment.getApplication()
        val phrases = PhraseUsageStore(context).apply { clearAll() }
        val predictor = NextWordPredictor(store, asyncLearning = false)
        val locale = Locale.GERMANY
        repeat(27) { predictor.learn(locale, "à", "11") }
        predictor.learn(locale, "à", "ZZ")
        phrases.learn(locale, listOf("à", "ZZ"))
        repeat(14) { phrases.learn(locale, listOf("à", "la")) }
        val controller = SuggestionController(context, context.assets,
            settingsProvider = { SuggestionSettings(suggestionsEnabled = true) },
            onSuggestionsUpdated = { snapshots += it }, currentLocale = locale,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> fakeRepository },
            nextWordPredictorOverride = predictor, phraseUsageRepositoryOverride = phrases)
        controllers.add(controller)
        typeWord(controller, "à"); pressSpace(controller)
        assertEquals("11", snapshots.last().first().candidate)
        controller.destroy(); controllers.remove(controller)
        phrases.close()
    }

    @Test
    fun backupLearningRetainsNumberInsidePhraseAndWordUsage() {
        val context = RuntimeEnvironment.getApplication()
        val locale = Locale.FRANCE
        val phrases = PhraseUsageStore(context).apply { clearAll() }
        phrases.learn(locale, listOf("à", "11", "heures"))
        assertEquals(1, phrases.getUsage(locale, listOf("à", "11", "heures"))?.count)
        val words = WordUsageStore(context).apply { clearAll() }
        words.learn(locale, "11")
        assertEquals(1, words.getUsage(locale, "11")?.count)
        assertEquals("11", WordNormalization.normalizeForSuggestion("11", locale))
        assertEquals("12", WordNormalization.normalizeForSuggestion("12", locale))
        phrases.close(); words.close()
    }

    @Test
    fun backupCursorRefreshKeepsCompoundCompletionAheadOfDictionaryFillers() {
        val controller = newController()
        fakeRepository.addTestEntry("retour", 10000)
        fakeRepository.addTestEntry("reste", 10000)
        fakeRepository.addTestEntry("région", 10000)
        typeWord(controller, "J'ai"); pressSpace(controller)
        typeWord(controller, "rendez-vous"); pressSpace(controller)
        controller.onContextReset()
        val input = object : android.view.inputmethod.BaseInputConnection(android.view.View(RuntimeEnvironment.getApplication()), true) {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = "heures. J'ai re".takeLast(n)
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        }
        snapshots.clear()
        controller.onCursorMoved(input)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        repeat(50) {
            shadowOf(Looper.getMainLooper()).idle()
            if (snapshots.lastOrNull()?.firstOrNull()?.candidate == "rendez-vous") return@repeat
            Thread.sleep(10)
        }
        assertEquals("rendez-vous", snapshots.last().first().candidate)
        assertEquals(SuggestionKind.CURRENT_WORD, snapshots.last().first().kind)
    }

    @Test
    fun provisionalPunctuationDoesNotLearnAcceptedWordTwice() {
        val controller = newController()
        val context = RuntimeEnvironment.getApplication()
        val usage = WordUsageStore(context).apply { clearAll() }
        typeWord(controller, "je"); pressSpace(controller)
        controller.onSuggestionCommitted("voudrais")
        val countBefore = usage.getUsage(Locale.GERMANY, "voudrais")?.count
        val input = object : android.view.inputmethod.BaseInputConnection(android.view.View(context), true) {
            private val buffer = StringBuilder("voudrais ")
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = buffer.takeLast(n)
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                buffer.setLength(buffer.length - beforeLength); return true
            }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                buffer.append(text); return true
            }
        }
        rocks.pkb.plektra.core.AutoSpaceTracker.markSuggestionAutoSpace(input)
        rocks.pkb.plektra.core.PunctuationAfterSpace.beforeTextCommit(input, ")")
        controller.onBoundaryKey(KeyEvent.KEYCODE_UNKNOWN, null, input, boundaryCharOverride = ')')
        assertEquals(countBefore, usage.getUsage(Locale.GERMANY, "voudrais")?.count)
        usage.close()
    }

    @Test
    fun numericTransitionsPredictNumberThenHours() {
        val controller = newController()
        typeWord(controller, "à"); pressSpace(controller)
        typeWord(controller, "11"); pressSpace(controller)
        typeWord(controller, "heures"); pressSpace(controller)
        controller.onContextReset()
        typeWord(controller, "à"); pressSpace(controller)
        assertEquals(listOf("11"), snapshots.last().map { it.candidate })
        typeWord(controller, "11"); pressSpace(controller)
        assertEquals(listOf("heures"), snapshots.last().map { it.candidate })
    }

    @Test
    fun numericPrefixCompletesContextualNumberWithoutPersonalDictionaryEntry() {
        val controller = newController()
        typeWord(controller, "à"); pressSpace(controller)
        typeWord(controller, "11"); pressSpace(controller)
        controller.onContextReset()
        typeWord(controller, "à"); pressSpace(controller)
        typeWord(controller, "1")
        assertEquals(listOf("11"), waitForSuggestionCandidates("11").map { it.candidate })
        assertEquals(null, controller.pendingAddWord())
    }

    @Test
    fun numericLongPressReplacementPreservesPreviousWordContext() {
        val controller = newController()
        typeWord(controller, "à"); pressSpace(controller)
        typeWord(controller, "11"); pressSpace(controller)
        controller.onContextReset()
        typeWord(controller, "à"); pressSpace(controller)
        controller.onCharacterCommitted("x", null)
        controller.onCharacterCommitted("1", null, replacesPreviousCharacter = true)
        assertEquals(listOf("11"), waitForSuggestionCandidates("11").map { it.candidate })
        assertEquals(null, controller.pendingAddWord())
    }

    @Test
    fun numericTokensNeverAutoPromoteToPersonalVocabulary() {
        val controller = newController()
        repeat(4) { typeWord(controller, "123"); pressSpace(controller) }
        assertEquals(0, fakeRepository.autoLearnedAddCount)
        assertEquals(false, fakeRepository.isKnownWord("123"))
    }

    @Test
    fun numericAcceptedSuggestionImmediatelyPredictsHours() {
        val controller = newController()
        typeWord(controller, "à"); pressSpace(controller)
        typeWord(controller, "11"); pressSpace(controller)
        typeWord(controller, "heures"); pressSpace(controller)
        controller.onContextReset()
        typeWord(controller, "à"); pressSpace(controller)
        typeWord(controller, "1")
        controller.onSuggestionCommitted("11")
        assertEquals(listOf("heures"), snapshots.last().map { it.candidate })
    }


    private fun punctuationController(frenchSpacing: Boolean = false): SuggestionController {
        val context = RuntimeEnvironment.getApplication()
        PhraseUsageStore(context).apply { clearAll(); close() }
        WordUsageStore(context).apply { clearAll(); close() }
        context.getFileStreamPath("multi_word_sequences.json").delete()
        return SuggestionController(context, context.assets,
            settingsProvider = { SuggestionSettings(suggestionsEnabled = true, multiWordSuggestionsEnabled = true, maxSuggestions = 3, frenchPunctuationSpacing = frenchSpacing) },
            onSuggestionsUpdated = { snapshots.add(it) }, currentLocale = Locale.FRANCE,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> fakeRepository },
            nextWordPredictorOverride = NextWordPredictor(store, asyncLearning = false)
        ).also { controllers.add(it) }
    }

    private fun trainGreeting(controller: SuggestionController, recipient: String, punctuationAfterSpace: Boolean = false) {
        controller.onContextReset()
        typeWord(controller, "Bonjour"); pressSpace(controller)
        typeWord(controller, recipient)
        if (punctuationAfterSpace) pressSpace(controller)
        controller.onBoundaryKey(KeyEvent.KEYCODE_COMMA, null, null, boundaryCharOverride = ',')
        pressSpace(controller)
        typeWord(controller, "je"); pressSpace(controller)
        typeWord(controller, "me"); pressSpace(controller)
        typeWord(controller, "permets"); pressSpace(controller)
    }

    @Test fun punctuatedGreetingsOfferBothCompleteLearnedBranches() {
        val controller = punctuationController()
        repeat(10) { trainGreeting(controller, "monsieur"); trainGreeting(controller, "madame") }
        controller.onContextReset()
        typeWord(controller, "Bonjour"); pressSpace(controller)
        val values = controller.currentSuggestions().map { it.candidate }
        assertTrue(values.toString(), "monsieur, je me permets" in values)
        assertTrue(values.toString(), "madame, je me permets" in values)
        assertTrue(values.none { ",," in it || " ," in it })
    }

    @Test fun punctuatedCommaAfterSpaceIsRetainedWithoutDuplicateLearning() {
        val controller = punctuationController()
        repeat(10) { trainGreeting(controller, "monsieur", punctuationAfterSpace = true) }
        controller.onContextReset()
        typeWord(controller, "Bonjour"); pressSpace(controller)
        assertTrue(controller.currentSuggestions().toString(), controller.currentSuggestions().any { it.candidate == "monsieur, je me permets" })
    }

    @Test fun punctuatedAcceptedPhraseKeepsCommaAndContinuesLearning() {
        val controller = punctuationController()
        repeat(10) {
            controller.onContextReset()
            typeWord(controller, "Bonjour"); pressSpace(controller)
            controller.onSuggestionCommitted("monsieur, je me")
            typeWord(controller, "permets"); pressSpace(controller)
        }
        controller.onContextReset()
        typeWord(controller, "Bonjour"); pressSpace(controller)
        assertTrue(controller.currentSuggestions().toString(), controller.currentSuggestions().any { it.candidate == "monsieur, je me permets" })
    }

    @Test fun punctuatedSentenceEndingDoesNotLearnAcrossSentences() {
        val controller = punctuationController()
        repeat(10) {
            controller.onContextReset()
            typeWord(controller, "Bonjour"); pressPeriod(controller)
            typeWord(controller, "monsieur"); pressSpace(controller)
            typeWord(controller, "je"); pressSpace(controller)
        }
        controller.onContextReset()
        typeWord(controller, "Bonjour"); pressSpace(controller)
        assertTrue(controller.currentSuggestions().none { it.candidate.startsWith("monsieur") })
    }

    @Test fun punctuatedLongPressCapitalKeepsFirstNameSurnameTransition() {
        val controller = punctuationController()
        repeat(5) {
            controller.onContextReset()
            typeWord(controller, "Emmanuel"); pressSpace(controller)
            typeWord(controller, "c")
            controller.onCharacterCommitted("C", null, replacesPreviousCharacter = true)
            typeWord(controller, "astel"); pressSpace(controller)
        }
        controller.onContextReset()
        typeWord(controller, "Emmanuel"); pressSpace(controller)
        assertTrue(controller.currentSuggestions().toString(), controller.currentSuggestions().any { it.candidate == "Castel" })
        typeWord(controller, "c")
        assertTrue(waitForPersonalCompletion(controller, "Castel"))
    }

    @Test fun punctuatedRepeatedPersonalCapitalizationSurvivesLowercaseDictionaryAlternative() {
        val controller = punctuationController()
        fakeRepository.addTestEntry("castel", 20000)
        repeat(5) {
            controller.onContextReset()
            typeWord(controller, "Emmanuel"); pressSpace(controller)
            typeWord(controller, "Castel"); pressSpace(controller)
        }
        controller.onContextReset()
        typeWord(controller, "Emmanuel"); pressSpace(controller)
        assertTrue(controller.currentSuggestions().toString(), controller.currentSuggestions().any { it.candidate == "Castel" })
    }

    @Test fun finalSignsAfterTypedSpaceAreLearnedWithoutCountingTheWordTwice() {
        val context = RuntimeEnvironment.getApplication()
        val controller = punctuationController(frenchSpacing = true)
        repeat(10) {
            controller.onContextReset()
            val buffer = android.text.SpannableStringBuilder().apply { android.text.Selection.setSelection(this, 0) }
            val input = object : android.view.inputmethod.BaseInputConnection(android.view.View(context), true) {
                override fun getEditable(): android.text.Editable = buffer
            }
            for (word in listOf("Emmanuel", "Castel")) {
                word.forEach { ch -> input.commitText(ch.toString(), 1); controller.onCharacterCommitted(ch.toString(), input) }
                controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)
            }
            controller.onBoundaryKey(KeyEvent.KEYCODE_UNKNOWN, null, input, boundaryCharOverride = '?')
            assertEquals("Emmanuel Castel\u202f?", buffer.toString())
        }
        assertEquals(10, store.getUsage("fr-FR", "emmanuel", "Castel")?.count)
        controller.onContextReset()
        typeWord(controller, "Emmanuel"); pressSpace(controller)
        assertTrue(controller.currentSuggestions().toString(), controller.currentSuggestions().any { it.candidate == "Castel\u202f?" })
    }

    @Test fun acceptedSentenceEndingDoesNotTeachACrossSentenceTransition() {
        val controller = punctuationController(frenchSpacing = true)
        typeWord(controller, "Emmanuel"); pressSpace(controller)
        controller.onSuggestionCommitted("Castel\u202f!")
        typeWord(controller, "Bonjour"); pressSpace(controller)
        assertEquals(null, store.getUsage("fr-FR", "castel", "Bonjour"))
        assertEquals(1, store.getUsage("fr-FR", "emmanuel", "Castel")?.count)
    }

    @Test fun punctuatedPersonalCaseDoesNotDependOnPreviousWordCapitalization() {
        val controller = punctuationController()
        fakeRepository.addTestEntry("castel", 20000)
        repeat(5) {
            controller.onContextReset()
            typeWord(controller, "emmanuel"); pressSpace(controller)
            typeWord(controller, "Castel"); pressSpace(controller)
        }
        controller.onContextReset()
        typeWord(controller, "emmanuel"); pressSpace(controller)
        assertTrue(controller.currentSuggestions().toString(), controller.currentSuggestions().any { it.candidate == "Castel" })
    }

    @Test fun frenchExclamationKeepsLearnedSurname() {
        val context = RuntimeEnvironment.getApplication()
        val controller = punctuationController(frenchSpacing = true)
        fakeRepository.addTestEntry("castel", 20000)
        repeat(5) {
            controller.onContextReset()
            val buffer = android.text.SpannableStringBuilder().apply { android.text.Selection.setSelection(this, 0) }
            val input = object : android.view.inputmethod.BaseInputConnection(android.view.View(context), true) {
                override fun getEditable(): android.text.Editable = buffer
            }
            "Emmanuel".forEach { ch -> input.commitText(ch.toString(), 1); controller.onCharacterCommitted(ch.toString(), input) }
            controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)
            "Castel".forEach { ch -> input.commitText(ch.toString(), 1); controller.onCharacterCommitted(ch.toString(), input) }
            controller.onBoundaryKey(KeyEvent.KEYCODE_UNKNOWN, null, input, boundaryCharOverride = '!')
            assertEquals("Emmanuel Castel\u202f!", buffer.toString())
        }
        assertEquals(5, store.getUsage("fr-FR", "emmanuel", "Castel")?.count)
        controller.onContextReset()
        typeWord(controller, "Emmanuel"); pressSpace(controller)
        assertTrue(controller.currentSuggestions().toString(), controller.currentSuggestions().any { it.candidate == "Castel" })
        assertTrue(controller.currentSuggestions().toString(), controller.currentSuggestions().any { it.candidate == "Castel\u202f!" })
    }

    @Test fun whatsappFormattingMarkersKeepPersonalTransitionLearning() {
        val context = RuntimeEnvironment.getApplication()
        val controller = punctuationController()
        fakeRepository.addTestEntry("castel", 20000)
        rocks.pkb.plektra.SettingsManager.setNavModeCtrlHoldEnabled(context, false)
        val router = rocks.pkb.plektra.inputmethod.InputEventRouter(context,
            rocks.pkb.plektra.core.NavModeController(context, rocks.pkb.plektra.core.ModifierStateController(300L)))
        router.editorInfoProvider = { android.view.inputmethod.EditorInfo().apply { packageName = "com.whatsapp" } }
        router.suggestionController = controller
        repeat(5) {
            controller.onContextReset()
            val buffer = android.text.SpannableStringBuilder().apply { android.text.Selection.setSelection(this, 0) }
            val input = object : android.view.inputmethod.BaseInputConnection(android.view.View(context), true) {
                override fun getEditable(): android.text.Editable = buffer
            }
            fun italic() {
                router.handleCtrlModifiedKey(KeyEvent.KEYCODE_I,
                    KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON),
                    input, emptyMap(), false, false, true,
                    clearCtrlOneShot = {}, updateStatusBar = {}, callSuper = { false }, toggleMinimalUi = {})
            }
            italic()
            "Emmanuel".forEach { ch -> input.commitText(ch.toString(), 1); controller.onCharacterCommitted(ch.toString(), input) }
            controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)
            "Castel".forEach { ch -> input.commitText(ch.toString(), 1); controller.onCharacterCommitted(ch.toString(), input) }
            italic()
            controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)
            assertEquals("_Emmanuel Castel_ ", buffer.toString())
        }
        assertEquals(5, store.getUsage("fr-FR", "emmanuel", "Castel")?.count)
        controller.onContextReset()
        typeWord(controller, "Emmanuel"); pressSpace(controller)
        assertTrue(controller.currentSuggestions().toString(), controller.currentSuggestions().any { it.candidate == "Castel" })
    }

    private fun waitForPersonalCompletion(controller: SuggestionController, value: String): Boolean {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (controller.currentSuggestions().any { it.candidate == value }) return true
            Thread.sleep(10)
        }
        return false
    }

        private fun newController(): SuggestionController {
            val context = RuntimeEnvironment.getApplication()
            return SuggestionController(
                context = context,
                assets = context.assets,
                settingsProvider = { SuggestionSettings(suggestionsEnabled = true, maxSuggestions = 3) },
                onSuggestionsUpdated = { snapshots.add(it) },
                currentLocale = Locale.GERMANY,
                dictionaryRepositoryFactory = { _, _, _, _, _ -> fakeRepository },
                nextWordPredictorOverride = NextWordPredictor(store)
            ).also { controllers.add(it) }
        }

        private fun typeWord(controller: SuggestionController, word: String) {
            word.forEach { ch ->
                controller.onCharacterCommitted(ch.toString(), null)
            }
        }

        private fun waitForSuggestionCandidates(vararg candidates: String, kind: SuggestionKind? = null): List<SuggestionResult> {
            val expected = candidates.toList()
            repeat(50) {
                shadowOf(Looper.getMainLooper()).idle()
                snapshots.lastOrNull()?.let { latest ->
                    if (latest.map { it.candidate } == expected && (kind == null || latest.firstOrNull()?.kind == kind)) {
                        return latest
                    }
                }
                Thread.sleep(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
            return snapshots.last()
        }

        private fun pressSpace(controller: SuggestionController) {
            controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, null)
        }

    private fun pressSemicolon(controller: SuggestionController) {
        controller.onBoundaryKey(KeyEvent.KEYCODE_SEMICOLON, keyEvent(KeyEvent.KEYCODE_SEMICOLON), null)
    }

    private fun pressPeriod(controller: SuggestionController) {
        controller.onBoundaryKey(KeyEvent.KEYCODE_PERIOD, keyEvent(KeyEvent.KEYCODE_PERIOD), null)
    }

    private fun keyEvent(keyCode: Int): KeyEvent {
        val character = when (keyCode) {
            KeyEvent.KEYCODE_SEMICOLON -> ';'
            KeyEvent.KEYCODE_PERIOD -> '.'
            else -> null
        }
        val event = character?.let {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                .getEvents(charArrayOf(it))
                ?.firstOrNull { generated -> generated.action == KeyEvent.ACTION_DOWN }
        }
        return event ?: KeyEvent(
            0L,
            0L,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0
        )
    }

    private fun emptyInputConnection(): InputConnection {
        return Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getTextBeforeCursor", "getTextAfterCursor" -> ""
                else -> null
            }
        } as InputConnection
    }

    private class CountingPhraseUsageRepository : PhraseUsageRepository {

        var pruneCallCount = 0
            private set

        override fun learn(
            locale: Locale,
            words: List<String>,
            nowMs: Long
        ) = Unit

        override fun getUsage(
            locale: Locale,
            words: List<String>
        ): PhraseUsageStore.Usage? = null

        override fun getPromotionCandidates(
            locale: Locale,
            minimumCount: Int,
            limit: Int
        ): List<PhraseUsageStore.Usage> = emptyList()

        override fun predictNext(
            locale: Locale,
            contextWords: List<String>,
            limit: Int
        ): List<PhraseUsageStore.Prediction> = emptyList()

        override fun prune(
            nowMs: Long,
            staleAfterMs: Long,
            weakCountThreshold: Int,
            maxEntriesPerLocale: Int
        ): Int {
            pruneCallCount++
            return 0
        }

        override fun clearAll() = Unit
    }

    private class BlockingWordUsageRepository : WordUsageRepository {

        val pruneStarted = CountDownLatch(1)
        val allowPruneToFinish = CountDownLatch(1)

        val pruneFinished = CountDownLatch(1)

        override fun learn(
            locale: Locale,
            word: String,
            nowMs: Long
        ) = Unit

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
        ): Int {
            pruneStarted.countDown()

            allowPruneToFinish.await(5, TimeUnit.SECONDS)

            pruneFinished.countDown()
            return 0
        }

        override fun clearAll() = Unit
    }
}
