package rocks.pkb.plektra.core.suggestions

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PunctuatedPhraseTest {
    private val locale = Locale.FRANCE
    @Test fun sentenceEndingRetainsTypographyWithoutEnteringTheLexicalModel() {
        assertEquals("Castel\u202f!", PunctuatedPhrase.attach("Castel", '!', true))
        assertEquals("Castel", PunctuatedPhrase.lexical("Castel\u202f!"))
        assertEquals("castel!", PunctuatedPhrase.normalize("Castel\u202f!", locale))
        assertNotEquals(PunctuatedPhrase.normalize("Castel", locale), PunctuatedPhrase.normalize("Castel!", locale))
        assertEquals("Castel.", PunctuatedPhrase.attach("Castel", '.', true))
        assertEquals("Castel…", PunctuatedPhrase.attach("Castel", '…', true))
        assertEquals(2, PunctuatedPhrase.overlappingAfter("Castel\u202f!", "\u202f! suite"))
    }
    @Test fun terminalVariantPersistsAlongsidePlainAndCommaVariants() {
        val context = RuntimeEnvironment.getApplication()
        PhraseUsageStore(context).use { sql ->
            sql.clearAll()
            for (variant in listOf("Castel", "Castel,", "Castel\u202f!")) sql.learn(locale, listOf("Emmanuel", variant), 1L)
        }
        PhraseUsageStore(context).use { sql ->
            assertEquals(setOf("Castel", "Castel,", "Castel\u202f!"), sql.predictNext(locale, listOf("Emmanuel"), 10).map { it.word }.toSet())
            sql.clearAll()
        }
    }
    @Test fun legacyKeysStayCompatibleAndPunctuationVariantsRemainDistinct() {
        assertEquals(WordNormalization.normalizeForSuggestion("École", locale), PunctuatedPhrase.normalize("École", locale))
        assertNotEquals(PunctuatedPhrase.normalize("monsieur", locale), PunctuatedPhrase.normalize("monsieur,", locale))
        assertEquals("monsieur,", PunctuatedPhrase.normalize("Monsieur,", locale))
    }
    @Test fun contextPreservesCommasAccentsApostrophesAndFrenchSpaces() {
        assertEquals(listOf("Bonjour", "monsieur,", "je"), PunctuatedPhrase.contextTokens("Bonjour monsieur, je "))
        assertEquals(listOf("donc\u00a0;", "l’école"), PunctuatedPhrase.contextTokens("donc\u00a0; l’école "))
        assertEquals(listOf("à", "11", "rendez-vous"), PunctuatedPhrase.contextTokens("à 11 rendez-vous "))
    }
    @Test fun terminalPunctuationAndNewlinesResetCursorContext() {
        for (separator in listOf(".", "!", "?", "…", "\n")) {
            assertEquals(listOf("madame,"), PunctuatedPhrase.contextTokens("ancien$separator madame, "))
        }
        assertEquals(emptyList<String>(), PunctuatedPhrase.contextTokens("Bonjour."))
    }
    @Test fun sqlAndAsyncModelsKeepPunctuationAcrossReopening() {
        val context = RuntimeEnvironment.getApplication()
        val store = PhraseUsageStore(context).apply { clearAll() }
        val async = AsyncPhraseUsageRepository(store)
        async.learn(locale, listOf("Bonjour", "monsieur,", "je"), 1L)
        assertEquals("je", async.predictNext(locale, listOf("monsieur,"), 3).first().word)
        assertEquals(1, async.getUsage(locale, listOf("Bonjour", "monsieur,", "je"))?.count)
        assertNull(async.getUsage(locale, listOf("Bonjour", "monsieur", "je")))
        async.flushPendingWrites(); async.closeWhenDrained(); store.close()
        PhraseUsageStore(context).use { reopened ->
            assertEquals(listOf("Bonjour", "monsieur,", "je"), reopened.getUsage(locale, listOf("Bonjour", "monsieur,", "je"))?.words)
            assertEquals("je", reopened.predictNext(locale, listOf("monsieur,"), 3).first().word)
            reopened.clearAll()
        }
    }
    @Test fun overlapConsumesMatchingPunctuationOnly() {
        assertEquals(1, PunctuatedPhrase.overlappingAfter("monsieur, je", ", suite"))
        assertEquals(2, PunctuatedPhrase.overlappingAfter("monsieur, je", " , suite"))
        assertEquals(0, PunctuatedPhrase.overlappingAfter("monsieur, je", "; suite"))
        assertEquals(0, PunctuatedPhrase.overlappingAfter("monsieur je", ", suite"))
    }

    @Test fun backupRoundTrip() {
        val context = RuntimeEnvironment.getApplication()
        val words = listOf("Bonjour", "monsieur,", "je", "me", "permets")
        val store = MultiWordStore(context).apply { clearAll() }
        // *Create the synthetic initial dataset as one atomic snapshot, including both punctuation variants.*
        store.batchUpdates {
            store.promote(locale, words, 10)
            store.promote(locale, listOf("Bonjour", "monsieur", "je", "me", "permets"), 9)
        }
        assertEquals("before export model: " + store.getAll(), 2, store.getAll().size)
        assertEquals("before export disk", 2, org.json.JSONObject(context.getFileStreamPath("multi_word_sequences.json").readText()).getJSONArray("entries").length())
        val phrases = PhraseUsageStore(context).apply { clearAll(); learn(locale, words, 1L); close() }
        val backup = java.io.File(context.cacheDir, "p").apply { mkdirs() }
        rocks.pkb.plektra.backup.LearningBackupHelper.export(context, backup)
        rocks.pkb.plektra.backup.LearningBackupHelper.validateBackup(backup)
        assertEquals("exported disk", 2, org.json.JSONObject(java.io.File(backup, "multi_word_sequences.json").readText()).getJSONArray("entries").length())
        store.clearAll()
        PhraseUsageStore(context).apply { clearAll(); close() }
        rocks.pkb.plektra.backup.LearningBackupHelper.importBackup(context, backup)
        val restored = MultiWordStore(context)
        assertEquals(words, restored.find(locale, words)?.words)
        assertEquals(restored.getAll().toString(), 2, restored.getForContext(locale, listOf("bonjour")).size)
        PhraseUsageStore(context).use { sql ->
            assertEquals(words, sql.getUsage(locale, words)?.words)
            sql.clearAll()
        }
        restored.clearAll()
    }
}
