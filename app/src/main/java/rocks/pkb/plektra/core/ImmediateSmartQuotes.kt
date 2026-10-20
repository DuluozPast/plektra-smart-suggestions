package rocks.pkb.plektra.core

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import java.util.Locale

/** Reconstruct nesting from editor text on every press, including after paste/cursor moves. */
internal object ImmediateQuotePolicy {
    // WhatsApp does not recognize a style delimiter next to a narrow NBSP.
    // Adapt quote edges only, never the quoted body or French punctuation inside it.
    fun formattingEdge(edge: String, neighbor: Char?, packageName: String?): String =
        if (packageName in setOf("com.whatsapp", "com.whatsapp.w4b") && neighbor in listOf('_', '*'))
            edge.replace('\u202f', ' ') else edge

    fun isQuoteInput(text: String): Boolean = text.length == 1 && text[0] in "\"“”"
    enum class Action { AUTO, OPEN, CLOSE, STRAIGHT }
    enum class Kind(val opening: String, val closing: String) {
        OUTER("«\u202f", "\u202f»"), INNER("“", "”")
    }
    data class Decision(val text: String, val kind: Kind?, val closing: Boolean = false)

    fun decide(before: CharSequence, after: CharSequence, locale: Locale,
        action: Action = Action.AUTO, automaticSpace: Boolean = false): Decision? {
        if (action == Action.STRAIGHT) return Decision("\"", null)
        if (locale.language != "fr") return if (action == Action.AUTO) null else Decision("\"", null)
        val stack = ArrayList<Kind>()
        var escaped = false
        for (char in before) {
            if (char == '\\') { escaped = !escaped; continue }
            if (!escaped) when (char) {
                '«' -> stack.add(Kind.OUTER)
                // English quotes in pasted/older text are not a French nesting
                // level on their own. Only an actual outer « opens that scope.
                '“' -> if (Kind.OUTER in stack) stack.add(Kind.INNER)
                '»', '”' -> {
                    val index = stack.lastIndexOf(if (char == '»') Kind.OUTER else Kind.INNER)
                    if (index >= 0) while (stack.size > index) stack.removeAt(stack.lastIndex)
                }
            }
            escaped = false
        }
        val following = after.take(3).dropWhile { it.isWhitespace() }
        val inferred = when (following.firstOrNull()) { '»' -> Kind.OUTER; '”' -> Kind.INNER; else -> null }
        val active = stack.lastOrNull() ?: inferred
        val previous = before.lastOrNull()
        val visible = before.lastOrNull { !it.isWhitespace() }
        val openingContext = previous == null || previous.isWhitespace() || previous in "([{:<–—«“"
        val close = when (action) {
            Action.CLOSE -> true
            Action.OPEN -> false
            else -> active != null && (inferred != null && stack.isEmpty() || !openingContext ||
                automaticSpace && visible != null && visible !in "«“([{:<–—")
        }
        if (close) {
            val kind = active ?: Kind.OUTER
            return Decision(kind.closing, kind, true)
        }
        if (action == Action.AUTO && !openingContext) return null
        val kind = when {
            stack.isEmpty() -> Kind.OUTER
            stack.last() == Kind.OUTER -> Kind.INNER
            else -> return Decision("\"", null) // Only two automatic levels are defined.
        }
        return Decision(kind.opening, kind)
    }
}

/** Rich text never leaves the editor: commitText receives quote edges only, never a body. */
internal object ImmediateQuoteEditor {
    data class Result(val consumed: Boolean, val committedText: String? = null,
        val selectionChanged: Boolean = false, val unsupportedSelection: Boolean = false)
    private data class Range(val start: Int, val end: Int, val text: CharSequence, val offset: Int)

    private fun matches(range: Range, position: Int, edge: String): Boolean {
        val index = position - range.offset
        return index >= 0 && index <= range.text.length - edge.length &&
            edge.indices.all { range.text[index + it] == edge[it] }
    }

    private fun selection(input: InputConnection, selected: CharSequence?): Range? {
        val extracted = input.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 4096 }, 0) ?: return null
        val text = extracted.text ?: return null
        val start = minOf(extracted.selectionStart, extracted.selectionEnd)
        val end = maxOf(extracted.selectionStart, extracted.selectionEnd)
        if (extracted.startOffset < 0 || start < 0 || end > text.length) return null
        if (selected == null) {
            if (start != end) return null
        } else {
            if (end - start != selected.length || selected.indices.any { text[start + it] != selected[it] }) return null
        }
        val base = extracted.startOffset.toLong()
        if (base + end > Int.MAX_VALUE) return null
        return Range((base + start).toInt(), (base + end).toInt(), text, extracted.startOffset)
    }

    fun apply(input: InputConnection, locale: Locale,
        action: ImmediateQuotePolicy.Action = ImmediateQuotePolicy.Action.AUTO, packageName: String? = null): Result {
        val before = input.getTextBeforeCursor(4096, 0) ?: ""
        val after = input.getTextAfterCursor(128, 0) ?: ""
        val selected = input.getSelectedText(0)?.takeIf { it.isNotEmpty() }
        val decisionAction = if (selected != null && locale.language == "fr" &&
            action == ImmediateQuotePolicy.Action.AUTO) ImmediateQuotePolicy.Action.OPEN else action
        val automaticSpace = AutoSpaceTracker.isPending() || PunctuationAfterSpace.hasAutoSpaceForFormatting(input)
        val decision = ImmediateQuotePolicy.decide(before, after, locale, decisionAction, automaticSpace)
            ?: return Result(false)
        if (selected != null) {
            val range = selection(input, selected) ?: return Result(true, unsupportedSelection = true)
            val opening = ImmediateQuotePolicy.formattingEdge(decision.kind?.opening ?: decision.text, selected.firstOrNull(), packageName)
            val closing = ImmediateQuotePolicy.formattingEdge(decision.kind?.closing ?: decision.text, selected.lastOrNull(), packageName)
            input.beginBatchEdit()
            try {
                val position = if (action == ImmediateQuotePolicy.Action.CLOSE) range.end else range.start
                if (!input.setSelection(position, position)) return Result(true, unsupportedSelection = true)
                if (!input.commitText(if (action == ImmediateQuotePolicy.Action.CLOSE) closing else opening, 1)) {
                    return Result(true, selectionChanged = true, unsupportedSelection = true)
                }
                if (action == ImmediateQuotePolicy.Action.AUTO) {
                    val end = range.end + opening.length
                    if (!input.setSelection(end, end) || !input.commitText(closing, 1)) {
                        return Result(true, selectionChanged = true, unsupportedSelection = true)
                    }
                }
                AutoSpaceTracker.clear()
                return Result(true, selectionChanged = true)
            } finally { input.endBatchEdit() }
        }

        // When editing before an existing closer, move over that edge instead of duplicating it.
        if (decision.closing && decision.kind != null) {
            val candidates = if (decision.kind == ImmediateQuotePolicy.Kind.OUTER) listOf("\u202f»", "»") else listOf("”")
            val existing = candidates.firstOrNull { edge -> after.length >= edge.length && edge.indices.all { after[it] == edge[it] } }
            if (existing != null) {
                val range = selection(input, null) ?: return Result(true, unsupportedSelection = true)
                if (!matches(range, range.end, existing)) return Result(true, unsupportedSelection = true)
                val success = input.setSelection(range.end + existing.length, range.end + existing.length)
                AutoSpaceTracker.clear()
                return Result(true, selectionChanged = true, unsupportedSelection = !success)
            }
        }
        input.beginBatchEdit()
        try {
            var moveSpace = false
            var text = decision.text
            if (decision.closing) {
                moveSpace = AutoSpaceTracker.takeSuggestionSpaceForFormatting(input) || PunctuationAfterSpace.takeAutoSpaceForFormatting(input)
                if (moveSpace) { PunctuationAfterSpace.clear(); DeferredPunctuationSpaceTracker.clear() }
                val last = input.getTextBeforeCursor(1, 0)?.lastOrNull()
                if (decision.kind == ImmediateQuotePolicy.Kind.OUTER) {
                    if (last == '\u202f') text = "»"
                    else if (last == ' ' && input.deleteSurroundingText(1, 0)) text = "\u202f»"
                }
            }
            if (decision.closing) {
                val visible = input.getTextBeforeCursor(8, 0)?.lastOrNull { !it.isWhitespace() }
                text = ImmediateQuotePolicy.formattingEdge(text, visible, packageName)
            }
            val committed = text + if (moveSpace) " " else ""
            val success = input.commitText(committed, 1)
            if (success && moveSpace) AutoSpaceTracker.markSuggestionAutoSpace(input) else AutoSpaceTracker.clear()
            return Result(true, if (success) committed else null, unsupportedSelection = !success)
        } finally { input.endBatchEdit() }
    }

    /** Deferred compatibility mode also replaces only the two quote characters. */
    fun replacePair(input: InputConnection, before: String, openingIndex: Int,
        opening: String, closing: String, delimiter: String, packageName: String? = null): Result {
        val openingEdge = ImmediateQuotePolicy.formattingEdge(opening, before.getOrNull(openingIndex + 1), packageName)
        val closingEdge = ImmediateQuotePolicy.formattingEdge(closing, before.dropLast(1).lastOrNull { !it.isWhitespace() }, packageName)
        val range = selection(input, null) ?: return Result(false)
        val start = range.end - before.length + openingIndex
        val end = range.end - 1
        if (start < 0 || start >= end) return Result(false)
        // Some editor bridges expose stale extraction/cursor snapshots. Never replace
        // a character unless the extracted text confirms both quote positions.
        if (!matches(range, start, before[openingIndex].toString()) ||
            !matches(range, end, before.last().toString())) return Result(false)
        input.beginBatchEdit()
        try {
            if (!input.setSelection(end, end + 1)) return Result(false)
            if (!input.commitText(closingEdge + delimiter, 1)) return Result(true, selectionChanged = true, unsupportedSelection = true)
            if (!input.setSelection(start, start + 1) || !input.commitText(openingEdge, 1)) {
                return Result(true, selectionChanged = true, unsupportedSelection = true)
            }
            val cursor = range.end + openingEdge.length + closingEdge.length - 2 + delimiter.length
            val moved = input.setSelection(cursor, cursor)
            return Result(true, selectionChanged = true, unsupportedSelection = !moved)
        } finally { input.endBatchEdit() }
    }
}
