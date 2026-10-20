package rocks.pkb.plektra.inputmethod

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import rocks.pkb.plektra.SettingsManager

/** Optional key filter outside editors. Ordinary key filtering reads focus metadata only; the explicit selector action reads visible text. */
class ScreenNavAccessibilityService: AccessibilityService() {
    private val owned=mutableSetOf<Pair<Int,Int>>()
    private val repeater=ScreenNavKeyRepeater { event ->
        eligible() && ScreenNavKeyBridge.locked && ScreenNavKeyBridge.dispatch(event)
    }
    private var pendingTap=false
    private var lastPackage: String?=null
    private val textSelector by lazy { LocalScreenTextSelector(this) { rootInActiveWindow } }
    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenTextSelectorBridge.register(textSelector)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if(event.action==KeyEvent.ACTION_UP) repeater.release(event)
        val key=event.deviceId to event.keyCode
        val fn=event.keyCode==KeyEvent.KEYCODE_CTRL_LEFT || event.keyCode==KeyEvent.KEYCODE_CTRL_RIGHT
        if(event.action==KeyEvent.ACTION_UP && owned.remove(key)) {
            if(fn) ScreenNavKeyBridge.dispatch(event)
            return true
        }
        if(event.action!=KeyEvent.ACTION_DOWN) return false
        if(owned.contains(key)) return true
        if(!fn && !ScreenNavKeyBridge.locked) {
            if(pendingTap) { ScreenNavKeyBridge.breakTap();pendingTap=false }
            return false
        }
        repeater.stop()
        if(!eligible()) return false
        val handled=ScreenNavKeyBridge.dispatch(event)
        if(handled) {
            owned.add(key)
            if(fn) pendingTap=true
            else if(ScreenNavKeyBridge.canRepeat(event)) repeater.start(event)
        }
        return handled
    }
    private fun eligible(): Boolean {
        if(!SettingsManager.getNavModeEnabled(this)) return false
        val currentIme=Settings.Secure.getString(contentResolver,Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        return currentIme==packageName && (ScreenTextSelectorBridge.active || outsideEditor())
    }
    @Suppress("DEPRECATION") private fun outsideEditor(): Boolean {
        if((getSystemService(KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked==true) return false
        val root=rootInActiveWindow ?: return false
        try {
            if(root.packageName?.toString()==packageName) return false
            val focus=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            try { return focus?.isEditable!=true && focus?.isPassword!=true }
            finally { if(focus!==root) focus?.recycle() }
        } finally { root.recycle() }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if(event?.eventType!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val next=event.packageName?.toString() ?: return
        // IME/status windows belong to our process, not to a newly opened app.
        if(next==packageName) return
        if(lastPackage!=null && lastPackage!=next) {
            textSelector.close()
            repeater.stop();pendingTap=false
            if(ScreenNavKeyBridge.locked || owned.isNotEmpty()) ScreenNavKeyBridge.reset()
        }
        lastPackage=next
    }
    override fun onInterrupt() { ScreenTextSelectorBridge.close(); repeater.stop();ScreenNavKeyBridge.reset();owned.clear();pendingTap=false }
    override fun onDestroy() { ScreenTextSelectorBridge.unregister(textSelector);onInterrupt();super.onDestroy() }
}
