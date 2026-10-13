package rocks.pkb.plektra.core.suggestions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MultiWordStoreTest {

    private lateinit var context: Context
    private lateinit var store: MultiWordStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "multi_word_sequences.json").delete()
        File(context.filesDir, "multi_word_sequences.json.tmp").delete()

        store = MultiWordStore(context)
    }

    @After
    fun tearDown() {
        if (::store.isInitialized) {
            store.clearAll()
        }

        if (::context.isInitialized) {
            File(context.filesDir, "multi_word_sequences.json.tmp").delete()
        }
    }

    @Test
    fun promoteStoresAndFindsSequence() {
        val locale = Locale.FRENCH

        val promoted = store.promote(
            locale = locale,
            words = listOf("à", "la", "maison"),
            phraseCount = 8,
            nowMs = 1000L
        )

        assertNotNull(promoted)

        val found = store.find(
            locale,
            listOf("à", "la", "maison")
        )

        assertNotNull(found)
        assertEquals(listOf("à", "la", "maison"), found!!.words)
        assertEquals(8, found.phraseCount)
        assertEquals(1000L, found.promotedAt)
        assertEquals(1000L, found.updatedAt)
    }

    @Test
    fun storedSequenceSurvivesStoreRecreation() {
        val locale = Locale.FRENCH

        store.promote(
            locale,
            listOf("à", "la", "maison"),
            8,
            1000L
        )

        val recreatedStore = MultiWordStore(context)

        val found = recreatedStore.find(
            locale,
            listOf("à", "la", "maison")
        )

        assertNotNull(found)
        assertEquals(listOf("à", "la", "maison"), found!!.words)
        assertEquals(8, found.phraseCount)
    }

    @Test
    fun invalidJsonIsTreatedAsEmptyStore() {
        File(
            context.filesDir,
            "multi_word_sequences.json"
        ).writeText("{ this is not valid json")

        val recreatedStore = MultiWordStore(context)

        assertTrue(recreatedStore.getAll().isEmpty())
    }

    @Test
    fun promotingSameNormalizedSequenceUpdatesInsteadOfDuplicating() {
        val locale = Locale.FRENCH

        store.promote(
            locale = locale,
            words = listOf("À", "la", "maison"),
            phraseCount = 5,
            nowMs = 1000L
        )

        store.promote(
            locale = locale,
            words = listOf("à", "la", "maison"),
            phraseCount = 9,
            nowMs = 2000L
        )

        val entries = store.getForLocale(locale)

        assertEquals(1, entries.size)
        assertEquals(listOf("à", "la", "maison"), entries.single().words)
        assertEquals(9, entries.single().phraseCount)
        assertEquals(1000L, entries.single().promotedAt)
        assertEquals(2000L, entries.single().updatedAt)
    }

    @Test
    fun localesRemainSeparate() {
        val french = Locale.FRENCH
        val english = Locale.ENGLISH

        store.promote(
            french,
            listOf("à", "la", "maison"),
            phraseCount = 8,
            nowMs = 1000L
        )

        store.promote(
            english,
            listOf("at", "home"),
            phraseCount = 8,
            nowMs = 1000L
        )

        assertEquals(1, store.getForLocale(french).size)
        assertEquals(1, store.getForLocale(english).size)
        assertEquals(2, store.getAll().size)
    }

    @Test
    fun removeDeletesOnlyRequestedSequence() {
        val locale = Locale.FRENCH

        store.promote(
            locale,
            listOf("à", "la", "maison"),
            phraseCount = 8
        )

        store.promote(
            locale,
            listOf("en", "train", "de"),
            phraseCount = 8
        )

        assertTrue(
            store.remove(
                locale,
                listOf("à", "la", "maison")
            )
        )

        assertNull(
            store.find(
                locale,
                listOf("à", "la", "maison")
            )
        )

        assertNotNull(
            store.find(
                locale,
                listOf("en", "train", "de")
            )
        )
    }

    @Test
    fun invalidSequenceIsNotStored() {
        val locale = Locale.FRENCH

        val result = store.promote(
            locale,
            listOf("bonjour"),
            phraseCount = 10
        )

        assertNull(result)
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun unchangedPromotionDoesNotRewriteOrRefreshTimestamps() {
        val words = listOf("à", "la", "maison")
        store.promote(Locale.FRENCH, words, phraseCount = 8, nowMs = 1000L)
        val file = File(context.filesDir, "multi_word_sequences.json")
        val before = file.readText()
        assertTrue(file.setLastModified(1000L))
        val modifiedBefore = file.lastModified()

        val repeated = store.promote(Locale.FRENCH, words, phraseCount = 8, nowMs = 2000L)

        assertEquals(1000L, repeated!!.updatedAt)
        assertEquals(before, file.readText())
        assertEquals(modifiedBefore, file.lastModified())
    }

    @Test
    fun invalidationReloadsExternallyRestoredSequences() {
        val words = listOf("à", "la", "maison")
        store.promote(Locale.FRENCH, words, phraseCount = 8, nowMs = 1000L)
        assertEquals(8, store.find(Locale.FRENCH, words)!!.phraseCount)

        // Restoration replaces the backing file outside this store instance.
        val file = File(context.filesDir, "multi_word_sequences.json")
        val restored = org.json.JSONObject(file.readText())
        restored.getJSONArray("entries").getJSONObject(0).put("phraseCount", 12)
        file.writeText(restored.toString())
        store.invalidateCache()

        assertEquals(12, store.find(Locale.FRENCH, words)!!.phraseCount)
    }

    @Test
    fun cachedSequencesCanBeReadWithoutTheBackingFile() {
        val words = listOf("à", "la", "maison")
        store.promote(Locale.FRENCH, words, phraseCount = 8, nowMs = 1000L)
        val file = File(context.filesDir, "multi_word_sequences.json")
        val heldFile = File(context.filesDir, "multi_word_sequences.test-held")
        assertTrue(file.renameTo(heldFile))
        try {
            assertEquals(words, store.find(Locale.FRENCH, words)!!.words)
            assertEquals(1, store.getForLocale(Locale.FRENCH).size)
        } finally {
            assertTrue(heldFile.renameTo(file))
        }
    }

    @Test
    fun clearAllRemovesStoredEntries() {
        store.promote(
            Locale.FRENCH,
            listOf("à", "la", "maison"),
            phraseCount = 8
        )

        assertFalse(store.getAll().isEmpty())

        store.clearAll()

        assertTrue(store.getAll().isEmpty())
    }
}
