package rocks.pkb.plektra.core.suggestions

import java.util.Locale

/** *Phrase punctuation is retained without changing dictionary or single-word normalization.* */
internal object PunctuatedPhrase {
    fun isInternal(char: Char?) = char == ',' || char == ';' || char == ':'
    fun isTerminal(char: Char) = char in ".!?…\n\r"
    // *Keep printed sentence endings in learned expressions, while newlines only reset context.*
    fun isRetained(char: Char?) = isInternal(char) || char != null && char in ".!?…"
    fun lexical(token: String): String = token.trim().trimEnd(',', ';', ':', '.', '!', '?', '…').trimEnd()
    fun normalize(token: String, locale: Locale): String {
        val word = WordNormalization.normalizeForSuggestion(token, locale)
        return if (word.isEmpty()) "" else word + token.trimEnd().lastOrNull().takeIf { isRetained(it) }.let { it?.toString().orEmpty() }
    }
    fun attach(word: String, punctuation: Char, frenchSpacing: Boolean = false): String {
        if (word.endsWith(punctuation)) return word
        val space = if (frenchSpacing && punctuation in ";:!?") "\u202f" else ""
        return lexical(word) + space + punctuation
    }

    /** *Bounded editor context: sentence endings reset the phrase, commas remain attached to words.* */
    fun contextTokens(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val word = StringBuilder()
        var spacing = ""
        fun flush() { if (word.isNotEmpty()) { tokens += word.toString(); word.setLength(0) } }
        text.forEach { ch ->
            when {
                ch.isLetterOrDigit() || ch in "'’ʼ-" -> { word.append(ch); spacing = "" }
                isTerminal(ch) -> { word.setLength(0); tokens.clear(); spacing = "" }
                isInternal(ch) -> {
                    flush()
                    if (tokens.isNotEmpty()) {
                        val tail = tokens.removeAt(tokens.lastIndex)
                        tokens += lexical(tail) + (if (ch in ";:") spacing else "") + ch
                    }
                    spacing = ""
                }
                else -> { flush(); spacing = if (ch == '\u00a0' || ch == '\u202f' || ch == ' ') ch.toString() else "" }
            }
        }
        flush()
        return tokens.takeLast(PhraseUsageStore.MAX_SEQUENCE_LENGTH - 1)
    }

    /** *A short prefix must not crowd out its own complete punctuated continuation or a second branch.* */
    fun diverse(candidates: List<SuggestionResult>, locale: Locale): List<SuggestionResult> {
        val prefixes = HashSet<String>()
        candidates.filter { it.candidate.any { ch -> isInternal(ch) } }.forEach { candidate ->
            val words = candidate.candidate.split(' ')
            for (length in 1 until words.size) prefixes += words.take(length).joinToString(" ").lowercase(locale)
        }
        return candidates.filterNot { it.candidate.any { ch -> isInternal(ch) } && it.candidate.lowercase(locale) in prefixes }
    }

    /** *Consume only punctuation already supplied immediately after the replaced word by this candidate.* */
    fun overlappingAfter(replacement: String, afterWord: String): Int {
        val first = replacement.substringBefore(' ')
        val punctuation = first.lastOrNull()?.takeIf { isRetained(it) } ?: return 0
        val spacing = afterWord.takeWhile { it == ' ' || it == '\u00a0' || it == '\u202f' }
        return if (afterWord.getOrNull(spacing.length) == punctuation) spacing.length + 1 else 0
    }
}
