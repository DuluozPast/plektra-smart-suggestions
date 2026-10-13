package rocks.pkb.plektra.core.suggestions

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MultiWordIndexedPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun emptyBatchDoesNotEagerlyLoadDisabledMultiwordData() {
        MultiWordStore(context).clearAll()
        val store = MultiWordStore(context)
        store.batchUpdates { }
        MultiWordStore(context).promote(Locale.FRENCH, listOf("11", "heures"), 5, 100L)
        assertEquals(1, store.getAll().size)
        store.clearAll()
    }

    @Test fun indexedQueriesMatchLegacyScansIncludingOrderingAfterUpdates() {
        val store = MultiWordStore(context)
        store.clearAll()
        store.batchUpdates {
            for (i in 0 until 600) {
                val locale = if (i % 3 == 0) Locale.GERMAN else Locale.FRENCH
                store.promote(locale, listOf("start${i % 17}", "word$i", "end"), i + 3, i.toLong())
            }
            store.promote(Locale.FRENCH, listOf("je", "voudrais", "aller", "à", "plage"), 12, 900L)
            store.promote(Locale.FRENCH, listOf("voudrais", "aller", "à"), 10, 901L)
            store.promote(Locale.FRENCH, listOf("je", "voudrais", "aller", "à", "plage"), 14, 902L)
            store.markUsed(Locale.FRENCH, listOf("voudrais", "aller", "à"), 903L)
            store.remove(Locale.FRENCH, listOf("start1", "word1", "end"))
        }
        for (locale in listOf(Locale.FRENCH, Locale.GERMAN)) {
            val all = store.getForLocale(locale)
            for (context in listOf(listOf("je"), listOf("je", "voudrais"), listOf("start1"), listOf("unknown", "start2"), emptyList())) {
                val expected = all.filter { entry ->
                    (minOf(context.size, entry.normalizedWords.size - 1) downTo 1).any { length ->
                        context.takeLast(length) == entry.normalizedWords.take(length)
                    }
                }
                assertEquals(expected, store.getForContext(locale, context))
            }
            val words = setOf("start2", "je", "start1", "unknown")
            assertEquals(all.filter { it.normalizedWords.firstOrNull() in words }, store.getStartingWith(locale, words))
        }
        val saved = store.getAll()
        assertEquals(saved, MultiWordStore(context).getAll())
        store.invalidateCache()
        assertEquals(saved, store.getAll())
        store.clearAll()
        assertTrue(store.getForContext(Locale.FRENCH, listOf("je")).isEmpty())
    }

    @Test fun oneWordOperationPersistsOnceAndIsImmediatelyVisibleAndDurable() {
        val store = MultiWordStore(context)
        store.clearAll()
        val before = store.persistenceCountForTests
        store.batchUpdates {
            for (length in 2..5) {
                val words = listOf("je", "voudrais", "aller", "à", "plage").take(length)
                store.promote(Locale.FRENCH, words, 5, 100L)
                assertNotNull(store.find(Locale.FRENCH, words))
                store.markUsed(Locale.FRENCH, words, 200L)
                store.promote(Locale.FRENCH, words, 6, 300L)
            }
        }
        assertEquals(before + 1, store.persistenceCountForTests)
        assertEquals(store.getAll(), MultiWordStore(context).getAll())
        assertEquals(4, store.getAll().size)
        assertTrue(store.getAll().all { it.phraseCount == 6 && it.lastUsedAt == 200L })
        store.clearAll()
    }

    @Test fun nestedBatchAndThrownLearningStillPersistCompletedUpdates() {
        val store = MultiWordStore(context)
        store.clearAll()
        val before = store.persistenceCountForTests
        try {
            store.batchUpdates {
                store.promote(Locale.FRENCH, listOf("à", "11"), 8, 100L)
                store.batchUpdates { store.promote(Locale.FRENCH, listOf("11", "heures"), 9, 200L) }
                throw IllegalArgumentException("synthetic interruption")
            }
        } catch (_: IllegalArgumentException) { }
        assertEquals(before + 1, store.persistenceCountForTests)
        assertEquals(store.getAll(), MultiWordStore(context).getAll())
        store.clearAll()
    }
}
