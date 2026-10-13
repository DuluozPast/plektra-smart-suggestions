package rocks.pkb.plektra.core.suggestions

import android.util.Log
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.core.AutoSpaceTracker

class CurrentWordTracker(
    private val onWordChanged: (String) -> Unit,
    private val onWordReset: () -> Unit,
    private val autoSpacePunctuationProvider: () -> String = {
        rocks.pkb.plektra.core.Punctuation.DEFAULT_AUTO_SPACE
    },
    private val maxLength: Int = 48
) {

    private val current = StringBuilder()
    private var overflowLength = 0
    private val tag = "CurrentWordTracker"
    private val debugLogging = false
    val currentWord: String
        get() = if (overflowLength == 0) current.toString() else ""

    fun setWord(word: String, notify: Boolean = true) {
        current.clear()
        current.append(word.take(maxLength))
        overflowLength = (word.length - maxLength).coerceAtLeast(0)
        if (debugLogging) Log.d(tag, "setWord currentWord='$current'")
        if (notify) {
            onWordChanged(currentWord)
        }
    }

    fun onCharacterCommitted(text: CharSequence) {
        if (text.isEmpty()) return

        text.forEach { char ->
            // Special case: "\bX" pattern means "replace last with X" (used by multi-tap).
            if (char == '\b') {
                onBackspace()
                return@forEach
            }

            val normalizedChar = normalizeApostrophe(char)

            val isInWordApostrophe =
                normalizedChar == '\'' &&
                        current.isNotEmpty() &&
                        current.last().isLetterOrDigit()

            val isInWordHyphen =
                normalizedChar == '-' &&
                        current.isNotEmpty() &&
                        current.last().isLetterOrDigit()

            val isWordChar =
                normalizedChar.isLetterOrDigit() ||
                        isInWordApostrophe ||
                        isInWordHyphen

            if (isWordChar) {
                if (current.length < maxLength) {
                    current.append(normalizedChar)
                    if (debugLogging) Log.d(tag, "currentWord='$current'")
                    onWordChanged(currentWord)
                } else {
                    overflowLength += 1
                    // Clear suggestions once; subsequent overflow characters do no dictionary work.
                    if (overflowLength == 1) onWordChanged("")
                }
            } else {
                if (debugLogging) {
                    Log.d(tag, "reset on non-letter char='$char'")
                }

                reset()
            }
        }
    }

    private fun normalizeApostrophe(c: Char): Char = when (c) {
        '’', '‘', 'ʼ' -> '\''
        else -> c
    }

    fun onBackspace() {
        if (overflowLength > 0) {
            overflowLength -= 1
            if (overflowLength == 0) onWordChanged(currentWord)
            return
        }
        if (current.isNotEmpty()) {
            current.deleteCharAt(current.length - 1)
            if (current.isNotEmpty()) {
                if (debugLogging) Log.d(tag, "currentWord after backspace='$current'")
                onWordChanged(currentWord)
            } else {
                reset()
            }
        }
    }

    fun onBoundaryReached(boundaryChar: Char? = null, inputConnection: InputConnection? = null) {
        if (boundaryChar != null) {
            // If an auto-space is pending, replace it with "<punctuation> " when punctuation is pressed.
            val punctuationSet = autoSpacePunctuationProvider()
            if (inputConnection != null && boundaryChar in punctuationSet) {
                val replaced = AutoSpaceTracker.replaceAutoSpaceWithPunctuation(
                    inputConnection,
                    boundaryChar.toString()
                )
                if (replaced) {
                    reset()
                    return
                }
            }
            inputConnection?.commitText(boundaryChar.toString(), 1)
        }
        reset()
    }

    fun reset() {
        if (current.isNotEmpty()) {
            if (debugLogging) Log.d(tag, "reset currentWord='$current'")
            current.clear()
            overflowLength = 0
            onWordReset()
        }
    }

    fun onCursorMoved() {
        reset()
    }

    fun onContextChanged() {
        reset()
    }
}
