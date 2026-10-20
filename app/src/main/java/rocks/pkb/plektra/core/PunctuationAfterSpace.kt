package rocks.pkb.plektra.core

import android.content.Context
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.SettingsManager

object PunctuationAfterSpace {
    private var connection: InputConnection? = null
    private var lastPunctuation: Char? = null
    private var automaticSuffix: String? = null
    private const val CLOSE_PUNCTUATION = ".…,;:!?/\\)]}»›"

    fun beforeTextCommit(input: InputConnection, text: CharSequence): Boolean {
        AutoSpaceTracker.prepareSuggestionSpace(input, text)
        val punctuation = lastPunctuation ?: return false
        val first = text.firstOrNull() ?: return false
        if (connection !== input) { clear(); return false }
        val before = input.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        clear()
        if (before != "$punctuation ") return false
        if (text.toString() == " ") return true
        if (first in CLOSE_PUNCTUATION || first == '\n') input.deleteSurroundingText(1, 0)
        return false
    }

    fun afterCommit(context: Context, input: InputConnection, punctuation: Char) {
        if (punctuation !in SettingsManager.getSpaceAfterPunctuation(context)) return
        val before = input.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        val hasSpace = before == "$punctuation "
        if (!hasSpace && before.lastOrNull() != punctuation) return
        if (!hasSpace && !input.commitText(" ", 1)) return
        DeferredPunctuationSpaceTracker.clear()
        recordAutoSpace(input, punctuation)
    }

    /** Record a separator just committed by a punctuation path, including the comma shortcut. */
    fun recordAutoSpace(input: InputConnection, punctuation: Char) {
        connection = input
        lastPunctuation = punctuation
        automaticSuffix = input.getTextBeforeCursor(128, 0)?.toString()?.takeIf { it.endsWith("$punctuation ") }
    }

    /** Move only our verified automatic trailing space, never the French space before the sign.
     * The connection and bounded suffix must still match; manual spaces are left untouched. */
    fun hasAutoSpaceForFormatting(input: InputConnection): Boolean {
        val suffix = automaticSuffix
        return connection === input && suffix != null &&
            input.getTextBeforeCursor(128, 0)?.toString() == suffix
    }

    fun takeAutoSpaceForFormatting(input: InputConnection): Boolean {
        val matches = hasAutoSpaceForFormatting(input)
        clear()
        return matches && input.deleteSurroundingText(1, 0)
    }

    fun clear() { connection = null; lastPunctuation = null; automaticSuffix = null }
}
