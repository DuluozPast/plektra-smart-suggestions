package rocks.pkb.plektra.inputmethod
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[33])
class EditorSendPolicyTest {
    @Test fun explicitSendNeverTurnsIntoDoneNextOrSearch() {
        for (action in listOf(EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_NONE)) {
            val editor = EditorInfo().apply { imeOptions = action or EditorInfo.IME_FLAG_NO_ENTER_ACTION; actionId = 77 }
            assertEquals(EditorInfo.IME_ACTION_SEND, EditorSendPolicy.actionId(editor))
        }
    }
    @Test fun advertisedCustomSendActionIsRetained() {
        val editor = EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_SEND; actionId = 77 }
        assertEquals(77, EditorSendPolicy.actionId(editor))
    }
    @Test fun missingEditorUsesSend() { assertEquals(EditorInfo.IME_ACTION_SEND, EditorSendPolicy.actionId(null)) }
}
