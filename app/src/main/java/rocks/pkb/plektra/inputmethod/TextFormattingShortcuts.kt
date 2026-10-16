package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.PunctuationAfterSpace
import rocks.pkb.plektra.core.DeferredPunctuationSpaceTracker
import rocks.pkb.plektra.core.AutoSpaceTracker
import java.util.Locale

/** *Translate formatting intentions for the active editor; never substitute decorative Unicode letters.* */
internal object TextFormattingShortcuts {
    fun actionForKey(keyCode: Int, locale: Locale = Locale.FRENCH): String? {
        val french = locale.language == "fr"
        return when (keyCode) {
            (if (french) KeyEvent.KEYCODE_G else KeyEvent.KEYCODE_B) -> "format_bold"
            KeyEvent.KEYCODE_I -> "format_italic"
            (if (french) KeyEvent.KEYCODE_S else KeyEvent.KEYCODE_U) -> "format_underline"
            else -> null
        }
    }

    fun isAction(action: String?) = action in setOf("format_bold", "format_italic", "format_underline")

    fun handle(context: Context, input: InputConnection, editor: EditorInfo?, action: String, event: KeyEvent?): Boolean {
        if (!isAction(action)) return false
        // *Holding a key must not toggle a style repeatedly or flood a message with markers.*
        if (event?.action == KeyEvent.ACTION_UP || (event?.repeatCount ?: 0) > 0) return true
        val inputType = editor?.inputType ?: InputType.TYPE_CLASS_TEXT
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if ((inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER ||
            variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) return true
        val app = editor?.packageName.orEmpty()
        val whatsapp = app == "com.whatsapp" || app == "com.whatsapp.w4b"
        val telegram = app in setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")
        val redditMarkdown = app == "com.reddit.frontpage" && SettingsManager.getRedditMarkdownFormatting(context)
        if (action == "format_underline" && (whatsapp || redditMarkdown || telegram)) {
            Toast.makeText(context, R.string.formatting_unavailable, Toast.LENGTH_SHORT).show()
            return true
        }
        if (whatsapp || redditMarkdown || telegram) {
            val marker = if (action == "format_bold") { if (whatsapp) "*" else "**" }
                else if (telegram) "__" else if (whatsapp) "_" else "*"
            val selected = input.getSelectedText(0)?.toString().orEmpty()
            val closing = selected.isEmpty() && hasOpenMarker(
                input.getTextBeforeCursor(4096, 0)?.toString().orEmpty().substringAfterLast('\n'),
                marker, redditMarkdown || telegram)
            // *Without a selection each press inserts one marker: open, type, then press again to close.*
            val replacement = if (selected.isEmpty()) marker else {
                if (selected.length >= marker.length * 2 && selected.startsWith(marker) && selected.endsWith(marker))
                    selected.substring(marker.length, selected.length - marker.length)
                else marker + selected + marker
            }
            input.beginBatchEdit()
            try {
                // A punctuation-generated separator is just as provisional as a suggestion separator.
                // Put it after the closing marker, preserving the nonbreaking space before ? or !.
                val moveSpace = closing && (AutoSpaceTracker.takeSuggestionSpaceForFormatting(input) ||
                    PunctuationAfterSpace.takeAutoSpaceForFormatting(input))
                if (moveSpace) {
                    PunctuationAfterSpace.clear()
                    DeferredPunctuationSpaceTracker.clear()
                }
                val committed = input.commitText(if (moveSpace) replacement + " " else replacement, 1)
                if (committed && moveSpace) AutoSpaceTracker.markSuggestionAutoSpace(input)
                else AutoSpaceTracker.clear()
            } finally { input.endBatchEdit() }
            return true
        }
        // *Word and other rich-text editors receive the standard native Ctrl+B/I/U chord.*
        val keyCode = when (action) {
            "format_bold" -> KeyEvent.KEYCODE_B
            "format_italic" -> KeyEvent.KEYCODE_I
            else -> KeyEvent.KEYCODE_U
        }
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        input.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        input.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
        return true
    }

    /** *Inspect only this bounded paragraph; escaped markers and Reddit bold/italic runs are distinct.* */
    private fun hasOpenMarker(text: String, marker: String, reddit: Boolean): Boolean {
        var open = false
        var index = 0
        while (index < text.length) {
            if (text[index] == '\\') { index += 2; continue }
            if (text[index] != marker[0]) { index++; continue }
            val start = index
            while (index < text.length && text[index] == marker[0]) index++
            val length = index - start
            val relevant = if (!reddit) length % 2 == 1 else
                if (marker.length == 2) length == 2 || length == 3 else length % 2 == 1
            if (relevant) open = !open
        }
        return open
    }
}
