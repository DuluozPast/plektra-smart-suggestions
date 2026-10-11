package rocks.pkb.plektra.inputmethod.speech

import android.os.Handler
import android.os.Looper

/** Upper bound for Android's recognizer; its own endpoint detection may stop sooner. */
internal class SpeechRecordingDeadline(onLimit: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val stop = Runnable(onLimit)

    fun start(maxDurationMs: Long) {
        cancel()
        if (maxDurationMs > 0) handler.postDelayed(stop, maxDurationMs)
    }

    fun cancel() { handler.removeCallbacks(stop) }
}
