package rocks.pkb.plektra.inputmethod

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Selection
import android.text.Spannable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import rocks.pkb.plektra.R

/** Explicit, in-memory selection snapshot. No capture at service startup or key filtering. */
internal object ScreenTextSelectorBridge {
    interface Host {
        val active: Boolean
        fun open(): Boolean
        fun dispatch(chord: ScreenNavKeyPolicy.Chord): Boolean
        fun close()
    }
    @Volatile private var host: Host? = null
    val available get() = host != null
    val active get() = host?.active == true
    fun register(value: Host) { host = value }
    fun unregister(value: Host) { if (host === value) { value.close(); host = null } }
    fun open(context: Context): Boolean {
        val target = host
        if (target == null) Toast.makeText(context, R.string.screen_text_enable_service, Toast.LENGTH_LONG).show()
        return target?.open() == true
    }
    fun dispatch(chord: ScreenNavKeyPolicy.Chord) = host?.takeIf { it.active }?.dispatch(chord) == true
    fun close() { host?.close() }
}

/** Only called on the user's selection action. Editable/password subtrees are excluded. */
internal object ScreenTextCollector {
    @Suppress("DEPRECATION")
    fun collect(root: AccessibilityNodeInfo, maxChars: Int = 32000, maxNodes: Int = 1000, viewport: android.graphics.Rect? = null): String {
        val parts = mutableListOf<String>()
        val seen = HashSet<String>()
        var remaining = maxChars.coerceAtLeast(0)
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (++visited > maxNodes || remaining <= 0 || depth > 40 || node.isPassword || node.isEditable) return
            val type = node.className?.toString().orEmpty()
            val bounds=android.graphics.Rect()
            if(viewport!=null) node.getBoundsInScreen(bounds)
            val readable=node.isVisibleToUser && !type.endsWith("Button") &&
                (viewport==null || android.graphics.Rect.intersects(bounds,viewport))
            val text = if(readable) node.text?.toString()?.trim().orEmpty() else ""
            if (text.isNotEmpty() && seen.add(text)) {
                val separator = if (parts.isEmpty()) 0 else 2
                if (remaining > separator) {
                    val part = text.take(remaining - separator).let { if(it.lastOrNull()?.isHighSurrogate()==true) it.dropLast(1) else it }
                    parts.add(part)
                    remaining -= separator + part.length
                }
            }
            for (index in 0 until node.childCount) {
                if (visited >= maxNodes || remaining <= 0) break
                val child = node.getChild(index) ?: continue
                try { walk(child, depth + 1) } finally { child.recycle() }
            }
        }
        walk(root, 0)
        return parts.joinToString("\n\n")
    }
}

internal data class LocalTextRange(val anchor: Int, val caret: Int) {
    fun selected(text: String): String = text.substring(minOf(anchor, caret), maxOf(anchor, caret))
    fun horizontal(text: String, right: Boolean, extend: Boolean, word: Boolean): LocalTextRange {
        var position = caret.coerceIn(0, text.length)
        if (!extend && anchor != caret && !word) {
            position = if (right) maxOf(anchor, caret) else minOf(anchor, caret)
        } else if (word) {
            if (right) {
                while (position < text.length && !text[position].isWhitespace()) position++
                while (position < text.length && text[position].isWhitespace()) position++
            } else {
                while (position > 0 && text[position - 1].isWhitespace()) position--
                while (position > 0 && !text[position - 1].isWhitespace()) position--
            }
        } else if (right && position < text.length) position = text.offsetByCodePoints(position, 1)
        else if (!right && position > 0) position = text.offsetByCodePoints(position, -1)
        return LocalTextRange(if (extend) anchor else position, position)
    }
}

/** Read-only selectable surface owned by the optional accessibility service. */
internal class LocalScreenTextSelector(
    private val service: AccessibilityService,
    private val rootProvider: () -> AccessibilityNodeInfo?
) : ScreenTextSelectorBridge.Host {
    private var panel: View? = null
    private var textView: TextView? = null
    override val active get() = panel != null
    private val windows get() = service.getSystemService(WindowManager::class.java)
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    @Suppress("DEPRECATION") override fun open(): Boolean {
        if (active) return true
        if (service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return false
        val root = rootProvider()
        val snapshot = if (root == null) "" else try {
            if (root.packageName?.toString() == service.packageName) "" else ScreenTextCollector.collect(root,viewport=android.graphics.Rect(0,0,service.resources.displayMetrics.widthPixels,service.resources.displayMetrics.heightPixels))
        } finally { root.recycle() }
        if (snapshot.isBlank()) {
            Toast.makeText(service, R.string.screen_text_unavailable, Toast.LENGTH_LONG).show()
            return false
        }
        val dark = service.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val ink = if (dark) Color.rgb(245,240,235) else Color.rgb(35,30,26)
        val surface = if (dark) Color.rgb(35,32,30) else Color.rgb(255,250,245)
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(12))
            background = GradientDrawable().apply { setColor(surface); cornerRadius=dp(20).toFloat(); setStroke(dp(1),if(dark) Color.DKGRAY else Color.LTGRAY) }
        }
        layout.addView(TextView(service).apply {
            setText(R.string.screen_text_title); textSize=20f; setTextColor(ink); setTypeface(typeface,Typeface.BOLD)
        })
        layout.addView(TextView(service).apply {
            setText(R.string.screen_text_instructions); textSize=14f; setTextColor(ink); setPadding(0,dp(8),0,dp(12))
        })
        val scroll=ScrollView(service)
        val body=TextView(service).apply {
            textSize=18f; setTextColor(ink); setPadding(dp(4),dp(8),dp(4),dp(12))
            setText(snapshot,TextView.BufferType.SPANNABLE); setTextIsSelectable(true)
        }
        scroll.addView(body,FrameLayout.LayoutParams(-1,-2))
        layout.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val buttons=LinearLayout(service).apply { gravity=Gravity.END }
        fun button(label:Int, action:()->Unit) = Button(service).apply { setText(label); setOnClickListener{action()} }
        buttons.addView(button(android.R.string.selectAll) { setRange(LocalTextRange(0,body.text.length)) },LinearLayout.LayoutParams(0,-2,1f))
        buttons.addView(button(android.R.string.copy) { copySelection() },LinearLayout.LayoutParams(0,-2,1f))
        buttons.addView(button(android.R.string.cancel) { close() },LinearLayout.LayoutParams(0,-2,1f))
        layout.addView(buttons)
        return try {
            windows.addView(layout,WindowManager.LayoutParams(
                service.resources.displayMetrics.widthPixels-dp(24),
                (service.resources.displayMetrics.heightPixels*.75f).toInt(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT).apply { gravity=Gravity.CENTER })
            panel=layout; textView=body
            body.requestFocus(); setRange(LocalTextRange(0,0))
            true
        } catch (_: RuntimeException) {
            Toast.makeText(service,R.string.screen_text_unavailable,Toast.LENGTH_LONG).show(); false
        }
    }
    private fun range(): LocalTextRange {
        val text=textView?.text ?: return LocalTextRange(0,0)
        return LocalTextRange(Selection.getSelectionStart(text).coerceAtLeast(0).coerceAtMost(text.length),
            Selection.getSelectionEnd(text).coerceAtLeast(0).coerceAtMost(text.length))
    }
    private fun setRange(value:LocalTextRange) {
        val view=textView ?: return
        val text=view.text as? Spannable ?: return
        Selection.setSelection(text,value.anchor.coerceIn(0,text.length),value.caret.coerceIn(0,text.length))
        view.bringPointIntoView(value.caret.coerceIn(0,text.length))
    }
    private fun copySelection(): Boolean {
        val view=textView ?: return false
        val selected=range().selected(view.text.toString())
        if(selected.isEmpty()) return true
        service.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("",selected))
        close()
        return true
    }
    override fun dispatch(chord:ScreenNavKeyPolicy.Chord):Boolean {
        if(!active) return false
        val view=textView ?: return false
        val text=view.text.toString()
        val current=range()
        val extend=chord.meta and KeyEvent.META_SHIFT_ON != 0
        val word=chord.meta and KeyEvent.META_CTRL_ON != 0
        when(chord.keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> { close();return true }
            KeyEvent.KEYCODE_C -> return if(word) copySelection() else false
            KeyEvent.KEYCODE_A -> { if(!word) return false;setRange(LocalTextRange(0,text.length));return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                setRange(current.horizontal(text,chord.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT,extend,word));return true
            }
        }
        val lines=view.layout ?: return false
        val line=lines.getLineForOffset(current.caret)
        val position=when(chord.keyCode) {
            KeyEvent.KEYCODE_MOVE_HOME -> lines.getLineStart(line)
            KeyEvent.KEYCODE_MOVE_END -> lines.getLineEnd(line).coerceAtMost(text.length)
            KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_PAGE_UP,KeyEvent.KEYCODE_PAGE_DOWN -> {
                val delta=if(chord.keyCode==KeyEvent.KEYCODE_DPAD_UP || chord.keyCode==KeyEvent.KEYCODE_PAGE_UP) -1 else 1
                val page=if(chord.keyCode==KeyEvent.KEYCODE_PAGE_UP || chord.keyCode==KeyEvent.KEYCODE_PAGE_DOWN) (view.parent as? View)?.height?.div(view.lineHeight)?.coerceAtLeast(1) ?: 1 else 1
                val target=(line+delta*page).coerceIn(0,lines.lineCount-1)
                lines.getOffsetForHorizontal(target,lines.getPrimaryHorizontal(current.caret))
            }
            else -> return false
        }
        setRange(LocalTextRange(if(extend) current.anchor else position,position));return true
    }
    override fun close() {
        val old=panel ?: return
        panel=null
        textView?.text="";textView=null
        try { windows.removeView(old) } catch (_: IllegalArgumentException) { }
    }
}
