package com.asnidev.sysreadout.cover

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import com.asnidev.sysreadout.data.CoverPrefs
import com.asnidev.sysreadout.data.LockScreenMode
import com.asnidev.sysreadout.data.Presets
import com.asnidev.sysreadout.data.SettingsStore
import com.asnidev.sysreadout.shade.ShadeContent
import com.asnidev.sysreadout.shade.Toggles
import com.asnidev.sysreadout.monitor.ShizukuBridge
import com.asnidev.sysreadout.system.CrashGuard
import com.asnidev.sysreadout.system.SystemActions
import com.asnidev.sysreadout.ui.rememberStyled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Draws the always-on display, lock cover, own lock and shade as accessibility
 * overlays from inside the lock service. Overlays sit above Android's lock screen
 * and status bar, turn the screen on reliably and show no launch splash. While
 * this runs it owns the screen events; [ScreenService] only steps in without it.
 */
class CoverHost(private val service: AccessibilityService) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = SettingsStore(service)
    private val pins = PinStore(service)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val power = service.getSystemService(PowerManager::class.java)
    private val sensors = service.getSystemService(SensorManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var togglesMade: Toggles? = null
    private val toggles: Toggles get() = togglesMade ?: Toggles(service) { ShizukuBridge.current }.also { togglesMade = it }

    @Volatile private var prefs = CoverPrefs()
    private var theme by mutableStateOf(Presets.default.theme)

    /** Locked by SysReadout's own lock: set at screen off, cleared by the right PIN. */
    @Volatile var ownLocked = false
        private set

    private var mode by mutableStateOf<Mode?>(null)
    private var wake: PowerManager.WakeLock? = null

    private enum class Mode { AOD, COVER, OWN }

    private val screen = OverlayWindow(service, "screen") {
        val styled = rememberStyled(if (CrashGuard.isSafeMode(service)) Presets.default.theme else theme, 0)
        when (mode) {
            Mode.AOD -> AodContent(prefs.aodLook, prefs.aodRows, onTap = ::leaveAod) {
                CoverState.aodSeenAt = SystemClock.elapsedRealtime()
            }
            Mode.COVER -> LockContent(prefs.lockPreset, onUnlock = ::coverUnlock, onAction = ::coverAction)
            Mode.OWN -> OwnLockContent(prefs.lockPreset, styled, pins, onUnlocked = ::ownUnlocked, onEmergency = ::emergency)
            null -> {}
        }
    }

    private val shade = OverlayWindow(service, "shade") {
        val styled = rememberStyled(theme, 0)
        ShadeContent(styled, toggles, close = ::closeShade, systemShade = {
            closeShade()
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        })
    }
    private val strip = OverlayWindow(service, "shade strip") {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                var drag = 0f
                detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { if (drag > 40f) openShade() }) { _, dy -> drag += dy }
            }.pointerInput(Unit) { detectTapGestures { } },
        )
    }

    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOff()
                Intent.ACTION_SCREEN_ON -> screenOn()
                Intent.ACTION_USER_PRESENT -> {
                    CoverState.suppressed = false
                    CoverState.lastUnlock = System.currentTimeMillis()
                    if (mode == Mode.COVER) hideScreen()
                }
            }
        }
    }

    private val proximity = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val sensor = e.sensor ?: return
            if (mode == Mode.AOD && e.values.firstOrNull()?.let { it < sensor.maximumRange } == true) {
                CoverState.suppressed = true
                CoverState.aodSeenAt = 0L
                afterAod()
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        active = this
        ContextCompat.registerReceiver(
            service, events,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            combine(store.cover, store.theme) { c, t -> c to t }.collect { (c, t) ->
                val was = prefs
                prefs = c
                theme = t
                syncStrip()
                // The own lock was just switched on: it starts with the next screen off, not mid-use.
                if (was.lockScreen == LockScreenMode.OWN && c.lockScreen != LockScreenMode.OWN && mode == Mode.OWN) ownUnlocked()
            }
        }
        // Restarted (crash, update) while the own lock was up: lock again rather than open the phone.
        if (pins.isSet && wasOwnLocked()) lockOwn()
    }

    fun stop() {
        if (active === this) active = null
        runCatching { service.unregisterReceiver(events) }
        sensors.unregisterListener(proximity)
        screen.destroy()
        shade.destroy()
        strip.destroy()
        releaseWake()
        scope.cancel()
        togglesMade?.close()
    }

    private fun phone(): PhoneNow {
        val battery = service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && s > 0) l * 100 / s else null
        }
        val audio = service.getSystemService(AudioManager::class.java).mode
        return PhoneNow(
            battery = level,
            charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            inCall = audio == AudioManager.MODE_IN_CALL || audio == AudioManager.MODE_IN_COMMUNICATION,
            locked = keyguard.isKeyguardLocked || ownLocked,
            // Accessibility overlays need no "display over other apps".
            canOverlay = true,
            safeMode = CrashGuard.isSafeMode(service),
        )
    }

    // --- screen events ---

    private fun screenOff() {
        closeShade()
        val t = SystemClock.elapsedRealtime()
        val wasAod = mode == Mode.AOD
        if (prefs.lockScreen == LockScreenMode.OWN && pins.isSet) lockOwn(show = false)
        if (wasAod) {
            // Switched off while the always-on display showed: dark until the next wake.
            CoverState.suppressed = true
            CoverState.aodSeenAt = 0L
            afterAod()
            return
        }
        if (mode == Mode.COVER) hideScreen()
        if (ScreenPolicy.aodOnScreenOff(prefs, phone(), CoverState.suppressed)) {
            CoverState.aodLaunchedAt = t
            showAod()
        } else if (ownLocked) {
            // Ready behind the dark screen, so nothing of the phone shows when it wakes.
            showScreen(Mode.OWN)
        }
    }

    private fun screenOn() {
        val t = SystemClock.elapsedRealtime()
        if (mode == Mode.AOD && t - CoverState.aodLaunchedAt < ScreenPolicy.AOD_WAKE_MS) return
        if (mode == Mode.AOD) {
            // The phone was woken some other way (power key) over the always-on display.
            afterAod()
            return
        }
        if (ownLocked) {
            showScreen(Mode.OWN)
            return
        }
        if (prefs.lockScreen == LockScreenMode.COVER && ScreenPolicy.coverOnScreenOn(prefs, phone(), false, t - CoverState.aodLaunchedAt)) {
            showScreen(Mode.COVER)
        }
    }

    // --- always-on display ---

    private fun showAod() {
        // A wake lock that turns the screen on; the overlay's own flag does it too where Android allows.
        releaseWake()
        @Suppress("DEPRECATION")
        wake = power.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "sysreadout:aod",
        ).apply { runCatching { acquire(AOD_WAKE_LOCK_MS) } }
        mode = Mode.AOD
        screen.show(OverlayWindow.fullScreen(focusable = false, turnScreenOn = true, keepOn = true, brightness = AOD_BRIGHTNESS))
        sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sensors.registerListener(proximity, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    /** Leaves the always-on display: to the own lock or cover if they're on, else to Android. */
    private fun afterAod() {
        sensors.unregisterListener(proximity)
        releaseWake()
        CoverState.aodSeenAt = 0L
        when {
            ownLocked -> showScreen(Mode.OWN)
            else -> hideScreen()
        }
    }

    private fun leaveAod() {
        sensors.unregisterListener(proximity)
        releaseWake()
        CoverState.aodSeenAt = 0L
        when {
            ownLocked -> showScreen(Mode.OWN)
            ScreenPolicy.coverAfterAod(prefs, keyguard.isKeyguardLocked) -> showScreen(Mode.COVER)
            else -> hideScreen()
        }
    }

    private fun releaseWake() {
        wake?.let { if (it.isHeld) runCatching { it.release() } }
        wake = null
    }

    // --- cover over Android's lock ---

    /** Uncovers Android's lock screen, which then takes the fingerprint, PIN or swipe as usual. */
    private fun coverUnlock() = hideScreen()

    private fun coverAction(action: String) {
        when (action) {
            LockPresets.ACTION_UNLOCK -> coverUnlock()
            LockPresets.ACTION_CAMERA -> if (SystemActions.open(service, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE))) hideScreen()
            LockPresets.ACTION_EMERGENCY -> emergency()
        }
    }

    // --- own lock ---

    private fun lockOwn(show: Boolean = true) {
        if (!pins.isSet) return
        ownLocked = true
        markOwnLocked(true)
        if (show) showScreen(Mode.OWN)
    }

    private fun ownUnlocked() {
        ownLocked = false
        markOwnLocked(false)
        CoverState.lastUnlock = System.currentTimeMillis()
        CoverState.suppressed = false
        hideScreen()
    }

    /** Hides the lock while the emergency dialer is up; it comes back on its own unless a call is running. */
    private fun emergency() {
        val dial = Intent(EMERGENCY_DIAL)
        if (!SystemActions.open(service, dial) && !SystemActions.open(service, Intent(Intent.ACTION_DIAL))) return
        hideScreen()
        main.postDelayed(object : Runnable {
            override fun run() {
                if (!ownLocked || mode != null) return
                if (phone().inCall) main.postDelayed(this, EMERGENCY_MS) else showScreen(Mode.OWN)
            }
        }, EMERGENCY_MS)
    }

    private fun markOwnLocked(locked: Boolean) {
        runCatching {
            val f = java.io.File(service.noBackupFilesDir, "lock_engaged")
            if (locked) f.writeText("1") else f.delete()
        }
    }

    private fun wasOwnLocked(): Boolean = java.io.File(service.noBackupFilesDir, "lock_engaged").exists()

    // --- windows ---

    private fun showScreen(m: Mode) {
        sensors.unregisterListener(proximity)
        releaseWake()
        mode = m
        screen.show(OverlayWindow.fullScreen(focusable = m != Mode.AOD))
        // A swipe on the status bar must not reach Android's shade while locked.
        syncStrip()
    }

    private fun hideScreen() {
        mode = null
        screen.hide()
        syncStrip()
    }

    private fun syncStrip() {
        val want = prefs.shade && mode == null && !ownLocked && !(keyguard.isKeyguardLocked)
        if (want && !strip.isShown) {
            strip.show(OverlayWindow.topStrip(statusBarHeight()))
        } else if (!want && strip.isShown) {
            strip.hide()
        }
    }

    fun openShade() {
        if (ownLocked || mode != null) return
        shade.show(OverlayWindow.fullScreen(focusable = true))
    }

    fun closeShade() {
        shade.hide()
    }

    private fun statusBarHeight(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (id > 0) service.resources.getDimensionPixelSize(id) else 0
        return h.takeIf { it > 0 } ?: (24 * service.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val AOD_BRIGHTNESS = 0.02f
        private const val AOD_WAKE_LOCK_MS = 10 * 60_000L
        private const val EMERGENCY_MS = 30_000L
        private const val EMERGENCY_DIAL = "com.android.phone.EmergencyDialer.DIAL"

        @Volatile var active: CoverHost? = null
            private set

        /** Can SysReadout's own lock and shade run: the lock service is on. */
        val available: Boolean get() = active != null

        fun canDrawOverlays(context: Context) = available || Settings.canDrawOverlays(context)
    }
}
