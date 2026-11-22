package space.oiupoyt.droidtty.system

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import space.oiupoyt.droidtty.cover.CoverHost
import java.lang.ref.WeakReference

/**
 * Locks the screen on a double-tap, and hosts the always-on display, lock
 * screens and shade as overlays ([CoverHost]). Listens to no events and reads
 * no window content.
 */
class LockService : AccessibilityService() {

    private var host: CoverHost? = null

    override fun onServiceConnected() {
        instance = WeakReference(this)
        host = CoverHost(this).also {
            it.shadeProbe = ::systemShadeOpen
            it.start()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        host?.stop()
        host = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        host?.stop()
        host = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                host?.windowChanged(event.packageName?.toString(), event.className?.toString())
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> host?.windowsChanged(systemShadeOpen())
        }
    }

    /**
     * Android's shade lives in one SystemUI window that stays put; when pulled down it
     * becomes a full-height system window on top. Only window types and sizes are read.
     */
    private fun systemShadeOpen(): Boolean = runCatching {
        val screen = resources.displayMetrics.heightPixels
        windows.any { w ->
            val bounds = Rect().also(w::getBoundsInScreen)
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM && w.layer >= 0 && bounds.top <= 0 && bounds.height() >= screen * 2 / 3
        }
    }.getOrDefault(false)

    override fun onKeyEvent(event: KeyEvent): Boolean = host?.keyEvent(event) ?: false
    override fun onInterrupt() {}

    companion object {
        private var instance: WeakReference<LockService>? = null

        val isRunning: Boolean get() = instance?.get() != null

        /** False when the service isn't enabled or the OS is older than Android 9. */
        fun lock(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            return instance?.get()?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
        }
    }
}
