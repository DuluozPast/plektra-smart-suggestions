package rocks.pkb.plektra.core.suggestions

import java.io.Closeable

/** Static dictionaries stay on disk; callers materialize only small result sets. */
interface StaticDictionaryReader : Closeable {
    val wordCount: Int
    val normalizedCount: Int
    val prefixCount: Int
    fun normalizedEntries(normalized: String): List<CompactDictionaryV31Reader.WordEntry>
    fun prefixEntries(prefix: String, limit: Int): List<CompactDictionaryV31Reader.WordEntry>
    fun visitDeleteEntries(deleteKey: String, minLength: Int = 0, maxLength: Int = Int.MAX_VALUE, visit: (CompactDictionaryV31Reader.WordEntry) -> Unit)
    fun commonEntries(limit: Int): List<CompactDictionaryV31Reader.WordEntry>? = null
}
