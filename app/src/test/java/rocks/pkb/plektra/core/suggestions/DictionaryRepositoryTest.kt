package rocks.pkb.plektra.core.suggestions

import android.content.Context
import android.content.res.AssetManager
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.pow
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class DictionaryRepositoryTest {

    private lateinit var context: Context
    private lateinit var assets: AssetManager
    private lateinit var userDictionaryStore: UserDictionaryStore
    private lateinit var repository: AndroidDictionaryRepository

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        assets = mock(AssetManager::class.java)
        userDictionaryStore = mock(UserDictionaryStore::class.java)
        repository = AndroidDictionaryRepository(
            context = context,
            assets = assets,
            userDictionaryStore = userDictionaryStore,
            baseLocale = Locale.ITALIAN
        )
        // Set isReady to true to bypass load checks in methods
        repository.isReady = true
    }

    @Test
    fun testFrequencyScaling() {
        // Test values based on pow(0.75) * 1600.0
        // freq 0 -> (0/255)^0.75 * 1600 = 0 -> coerced to 1
        // freq 255 -> (255/255)^0.75 * 1600 = 1600
        // freq 127 -> (127/255)^0.75 * 1600 approx 951

        val entry0 = DictionaryEntry("test", 0)
        val entry127 = DictionaryEntry("test", 127)
        val entry255 = DictionaryEntry("test", 255)

        assertEquals(1, repository.effectiveFrequency(entry0))
        assertEquals(1600, repository.effectiveFrequency(entry255))

        val expected127 = ((127.0 / 255.0).pow(0.75) * 1600.0).toInt()
        assertEquals(expected127, repository.effectiveFrequency(entry127))
    }

    @Test
    fun testPrefixCache() {
        val entries = listOf(
            DictionaryEntry("ciao", 200, SuggestionSource.MAIN),
            DictionaryEntry("casa", 150, SuggestionSource.MAIN),
            DictionaryEntry("cielo", 100, SuggestionSource.MAIN),
            DictionaryEntry("come", 50, SuggestionSource.MAIN)
        )

        repository.index(entries)

        // "ci" should find "ciao" and "cielo"
        val resultsCi = repository.lookupByPrefixMerged("ci", 10)
        // resultsCi should contain ciao and cielo, but might also contain casa and come if they are in the 'c' bucket
        // and lookupByPrefixMerged iterates down to length 1.
        assertTrue(resultsCi.any { it.word == "ciao" })
        assertTrue(resultsCi.any { it.word == "cielo" })

        // "ca" should find "casa"
        val resultsCa = repository.lookupByPrefixMerged("ca", 10)
        assertTrue(resultsCa.any { it.word == "casa" })

        // "c" should find all
        val resultsC = repository.lookupByPrefixMerged("c", 10)
        assertEquals(4, resultsC.size)
    }

    @Test
    fun topCommonEntriesWithoutUserDoesNotReuseUserCache() {
        val entries = listOf(
            DictionaryEntry("halètement", 255, SuggestionSource.USER),
            DictionaryEntry("hahaha", 255, SuggestionSource.USER),
            DictionaryEntry("autocorrection", 255, SuggestionSource.USER),
            DictionaryEntry("the", 240, SuggestionSource.MAIN),
            DictionaryEntry("to", 230, SuggestionSource.MAIN),
            DictionaryEntry("of", 220, SuggestionSource.MAIN)
        )

        repository.index(entries)

        // First populate the cache that includes USER entries.
        val withUser = repository.topCommonEntries(
            limit = 3,
            includeUserEntries = true
        )

        assertTrue(withUser.any { it.source == SuggestionSource.USER })

        // Then request starter-style entries without USER entries.
        // This must use the separate cache.
        val withoutUser = repository.topCommonEntries(
            limit = 3,
            includeUserEntries = false
        )

        assertEquals(3, withoutUser.size)
        assertTrue(withoutUser.none { it.source == SuggestionSource.USER })
        assertEquals(
            listOf("the", "to", "of"),
            withoutUser.map { it.word }
        )
    }

    @Test
    fun testMergeLogic() {
        // Scenario: A word is in both Main and User dictionary.
        // User dictionary entry has higher frequency.
        val mainEntry = DictionaryEntry("test", 100, SuggestionSource.MAIN)
        val userEntry = DictionaryEntry("test", 200, SuggestionSource.USER)

        // Indexing both. The order of indexing matters for bucket.removeAll
        repository.index(listOf(mainEntry, userEntry), keepExisting = true)

        // normalizedIndex["test"] should contain both entries
        val normalized = repository.normalize("test")
        // We can't access private members directly easily, but we can use bestEntryForNormalized
        val best = repository.bestEntryForNormalized(normalized)

        assertEquals("test", best?.word)
        assertEquals(SuggestionSource.USER, best?.source)
        assertEquals(200, best?.frequency)

        // Test with Main having higher frequency
        val userEntryLow = DictionaryEntry("hello", 50, SuggestionSource.USER)
        val mainEntryHigh = DictionaryEntry("hello", 150, SuggestionSource.MAIN)

        repository.index(listOf(userEntryLow, mainEntryHigh), keepExisting = true)
        val bestHello = repository.bestEntryForNormalized(repository.normalize("hello"))
        assertEquals(SuggestionSource.MAIN, bestHello?.source)
        assertEquals(150, bestHello?.frequency)
    }

    @Test
    fun removeStoredUserEntryQuickRemovesUserEntryFromLoadedIndexes() {
        repository.index(
            listOf(
                DictionaryEntry(
                    "monpetitloup",
                    1,
                    SuggestionSource.USER
                )
            ),
            keepExisting = true
        )

        assertTrue(repository.isKnownWord("monpetitloup"))

        repository.removeStoredUserEntryQuick("monpetitloup")

        assertTrue(
            repository.lookupByPrefixMerged(
                "monpetit",
                10
            ).none {
                it.word.equals(
                    "monpetitloup",
                    ignoreCase = true
                ) &&
                        it.source == SuggestionSource.USER
            }
        )
    }

    @Test
    fun removeStoredUserEntryQuickRemovesUserEntryFromLegacySymSpell() {
        repository.index(
            listOf(
                DictionaryEntry(
                    "monpetitloup",
                    100,
                    SuggestionSource.USER
                )
            ),
            keepExisting = true
        )

        repository.buildSymSpellForTests()

        assertTrue(
            repository.symSpellLookup(
                "monpetitlou",
                10
            ).any {
                it.term == "monpetitloup"
            }
        )

        repository.removeStoredUserEntryQuick("monpetitloup")

        assertTrue(
            repository.symSpellLookup(
                "monpetitlou",
                10
            ).none {
                it.term == "monpetitloup"
            }
        )
    }

    @Test fun boundedStarterSelectionPreservesRankingAcrossLargeInput() {
        val entries = (0 until 2000).map { n ->
            val suffix = "${('a'.code + n / 676).toChar()}${('a'.code + n / 26 % 26).toChar()}${('a'.code + n % 26).toChar()}"
            DictionaryEntry("mot$suffix", (n * 37) % 256, SuggestionSource.MAIN)
        } + listOf(DictionaryEntry("personnel", 1, SuggestionSource.USER),
            DictionaryEntry("ignoré", 255, SuggestionSource.DEFAULT_USER))
        repository.index(entries)
        for (includeUsers in listOf(false, true)) {
            val expected = entries.filter { it.source == SuggestionSource.MAIN ||
                (includeUsers && it.source == SuggestionSource.USER) }
                .sortedWith(compareByDescending<DictionaryEntry> { it.source == SuggestionSource.USER }
                    .thenByDescending { repository.effectiveFrequency(it) }.thenBy { it.word.length })
                .take(48)
            assertEquals(expected, repository.topCommonEntries(48, includeUsers))
            assertEquals(expected.take(3), repository.topCommonEntries(3, includeUsers))
        }
    }

    @Test fun boundedMergedPrefixPreservesHighestFrequencyCompletionsInLargeBuckets() {
        val entries = (0 until 3000).map { n ->
            val suffix = "${('a'.code + n / 676).toChar()}${('a'.code + n / 26 % 26).toChar()}${('a'.code + n % 26).toChar()}"
            DictionaryEntry("al$suffix", (n * 41) % 256)
        }
        repository.index(entries)
        val expected = entries.sortedByDescending { repository.effectiveFrequency(it) }.take(200)
        assertEquals(expected, repository.lookupByPrefixMerged("al", 200))
    }
}

