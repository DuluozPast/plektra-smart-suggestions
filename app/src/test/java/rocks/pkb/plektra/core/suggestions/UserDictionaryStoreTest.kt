package rocks.pkb.plektra.core.suggestions

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UserDictionaryStoreTest {

    private lateinit var store: UserDictionaryStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()

        store = UserDictionaryStore()

        store.getSnapshot()
            .map { it.word }
            .forEach { word ->
                store.removeWord(context, word)
            }
    }

    @Test
    fun explicitWordCannotBeRemovedAsAutoLearned() {
        val context = RuntimeEnvironment.getApplication()

        store.addWord(context, "monpetitloup")

        assertFalse(store.isAutoLearnedWord("monpetitloup"))

        val removed =
            store.removeAutoLearnedWord(context, "monpetitloup")

        assertFalse(removed)

        val entry =
            store.getSnapshot()
                .single { it.word == "monpetitloup" }

        assertEquals(
            UserDictionaryStore.EntryOrigin.EXPLICIT,
            entry.origin
        )
    }

    @Test
    fun autoLearnedWordCanBeRemovedAsAutoLearned() {
        val context = RuntimeEnvironment.getApplication()

        store.addAutoLearnedWord(context, "monpetitloup")

        assertTrue(store.isAutoLearnedWord("monpetitloup"))

        val removed =
            store.removeAutoLearnedWord(context, "monpetitloup")

        assertTrue(removed)

        assertTrue(
            store.getSnapshot()
                .none { it.word == "monpetitloup" }
        )
    }

    @Test
    fun explicitWordRemainsExplicitWhenLearnedAutomaticallyLater() {
        val context = RuntimeEnvironment.getApplication()

        store.addWord(context, "monpetitloup")
        store.addAutoLearnedWord(context, "monpetitloup")

        assertFalse(store.isAutoLearnedWord("monpetitloup"))

        val entry =
            store.getSnapshot()
                .single { it.word == "monpetitloup" }

        assertEquals(
            UserDictionaryStore.EntryOrigin.EXPLICIT,
            entry.origin
        )

        assertFalse(
            store.removeAutoLearnedWord(context, "monpetitloup")
        )

        assertTrue(
            store.getSnapshot()
                .any { it.word == "monpetitloup" }
        )
    }

    @Test
    fun pruneAutoLearnedNeverRemovesExplicitWords() {
        val context = RuntimeEnvironment.getApplication()

        store.addWord(context, "motexplicite")
        store.addAutoLearnedWord(context, "motautomatique")

        val removed = store.pruneAutoLearned(
            context = context,
            nowMs = System.currentTimeMillis() + 100_000L,
            staleAfterMs = 1L,
            weakFrequencyThreshold = 10,
            maxEntries = 100
        )

        assertEquals(1, removed)

        assertTrue(
            store.getSnapshot()
                .any { it.word == "motexplicite" }
        )

        assertFalse(
            store.getSnapshot()
                .any { it.word == "motautomatique" }
        )

        assertEquals(
            UserDictionaryStore.EntryOrigin.EXPLICIT,
            store.getSnapshot()
                .single { it.word == "motexplicite" }
                .origin
        )
    }

    @Test
    fun pruneAutoLearnedKeepsStrongRecentWord() {
        val context = RuntimeEnvironment.getApplication()

        repeat(5) {
            store.addAutoLearnedWord(context, "monpetitloup")
        }

        val removed = store.pruneAutoLearned(
            context = context,
            nowMs = System.currentTimeMillis() + 1_000L,
            staleAfterMs = 90L * 24L * 60L * 60L * 1000L,
            weakFrequencyThreshold = 2,
            maxEntries = 100
        )

        assertEquals(0, removed)

        val entry =
            store.getSnapshot()
                .single { it.word == "monpetitloup" }

        assertEquals(
            UserDictionaryStore.EntryOrigin.AUTO_LEARNED,
            entry.origin
        )

        assertEquals(5, entry.frequency)
    }

    @Test
    fun pruneAutoLearnedEnforcesMaximumWithoutRemovingExplicitWords() {
        val context = RuntimeEnvironment.getApplication()

        store.addWord(context, "motexplicite")

        store.addAutoLearnedWord(context, "faible")
        repeat(2) {
            store.addAutoLearnedWord(context, "moyen")
        }
        repeat(3) {
            store.addAutoLearnedWord(context, "fort")
        }

        val removed = store.pruneAutoLearned(
            context = context,
            nowMs = System.currentTimeMillis(),
            staleAfterMs = Long.MAX_VALUE,
            weakFrequencyThreshold = 0,
            maxEntries = 2
        )

        assertEquals(1, removed)

        val words = store.getSnapshot()
            .map { it.word }
            .toSet()

        assertTrue("motexplicite" in words)
        assertFalse("faible" in words)
        assertTrue("moyen" in words)
        assertTrue("fort" in words)
    }

    @Test
    fun pruneAutoLearnedRemovesStaleSuppressedAutoLearnedWord() {
        val context = RuntimeEnvironment.getApplication()
        val locale = Locale.FRENCH

        store.addAutoLearnedWord(context, "monpetitloup")

        store.removeWordsAlreadyInBase(
            context = context,
            baseNormalizedWords = setOf(
                WordNormalization.normalizeForDictionary(
                    "monpetitloup",
                    locale
                )
            ),
            locale = locale
        )

        assertFalse(
            store.getSnapshot()
                .any { it.word == "monpetitloup" }
        )

        val removed = store.pruneAutoLearned(
            context = context,
            nowMs = System.currentTimeMillis() + 100_000L,
            staleAfterMs = 1L,
            weakFrequencyThreshold = 10,
            maxEntries = 100
        )

        assertEquals(1, removed)

        store.restoreWordsRemovedForBase(
            context = context,
            locale = locale
        )

        assertFalse(
            store.getSnapshot()
                .any { it.word == "monpetitloup" }
        )
    }

    @Test
    fun pruneAutoLearnedNeverRemovesSuppressedExplicitWord() {
        val context = RuntimeEnvironment.getApplication()
        val locale = Locale.FRENCH

        store.addWord(context, "motexplicite")

        store.removeWordsAlreadyInBase(
            context = context,
            baseNormalizedWords = setOf(
                WordNormalization.normalizeForDictionary(
                    "motexplicite",
                    locale
                )
            ),
            locale = locale
        )

        assertFalse(
            store.getSnapshot()
                .any { it.word == "motexplicite" }
        )

        val removed = store.pruneAutoLearned(
            context = context,
            nowMs = System.currentTimeMillis() + 100_000L,
            staleAfterMs = 1L,
            weakFrequencyThreshold = 10,
            maxEntries = 100
        )

        assertEquals(0, removed)

        store.restoreWordsRemovedForBase(
            context = context,
            locale = locale
        )

        val restored =
            store.getSnapshot()
                .single { it.word == "motexplicite" }

        assertEquals(
            UserDictionaryStore.EntryOrigin.EXPLICIT,
            restored.origin
        )
    }

    @Test
    fun pruneAutoLearnedReturnsRemovedWords() {
        val context = RuntimeEnvironment.getApplication()

        store.addAutoLearnedWord(context, "monpetitloup")

        val removedWords = store.pruneAutoLearnedWords(
            context = context,
            nowMs = System.currentTimeMillis() + 100_000L,
            staleAfterMs = 1L,
            weakFrequencyThreshold = 10,
            maxEntries = 100
        )

        assertEquals(
            listOf("monpetitloup"),
            removedWords
        )

        assertFalse(
            store.getSnapshot()
                .any { it.word == "monpetitloup" }
        )
    }
}