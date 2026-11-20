package com.asnidev.sysreadout.system

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.asnidev.sysreadout.cover.CoverHost
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
        host = CoverHost(this).also { it.start() }
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
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
