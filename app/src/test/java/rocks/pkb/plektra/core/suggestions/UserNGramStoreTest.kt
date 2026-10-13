package rocks.pkb.plektra.core.suggestions

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.Assert.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UserNGramStoreTest {

    private lateinit var store: UserNGramStore

    @Before
    fun setUp() {
        store = UserNGramStore(RuntimeEnvironment.getApplication())
        store.clearAll()
    }

    @After
    fun tearDown() {
        store.clearAll()
        store.close()
    }

    @Test
    fun learn_insertsNewBigram() {
        store.learn("de-DE", "ich", "bin", nowMs = 100L)

        val predictions = store.predict("de-DE", "ich", limit = 3)

        assertEquals(listOf("bin"), predictions.map { it.word })
        assertEquals(1, predictions.first().count)
        assertEquals(100L, predictions.first().lastUsed)
    }

    @Test
    fun learn_incrementsExistingBigram() {
        store.learn("de-DE", "ich", "bin", nowMs = 100L)
        store.learn("de-DE", "ich", "bin", nowMs = 200L)

        val prediction = store.predict("de-DE", "ich", limit = 3).first()

        assertEquals("bin", prediction.word)
        assertEquals(2, prediction.count)
        assertEquals(200L, prediction.lastUsed)
    }

    @Test
    fun predict_sortsByCountThenRecency() {
        store.learn("de-DE", "ich", "werde", nowMs = 300L)
        store.learn("de-DE", "ich", "bin", nowMs = 100L)
        store.learn("de-DE", "ich", "bin", nowMs = 200L)
        store.learn("de-DE", "ich", "habe", nowMs = 400L)

        val predictions = store.predict("de-DE", "ich", limit = 3)

        assertEquals(listOf("bin", "habe", "werde"), predictions.map { it.word })
    }

    @Test
    fun predict_keepsLocalesSeparate() {
        store.learn("de-DE", "ich", "bin", nowMs = 100L)
        store.learn("en-US", "ich", "am", nowMs = 200L)

        assertEquals(listOf("bin"), store.predict("de-DE", "ich", limit = 3).map { it.word })
        assertEquals(listOf("am"), store.predict("en-US", "ich", limit = 3).map { it.word })
        assertTrue(store.predict("fr-FR", "ich", limit = 3).isEmpty())
    }

    @Test
    fun delete_removesOneBigramCaseInsensitively() {
        store.learn("de-DE", "ich", "Bin", nowMs = 100L)
        store.learn("de-DE", "ich", "habe", nowMs = 200L)

        val deleted = store.delete("de-DE", "ich", "bin")

        assertEquals(1, deleted)
        assertEquals(listOf("habe"), store.predict("de-DE", "ich", limit = 3).map { it.word })
    }

    @Test
    fun deleteNextWord_removesWordAcrossPrefixes() {
        store.learn("de-DE", "ich", "bin", nowMs = 100L)
        store.learn("de-DE", "wir", "bin", nowMs = 200L)

        val deleted = store.deleteNextWord("de-DE", "bin")

        assertEquals(2, deleted)
        assertTrue(store.predict("de-DE", "ich", limit = 3).isEmpty())
        assertTrue(store.predict("de-DE", "wir", limit = 3).isEmpty())
    }

    @Test
    fun prune_removesWeakStaleTransition() {
        store.learn("fr-FR", "je", "vais", nowMs = 100L)

        val deleted = store.prune(
            nowMs = 10_000L,
            staleAfterMs = 5_000L,
            weakCountThreshold = 2,
            maxEntriesPerLocale = 100
        )

        assertEquals(1, deleted)
        assertNull(store.getUsage("fr-FR", "je", "vais"))
    }

    @Test
    fun prune_keepsWeakRecentTransition() {
        store.learn("fr-FR", "je", "vais", nowMs = 9_000L)

        val deleted = store.prune(
            nowMs = 10_000L,
            staleAfterMs = 5_000L,
            weakCountThreshold = 2,
            maxEntriesPerLocale = 100
        )

        assertEquals(0, deleted)
        assertEquals(
            1,
            store.getUsage("fr-FR", "je", "vais")?.count
        )
    }

    @Test
    fun prune_keepsStrongStaleTransition() {
        repeat(3) { index ->
            store.learn(
                "fr-FR",
                "je",
                "vais",
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
            store.getUsage("fr-FR", "je", "vais")?.count
        )
    }

    @Test
    fun prune_enforcesCapPerLocale() {
        store.learn("fr-FR", "je", "pars", nowMs = 300L)

        repeat(2) {
            store.learn("fr-FR", "je", "vais", nowMs = 200L)
        }

        repeat(3) {
            store.learn("fr-FR", "je", "suis", nowMs = 100L)
        }

        val deleted = store.prune(
            nowMs = 1_000L,
            staleAfterMs = 10_000L,
            weakCountThreshold = 0,
            maxEntriesPerLocale = 2
        )

        assertEquals(1, deleted)
        assertNull(store.getUsage("fr-FR", "je", "pars"))
        assertEquals(
            2,
            store.getUsage("fr-FR", "je", "vais")?.count
        )
        assertEquals(
            3,
            store.getUsage("fr-FR", "je", "suis")?.count
        )
    }

    @Test
    fun prune_capUsesRecencyWhenCountsAreEqual() {
        store.learn("fr-FR", "je", "pars", nowMs = 100L)
        store.learn("fr-FR", "je", "reste", nowMs = 200L)

        val deleted = store.prune(
            nowMs = 1_000L,
            staleAfterMs = 10_000L,
            weakCountThreshold = 0,
            maxEntriesPerLocale = 1
        )

        assertEquals(1, deleted)
        assertNull(store.getUsage("fr-FR", "je", "pars"))
        assertEquals(
            1,
            store.getUsage("fr-FR", "je", "reste")?.count
        )
    }

    @Test
    fun prune_appliesCapIndependentlyPerLocale() {
        store.learn("fr-FR", "je", "pars", nowMs = 100L)
        store.learn("fr-FR", "je", "reste", nowMs = 200L)

        store.learn("en-US", "i", "leave", nowMs = 100L)
        store.learn("en-US", "i", "stay", nowMs = 200L)

        val deleted = store.prune(
            nowMs = 1_000L,
            staleAfterMs = 10_000L,
            weakCountThreshold = 0,
            maxEntriesPerLocale = 1
        )

        assertEquals(2, deleted)

        assertNull(store.getUsage("fr-FR", "je", "pars"))
        assertEquals(
            1,
            store.getUsage("fr-FR", "je", "reste")?.count
        )

        assertNull(store.getUsage("en-US", "i", "leave"))
        assertEquals(
            1,
            store.getUsage("en-US", "i", "stay")?.count
        )
    }

    @Test
    fun prune_capCountsTransitionsNotPrefixes() {
        store.learn("fr-FR", "je", "vais", nowMs = 100L)
        store.learn("fr-FR", "tu", "vas", nowMs = 200L)
        store.learn("fr-FR", "nous", "allons", nowMs = 300L)

        val deleted = store.prune(
            nowMs = 1_000L,
            staleAfterMs = 10_000L,
            weakCountThreshold = 0,
            maxEntriesPerLocale = 2
        )

        assertEquals(1, deleted)

        assertNull(store.getUsage("fr-FR", "je", "vais"))
        assertEquals(
            1,
            store.getUsage("fr-FR", "tu", "vas")?.count
        )
        assertEquals(
            1,
            store.getUsage("fr-FR", "nous", "allons")?.count
        )
    }
}
