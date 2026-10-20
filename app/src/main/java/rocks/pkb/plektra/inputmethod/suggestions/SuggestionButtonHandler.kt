package rocks.pkb.plektra.inputmethod.suggestions

import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.suggestions.CasingHelper
import rocks.pkb.plektra.inputmethod.AutoCapitalizeHelper
import rocks.pkb.plektra.inputmethod.DebugCaptureStore
import rocks.pkb.plektra.inputmethod.VariationButtonHandler
import rocks.pkb.plektra.core.AutoSpaceTracker
import rocks.pkb.plektra.core.Punctuation

/**
 * Handles clicks on suggestion buttons (full word replacements).
 */
object SuggestionButtonHandler {
    private const val TAG = "SuggestionButtonHandler"

    fun createSuggestionClickListener(
        suggestion: String,
        inputConnection: InputConnection?,
        listener: VariationButtonHandler.OnVariationSelectedListener? = null,
        shouldDisableAutoCapitalize: Boolean,
        onSuggestionCommitted: ((String, String) -> Unit)? = null,
        capsLockActive: () -> Boolean = { false }
    ): View.OnClickListener {
        return View.OnClickListener {
            Log.d(TAG, "Click on suggestion button: $suggestion")

            if (inputConnection == null) {
                Log.w(TAG, "No inputConnection available to insert suggestion")
                return@OnClickListener
            }

            val context = it.context.applicationContext
            val forceLeadingCapital = AutoCapitalizeHelper.shouldAutoCapitalizeAtCursor(
                context = context,
                inputConnection = inputConnection,
                shouldDisableAutoCapitalize = shouldDisableAutoCapitalize
            ) && SettingsManager.getAutoCapitalizeFirstLetter(context)

            val result = replaceCurrentWord(
                inputConnection,
                suggestion,
                forceLeadingCapital,
                capsLockActive(), context.resources.configuration.locales[0]
            )

            if (result != null) {
                onSuggestionCommitted?.invoke(
                    result.original,
                    result.replacement
                )
            }

            listener?.onVariationSelected(suggestion)
        }
    }

    private data class ReplacementResult(
        val original: String,
        val replacement: String
    )

    /**
     * Replace the word immediately before the cursor with the given suggestion.
     * Deletes up to the nearest whitespace/punctuation boundary and applies basic casing
     * including the explicit Caps Lock state for suggestions without a typed prefix.
     */


    private fun replaceCurrentWord(
        inputConnection: InputConnection,
        suggestion: String,
        forceLeadingCapital: Boolean,
        capsLockActive: Boolean,
        locale: java.util.Locale
    ): ReplacementResult? {
        val before = inputConnection.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val after = inputConnection.getTextAfterCursor(64, 0)?.toString().orEmpty()
        fun isBoundaryChar(ch: Char, prev: Char?, next: Char?): Boolean {
            return Punctuation.isWordBoundary(ch, prev, next)
        }

        // Find start of word in 'before'
        var start = before.length
        while (start > 0) {
            val ch = before[start - 1]
            val prev = before.getOrNull(start - 2)
            val next = before.getOrNull(start)
            if (!isBoundaryChar(ch, prev, next)) {
                start--
                continue
            }
            break
        }
        // Find end of word in 'after'
        var end = 0
        while (end < after.length) {
            val ch = after[end]
            val prev = if (end == 0) before.lastOrNull() else after[end - 1]
            val next = after.getOrNull(end + 1)
            if (!isBoundaryChar(ch, prev, next)) {
                end++
                continue
            }
            break
        }

        val wordBeforeCursor = before.substring(start)
        val wordAfterCursor = after.substring(0, end)
        val currentWord = wordBeforeCursor + wordAfterCursor

        // Delete the full word around the cursor
        val deleteBefore = wordBeforeCursor.length
        val deleteAfter = wordAfterCursor.length + rocks.pkb.plektra.core.suggestions.PunctuatedPhrase.overlappingAfter(suggestion, after.substring(end))
        val replacement = CasingHelper.applyCasing(suggestion, currentWord, forceLeadingCapital,
            forceUppercase = capsLockActive, locale = locale)
        val committed = SuggestionCommitter.commit(inputConnection, replacement, deleteBefore, deleteAfter, after)
        if (committed) {
            DebugCaptureStore.recordAutoCorrectionCommit(
                before = currentWord,
                after = replacement,
                trigger = DebugCaptureStore.AutoCorrectionTrigger.SUGGESTION_TAP,
                source = "UNKNOWN"
            )
        }
        return if (committed) {
            ReplacementResult(
                original = currentWord,
                replacement = replacement
            )
        } else {
            null
        }
    }
}
