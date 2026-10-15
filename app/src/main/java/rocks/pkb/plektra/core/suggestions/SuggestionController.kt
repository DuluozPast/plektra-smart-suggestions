package rocks.pkb.plektra.core.suggestions

import android.content.Context
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CancellationException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import rocks.pkb.plektra.inputmethod.AutoCorrector
import rocks.pkb.plektra.inputmethod.NotificationHelper
import rocks.pkb.plektra.backup.LearningOperationGate
import rocks.pkb.plektra.statistics.TypingStatisticsStore
import rocks.pkb.plektra.statistics.TypingStatisticsTracker

class SuggestionController(
    context: Context,
    private val assets: AssetManager,
    private val settingsProvider: () -> SuggestionSettings,
    private val isEnabled: () -> Boolean = { true },
    debugLogging: Boolean = false,
    private val onSuggestionsUpdated: (List<SuggestionResult>) -> Unit,
    private var currentLocale: Locale = Locale.ITALIAN,
    private val keyboardLayoutProvider: () -> String = { "qwerty" },
    private val dictionaryRepositoryFactory: ((
        Context,
        AssetManager,
        UserDictionaryStore,
        Locale,
        Boolean
    ) -> DictionaryRepository)? = null,
    nextWordPredictorOverride: NextWordPredictor? = null,
    private val activeSuggestionLocalesProvider: (() -> List<Locale>)? = null,
    wordUsageRepositoryOverride: WordUsageRepository? = null,
    phraseUsageRepositoryOverride: PhraseUsageRepository? = null,
    multiWordStoreOverride: MultiWordStore? = null,
    correctionUsageRepositoryOverride: CorrectionUsageRepository? = null,
    private val asyncPersistence: Boolean = false
) {

    private val appContext = context.applicationContext
    private val typingStatisticsStore = TypingStatisticsStore.get(appContext)
    private val typingStatisticsTracker = TypingStatisticsTracker(typingStatisticsStore::record)
    private val debugLogging: Boolean = debugLogging
    private val userDictionaryStore = UserDictionaryStore()
    private val sharedLearningStores = if (asyncPersistence) SharedLearningStores.get(appContext) else null

    private val wordUsageStore: WordUsageRepository =
        wordUsageRepositoryOverride ?: sharedLearningStores?.words ?: WordUsageStore(appContext)

    private val phraseUsageStore: PhraseUsageRepository =
        phraseUsageRepositoryOverride ?: sharedLearningStores?.phrases ?: PhraseUsageStore(appContext)

    private val correctionUsageStore: CorrectionUsageRepository =
        correctionUsageRepositoryOverride ?: sharedLearningStores?.corrections ?: CorrectionUsageStore(appContext)

    private val dictionaryRepositoryCache =
        mutableMapOf<String, DictionaryRepository>()
    private var dictionaryRepository: DictionaryRepository = createDictionaryRepository(currentLocale)
    private var suggestionEngine = SuggestionEngine(
        dictionaryRepository,
        locale = currentLocale,
        debugLogging = debugLogging,
        usageCountProvider = { locale, word ->
            wordUsageStore.getUsage(locale, word)?.count ?: 0
        },
        exactUsageCountProvider = { locale, word ->
            wordUsageStore.getExactUsage(locale, word)?.count ?: 0
        },
        correctionUsageCountProvider = { locale, original, correction ->
            correctionUsageStore.getUsage(
                locale = locale,
                original = original,
                correction = correction
            )?.count ?: 0
        }
    ).apply {
        setKeyboardLayout(keyboardLayoutProvider())
    }
    private var tracker = CurrentWordTracker(
        onWordChanged = { word ->
            updateSuggestionsForWord(word)
        },
        onWordReset = {
            cancelPendingWordSuggestions()
            pendingAddUserWord = null
            suggestionsListener?.invoke(emptyList())
        },
        autoSpacePunctuationProvider = { settingsProvider().autoSpacePunctuation }
    )
    private var autoReplaceController = createAutoReplaceController()
    private val nextWordPredictor = nextWordPredictorOverride ?: sharedLearningStores?.nextWord ?: NextWordPredictor(UserNGramStore(appContext))
    private val multiWordStore =
        multiWordStoreOverride ?: sharedLearningStores?.multiwords ?: MultiWordStore(appContext)

    private val multiWordPromotionEngine = MultiWordPromotionEngine(
        phraseUsageRepository = phraseUsageStore,
        nextWordPredictor = nextWordPredictor,
        multiWordStore = multiWordStore
    )
    private val extraSuggestionEngines = mutableMapOf<String, SuggestionLanguageEngine>()

    private data class SuggestionLanguageEngine(
        val locale: Locale,
        val repository: DictionaryRepository,
        val engine: SuggestionEngine
    )

    private fun createDictionaryRepository(locale: Locale): DictionaryRepository {
        val cacheKey = dictionaryCacheKey(locale)
        return dictionaryRepositoryCache.getOrPut(cacheKey) {
            dictionaryRepositoryFactory?.invoke(appContext, assets, userDictionaryStore, locale, debugLogging)
                ?: AndroidDictionaryRepository(
                appContext,
                assets,
                userDictionaryStore,
                baseLocale = locale,
                debugLogging = debugLogging
            )
        }
    }

    private fun dictionaryCacheKey(locale: Locale): String {
        return locale.language
            .takeIf { it.isNotBlank() }
            ?.lowercase(Locale.ROOT)
            ?: locale.toLanguageTag().lowercase(Locale.ROOT)
    }
    private fun isExactKnownWordInPrimaryDictionary(word: String): Boolean {
        if (!dictionaryRepository.isReady) return false

        val normalized =
            WordNormalization.normalizeForSuggestion(word, currentLocale)

        return dictionaryRepository
            .topByNormalized(normalized, limit = 16)
            .any { entry ->
                entry.word.equals(word, ignoreCase = true)
            }
    }

    private fun createAutoReplaceController(): AutoReplaceController {
        return AutoReplaceController(
            repository = dictionaryRepository,
            suggestionEngine = suggestionEngine,
            settingsProvider = settingsProvider,
            knownWordProvider = { word -> isKnownWordInActiveDictionaries(word) },
            exactReplacementProvider = { word, boundaryChar ->
                val boundary = boundaryChar ?: ' '
                AutoCorrector.processText(
                    textBeforeCursor = word + boundary,
                    locale = currentLocale.language,
                    context = appContext,
                    isKnownWord = { candidate ->
                        isExactKnownWordInPrimaryDictionary(candidate)
                    }
                )?.takeIf { (original, replacement) ->
                    original == word && replacement != word
                }?.second
            }
        )
    }
    
    /**
     * Updates the locale and reloads the dictionary for the new language.
     */
    fun reloadDictionaries() = updateLocale(currentLocale, forceReload = true)

    fun updateLocale(newLocale: Locale, forceReload: Boolean = false) {
        if (newLocale == currentLocale && !forceReload) return
        val preserveInputContext = forceReload && newLocale == currentLocale
        val preservedWord = tracker.currentWord
        if (forceReload) dictionaryRepositoryCache.clear()
        
        // Cancel previous load job if still running to prevent conflicts
        currentLoadJob?.cancel()
        currentLoadJob = null
        cancelPendingWordSuggestions()
        if (!preserveInputContext) pendingInitialContextConnection = null
        pendingPrimaryRefreshAfterLoad = false
        pendingExtraRefreshAfterLoad = false
        
        currentLocale = newLocale
        dictionaryRepository = createDictionaryRepository(currentLocale)
        suggestionEngine = SuggestionEngine(
            dictionaryRepository,
            locale = currentLocale,
            debugLogging = debugLogging,
            usageCountProvider = { locale, word ->
                wordUsageStore.getUsage(locale, word)?.count ?: 0
            },
            exactUsageCountProvider = { locale, word ->
                wordUsageStore.getExactUsage(locale, word)?.count ?: 0
            },
            correctionUsageCountProvider = { locale, original, correction ->
                correctionUsageStore.getUsage(
                    locale = locale,
                    original = original,
                    correction = correction
                )?.count ?: 0
            }
        ).apply {
            setKeyboardLayout(keyboardLayoutProvider())
        }
        autoReplaceController = createAutoReplaceController()
        extraSuggestionEngines.clear()
        
        // Recreate tracker to use new engine (tracker captures suggestionEngine in closure)
        tracker = CurrentWordTracker(
            onWordChanged = { word ->
                updateSuggestionsForWord(word)
            },
            onWordReset = {
                cancelPendingWordSuggestions()
                pendingAddUserWord = null
                suggestionsListener?.invoke(emptyList())
            },
            autoSpacePunctuationProvider = { settingsProvider().autoSpacePunctuation }
        )
        
        // Reload dictionary in background and refresh the current word when ready.
        schedulePrimaryDictionaryLoad(refreshAfterLoad = true)

        if (!preserveInputContext) {
            previousCompletedWord = null
            recentCompletedWords.clear()
            sentenceStartPending = true
            tracker.reset()
            latestSuggestions.set(emptyList())
            suggestionsListener?.invoke(emptyList())
        } else if (preservedWord.isNotBlank()) tracker.setWord(preservedWord)
        pendingAddUserWord = null
        // Keep the active word and pending cursor context when only dictionary files changed.
        schedulePrimaryDictionaryLoad(refreshAfterLoad = true)
    }

    /**
     * Updates the keyboard layout for proximity-based ranking.
     */
    fun updateKeyboardLayout(layout: String) {
        suggestionEngine.setKeyboardLayout(layout)
        extraSuggestionEngines.values.forEach { it.engine.setKeyboardLayout(layout) }
    }

    private val latestSuggestions: AtomicReference<List<SuggestionResult>> = AtomicReference(emptyList())
    // Dedicated IO scope so dictionary preload never blocks the main thread.
    private val loadScope = CoroutineScope(Dispatchers.IO)
    private val suggestionScope = CoroutineScope(Dispatchers.Default)
    private var currentLoadJob: Job? = null
    private var suggestionJob: Job? = null
    private val cursorHandler = Handler(Looper.getMainLooper())
    private var cursorRunnable: Runnable? = null
    private val cursorDebounceMs = 120L
    private var pendingAddUserWord: String? = null
    private var previousCompletedWord: String? = null
    private var manualEditOriginalWord: String? = null
    private var manualEditExpectedWord: String? = null
    private var suppressCursorPredictionUntilMs: Long = 0L
    private val recentCompletedWords = ArrayDeque<String>()
    private var pendingInitialContextConnection: InputConnection? = null
    @Volatile private var pendingPrimaryRefreshAfterLoad: Boolean = false
    @Volatile private var pendingExtraRefreshAfterLoad: Boolean = false
    @Volatile private var suggestionGeneration: Int = 0
    private var completedWordsSinceMaintenance: Int = 0
    private var maintenanceJob: Job? = null
    private var maintenanceNowMsProvider: () -> Long = { System.currentTimeMillis() }
    internal var maintenanceRunCountForTests: Int = 0
        private set

    internal var maintenanceCompletedCountForTests: Int = 0
        private set
    private var sentenceStartPending: Boolean = true

    private fun cancelPendingWordSuggestions() {
        suggestionGeneration += 1
        suggestionJob?.cancel()
        suggestionJob = null
    }

    private fun updateSuggestionsForWord(word: String) {
        val settings = settingsProvider()
        if (!isEnabled() || !settings.suggestionsEnabled || word.isBlank() || word.length > 48) {
            cancelPendingWordSuggestions()
            pendingAddUserWord = null
            latestSuggestions.set(emptyList())
            suggestionsListener?.invoke(emptyList())
            return
        }
        if (debugLogging) {
            Log.d("PlektraIME", "trackerWordChanged='$word' len=${word.length}")
        }

        val generation = suggestionGeneration + 1
        suggestionGeneration = generation
        suggestionJob?.cancel()

        val wordSnapshot = word
        val localeSnapshot = currentLocale
        val layoutSnapshot = keyboardLayoutProvider()
        val primaryRepository = dictionaryRepository
        val previousWordSnapshot = previousCompletedWord
        // This is a bounded hash lookup, independent of expensive suggestion ranking.
        // Publish the new label immediately rather than hiding the add button until results arrive.
        pendingAddUserWord = addWordCandidateFor(wordSnapshot, primaryRepository)
        suggestionsListener?.invoke(latestSuggestions.get())
        val extraRepositories = activeExtraSuggestionEngines().mapNotNull { extra ->
            if (!extra.repository.isReady) {
                scheduleRepositoryLoad(extra.repository, refreshAfterLoad = true)
                null
            } else {
                extra.locale to extra.repository
            }
        }

        suggestionJob = suggestionScope.launch {
            // Coalesce key repeat/backspace bursts before doing CPU-bound dictionary work.
            delay(40)
            val searchContext = coroutineContext
            SuggestionWorkCancellation.withCheckpoint({ searchContext.ensureActive() }) {
            val primary = if (primaryRepository.isReady) {
                SuggestionEngine(
                    primaryRepository,
                    locale = localeSnapshot,
                    debugLogging = debugLogging,
                    usageCountProvider = { usageLocale, word ->
                        wordUsageStore.getUsage(usageLocale, word)?.count ?: 0
                    },
                    exactUsageCountProvider = { usageLocale, word ->
                        wordUsageStore.getExactUsage(usageLocale, word)?.count ?: 0
                    },
                    correctionUsageCountProvider = { locale, original, correction ->
                        correctionUsageStore.getUsage(
                            locale = locale,
                            original = original,
                            correction = correction
                        )?.count ?: 0
                    }
                ).apply {
                    setKeyboardLayout(layoutSnapshot)
                }.suggest(
                    wordSnapshot,
                    settings.maxSuggestions,
                    settings.accentMatching,
                    settings.useKeyboardProximity,
                    settings.useEditTypeRanking
                )
            } else {
                emptyList()
            }

            coroutineContext.ensureActive()
            val extraSuggestions = extraRepositories.flatMap { (locale, repository) ->
                SuggestionEngine(
                    repository,
                    locale = locale,
                    debugLogging = debugLogging,
                    usageCountProvider = { usageLocale, word ->
                        wordUsageStore.getUsage(usageLocale, word)?.count ?: 0
                    },
                    exactUsageCountProvider = { usageLocale, word ->
                        wordUsageStore.getExactUsage(usageLocale, word)?.count ?: 0
                    },
                    correctionUsageCountProvider = { locale, original, correction ->
                        correctionUsageStore.getUsage(
                            locale = locale,
                            original = original,
                            correction = correction
                        )?.count ?: 0
                    }
                ).apply {
                    setKeyboardLayout(layoutSnapshot)
                }.suggest(
                    wordSnapshot,
                    settings.maxSuggestions,
                    settings.accentMatching,
                    settings.useKeyboardProximity,
                    settings.useEditTypeRanking
                )
            }

            // Learned numbers and compounds can complete a prefix before personal-word promotion.
            val contextualCompletions = if (wordSnapshot.isNotEmpty() && previousWordSnapshot != null) {
                val prefix = WordNormalization.normalizeApostrophes(wordSnapshot).lowercase(localeSnapshot)
                nextWordPredictor.predict(localeSnapshot, previousWordSnapshot, limit = 50)
                    .filter {
                        (it.candidate.all { char -> char.isDigit() } || '-' in it.candidate ||
                            (it.score >= 3.0 && (wordUsageStore.getUsage(localeSnapshot, it.candidate)?.count ?: 0) >= 3)) &&
                            WordNormalization.normalizeApostrophes(it.candidate).lowercase(localeSnapshot).startsWith(prefix)
                    }
                    .map { it.copy(kind = SuggestionKind.CURRENT_WORD,
                        score = it.score + (primary.maxOfOrNull { candidate -> candidate.score } ?: 0.0) + 1.0) }
            } else emptyList()

            val next = mergeSuggestionResults(
                primary + contextualCompletions,
                extraSuggestions,
                settings.maxSuggestions,
                localeSnapshot
            )

            if (debugLogging) {
                val usageCount =
                    wordUsageStore.getUsage(localeSnapshot, wordSnapshot)?.count ?: 0

                Log.d(
                    "PastieraIME",
                    "SUGGEST_DIAG word='$wordSnapshot' " +
                            "locale=${localeSnapshot.toLanguageTag()} " +
                            "usage=$usageCount " +
                            "primary=${primary.joinToString { "${it.candidate}(${it.score})" }} " +
                            "extra=${extraSuggestions.joinToString { "${it.candidate}(${it.score})" }} " +
                            "merged=${next.joinToString { "${it.candidate}(${it.score})" }}"
                )
            }

// Do not offer to add an incomplete word when a real dictionary suggestion
// already completes what the user is typing.
            val normalizedInput = WordNormalization.normalizeForSuggestion(wordSnapshot, localeSnapshot)
            val hasDictionaryCompletion = normalizedInput.isNotEmpty() && next.any { suggestion ->
                if (suggestion.kind != SuggestionKind.CURRENT_WORD) {
                    false
                } else {
                    val normalizedSuggestion =
                        WordNormalization.normalizeForSuggestion(suggestion.candidate, localeSnapshot)

                    normalizedSuggestion.length > normalizedInput.length &&
                            normalizedSuggestion.startsWith(normalizedInput)
                }
            }

            val pendingCandidate = if (hasDictionaryCompletion) {
                null
            } else {
                addWordCandidateFor(wordSnapshot, primaryRepository)
            }

            cursorHandler.post {
                if (!isEnabled() || generation != suggestionGeneration || tracker.currentWord != wordSnapshot) {
                    return@post
                }
                pendingAddUserWord = pendingCandidate
                latestSuggestions.set(next)
                suggestionsListener?.invoke(next)
            }
            } // *Restore the thread checkpoint even when an obsolete search is cancelled.*
        }
    }

    private fun addWordCandidateFor(word: String?, repository: DictionaryRepository = dictionaryRepository): String? {
        val candidate = word?.trim() ?: return null
        if (candidate.isEmpty() || candidate.length > 48 || candidate.none { it.isLetter() }) return null
        if (!repository.isReady) return null
        return if (repository.isKnownWord(candidate)) null else candidate
    }
    var suggestionsListener: ((List<SuggestionResult>) -> Unit)? = onSuggestionsUpdated

    fun onCharacterCommitted(text: CharSequence, inputConnection: InputConnection?, replacesPreviousCharacter: Boolean = false) {
        val statisticsText = if (replacesPreviousCharacter) "\b$text" else text
        if (!isEnabled()) {
            typingStatisticsTracker.onTextCommitted(statisticsText)
            return
        }
        if (debugLogging) {
            val caller = Throwable().stackTrace.getOrNull(1)?.let { "${it.className}#${it.methodName}:${it.lineNumber}" }
            Log.d("PlektraIME", "SuggestionController.onCharacterCommitted('$text') caller=$caller")
        }
        ensureDictionaryLoaded()

        val wordBeforeCharacter = extractWordAtCursor(inputConnection)
        val cursorOffsetBeforeCharacter = cursorOffsetInWord(inputConnection)
        // Reuse the existing cursor inspection; statistics add no editor reads per key.
        typingStatisticsTracker.onTextCommitted(
            statisticsText,
            wordBeforeCharacter?.take(cursorOffsetBeforeCharacter ?: wordBeforeCharacter.length),
            cursorOffsetBeforeCharacter?.let { wordBeforeCharacter?.drop(it)?.take(1) }
        )

        // Normalize curly/variant apostrophes to straight for tracking and suggestions.
        val normalizedText = text
            .toString()
            .replace("'", "'")
            .replace("'", "'")
            .replace("ʼ", "'")
        if (
            normalizedText.length == 1 &&
            normalizedText[0].isLetterOrDigit() &&
            wordBeforeCharacter != null &&
            cursorOffsetBeforeCharacter != null &&
            cursorOffsetBeforeCharacter > 0 &&
            cursorOffsetBeforeCharacter < wordBeforeCharacter.length
        ) {
            val insertedIndex = cursorOffsetBeforeCharacter - 1

            if (
                manualEditOriginalWord != null &&
                manualEditExpectedWord != null
            ) {
                val expectedBeforeInsertion =
                    wordBeforeCharacter.removeRange(
                        insertedIndex,
                        insertedIndex + 1
                    )

                if (expectedBeforeInsertion == manualEditExpectedWord) {
                    manualEditExpectedWord = wordBeforeCharacter
                } else {
                    manualEditOriginalWord = null
                    manualEditExpectedWord = null
                }
            } else {
                manualEditOriginalWord =
                    wordBeforeCharacter.removeRange(
                        insertedIndex,
                        insertedIndex + 1
                    )

                manualEditExpectedWord = wordBeforeCharacter
            }
        }
        
        // Clear last replacement if user types new characters
        autoReplaceController.clearLastReplacement()
        
        // Clear rejected words when user types a new letter (allows re-correction)
        if (normalizedText.isNotEmpty() && normalizedText.any { it.isLetterOrDigit() }) {
            autoReplaceController.clearRejectedWords()
            pendingAddUserWord = null
        }
        
        if (replacesPreviousCharacter) {
            if (wordBeforeCharacter != null) tracker.setWord(wordBeforeCharacter)
            else { tracker.onBackspace(); tracker.onCharacterCommitted(normalizedText) }
        } else tracker.onCharacterCommitted(normalizedText)
    }

    fun refreshFromInputConnection(inputConnection: InputConnection?) {
        if (!isEnabled()) return

        val currentWord = extractWordAtCursor(inputConnection)
        val cursorOffset = cursorOffsetInWord(inputConnection)

        if (manualEditOriginalWord == null) {
            manualEditOriginalWord = currentWord
            manualEditExpectedWord = currentWord
        } else if (
            manualEditExpectedWord != null &&
            currentWord != manualEditExpectedWord
        ) {
            manualEditOriginalWord = null
            manualEditExpectedWord = null
        }
        tracker.onBackspace()

        manualEditExpectedWord =
            manualEditOriginalWord?.let { original ->
                val offset = cursorOffset

                if (offset != null && offset > 0 && offset <= original.length) {
                    original.removeRange(offset - 1, offset)
                        .takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }

        if (manualEditExpectedWord == null) {
            manualEditOriginalWord = null
        }
    }

    fun onBoundaryKey(
        keyCode: Int,
        event: KeyEvent?,
        inputConnection: InputConnection?,
        boundaryCharOverride: Char? = null
    ): AutoReplaceController.ReplaceResult {
        if (debugLogging) {
            Log.d(
                "PlektraIME",
                "SuggestionController.onBoundaryKey keyCode=$keyCode char=${event?.unicodeChar}"
            )
        }
        ensureDictionaryLoaded()
        val boundaryChar = boundaryCharOverride ?: boundaryCharFor(keyCode, event)
        val alreadyCompletedSuggestion = rocks.pkb.plektra.core.AutoSpaceTracker
            .consumeTrimmedSuggestionBoundary(inputConnection, boundaryChar)
        if (alreadyCompletedSuggestion) tracker.reset()

        // Exact text replacements do not depend on dictionary suggestions. In particular,
        // onCharacterCommitted intentionally does not track characters while experimental
        // suggestions are disabled, so the editor is the source of truth at a boundary.
        // Keep this synchronous and independent of repository readiness: waiting for an
        // asynchronous dictionary load would lose the boundary that triggered the replacement.
        if (inputConnection != null && !alreadyCompletedSuggestion) {
            val word = extractWordAtCursor(inputConnection, includeAfterCursor = false)
            if (!word.isNullOrBlank()) {
                tracker.setWord(word, notify = false)
                Log.d("PlektraIME", "SYNC: Synced tracker to actual word='$word' before boundary")
            }
        }

        val wordBeforeBoundary = tracker.currentWord.takeIf { it.isNotBlank() }

        val originalBeforeManualEdit = manualEditOriginalWord

        if (
            originalBeforeManualEdit != null &&
            wordBeforeBoundary != null &&
            !originalBeforeManualEdit.equals(wordBeforeBoundary, ignoreCase = false) &&
            dictionaryRepository.isAutoLearnedUserEntry(originalBeforeManualEdit)
        ) {
            learnCorrection(
                original = originalBeforeManualEdit,
                replacement = wordBeforeBoundary
            )

            manualEditOriginalWord = null
            manualEditExpectedWord = null
        }

        val result = autoReplaceController.handleBoundary(
            keyCode,
            event,
            tracker,
            inputConnection,
            boundaryCharOverride = boundaryChar
        )

        onTypingStatisticsBoundary()
        val completedWord = result.replacement ?: wordBeforeBoundary
        if (result.replaced) {
            pendingAddUserWord = addWordCandidateFor(result.replacement)
            NotificationHelper.triggerHapticFeedback(appContext)
        } else {
            pendingAddUserWord = null
        }
        handleCompletedWordBoundary(completedWord, boundaryChar)
        return result
    }

    /**
     * Reads the word at cursor immediately without debounce.
     * Use this when entering a text field to show suggestions right away.
     * If dictionary is not ready yet, does nothing - normal typing/cursor flow will handle it.
     */
    fun readInitialContext(inputConnection: InputConnection?) {
        if (!isEnabled()) return
        if (inputConnection == null) return
        if (!dictionaryRepository.isReady) {
            pendingInitialContextConnection = inputConnection
            ensureDictionaryLoaded(refreshAfterLoad = true)
            return
        }
        
        val word = extractWordAtCursor(inputConnection)
        if (!word.isNullOrBlank()) {
            tracker.setWord(word)
        } else if (previousCompletedWord == null) {
            publishSentenceStartPredictionsOrStarter()
        }
    }

    private fun readCompletedWordsBeforeCursor(
        inputConnection: InputConnection?,
        excludeCurrentWord: Boolean = false
    ): List<String> {
        if (inputConnection == null) return emptyList()

        val textBeforeCursor = inputConnection
            .getTextBeforeCursor(CURSOR_WORD_CONTEXT_CHARS, 0)
            ?.toString()
            .orEmpty()

        if (textBeforeCursor.isBlank()) return emptyList()

        var completedText = textBeforeCursor
        if (excludeCurrentWord) {
            var end = completedText.length
            while (end > 0 && !rocks.pkb.plektra.core.Punctuation.isWordBoundary(
                    completedText[end - 1], completedText.getOrNull(end - 2), completedText.getOrNull(end))) end--
            completedText = completedText.substring(0, end)
        }
        // *Reconstruct only this sentence and retain the internal punctuation used by phrase indexes.*
        return PunctuatedPhrase.contextTokens(completedText)
    }

    private fun readPreviousWordBeforeCursor(
        inputConnection: InputConnection?
    ): String? {
        if (inputConnection == null) return null

        val textBeforeCursor = inputConnection
            .getTextBeforeCursor(CURSOR_WORD_CONTEXT_CHARS, 0)
            ?.toString()
            .orEmpty()

        if (textBeforeCursor.isBlank()) return null

        val trimmed = textBeforeCursor.trimEnd()
        if (trimmed.isEmpty()) return null

        val end = trimmed.length
        var start = end

        while (
            start > 0 &&
            (
                    trimmed[start - 1].isLetterOrDigit() ||
                            trimmed[start - 1] == '\'' ||
                            trimmed[start - 1] == '’' ||
                            trimmed[start - 1] == '-'
                    )
        ) {
            start--
        }

        if (start == end) return null

        return trimmed.substring(start, end)
    }

    fun onCursorMoved(inputConnection: InputConnection?) {
        if (!isEnabled()) return

        ensureDictionaryLoaded()
        cursorRunnable?.let { cursorHandler.removeCallbacks(it) }

        if (inputConnection == null) {
            tracker.reset()
            previousCompletedWord = null
            recentCompletedWords.clear()
            sentenceStartPending = true
            suggestionsListener?.invoke(emptyList())
            return
        }

        cursorRunnable = Runnable {
            if (!isEnabled()) return@Runnable
            if (!dictionaryRepository.isReady) {
                return@Runnable
            }

            val word = extractWordAtCursor(inputConnection)
            val lastChar = lastCharBeforeCursor(inputConnection)

            if (
                !word.isNullOrBlank() &&
                !isSoftPredictionBoundary(boundaryChar = lastChar)
            ) {
                val contextFromCursor = readCompletedWordsBeforeCursor(inputConnection, excludeCurrentWord = true)
                previousCompletedWord = contextFromCursor.lastOrNull()?.let(PunctuatedPhrase::lexical)
                recentCompletedWords.clear()
                contextFromCursor.forEach { recentCompletedWords.addLast(it) }
                sentenceStartPending = previousCompletedWord == null
                tracker.setWord(word)
            } else {
                tracker.reset()

                val contextFromCursor =
                    readCompletedWordsBeforeCursor(inputConnection)

                val previousFromCursor =
                    contextFromCursor.lastOrNull()?.let(PunctuatedPhrase::lexical)

                if (previousFromCursor != null) {
                    previousCompletedWord = previousFromCursor

                    recentCompletedWords.clear()
                    contextFromCursor.forEach { word ->
                        recentCompletedWords.addLast(word)
                    }

                    sentenceStartPending = false
                }

                val previous = previousCompletedWord

                if (
                    previous != null &&
                    isSoftPredictionBoundary(boundaryChar = lastChar)
                ) {
                    val suppressPrediction =
                        android.os.SystemClock.uptimeMillis() <
                                suppressCursorPredictionUntilMs

                    if (!suppressPrediction) {
                        publishNextWordPredictions(
                            previousWord = previous
                        )
                    }
                } else {
                    previousCompletedWord = null
                    recentCompletedWords.clear()
                    sentenceStartPending = true
                    publishSentenceStartPredictionsOrStarter()
                }
            }
        }

        cursorHandler.postDelayed(
            cursorRunnable!!,
            cursorDebounceMs
        )
    }

    fun learnCorrection(
        original: String,
        replacement: String
    ) {

        if (!isEnabled() || LearningOperationGate.isLearningSuspended) return

        if (original.isBlank() || replacement.isBlank()) return
        if (
            original.any { it.isWhitespace() } ||
            replacement.any { it.isWhitespace() }
        ) return

        if (!original.equals(replacement, ignoreCase = true)) {
            wordUsageStore.delete(currentLocale, original)

            dictionaryRepositoryCache.values
                .ifEmpty { listOf(dictionaryRepository) }
                .forEach { repository ->
                    repository.removeAutoLearnedUserEntry(original)
                }
        }

        correctionUsageStore.learn(
            locale = currentLocale,
            original = original,
            correction = replacement
        )
    }

    fun onSuggestionCommitted(suggestion: String) {
        typingStatisticsTracker.onSuggestionCommitted(suggestion)
        if (!isEnabled() || LearningOperationGate.isLearningSuspended) return


        val committedWords = suggestion
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (committedWords.isEmpty()) return
        cancelPendingWordSuggestions()
        tracker.reset()
        pendingAddUserWord = null

        committedWords.forEach { word ->
            previousCompletedWord?.let { previous ->
                nextWordPredictor.learn(
                    currentLocale,
                    previous,
                    PunctuatedPhrase.lexical(word)
                )
            }

            learnPhraseUsage(word)

            previousCompletedWord = PunctuatedPhrase.lexical(word)
            sentenceStartPending = false
            // *Accepting a suggestion ending a sentence must not link its last word to the next sentence.*
            if (word.lastOrNull()?.let { PunctuatedPhrase.isTerminal(it) } == true) {
                previousCompletedWord = null
                recentCompletedWords.clear()
                sentenceStartPending = true
            }
        }
        previousCompletedWord?.let { publishNextWordPredictions(it) }
            ?: publishSentenceStartPredictionsOrStarter()
    }

    fun onTypingStatisticsBoundary() { typingStatisticsTracker.finishWord() }

    fun onTypingStatisticsBackspace() { typingStatisticsTracker.onBackspace() }

    fun finishTypingStatistics() {
        typingStatisticsTracker.finishWord()
        typingStatisticsStore.flush()
    }

    fun onContextReset() {
        cursorRunnable?.let { cursorHandler.removeCallbacks(it) }
        cursorRunnable = null
        pendingInitialContextConnection = null
        cancelPendingWordSuggestions()
        latestSuggestions.set(emptyList())
        tracker.onContextChanged()
        pendingAddUserWord = null
        previousCompletedWord = null
        recentCompletedWords.clear()
        sentenceStartPending = true
        suggestionsListener?.invoke(emptyList())
    }

    fun onNavModeToggle() {
        if (!isEnabled()) return
        tracker.onContextChanged()
        previousCompletedWord = null
        sentenceStartPending = true
    }

    fun addUserWord(word: String) {
        if (!isEnabled()) return
        dictionaryRepositoryCache.values
            .ifEmpty { listOf(dictionaryRepository) }
            .forEach { repository -> repository.addUserEntryQuick(word) }
    }

    fun removeUserWord(word: String) {
        if (!isEnabled()) return
        dictionaryRepositoryCache.values
            .ifEmpty { listOf(dictionaryRepository) }
            .forEach { repository -> repository.removeUserEntry(word) }
        refreshUserDictionary()
    }

    fun markUsed(word: String) {
        if (!isEnabled()) return
        dictionaryRepositoryCache.values
            .ifEmpty { listOf(dictionaryRepository) }
            .forEach { repository -> repository.markUsed(word) }
    }

    fun isKnownWordInActiveDictionaries(word: String): Boolean {
        if (!isEnabled()) return false
        val candidate = word.trim()
        if (candidate.isEmpty()) return false

        ensureDictionaryLoaded()
        if (dictionaryRepository.isReady && dictionaryRepository.isKnownWord(candidate)) {
            return true
        }

        return activeExtraSuggestionEngines().any { extra ->
            if (!extra.repository.isReady) {
                scheduleRepositoryLoad(extra.repository, refreshAfterLoad = false)
                // Defer legacy auto-substitution while an explicitly active
                // extra dictionary is still loading; wrong replacements are
                // worse than skipping one boundary.
                true
            } else {
                extra.repository.isKnownWord(candidate)
            }
        }
    }

    fun currentSuggestions(): List<SuggestionResult> = latestSuggestions.get()

    fun userDictionarySnapshot(): List<UserDictionaryStore.UserEntry> = userDictionaryStore.getSnapshot()

    fun dismissSuggestion(candidate: String, hardDeleteUserWord: Boolean = false) {
        if (!isEnabled()) return
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) return

        val current = latestSuggestions.get()
        val suggestion = current.firstOrNull { it.candidate.equals(trimmed, ignoreCase = true) }
        if (suggestion?.kind == SuggestionKind.NEXT_WORD) {
            forgetNextWordSuggestion(trimmed, hardDeleteEverywhere = hardDeleteUserWord)
        }
        if (hardDeleteUserWord) {
            removeUserWord(trimmed)
            activeExtraSuggestionEngines().forEach { extra ->
                if (extra.repository.isReady) {
                    extra.repository.removeUserEntry(trimmed)
                }
            }
        }

        val next = fillWithStarterSuggestions(
            current.filterNot { it.candidate.equals(trimmed, ignoreCase = true) },
            settingsProvider(),
            excludedCandidates = setOf(trimmed)
        )
        latestSuggestions.set(next)
        suggestionsListener?.invoke(next)
    }

        private fun forgetNextWordSuggestion(candidate: String, hardDeleteEverywhere: Boolean) {
            val locales = listOf(currentLocale) + activeExtraLocales()
            locales.forEach { locale ->
                if (hardDeleteEverywhere) {
                    nextWordPredictor.forgetNextWordEverywhere(locale, candidate)
                } else {
                    val previous = previousCompletedWord
                    if (previous != null) {
                        nextWordPredictor.forget(locale, previous, candidate)
                    } else {
                        nextWordPredictor.forgetSentenceStart(locale, candidate)
                    }
                }
            }
        }

    /**
     * Forces a refresh of user dictionary entries.
     * Should be called when words are added/removed from settings.
     */
    /**
     * Reloads the active base dictionary after an Extended dictionary is
     * installed or removed.
     *
     * The repository cache must be invalidated first because the active
     * repository may still contain the previously loaded Basic or Extended
     * dictionary for the same language.
     */
    fun refreshBaseDictionary() {
        if (!isEnabled()) return

        // Stop any load that may still be using the previous repository.
        currentLoadJob?.cancel()
        currentLoadJob = null
        cancelPendingWordSuggestions()

        pendingInitialContextConnection = null
        pendingPrimaryRefreshAfterLoad = false
        pendingExtraRefreshAfterLoad = false

        // Remove the cached repository for the active language.
        /*
         * A base dictionary update may affect either the primary language or one of
         * the additional suggestion languages. Invalidate all cached repositories so
         * that each language reloads its current Basic or Extended dictionary the next
         * time it is used.
         */
        dictionaryRepositoryCache.clear()

        // Recreate every component that depends on the primary repository.
        dictionaryRepository = createDictionaryRepository(currentLocale)

        suggestionEngine = SuggestionEngine(
            dictionaryRepository,
            locale = currentLocale,
            debugLogging = debugLogging,
            usageCountProvider = { locale, word ->
                wordUsageStore.getUsage(locale, word)?.count ?: 0
            },
            exactUsageCountProvider = { locale, word ->
                wordUsageStore.getExactUsage(locale, word)?.count ?: 0
            },
            correctionUsageCountProvider = { locale, original, correction ->
        correctionUsageStore.getUsage(
            locale = locale,
            original = original,
            correction = correction
        )?.count ?: 0
    }
        ).apply {
            setKeyboardLayout(keyboardLayoutProvider())
        }

        autoReplaceController = createAutoReplaceController()

        /*
         * Extra-language engines may also reference cached repositories.
         * Recreate them lazily when they are needed again.
         */
        extraSuggestionEngines.clear()

        // Start loading the newly selected Basic or Extended dictionary.
        schedulePrimaryDictionaryLoad(refreshAfterLoad = true)

        // Clear suggestions produced from the previous base dictionary.
        latestSuggestions.set(emptyList())
        suggestionsListener?.invoke(emptyList())
    }
    fun refreshUserDictionary() {
        if (!isEnabled()) return
        loadScope.launch {
            try {
                dictionaryRepository.refreshUserEntries()
            } catch (_: CancellationException) {
                // Cancelled due to rapid switches; safe to ignore.
            } catch (e: Exception) {
                Log.e("PlektraIME", "Failed to refresh user dictionary", e)
            }
        }
    }

    fun invalidateCorrectionUsageCache() {
        correctionUsageStore.invalidateCache()
    }


    fun invalidateWordUsageCache() {
        (wordUsageStore as? WordUsageStore)?.invalidateCache()
    }

    fun invalidateMultiWordCache() {
        multiWordStore.invalidateCache()
        (phraseUsageStore as? AsyncPhraseUsageRepository)?.invalidateCache()
    }


    suspend fun flushPendingNextWordLearning() {
        nextWordPredictor.flushPendingLearning()
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            (wordUsageStore as? WordUsageStore)?.flushPendingWrites()
            (correctionUsageStore as? CorrectionUsageStore)?.flushPendingWrites()
            (phraseUsageStore as? AsyncPhraseUsageRepository)?.flushPendingWrites()
            multiWordStore.flushPendingWrites()
        }
    }


    suspend fun awaitLearningMaintenance() {
        maintenanceJob?.join()
    }




    fun handleBackspaceUndo(keyCode: Int, inputConnection: InputConnection?): Boolean {
        if (!isEnabled()) return false

        val undone = autoReplaceController.handleBackspaceUndo(
            keyCode,
            inputConnection
        )

        if (undone) {
            val restoredWord =
                autoReplaceController.consumeLastUndoOriginalWord()

            val rejectedWord =
                autoReplaceController.consumeLastUndoRejectedWord()

            pendingAddUserWord = restoredWord

            if (
                !restoredWord.isNullOrBlank() &&
                !rejectedWord.isNullOrBlank()
            ) {
                learnCorrection(
                    original = rejectedWord,
                    replacement = restoredWord
                )
            }
        }

        return undone
    }

    fun pendingAddWord(): String? = pendingAddUserWord
    fun clearPendingAddWord() {
        pendingAddUserWord = null
    }

    internal fun clearLearnedNextWordsForTests() {
        nextWordPredictor.clearAll()
        previousCompletedWord = null
    }

    internal fun flushNextWordLearningForTests() {
        nextWordPredictor.flushLearningForTests()
        (wordUsageStore as? WordUsageStore)?.flushPendingWrites()
        (correctionUsageStore as? CorrectionUsageStore)?.flushPendingWrites()
        (phraseUsageStore as? AsyncPhraseUsageRepository)?.flushPendingWrites()
        multiWordStore.flushPendingWrites()
    }

    internal fun noteCompletedWordForMaintenanceForTests() {
        noteCompletedWordForMaintenance()
    }
    internal fun setMaintenanceNowMsForTests(nowMs: Long) {
        maintenanceNowMsProvider = { nowMs }
    }

    internal fun maybeConsolidateUnknownWordForTests(word: String) {
        maybeConsolidateUnknownWord(word)
    }

    internal fun waitForMaintenanceForTests() {
        kotlinx.coroutines.runBlocking {
            maintenanceJob?.join()
        }
    }

    fun destroy() {
        finishTypingStatistics()
        currentLoadJob?.cancel()
        suggestionJob?.cancel()
        maintenanceJob?.cancel()
        maintenanceJob = null
        cursorRunnable?.let { cursorHandler.removeCallbacks(it) }
        if (!asyncPersistence) {
            nextWordPredictor.destroy()
            (wordUsageStore as? WordUsageStore)?.closeWhenDrained()
            (correctionUsageStore as? CorrectionUsageStore)?.closeWhenDrained()
            (phraseUsageStore as? AsyncPhraseUsageRepository)?.closeWhenDrained()
            multiWordStore.closeWhenDrained()
        }
        // *Production models survive a service restart; accepted writes keep draining.*
    }

    private fun learnPhraseUsage(
        completedWord: String
    ): List<List<String>> {
        if (!settingsProvider().multiWordSuggestionsEnabled) {
            recentCompletedWords.clear()
            return emptyList()
        }

        recentCompletedWords.addLast(completedWord)

        while (recentCompletedWords.size > PhraseUsageStore.MAX_SEQUENCE_LENGTH) {
            recentCompletedWords.removeFirst()
        }

        val words = recentCompletedWords.toList()
        val learnedSequences = mutableListOf<List<String>>()

        for (length in PhraseUsageStore.MIN_SEQUENCE_LENGTH..words.size) {
            val sequence = words.takeLast(length)

            phraseUsageStore.learn(
                currentLocale,
                sequence
            )

            learnedSequences += sequence
        }

        return learnedSequences
    }

    private fun promoteEligibleMultiWordSequences() {
        if (!settingsProvider().multiWordSuggestionsEnabled) {
            return
        }

        multiWordPromotionEngine.promoteEligible(
            locale = currentLocale
        )
    }

    private fun maybeConsolidateUnknownWord(word: String) {
        if (word.length < 3 || word.none { it.isLetter() }) return

        // A normal dictionary word does not need promotion to personal vocabulary.
        if (isKnownWordInActiveDictionaries(word)) return

        val usage = wordUsageStore.getUsage(currentLocale, word) ?: return
        if (usage.count < UNKNOWN_WORD_PROMOTION_THRESHOLD) return

        userDictionaryStore.addAutoLearnedWord(
            appContext,
            usage.word
        )

        dictionaryRepositoryCache.values
            .ifEmpty { listOf(dictionaryRepository) }
            .forEach { repository ->
                repository.indexStoredUserEntryQuick(usage.word)
            }
    }

    private fun noteCompletedWordForMaintenance() {

        if (LearningOperationGate.isLearningSuspended) {
            return
        }

        completedWordsSinceMaintenance++

        if (completedWordsSinceMaintenance < LEARNING_MAINTENANCE_INTERVAL_WORDS) {
            return
        }

        completedWordsSinceMaintenance = 0

        if (maintenanceJob?.isActive == true) {
            return
        }

        maintenanceJob = loadScope.launch {
            maintenanceRunCountForTests++

            val nowMs = maintenanceNowMsProvider()

            val removedAutoLearnedWords =
                userDictionaryStore.pruneAutoLearnedWords(
                    context = appContext,
                    nowMs = nowMs,
                    staleAfterMs = AUTO_LEARNED_STALE_AFTER_MS,
                    weakFrequencyThreshold = AUTO_LEARNED_WEAK_FREQUENCY_THRESHOLD,
                    maxEntries = AUTO_LEARNED_MAX_ENTRIES
                )

            if (removedAutoLearnedWords.isNotEmpty()) {
                dictionaryRepositoryCache.values
                    .ifEmpty { listOf(dictionaryRepository) }
                    .forEach { repository ->
                        removedAutoLearnedWords.forEach { word ->
                            repository.removeStoredUserEntryQuick(word)
                        }
                    }
            }

            ensureActive()

            wordUsageStore.prune(
                nowMs = nowMs,
                staleAfterMs = WORD_USAGE_STALE_AFTER_MS,
                weakCountThreshold = WORD_USAGE_WEAK_COUNT_THRESHOLD,
                maxEntriesPerLocale = WORD_USAGE_MAX_ENTRIES_PER_LOCALE
            )

            ensureActive()

            nextWordPredictor.prune(
                nowMs = nowMs,
                staleAfterMs = NGRAM_STALE_AFTER_MS,
                weakCountThreshold = NGRAM_WEAK_COUNT_THRESHOLD,
                maxEntriesPerLocale = NGRAM_MAX_ENTRIES_PER_LOCALE
            )

            ensureActive()

            phraseUsageStore.prune(
                nowMs = nowMs,
                staleAfterMs = PHRASE_USAGE_STALE_AFTER_MS,
                weakCountThreshold = PHRASE_USAGE_WEAK_COUNT_THRESHOLD,
                maxEntriesPerLocale = PHRASE_USAGE_MAX_ENTRIES_PER_LOCALE
            )

            ensureActive()

            // The broad eligibility scan belongs to maintenance, not to every
            // word boundary. Newly learned sequences are promoted individually.
            promoteEligibleMultiWordSequences()
            multiWordPromotionEngine.demoteIneligibleAll()

            ensureActive()

            maintenanceCompletedCountForTests++
        }
    }

    private fun handleCompletedWordBoundary(
        completedWord: String?,
        boundaryChar: Char?
    ) {
        if (LearningOperationGate.isLearningSuspended) {
            return
        }
        val settings = settingsProvider()
        if (!settings.suggestionsEnabled) {
            previousCompletedWord = null
            latestSuggestions.set(emptyList())
            suggestionsListener?.invoke(emptyList())
            return
        }

        val cleanWord = completedWord
            ?.trim()
            ?.takeIf { it.any { ch -> ch.isLetterOrDigit() } }

        Log.d(
            "PastieraPhraseDebug",
            "completed='$completedWord' clean='$cleanWord' " +
                    "previous='$previousCompletedWord' " +
                    "context=${recentCompletedWords.toList()} " +
                    "locale=${currentLocale.toLanguageTag()}"
        )

        val originalBeforeManualEdit = manualEditOriginalWord
        val expectedAfterManualEdit = manualEditExpectedWord

        manualEditOriginalWord = null
        manualEditExpectedWord = null

        if (
            originalBeforeManualEdit != null &&
            expectedAfterManualEdit != null &&
            cleanWord != null &&
            cleanWord == expectedAfterManualEdit &&
            !originalBeforeManualEdit.equals(cleanWord, ignoreCase = false)
        ) {
            learnCorrection(
                original = originalBeforeManualEdit,
                replacement = cleanWord
            )
        }

        if (cleanWord == null && PunctuatedPhrase.isInternal(boundaryChar) && recentCompletedWords.isNotEmpty()) {
            // *Punctuation after Space or an accepted suggestion decorates the last token without relearning it.*
            val last = recentCompletedWords.removeLast()
            recentCompletedWords.addLast(PunctuatedPhrase.attach(last, boundaryChar!!, settingsProvider().frenchPunctuationSpacing))
        }
        val phraseWord = cleanWord?.let { word ->
            if (PunctuatedPhrase.isRetained(boundaryChar)) PunctuatedPhrase.attach(word, boundaryChar!!, settings.frenchPunctuationSpacing) else word
        }
        if (cleanWord == null && boundaryChar != null && PunctuatedPhrase.isTerminal(boundaryChar) &&
            PunctuatedPhrase.isRetained(boundaryChar) && recentCompletedWords.isNotEmpty()) {
            // *A final sign after Space or an accepted word records the decorated phrase, not a second word occurrence.*
            val last = recentCompletedWords.removeLast()
            val sequences = learnPhraseUsage(PunctuatedPhrase.attach(last, boundaryChar, settings.frenchPunctuationSpacing))
            multiWordStore.batchUpdates {
                sequences.forEach { sequence ->
                    multiWordStore.markUsed(locale = currentLocale, words = sequence)
                    multiWordPromotionEngine.promoteIfEligible(locale = currentLocale, words = sequence)
                }
            }
        }
        if (cleanWord != null) {
            wordUsageStore.learn(currentLocale, cleanWord)
            maybeConsolidateUnknownWord(cleanWord)

            val learnedPhraseSequences =
                learnPhraseUsage(phraseWord ?: cleanWord)

            if (sentenceStartPending) {
                nextWordPredictor.learnSentenceStart(
                    currentLocale,
                    cleanWord
                )
            }

            previousCompletedWord?.let { previous ->
                nextWordPredictor.learn(
                    currentLocale,
                    previous,
                    cleanWord
                )
            }

            multiWordStore.batchUpdates {
            learnedPhraseSequences.forEach { sequence ->
                multiWordStore.markUsed(
                    locale = currentLocale,
                    words = sequence
                )

                multiWordPromotionEngine.promoteIfEligible(
                    locale = currentLocale,
                    words = sequence
                )
            }

            }

            noteCompletedWordForMaintenance()
        }

        when {
            cleanWord != null && isSoftPredictionBoundary(boundaryChar) -> {
                previousCompletedWord = cleanWord
                sentenceStartPending = false
                suppressCursorPredictionUntilMs =
                    android.os.SystemClock.uptimeMillis() +
                            CURSOR_PREDICTION_SUPPRESSION_MS

                publishNextWordPredictions(
                    previousWord = cleanWord
                )
            }

            cleanWord == null && isSoftPredictionBoundary(boundaryChar) -> {
                val previous = previousCompletedWord

                if (previous != null) {
                    publishNextWordPredictions(
                        previousWord = previous
                    )
                } else {
                    latestSuggestions.set(emptyList())
                    suggestionsListener?.invoke(emptyList())
                }
            }

            else -> {
                previousCompletedWord = null
                recentCompletedWords.clear()
                sentenceStartPending = true
                latestSuggestions.set(emptyList())
                suggestionsListener?.invoke(emptyList())
            }
        }
    }

    private fun phrasePredictions(
        limit: Int
    ): List<PhraseUsageStore.Prediction> {
        if (!settingsProvider().multiWordSuggestionsEnabled) {
            return emptyList()
        }

        if (limit <= 0 || recentCompletedWords.isEmpty()) {
            return emptyList()
        }

        return phraseUsageStore.predictNext(
            locale = currentLocale,
            contextWords = recentCompletedWords.toList(),
            limit = limit
        )
    }



    private fun promotedMultiWordPredictions(
        phrasePredictions: List<PhraseUsageStore.Prediction>,
        limit: Int
    ): List<SuggestionResult> {
        if (!settingsProvider().multiWordSuggestionsEnabled || limit <= 0) {
            return emptyList()
        }

        val context = recentCompletedWords
            .map {
                PunctuatedPhrase.normalize(it, currentLocale)
            }
            .filter { it.isNotBlank() }

        if (context.isEmpty()) return emptyList()

        // *Lexical transition evidence also supports a comma attached after the word's earlier Space boundary.*
        val predictedWords = phrasePredictions.associateBy {
            WordNormalization.normalizeForSuggestion(it.word, currentLocale)
        }

        val candidates = multiWordStore.getForContext(currentLocale, context)
            .asSequence()
            .flatMap { entry ->
                val normalized = entry.normalizedWords

                if (normalized.size < 2) {
                    return@flatMap emptySequence<SuggestionResult>()
                }

                val matchLength =
                    (minOf(context.size, normalized.size - 1) downTo 1)
                        .firstOrNull { length ->
                            context.takeLast(length) == normalized.take(length)
                        }
                        ?: return@flatMap emptySequence<SuggestionResult>()

                val remainingWords = entry.words.drop(matchLength)

                if (remainingWords.isEmpty()) {
                    return@flatMap emptySequence<SuggestionResult>()
                }

                val prediction = predictedWords[PunctuatedPhrase.lexical(normalized[matchLength])]
                    ?: return@flatMap emptySequence<SuggestionResult>()

                val baseScore =
                    matchLength * 1_000_000.0 +
                            prediction.count.toDouble() +
                            entry.phraseCount.toDouble()

                remainingWords.indices.asSequence().map { index ->
                    val candidate = remainingWords
                        .take(index + 1)
                        .joinToString(" ")

                    SuggestionResult(
                        candidate = candidate,
                        distance = 0,
                        score = baseScore - index * 0.01,
                        source = SuggestionSource.USER,
                        kind = SuggestionKind.NEXT_WORD
                    )
                }
            }
            .distinctBy { it.candidate.lowercase(currentLocale) }
            .sortedByDescending { it.score }
            .toList()
        return PunctuatedPhrase.diverse(candidates, currentLocale).take(limit)
    }



    private fun canonicalizeNextWordPrediction(
        result: SuggestionResult,
        locale: Locale,
        repository: DictionaryRepository,
        contextWord: String? = previousCompletedWord
    ): SuggestionResult {
        if (result.kind != SuggestionKind.NEXT_WORD) {
            return result
        }

        if (result.candidate.any { it.isWhitespace() || PunctuatedPhrase.isRetained(it) }) {
            return result
        }

        if (!repository.isReady) {
            return result
        }

        val normalized =
            WordNormalization.normalizeForSuggestion(
                result.candidate,
                locale
            )

        if (result.candidate.contains("souviens", ignoreCase = true)) {
            val entries = repository.topByNormalized(normalized, limit = 16)

            android.util.Log.d(
                "PastieraSouviensRepo",
                "candidate='${result.candidate}' normalized='$normalized' " +
                        "entries=${entries.joinToString { "'${it.word}'(${it.source})" }}"
            )
        }

        // *Preserve the spelling actually repeated in this transition; do not infer first names or surnames.*
        if (result.candidate.any { it.isUpperCase() } && contextWord != null &&
            (nextWordPredictor.getUsage(locale, PunctuatedPhrase.lexical(contextWord), result.candidate)?.count ?: 0) >= 3) {
            return result
        }
        val canonical = repository
            .topByNormalized(normalized, limit = 16)
            .filter { entry ->
                entry.word.equals(result.candidate, ignoreCase = true)
            }
            .let { entries ->
                // Prefer the lowercase dictionary form when both sentence-initial
                // and ordinary spellings exist. Preserve mixed-case proper names
                // and acronyms when there is no lowercase dictionary alternative.
                entries.firstOrNull { it.word == it.word.lowercase(locale) }
                    ?: entries.firstOrNull()
            }
            ?.word
            ?: return result

        return result.copy(candidate = canonical)
    }
    private fun publishNextWordPredictions(
        previousWord: String
    ) {
        val settings = settingsProvider()

        val primary = nextWordPredictor.predict(
            currentLocale,
            previousWord,
            settings.maxSuggestions
        ).map { result ->
            canonicalizeNextWordPrediction(
                result = result,
                locale = currentLocale,
                repository = dictionaryRepository
            )
        }

        val extraLocales = activeExtraLocales()
        val extras = extraLocales.flatMap { locale ->
            val repository = dictionaryRepositoryCache[dictionaryCacheKey(locale)]

            nextWordPredictor.predict(
                locale,
                previousWord,
                settings.maxSuggestions
            ).map { result ->
                if (repository != null) {
                    canonicalizeNextWordPrediction(
                        result = result,
                        locale = locale,
                        repository = repository
                    )
                } else {
                    result
                }
            }
        }

        val phrasePredictions = phrasePredictions(
            limit = maxOf(settings.maxSuggestions, 30)
        )

        val phraseSingleWord = phrasePredictions
            .filter { it.words.size == 1 }
            .map { prediction ->
                canonicalizeNextWordPrediction(
                    result = SuggestionResult(
                        candidate = prediction.word,
                        distance = 0,
                        score =
                            (if (prediction.contextLength > 1) prediction.contextLength * 1_000_000.0 else 0.0) +
                                    prediction.count.toDouble(),
                        source = SuggestionSource.USER,
                        kind = SuggestionKind.NEXT_WORD
                    ),
                    locale = currentLocale,
                    repository = dictionaryRepository
                )
            }


        val directlyPromotedMultiWord = promotedMultiWordPredictions(
            phrasePredictions = phrasePredictions,
            limit = settings.maxSuggestions
        )

        val predictedFirstWords = (phraseSingleWord + primary)
            .map { prediction ->
                PunctuatedPhrase.normalize(
                    prediction.candidate,
                    currentLocale
                )
            }
            .toSet()

        val chainedMultiWord = if (
            settings.multiWordSuggestionsEnabled &&
            predictedFirstWords.isNotEmpty()
        ) {
            val chained = multiWordStore.getStartingWith(currentLocale, predictedFirstWords)
                .asSequence()
                .filter { entry ->
                    entry.words.size >= 2 &&
                            entry.normalizedWords.firstOrNull() in predictedFirstWords
                }
                .flatMap { entry ->
                    val firstWord = entry.words.first()
                    val remainingWords = entry.words.drop(1)

                    remainingWords.indices.asSequence().map { index ->
                        val candidate = (
                                listOf(firstWord) + remainingWords.take(index + 1)
                                ).joinToString(" ")

                        SuggestionResult(
                            candidate = candidate,
                            distance = 0,
                            score = entry.phraseCount.toDouble(),
                            source = SuggestionSource.USER,
                            kind = SuggestionKind.NEXT_WORD
                        )
                    }
                }
                .distinctBy { it.candidate.lowercase(currentLocale) }
                .sortedByDescending { it.score }
                .toList()
            PunctuatedPhrase.diverse(chained, currentLocale).take(settings.maxSuggestions)
        } else {
            emptyList()
        }

        val promotedMultiWord = (
                directlyPromotedMultiWord + chainedMultiWord
                ).map { result ->
                val words = result.candidate.split(Regex("\\s+"))
                result.copy(candidate = words.mapIndexed { index, word ->
                    canonicalizeNextWordPrediction(
                        result.copy(candidate = word), currentLocale, dictionaryRepository,
                        contextWord = words.getOrNull(index - 1) ?: previousCompletedWord
                    ).candidate
                }.joinToString(" "))
            }.distinctBy {
                it.candidate.lowercase(currentLocale)
            }



        val rankedPredictions = mergeSuggestionResults(
            promotedMultiWord + phraseSingleWord + primary,
            extras,
            settings.maxSuggestions
        )

        val bestMultiWord = promotedMultiWord.firstOrNull {
            it.candidate.any { char -> char.isWhitespace() }
        }

        val predictions = if (
            bestMultiWord != null &&
            settings.maxSuggestions > 0 &&
            rankedPredictions.none {
                it.candidate.equals(bestMultiWord.candidate, ignoreCase = true)
            }
        ) {
            val remaining = rankedPredictions
                .filterNot {
                    it.candidate.equals(bestMultiWord.candidate, ignoreCase = true)
                }
                .take(settings.maxSuggestions - 1)

            remaining + bestMultiWord
        } else {
            rankedPredictions
        }

        if (debugLogging) android.util.Log.d(
            "PastieraSouviensRun",
            "prev='$previousWord' " +
                    "primary=${primary.joinToString { "'${it.candidate}'" }} " +
                    "phraseSingle=${phraseSingleWord.joinToString { "'${it.candidate}'" }} " +
                    "promoted=${promotedMultiWord.joinToString { "'${it.candidate}'" }} " +
                    "extras=${extras.joinToString { "'${it.candidate}'" }} " +
                    "FINAL=${predictions.joinToString { "'${it.candidate}'" }}"
        )

        // After a completed word, only genuine contextual predictions belong here.
        // Do not fill empty slots with generic starter words: doing so can surface
        // unrelated high-frequency words from the primary or extra dictionaries.
        val suggestions = predictions

        latestSuggestions.set(suggestions)
        suggestionsListener?.invoke(suggestions)
    }

    private fun publishSentenceStartPredictionsOrStarter() {
        val settings = settingsProvider()
        val primary = nextWordPredictor.predictSentenceStart(currentLocale, settings.maxSuggestions)
        val extras = activeExtraLocales().flatMap { locale ->
            nextWordPredictor.predictSentenceStart(locale, settings.maxSuggestions)
        }
        val predictions = mergeSuggestionResults(primary, extras, settings.maxSuggestions)
        val suggestions = fillWithStarterSuggestions(predictions, settings)
        if (suggestions.isNotEmpty()) {
            latestSuggestions.set(suggestions)
            suggestionsListener?.invoke(suggestions)
        } else {
            publishStarterSuggestions()
        }
    }

    private fun publishStarterSuggestions() {

        val settings = settingsProvider()
        if (!settings.suggestionsEnabled || !dictionaryRepository.isReady) {
            latestSuggestions.set(emptyList())
            suggestionsListener?.invoke(emptyList())
            return
        }

        val suggestions = starterSuggestions(settings)
        latestSuggestions.set(suggestions)
        suggestionsListener?.invoke(suggestions)
    }

    private fun starterSuggestions(settings: SuggestionSettings): List<SuggestionResult> {

        val primary = starterSuggestionsFor(dictionaryRepository, PRIMARY_SUGGESTION_BOOST, settings.maxSuggestions)
        val extras = activeExtraSuggestionEngines().flatMap { extra ->
            if (!extra.repository.isReady) {
                scheduleRepositoryLoad(extra.repository, refreshAfterLoad = true)
                emptyList()
            } else {
                starterSuggestionsFor(extra.repository, 0.0, settings.maxSuggestions)
            }
        }
        return mergeSuggestionResults(primary, extras, settings.maxSuggestions)
    }

    private fun fillWithStarterSuggestions(
        predictions: List<SuggestionResult>,
        settings: SuggestionSettings
    ): List<SuggestionResult> {
        if (predictions.size >= settings.maxSuggestions) return predictions.take(settings.maxSuggestions)

        val seen = predictions
            .mapTo(HashSet()) { it.candidate.lowercase(currentLocale) }
        val fillers = starterSuggestions(settings)
            .filter { seen.add(it.candidate.lowercase(currentLocale)) }
        return (predictions + fillers).take(settings.maxSuggestions)
    }

    private fun fillWithStarterSuggestions(
        predictions: List<SuggestionResult>,
        settings: SuggestionSettings,
        excludedCandidates: Set<String>
    ): List<SuggestionResult> {
        if (predictions.size >= settings.maxSuggestions) return predictions.take(settings.maxSuggestions)

        val excluded = excludedCandidates.mapTo(HashSet()) { it.lowercase(currentLocale) }
        val seen = predictions
            .mapTo(HashSet()) { it.candidate.lowercase(currentLocale) }
        val fillers = starterSuggestions(settings)
            .filter { result ->
                val key = result.candidate.lowercase(currentLocale)
                key !in excluded && seen.add(key)
            }
        return (predictions + fillers).take(settings.maxSuggestions)
    }

    private fun starterSuggestionsFor(
        repository: DictionaryRepository,
        scoreBoost: Double,
        limit: Int
    ): List<SuggestionResult> {
        val starterEntries = repository.topCommonEntries(
            limit = limit,
            includeUserEntries = false
        )

        return starterEntries
            .map { entry ->
                SuggestionResult(
                    candidate = entry.word,
                    distance = 0,
                    score = repository.effectiveFrequency(entry) / 1_600.0 + scoreBoost,
                    source = entry.source,
                    kind = SuggestionKind.STARTER_WORD
                )
            }
            .take(limit)
    }

    private fun mergeSuggestionResults(
        primary: List<SuggestionResult>,
        extras: List<SuggestionResult>,
        limit: Int,
        locale: Locale = currentLocale
    ): List<SuggestionResult> {
        val seen = HashSet<String>()
        return (primary.map { it to PRIMARY_SUGGESTION_BOOST } + extras.map { it to 0.0 })
            .sortedWith(
                compareByDescending<Pair<SuggestionResult, Double>> { (result, boost) ->
                    result.score + boost
                }.thenBy { (result, _) -> result.candidate.length }
            )
            .map { it.first }
            .filter { result -> seen.add(result.candidate.lowercase(locale)) }
            .take(limit)
    }

    private fun activeExtraLocales(): List<Locale> {
        val primaryLanguage = currentLocale.language.lowercase(Locale.ROOT)
        return activeSuggestionLocalesProvider?.invoke().orEmpty()
            .filter { it.language.isNotBlank() }
            .filter { it.language.lowercase(Locale.ROOT) != primaryLanguage }
            .distinctBy { it.toLanguageTag().lowercase(Locale.ROOT) }
    }

    private fun activeExtraSuggestionEngines(): List<SuggestionLanguageEngine> {
        val activeLocales = activeExtraLocales()
        val activeTags = activeLocales.map { it.toLanguageTag() }.toSet()
        extraSuggestionEngines.keys
            .filterNot { it in activeTags }
            .forEach { extraSuggestionEngines.remove(it) }
        return activeLocales.map { locale ->
            val tag = locale.toLanguageTag()
            extraSuggestionEngines.getOrPut(tag) {
                val repository = createDictionaryRepository(locale)
                val engine = SuggestionEngine(
                    repository,
                    locale = locale,
                    debugLogging = debugLogging,
                    usageCountProvider = { usageLocale, word ->
                        wordUsageStore.getUsage(usageLocale, word)?.count ?: 0
                    },
                            exactUsageCountProvider = { usageLocale, word ->
                        wordUsageStore.getExactUsage(usageLocale, word)?.count ?: 0
                    }
                ).apply {
                    setKeyboardLayout(keyboardLayoutProvider())
                }
                SuggestionLanguageEngine(locale, repository, engine)
            }
        }
    }

    private fun scheduleRepositoryLoad(repository: DictionaryRepository, refreshAfterLoad: Boolean) {
        if (refreshAfterLoad) {
            pendingExtraRefreshAfterLoad = true
        }
        if (!repository.isReady && !repository.isLoadStarted) {
            loadScope.launch {
                try {
                    repository.loadIfNeeded()

// Warm up the starter-word cache for this repository off the main thread.
                    if (repository.isReady) {
                        repository.topCommonEntries(
                            limit = settingsProvider().maxSuggestions,
                            includeUserEntries = false
                        )
                    }

                    val shouldRefresh = refreshAfterLoad || pendingExtraRefreshAfterLoad
                    if (shouldRefresh && repository.isReady) {
                        pendingExtraRefreshAfterLoad = false
                        cursorHandler.post {
                            val word = tracker.currentWord
                            if (word.isNotBlank()) {
                                updateSuggestionsForWord(word)
                            } else if (previousCompletedWord == null) {
                                publishSentenceStartPredictionsOrStarter()
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    // Cancelled due to rapid switches; safe to ignore.
                } catch (e: Exception) {
                    Log.e("PlektraIME", "Failed to load extra dictionary", e)
                }
            }
        }
    }

    private fun isSoftPredictionBoundary(boundaryChar: Char?): Boolean {
        return boundaryChar == ' ' || boundaryChar == ',' || boundaryChar == ';' || boundaryChar == ':'
    }

    private fun boundaryCharFor(keyCode: Int, event: KeyEvent?): Char? {
        val unicodeChar = event?.unicodeChar ?: 0
        return when {
            unicodeChar != 0 -> unicodeChar.toChar()
            keyCode == KeyEvent.KEYCODE_SPACE -> ' '
            keyCode == KeyEvent.KEYCODE_ENTER -> '\n'
            keyCode == KeyEvent.KEYCODE_COMMA -> ','
            keyCode == KeyEvent.KEYCODE_SEMICOLON -> ';'
            keyCode == KeyEvent.KEYCODE_PERIOD -> '.'
            keyCode == KeyEvent.KEYCODE_SLASH -> '/'
            keyCode == KeyEvent.KEYCODE_LEFT_BRACKET -> '['
            keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET -> ']'
            keyCode == KeyEvent.KEYCODE_BACKSLASH -> '\\'
            else -> KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                .get(keyCode, 0)
                .takeIf { it != 0 }
                ?.toChar()
        }
    }

    private fun lastCharBeforeCursor(inputConnection: InputConnection?): Char? {
        return try {
            inputConnection?.getTextBeforeCursor(1, 0)?.lastOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Clears the pending add-word candidate if the cursor is no longer on that word.
     * Keeps the candidate only while the cursor remains on the originating token.
     */
    fun clearPendingAddWordIfCursorOutside(inputConnection: InputConnection?) {
        val pending = pendingAddUserWord ?: return
        val currentWord = extractWordAtCursor(inputConnection)
        if (currentWord == null || !currentWord.equals(pending, ignoreCase = true)) {
            pendingAddUserWord = null
        }
    }

    private fun cursorOffsetInWord(
        inputConnection: InputConnection?
    ): Int? {
        if (inputConnection == null) return null

        return try {
            val before = inputConnection
                .getTextBeforeCursor(CURSOR_WORD_CONTEXT_CHARS, 0)
                ?.toString()
                ?: return null

            var start = before.length

            while (start > 0) {
                val ch = before[start - 1]
                val prev = before.getOrNull(start - 2)
                val next = before.getOrNull(start)

                if (!rocks.pkb.plektra.core.Punctuation.isWordBoundary(
                        ch,
                        prev,
                        next
                    )
                ) {
                    start--
                } else {
                    break
                }
            }

            before.length - start
        } catch (_: Exception) {
            null
        }
    }

    private fun extractWordAtCursor(
        inputConnection: InputConnection?,
        includeAfterCursor: Boolean = true
    ): String? {
        if (inputConnection == null) return null
        return try {
            val before = inputConnection.getTextBeforeCursor(CURSOR_WORD_CONTEXT_CHARS, 0)?.toString() ?: ""
            val after = if (includeAfterCursor) {
                inputConnection.getTextAfterCursor(CURSOR_WORD_CONTEXT_CHARS, 0)?.toString() ?: ""
            } else {
                ""
            }
            var start = before.length
            while (start > 0) {
                val ch = before[start - 1]
                val prev = before.getOrNull(start - 2)
                val next = before.getOrNull(start)
                if (!rocks.pkb.plektra.core.Punctuation.isWordBoundary(ch, prev, next)) {
                    start--
                    continue
                }
                break
            }
            var end = 0
            if (includeAfterCursor) {
                while (end < after.length) {
                    val ch = after[end]
                    val prev = if (end == 0) before.lastOrNull() else after[end - 1]
                    val next = after.getOrNull(end + 1)
                    if (!rocks.pkb.plektra.core.Punctuation.isWordBoundary(ch, prev, next)) {
                        end++
                        continue
                    }
                    break
                }
            }
            val word = before.substring(start) + after.substring(0, end)
            if (word.isBlank()) null else word
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Preloads the dictionary in background.
     * Should be called during initialization to have dictionary ready when user focuses a field.
     */
    fun preloadDictionary() {
        if (settingsProvider().multiWordSuggestionsEnabled) {
            loadScope.launch { multiWordStore.preload() }
        }
        schedulePrimaryDictionaryLoad(refreshAfterLoad = false)
        activeExtraSuggestionEngines().forEach { scheduleRepositoryLoad(it.repository, refreshAfterLoad = false) }
    }

    private fun ensureDictionaryLoaded(refreshAfterLoad: Boolean = false) {
        if (!dictionaryRepository.isReady) {
            schedulePrimaryDictionaryLoad(refreshAfterLoad)
        }
    }

    private fun schedulePrimaryDictionaryLoad(refreshAfterLoad: Boolean) {
        val repository = dictionaryRepository
        if (refreshAfterLoad) {
            pendingPrimaryRefreshAfterLoad = true
        }

        if (repository.isReady) {
            if (refreshAfterLoad) {
                pendingPrimaryRefreshAfterLoad = false
                cursorHandler.post {
                    refreshSuggestionsAfterDictionaryReady(repository)
                }
            }
            return
        }

        if (repository.isLoadStarted) return

        currentLoadJob = loadScope.launch {
            try {
                repository.loadIfNeeded()

                // Warm up the starter-word cache off the main thread.
                // The first topCommonEntries() call is expensive; later calls are effectively free.
                if (repository.isReady) {
                    repository.topCommonEntries(
                        limit = settingsProvider().maxSuggestions,
                        includeUserEntries = false
                    )
                }

                val shouldRefresh =
                    refreshAfterLoad || pendingPrimaryRefreshAfterLoad

                if (shouldRefresh && repository.isReady) {
                    pendingPrimaryRefreshAfterLoad = false
                    cursorHandler.post {
                        refreshSuggestionsAfterDictionaryReady(repository)
                    }
                }
            } catch (_: CancellationException) {
                // Cancelled due to rapid switches; safe to ignore.
            } catch (e: Exception) {
                Log.e("PlektraIME", "Failed to load dictionary", e)
            }
        }
    }

    private fun refreshSuggestionsAfterDictionaryReady(repository: DictionaryRepository) {
        if (!isEnabled() || repository !== dictionaryRepository || !repository.isReady) return

        pendingInitialContextConnection?.let { inputConnection ->
            pendingInitialContextConnection = null
            val word = extractWordAtCursor(inputConnection)
            if (!word.isNullOrBlank()) {
                tracker.setWord(word)
                return
            }
            if (previousCompletedWord == null) {
                publishSentenceStartPredictionsOrStarter()
                return
            }
        }

        val word = tracker.currentWord
        val previous = previousCompletedWord
        if (word.isNotBlank()) {
            updateSuggestionsForWord(word)
        } else if (previous == null) {
            publishSentenceStartPredictionsOrStarter()
        } else {
            publishNextWordPredictions(previous)
        }
    }

    companion object {
        private const val CURSOR_WORD_CONTEXT_CHARS = 128
        private const val PRIMARY_SUGGESTION_BOOST = 0.35
        private const val CURSOR_PREDICTION_SUPPRESSION_MS = 300L
        private const val UNKNOWN_WORD_PROMOTION_THRESHOLD = 3
        private const val LEARNING_MAINTENANCE_INTERVAL_WORDS = 500
        private const val WORD_USAGE_STALE_AFTER_MS =
            90L * 24L * 60L * 60L * 1000L
        private const val WORD_USAGE_WEAK_COUNT_THRESHOLD = 2
        private const val WORD_USAGE_MAX_ENTRIES_PER_LOCALE = 20_000

        private const val NGRAM_STALE_AFTER_MS =
            60L * 24L * 60L * 60L * 1000L
        private const val NGRAM_WEAK_COUNT_THRESHOLD = 1
        private const val NGRAM_MAX_ENTRIES_PER_LOCALE = 30_000

        private const val PHRASE_USAGE_STALE_AFTER_MS =
            60L * 24L * 60L * 60L * 1000L
        private const val PHRASE_USAGE_WEAK_COUNT_THRESHOLD = 1
        private const val PHRASE_USAGE_MAX_ENTRIES_PER_LOCALE = 20_000
        private const val AUTO_LEARNED_STALE_AFTER_MS =
            90L * 24L * 60L * 60L * 1000L
        private const val AUTO_LEARNED_WEAK_FREQUENCY_THRESHOLD = 2
        private const val AUTO_LEARNED_MAX_ENTRIES = 10_000
    }
}

