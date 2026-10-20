package rocks.pkb.plektra.core.suggestions

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MultilingualDictionarySafetyTest {
    @Test fun failedEnglishLoadLeavesFrenchSuggestionsUsableAndDoesNotLoop() {
        val context = RuntimeEnvironment.getApplication()
        val french = FakeDictionaryRepository().apply { isReady = true; addTestEntry("bonjour", 255) }
        val english = FakeDictionaryRepository()
        val attempts = AtomicInteger()
        val attempted = CompletableDeferred<Unit>()
        val failingEnglish = object : DictionaryRepository by english {
            override suspend fun loadIfNeeded() {
                attempts.incrementAndGet()
                attempted.complete(Unit)
                throw OutOfMemoryError("synthetic: no actual heap exhaustion")
            }
        }
        val italian = FakeDictionaryRepository().apply { isReady = true; addTestEntry("buongiorno", 255) }
        val snapshots = mutableListOf<List<SuggestionResult>>()
        val controller = SuggestionController(context, context.assets,
            settingsProvider = { SuggestionSettings(suggestionsEnabled = true, maxSuggestions = 3) },
            onSuggestionsUpdated = { snapshots.add(it) }, currentLocale = Locale.FRENCH,
            dictionaryRepositoryFactory = { _, _, _, locale, _ -> when (locale.language) {
                "en" -> failingEnglish; "it" -> italian; else -> french
            } }, activeSuggestionLocalesProvider = { listOf(Locale.ENGLISH, Locale.ITALIAN) })
        try {
            controller.preloadDictionary()
            controller.onCharacterCommitted("bo", null)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                if (attempted.isCompleted && snapshots.lastOrNull().orEmpty().any { it.candidate == "bonjour" }) break
                Thread.sleep(10)
            }
            assertTrue("French typing must survive the English failure",
                snapshots.lastOrNull().orEmpty().any { it.candidate == "bonjour" })
            repeat(30) { controller.preloadDictionary() }
            // Drain the main thread; scheduling is reserved synchronously before each launch.
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, attempts.get())
        } finally { controller.destroy() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cachedLanguages(controller: SuggestionController): Set<String> {
        val field = SuggestionController::class.java.getDeclaredField("dictionaryRepositoryCache")
        field.isAccessible = true
        return (field.get(controller) as Map<String, DictionaryRepository>).keys.toSet()
    }

    @Test fun manualLanguageSwitchReleasesPreviousDictionaryRepositories() {
        val context = RuntimeEnvironment.getApplication()
        val controller = SuggestionController(context, context.assets,
            settingsProvider = { SuggestionSettings(suggestionsEnabled = true, maxSuggestions = 3) },
            onSuggestionsUpdated = {}, currentLocale = Locale.FRENCH,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> FakeDictionaryRepository().apply { isReady = true } })
        try {
            controller.preloadDictionary()
            for (locale in listOf(Locale.ITALIAN, Locale.ENGLISH, Locale.FRENCH, Locale.ITALIAN)) {
                controller.updateLocale(locale)
                assertEquals(setOf(locale.language), cachedLanguages(controller))
            }
        } finally { controller.destroy() }
    }

    @Test fun turningOffSupplementaryLanguagesReleasesTheirRepositories() {
        val context = RuntimeEnvironment.getApplication()
        var extras = listOf(Locale.ITALIAN, Locale.ENGLISH)
        val controller = SuggestionController(context, context.assets,
            settingsProvider = { SuggestionSettings(suggestionsEnabled = true, maxSuggestions = 3) },
            onSuggestionsUpdated = {}, currentLocale = Locale.FRENCH,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> FakeDictionaryRepository().apply { isReady = true } },
            activeSuggestionLocalesProvider = { extras })
        try {
            controller.preloadDictionary()
            assertEquals(setOf("fr", "it", "en"), cachedLanguages(controller))
            extras = emptyList()
            controller.preloadDictionary()
            assertEquals(setOf("fr"), cachedLanguages(controller))
        } finally { controller.destroy() }
    }

    @Test fun secondarySearchExceptionIsRecordedAndFrenchTypingCanContinue() {
        val context = RuntimeEnvironment.getApplication()
        val french = FakeDictionaryRepository().apply { isReady = true; addTestEntry("bonjour", 255) }
        val english = FakeDictionaryRepository().apply { isReady = true }
        val failing = object : DictionaryRepository by english {
            override fun lookupByPrefixMerged(prefix: String, maxSize: Int): List<DictionaryEntry> {
                throw IllegalStateException("private typed content must not appear in report")
            }
        }
        val reportFile = java.io.File(context.noBackupFilesDir, "keyboard-failure.txt")
        reportFile.delete()
        val snapshots = mutableListOf<List<SuggestionResult>>()
        val controller = SuggestionController(context, context.assets,
            settingsProvider = { SuggestionSettings(suggestionsEnabled = true, maxSuggestions = 3) },
            onSuggestionsUpdated = { snapshots.add(it) }, currentLocale = Locale.FRENCH,
            dictionaryRepositoryFactory = { _, _, _, locale, _ -> if (locale.language == "en") failing else french },
            activeSuggestionLocalesProvider = { listOf(Locale.ENGLISH) })
        fun waitUntil(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                if (predicate()) return
                Thread.sleep(10)
            }
            fail("Timed out waiting for query recovery")
        }
        try {
            controller.onCharacterCommitted("bo", null)
            waitUntil { reportFile.exists() && cachedLanguages(controller) == setOf("fr") }
            val report = reportFile.readText()
            assertTrue(report.contains("IllegalStateException"))
            assertFalse(report.contains("private typed content"))
            controller.onCharacterCommitted("n", null)
            waitUntil { snapshots.lastOrNull().orEmpty().any { it.candidate == "bonjour" } }
        } finally { controller.destroy(); reportFile.delete() }
    }
}
