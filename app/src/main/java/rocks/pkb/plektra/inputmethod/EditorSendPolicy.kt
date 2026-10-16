package rocks.pkb.plektra.inputmethod

import android.view.inputmethod.EditorInfo

/** One dispatch per request. A successful InputConnection return value is not proof
 * of delivery, so never cascade Send -> Ctrl+Enter -> Enter and risk duplicate sends. */
internal object EditorSendPolicy {
    fun actionId(info: EditorInfo?): Int =
        if (info != null && info.imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEND && info.actionId != 0)
            info.actionId else EditorInfo.IME_ACTION_SEND
}
