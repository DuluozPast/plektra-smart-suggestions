package rocks.pkb.plektra.core.suggestions

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SuggestionNormalizationCacheTest {
    @Test fun longSessionsEvictOldNormalizationEntriesWithoutChangingResults() {
        val engine = SuggestionEngine(FakeDictionaryRepository(), Locale.FRENCH)
        for ((methodName, fieldName) in listOf("normalizeCached" to "wordNormalizeCache", "stripAccents" to "accentCache")) {
            val method = SuggestionEngine::class.java.getDeclaredMethod(methodName, String::class.java).apply { isAccessible = true }
            val first = method.invoke(engine, "état")
            repeat(10000) { method.invoke(engine, "entrée$it") }
            val cache = SuggestionEngine::class.java.getDeclaredField(fieldName).apply { isAccessible = true }.get(engine) as Map<*, *>
            assertTrue(cache.size <= 512)
            assertFalse(cache.containsKey("état"))
            assertEquals(first, method.invoke(engine, "état"))
            assertTrue(cache.size <= 512)
        }
    }
}
