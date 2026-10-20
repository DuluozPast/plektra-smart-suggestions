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
class DictionaryResourceLifecycleTest {
    @Test fun switchesAndDestroyCloseEveryRetiredReaderExactlyOnce() {
        val context = RuntimeEnvironment.getApplication()
        val closed = mutableMapOf<String, Int>()
        val opened = mutableListOf<String>()
        val controller = SuggestionController(context, context.assets,
            settingsProvider = { SuggestionSettings(suggestionsEnabled = true) }, onSuggestionsUpdated = {},
            currentLocale = Locale.FRENCH,
            dictionaryRepositoryFactory = { _, _, _, locale, _ ->
                opened.add(locale.language)
                val repository = FakeDictionaryRepository().apply { isReady = true }
                object : DictionaryRepository by repository {
                    override fun releaseResources() { closed[locale.language] = (closed[locale.language] ?: 0) + 1 }
                }
            })
        controller.preloadDictionary()
        assertEquals(listOf("fr"), opened)
        controller.updateLocale(Locale.ITALIAN)
        assertEquals(1, closed["fr"])
        controller.updateLocale(Locale.ENGLISH)
        assertEquals(1, closed["it"])
        assertNull(closed["en"])
        controller.destroy()
        assertEquals(mapOf("fr" to 1, "it" to 1, "en" to 1), closed)
    }
}
