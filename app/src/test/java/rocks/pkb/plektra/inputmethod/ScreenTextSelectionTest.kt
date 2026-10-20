package rocks.pkb.plektra.inputmethod

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class ScreenTextSelectionTest {
    @Test fun textOutsideEditorIsIncludedAndCanHaveNoNativeSelection() {
        val node=AccessibilityNodeInfo.obtain().apply { text="Une réponse reçue"; isVisibleToUser=true; setBoundsInScreen(Rect(0,0,300,60)) }
        ScreenTextSelection.read(node,Rect(0,0,400,800)).use { snapshot ->
            assertEquals(listOf("Une réponse reçue"),snapshot.blocks.map { it.text })
            assertFalse(snapshot.blocks.single().nativeSelection)
        }
    }
    @Test fun passwordEditableAndOffscreenContentAreExcluded() {
        for(mode in 0..2) {
            val node=AccessibilityNodeInfo.obtain().apply { text="private";isVisibleToUser=true;isPassword=mode==0;isEditable=mode==1;setBoundsInScreen(if(mode==2) Rect(0,900,300,960) else Rect(0,0,300,60)) }
            ScreenTextSelection.read(node,Rect(0,0,400,800)).use { assertTrue(it.blocks.isEmpty()) }
        }
    }
    @Test fun nativeActionIsOnlyAvailableWhenTheAppAdvertisesIt() {
        val node=AccessibilityNodeInfo.obtain().apply { text="public";isVisibleToUser=true;setBoundsInScreen(Rect(0,0,300,60));addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION) }
        ScreenTextSelection.read(node,Rect(0,0,400,800)).use { assertTrue(it.blocks.single().nativeSelection) }
    }
    @Test fun rangesRejectStaleOffsetsAndSplitSurrogatePairs() {
        val text="A😀B"
        assertTrue(ScreenTextSelection.validRange(text,1,3))
        assertFalse(ScreenTextSelection.validRange(text,1,2))
        assertFalse(ScreenTextSelection.validRange(text,2,3))
        assertFalse(ScreenTextSelection.validRange(text,-1,9))
    }
}
