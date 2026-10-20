package rocks.pkb.plektra.inputmethod

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** On-demand reader for the future screen-selection UI. No events, logging, storage or clipboard writes. */
internal object ScreenTextSelection {
    const val MAX_NODES=1500
    const val MAX_BLOCKS=128
    const val MAX_CHARACTERS=65536
    data class Block(val text: String, val bounds: Rect, val nativeSelection: Boolean, internal val node: AccessibilityNodeInfo, internal val window: Int) {
        /** Refuse stale offsets if the app changed while the user chose text. */
        fun select(start: Int, end: Int): Boolean {
            if(!nativeSelection || !validRange(text,start,end) || !node.refresh() || node.windowId!=window || node.text?.toString()!=text || node.isPassword) return false
            val arguments=Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,end)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,arguments)
        }
    }
    class Snapshot(val blocks: List<Block>, val truncated: Boolean): AutoCloseable {
        @Suppress("DEPRECATION") override fun close() { blocks.forEach { it.node.recycle() } }
    }
    fun validRange(text: String,start: Int,end: Int): Boolean {
        fun boundary(i:Int)=i in 0..text.length && !(i>0 && i<text.length && text[i-1].isHighSurrogate() && text[i].isLowSurrogate())
        return start<end && boundary(start) && boundary(end)
    }
    /** Caller retains root ownership; returned snapshot owns only copied block nodes. */
    @Suppress("DEPRECATION")
    fun read(root: AccessibilityNodeInfo, viewport: Rect): Snapshot {
        val blocks=ArrayList<Block>(); var visited=0; var characters=0; var truncated=false
        val queue=java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        try {
            while(queue.isNotEmpty()) {
                val node=queue.removeFirst()
                try {
                    if(++visited>MAX_NODES) { truncated=true; break }
                    // A password subtree is excluded even when one child forgot its own flag.
                    if(node.isPassword) continue
                    if(node.childCount>MAX_NODES) truncated=true
                    for(i in 0 until minOf(node.childCount,MAX_NODES-visited-queue.size)) node.getChild(i)?.let { queue.add(it) }
                    if(!node.isVisibleToUser || node.isEditable) continue
                    val text=node.text?.toString()?.takeIf { it.isNotBlank() } ?: continue
                    val bounds=Rect();node.getBoundsInScreen(bounds)
                    if(!Rect.intersects(bounds,viewport)) continue
                    if(blocks.any { it.text==text && it.bounds==bounds }) continue
                    if(blocks.size>=MAX_BLOCKS || text.length>MAX_CHARACTERS-characters) { truncated=true; continue }
                    characters+=text.length
                    blocks.add(Block(text,bounds,node.actionList.any { it.id==AccessibilityNodeInfo.ACTION_SET_SELECTION },AccessibilityNodeInfo.obtain(node),node.windowId))
                } finally { node.recycle() }
            }
        } finally { while(queue.isNotEmpty()) queue.removeFirst().recycle() }
        return Snapshot(blocks.sortedWith(compareBy({it.bounds.top},{it.bounds.left})),truncated)
    }
}
