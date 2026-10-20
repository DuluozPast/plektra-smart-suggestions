package rocks.pkb.plektra.inputmethod

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class ScreenTextSelectorTest {
    private fun node(value: String, vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val node=mock(AccessibilityNodeInfo::class.java)
        `when`(node.text).thenReturn(value)
        `when`(node.isVisibleToUser).thenReturn(true)
        `when`(node.childCount).thenReturn(children.size)
        children.forEachIndexed { index, child -> `when`(node.getChild(index)).thenReturn(child) }
        return node
    }
    @Test fun snapshotExcludesEditableAndPasswordSubtreesAndInvisibleText() {
        val secretChild=node("secret child")
        val password=node("password", secretChild); `when`(password.isPassword).thenReturn(true)
        val editable=node("draft"); `when`(editable.isEditable).thenReturn(true)
        val invisible=node("hidden"); `when`(invisible.isVisibleToUser).thenReturn(false)
        val root=node("",node("Visible paragraph"),password,editable,invisible,node("Visible paragraph"))
        clearInvocations(password,editable,invisible,secretChild)
        assertEquals("Visible paragraph",ScreenTextCollector.collect(root))
        verify(password,never()).text; verify(editable,never()).text
        verify(invisible,never()).text; verify(secretChild,never()).text
    }
    @Test fun snapshotBoundsCharactersAndNodesWithoutSplittingEmoji() {
        assertEquals("A",ScreenTextCollector.collect(node("A😀B"),maxChars=2))
        assertEquals("First",ScreenTextCollector.collect(node("",node("First"),node("Second")),maxNodes=2))
        assertEquals("",ScreenTextCollector.collect(node("Ignored"),maxChars=0))
    }
    @Test fun horizontalSelectionKeepsUnicodeAndAnchorAndCanCollapse() {
        val text="A😀 bonjour monde"
        val range=LocalTextRange(1,1).horizontal(text,true,true,false)
        assertEquals("😀",range.selected(text)); assertEquals(3,range.caret)
        assertEquals(LocalTextRange(3,3),range.horizontal(text,true,false,false))
        assertEquals("😀",LocalTextRange(3,1).selected(text))
        assertEquals(12,LocalTextRange(4,4).horizontal(text,true,false,true).caret)
    }
}
