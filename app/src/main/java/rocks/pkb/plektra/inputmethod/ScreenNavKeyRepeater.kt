package rocks.pkb.plektra.inputmethod

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/** Local cadence: no dependence on hardware repeat delivery through AccessibilityService. */
internal class ScreenNavKeyRepeater(private val dispatch: (KeyEvent) -> Boolean) {
    private val handler=Handler(Looper.getMainLooper())
    private var held: KeyEvent?=null
    private val tick=object:Runnable {
        override fun run() {
            val event=held ?: return
            // Independent taps: editors differ in their handling of repeated synthetic key downs.
            if(!dispatch(KeyEvent.changeTimeRepeat(event,SystemClock.uptimeMillis(),0))) { stop();return }
            if(held===event) handler.postDelayed(this,65L)
        }
    }
    fun start(event:KeyEvent) {
        stop();held=KeyEvent(event);handler.postDelayed(tick,400L)
    }
    fun release(event:KeyEvent) {
        if(held?.keyCode==event.keyCode && held?.deviceId==event.deviceId) stop()
    }
    fun stop() { held=null;handler.removeCallbacks(tick) }
}
