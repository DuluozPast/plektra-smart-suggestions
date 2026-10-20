package rocks.pkb.plektra.core

import android.util.Log
import android.view.inputmethod.InputConnection

/**
 * Tracks when the IME commits a trailing space automatically (e.g., after accepting
 * a suggestion or auto-replace). Used to selectively trim that space if the next
 * keypress is punctuation, without affecting user-typed spaces.
 */
object AutoSpaceTracker {
    private const val TAG = "AutoSpaceTracker"
    private const val QUOTE_CONTEXT_LOOKBACK = 240
    @Volatile
    private var autoSpacePending: Boolean = false

    private var suggestionConnection: InputConnection? = null
    private var suggestionSuffix: String? = null
    private var trimmedBoundaryConnection: InputConnection? = null
    private var trimmedBoundary: Char? = null

    fun consumeTrimmedSuggestionBoundary(input: InputConnection?, boundary: Char?): Boolean {
        val matches = input != null && trimmedBoundaryConnection === input && boundary == trimmedBoundary
        trimmedBoundaryConnection = null; trimmedBoundary = null
        return matches
    }

    fun markSuggestionAutoSpace(input: InputConnection) {
        markAutoSpace()
        suggestionConnection = input
        suggestionSuffix = input.getTextBeforeCursor(128, 0)?.toString()?.takeIf { it.endsWith(" ") }
    }

    fun clearSuggestionSpace() {
        suggestionConnection = null
        suggestionSuffix = null
    }

    /** *Move only the verified suggestion-generated space outside a closing formatting marker.* */
    fun takeSuggestionSpaceForFormatting(input: InputConnection): Boolean {
        val matches = suggestionConnection === input && suggestionSuffix != null &&
            input.getTextBeforeCursor(128, 0)?.toString() == suggestionSuffix &&
            suggestionSuffix?.endsWith(" ") == true
        clearSuggestionSpace()
        if (!matches || !input.deleteSurroundingText(1, 0)) return false
        autoSpacePending = false
        trimmedBoundaryConnection = null
        trimmedBoundary = null
        return true
    }

    /** Remove only the space inserted by the preceding suggestion, before closing punctuation. */
    fun prepareSuggestionSpace(input: InputConnection, text: CharSequence) {
        if (text.isNotEmpty() && (trimmedBoundaryConnection !== input || text.first() != trimmedBoundary)) {
            trimmedBoundaryConnection = null; trimmedBoundary = null
        }
        val suffix = suggestionSuffix ?: return
        val first = text.firstOrNull() ?: return
        val sameConnection = suggestionConnection === input
        clearSuggestionSpace()
        if (!sameConnection) return
        val before = input.getTextBeforeCursor(128, 0)?.toString().orEmpty()
        if (before != suffix || !before.endsWith(" ")) return
        val closing = first in ".…,;:!?)]}»›”" ||
            (first == '"' && hasUnclosedStraightDoubleQuote(input))
        // The first manual Space after a suggestion must not erase the origin of
        // its existing separator: a second rapid Space replaces both trailing spaces.
        // The formatting suffix has already been cleared, so a manual space is not trimmed.
        if (text.toString() == " ") return
        // Opening parentheses and the separating space for the next word are retained.
        if (!closing) { autoSpacePending = false; return }
        autoSpacePending = false
        if (input.deleteSurroundingText(1, 0)) {
            trimmedBoundaryConnection = input
            trimmedBoundary = first
        }
        // The ordinary commit path applies French nonbreaking spacing, then the configured after-space.
    }

    fun markAutoSpace() {
        autoSpacePending = true
        Log.d(TAG, "markAutoSpace -> pending=true")
    }

    /**
     * Returns true if an auto-space was pending and clears the flag.
     */
    fun consumeAutoSpace(): Boolean {
        val had = autoSpacePending
        autoSpacePending = false
        Log.d(TAG, "consumeAutoSpace had=$had")
        return had
    }

    fun isPending(): Boolean {
        return autoSpacePending
    }

    fun clear() {
        clearSuggestionSpace()
        trimmedBoundaryConnection = null; trimmedBoundary = null
        autoSpacePending = false
        Log.d(TAG, "clear pending=false")
    }

    /**
     * If an auto-space is pending and the cursor is at "word<space>", replace the space
     * with "<punctuation> " in a single batch edit. Returns true if applied.
     */
    fun replaceAutoSpaceWithPunctuation(
        inputConnection: InputConnection,
        punctuation: String
    ): Boolean {
        if (!autoSpacePending) return false
        val beforeTwo = inputConnection.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        val lastIsSpace = beforeTwo.lastOrNull() == ' '
        val prevIsWordChar = beforeTwo.dropLast(1).lastOrNull()?.isLetterOrDigit() == true
        if (!lastIsSpace || !prevIsWordChar) {
            return false
        }
        if (punctuation == "\"" && !hasUnclosedStraightDoubleQuote(inputConnection)) {
            autoSpacePending = false
            Log.d(TAG, "Keeping auto-space before opening quote")
            return false
        }
        autoSpacePending = false
        inputConnection.beginBatchEdit()
        inputConnection.deleteSurroundingText(1, 0)
        inputConnection.commitText("$punctuation ", 1)
        inputConnection.endBatchEdit()
        Log.d(TAG, "replaceAutoSpaceWithPunctuation -> '$punctuation '")
        return true
    }

    private fun hasUnclosedStraightDoubleQuote(inputConnection: InputConnection): Boolean {
        val before = inputConnection.getTextBeforeCursor(QUOTE_CONTEXT_LOOKBACK, 0)
            ?.toString()
            .orEmpty()
            .dropLast(1)
            .substringAfterLast('\n')
        var quoteOpen = false
        before.forEachIndexed { index, char ->
            if (char != '"') return@forEachIndexed
            if (quoteOpen) {
                quoteOpen = false
            } else if (isOpeningQuoteContext(before, index)) {
                quoteOpen = true
            }
        }
        return quoteOpen
    }

    private fun isOpeningQuoteContext(text: String, quoteIndex: Int): Boolean {
        if (quoteIndex == 0) return true
        val previous = text[quoteIndex - 1]
        return previous.isWhitespace() ||
            previous in setOf('(', '[', '{', '<', '«', '‹', '„', '“', '”', '»', '›', '-', '–', '—')
    }
}
