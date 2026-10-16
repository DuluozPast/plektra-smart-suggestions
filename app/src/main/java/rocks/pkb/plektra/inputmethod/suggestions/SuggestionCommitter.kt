package rocks.pkb.plektra.inputmethod.suggestions

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.core.AutoSpaceTracker

/** Preserve the legacy word-then-space protocol for rich editors.
 * Do not put the primary separator in a batch: some bridges discard it there.
 * Formatting only consumes the verified provisional separator when closing a marker;
 * it must not change how suggestions are inserted into ordinary editors. */
internal object SuggestionCommitter {
    fun commit(input: InputConnection, replacement: String, deleteBefore: Int, deleteAfter: Int, after: String): Boolean {
        val next = after.getOrNull(deleteAfter)
        // A paragraph boundary after the caret does not separate text typed before it.
        val append = !replacement.endsWith("'") &&
            (next == ' ' || next == '\n' || next == '\r' || next == '\u2028' || next == '\u2029' || next?.isWhitespace() != true)
        val consumeSpace = append && next == ' '
        AutoSpaceTracker.clear()
        if (!input.deleteSurroundingText(deleteBefore, deleteAfter + if (consumeSpace) 1 else 0)) return false
        if (!input.commitText(replacement, 1)) return false
        if (!append) return true

        // This is the primary operation, even if the editor cannot expose surrounding text.
        // It restores the pre-regression insertion order instead of relying on a suffix read to send a space.
        input.commitText(" ", 1)
        val expected = replacement + " "
        var before = input.getTextBeforeCursor(expected.length, 0)?.toString()
        if (before?.endsWith(replacement) == true) {
            // Legacy native-key fallback, only when readable context confirms a missing separator.
            input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE))
            input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE))
            before = input.getTextBeforeCursor(expected.length, 0)?.toString()
        }
        if (before?.endsWith(replacement) == true) {
            // Retain compatibility with editors that accept word+space atomically but ignore
            // standalone spaces AND native space events. Replace only the exact suffix just verified.
            input.beginBatchEdit()
            val repaired = try {
                input.deleteSurroundingText(replacement.length, 0) && input.commitText(expected, 1)
            } finally { input.endBatchEdit() }
            if (!repaired) return false
            before = input.getTextBeforeCursor(expected.length, 0)?.toString()
        }
        if (before == expected) AutoSpaceTracker.markSuggestionAutoSpace(input)
        return true
    }
}
