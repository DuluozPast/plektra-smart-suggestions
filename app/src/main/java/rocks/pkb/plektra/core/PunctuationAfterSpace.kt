package rocks.pkb.plektra.core

import android.content.Context
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.SettingsManager

object PunctuationAfterSpace {
    private var connection: InputConnection? = null
    private var lastPunctuation: Char? = null
    private const val CLOSE_PUNCTUATION = ".,;:!?/\\)]}»›"

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
        connection = input
        lastPunctuation = punctuation
    }

    fun clear() { connection = null; lastPunctuation = null }
}
