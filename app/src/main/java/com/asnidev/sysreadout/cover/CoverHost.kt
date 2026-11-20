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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
            Mode.AOD -> AodContent(prefs, onTap = ::leaveAod) {
                CoverState.aodSeenAt = SystemClock.elapsedRealtime()
            }
            Mode.COVER -> LockContent(prefs.lockPreset, prefs.lockPlace, onUnlock = ::coverUnlock, onAction = ::coverAction)
            Mode.OWN -> OwnLockContent(
                prefs.lockPreset, styled, pins, onUnlocked = ::ownUnlocked,
                place = prefs.lockPlace, keypad = prefs.keypad, keypadLift = prefs.keypadLift, shuffle = prefs.shuffleKeypad,
            )
            null -> {}
        }
    }

    private val shade = OverlayWindow(service, "shade") {
        val styled = rememberStyled(theme, 0)
        ShadeContent(styled, toggles, close = ::closeShade, systemShade = ::systemShadeOnPurpose)
    }
    private val strip = OverlayWindow(service, "shade strip") {
        // Any downward pull that starts in the strip opens the shade, as soon as it passes the threshold.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var opened = false
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!opened && change.position.y - down.position.y > PULL_PX) {
                            opened = true
                            openShade()
                        }
                        change.consume()
                    } while (change.pressed)
                }
            },
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
                    // The shade's status-bar strip only exists while unlocked: bring it back now.
                    syncStrip()
                }
            }
        }
    }

    /** The always-on display goes dark only once the sensor stays covered: a passing hand or a flicky sensor doesn't count. */
    private val pocket = Runnable {
        if (mode != Mode.AOD) return@Runnable
        CoverState.suppressed = true
        afterAod()
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    private val proximity = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val sensor = e.sensor ?: return
            val value = e.values.firstOrNull() ?: return
            if (mode != Mode.AOD) return
            if (ScreenPolicy.isNear(value, sensor.maximumRange)) {
                // Some sensors read "near" for a moment as the screen lights up.
                val sinceLit = SystemClock.elapsedRealtime() - CoverState.aodLaunchedAt
                val wait = ScreenPolicy.POCKET_MS + (ScreenPolicy.POCKET_GRACE_MS - sinceLit).coerceAtLeast(0L)
                main.removeCallbacks(pocket)
                main.postDelayed(pocket, wait)
            } else {
                main.removeCallbacks(pocket)
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
                runCatching {
                    val f = java.io.File(service.noBackupFilesDir, OWN_ON_FILE)
                    if (c.lockScreen == LockScreenMode.OWN) f.writeText("1") else f.delete()
                }
                syncStrip()
                // The own lock was just switched on: it starts with the next screen off, not mid-use.
                if (was.lockScreen == LockScreenMode.OWN && c.lockScreen != LockScreenMode.OWN && mode == Mode.OWN) ownUnlocked()
            }
        }
        // Restarted (crash, update, reboot) with the own lock on: start locked rather than open.
        if (pins.isSet && (wasOwnLocked() || ownLockWanted())) lockOwn()
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
        if (strip.isShown) strip.hide()
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
        // The always-on display turned the screen on itself (some phones send that late, or
        // twice): it stays. Only a later wake, like the power key, moves on to the lock.
        if (mode == Mode.AOD) {
            if (t - CoverState.aodLaunchedAt >= ScreenPolicy.AOD_WAKE_MS) afterAod()
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
        screen.show(
            OverlayWindow.fullScreen(focusable = false, turnScreenOn = true, keepOn = true, brightness = prefs.aodBrightness / 100f),
        )
        main.removeCallbacks(aodTimeout)
        if (prefs.aodTimeoutMin > 0) main.postDelayed(aodTimeout, prefs.aodTimeoutMin * 60_000L)
        sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sensors.registerListener(proximity, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    /** The timeout ran out: dark until the next unlock, as if the user had switched the screen off. */
    private val aodTimeout = Runnable {
        if (mode == Mode.AOD) {
            CoverState.suppressed = true
            afterAod()
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    /** Leaves the always-on display: to the own lock or cover if they're on, else to Android. */
    private fun afterAod() {
        main.removeCallbacks(aodTimeout)
        main.removeCallbacks(pocket)
        sensors.unregisterListener(proximity)
        releaseWake()
        CoverState.aodSeenAt = 0L
        when {
            ownLocked -> showScreen(Mode.OWN)
            else -> hideScreen()
        }
    }

    private fun leaveAod() {
        main.removeCallbacks(aodTimeout)
        main.removeCallbacks(pocket)
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

    /** Uncovers Android's lock and opens its PIN pad and fingerprint straight away. */
    private fun coverUnlock() {
        hideScreen()
        if (keyguard.isKeyguardLocked) UnlockActivity.start(service)
    }

    private fun coverAction(action: String) {
        when (action) {
            LockPresets.ACTION_UNLOCK -> coverUnlock()
            LockPresets.ACTION_CAMERA -> if (SystemActions.open(service, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE))) hideScreen()
            // Android's own lock screen has the emergency call button; uncovering it gets there.
            LockPresets.ACTION_EMERGENCY -> hideScreen()
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

    /**
     * Something opened while the own lock is up (Android's shade, recents, an app from
     * a notification, an incoming call screen): close what can be closed and keep the
     * lock window on top. Calls are let through so they can be answered.
     */
    fun windowChanged(pkg: String?, className: String? = null) {
        if (pkg == null || pkg == service.packageName) return
        if (!ownLocked) {
            takeOverShade(pkg, className)
            return
        }
        val call = pkg in CALL_PACKAGES || phone().inCall
        when (ScreenPolicy.guard(ownLocked, screen.isShown, pkg == SYSTEM_UI, call)) {
            ScreenPolicy.Guard.IGNORE -> return
            ScreenPolicy.Guard.COLLAPSE_SHADE -> collapseSystemShade()
            // An app came up behind the lock; going home puts SysReadout's own home screen there.
            ScreenPolicy.Guard.SEND_HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
        relock()
    }

    /** Puts the own lock back on top, unless the always-on display is up: it hands over itself when tapped. */
    private fun relock() {
        if (mode == Mode.AOD && screen.isShown) return
        if (mode != Mode.OWN || !screen.isShown) showScreen(Mode.OWN)
    }

    /**
     * Android's shade opened (its own swipe from the top beat the strip): with
     * SysReadout's shade on, close Android's and open ours in its place. Skipped
     * right after [systemShadeOnPurpose], and on the lock screen.
     */
    private fun takeOverShade(pkg: String, className: String?) {
        if (!prefs.shade || pkg != SYSTEM_UI || mode != null || keyguard.isKeyguardLocked) return
        if (SystemClock.elapsedRealtime() < systemShadeAllowedUntil) return
        // SystemUI also raises dialogs (volume, power menu, screenshots, media output); those stay.
        if (className != null && NOT_SHADE.any { className.contains(it, ignoreCase = true) }) return
        collapseSystemShade()
        // One pull sends several window events; the first one does the swap.
        if (shade.isShown || takeoverPending) return
        takeoverPending = true
        main.postDelayed({
            takeoverPending = false
            openShade()
        }, TAKEOVER_DELAY_MS)
    }

    private var takeoverPending = false

    /** From the window list: Android's shade is down (or just went up). */
    fun windowsChanged(systemShadeOpen: Boolean) {
        if (!systemShadeOpen) return
        if (ownLocked) {
            // Android's shade can't open over SysReadout's own screen; this only fires when it isn't up.
            if (ScreenPolicy.guard(ownLocked, screen.isShown, fromSystemUi = true, inCall = false) == ScreenPolicy.Guard.COLLAPSE_SHADE) {
                collapseSystemShade()
                relock()
            }
            return
        }
        takeOverShade(SYSTEM_UI, null)
    }

    private fun collapseSystemShade() {
        val collapse = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
        } else {
            AccessibilityService.GLOBAL_ACTION_BACK
        }
        service.performGlobalAction(collapse)
    }

    /** "[system shade]" in ours: let Android's open for a while without being replaced. */
    private var systemShadeAllowedUntil = 0L

    fun systemShadeOnPurpose() {
        systemShadeAllowedUntil = SystemClock.elapsedRealtime() + SYSTEM_SHADE_GRACE_MS
        closeShade()
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    }

    /** While the own lock is up, the volume keys are left alone and nothing else gets through. */
    fun keyEvent(e: KeyEvent): Boolean {
        if (!ownLocked || mode != Mode.OWN) return false
        return e.keyCode in BLOCKED_KEYS
    }

    private fun markOwnLocked(locked: Boolean) {
        runCatching {
            val f = java.io.File(service.noBackupFilesDir, "lock_engaged")
            if (locked) f.writeText("1") else f.delete()
        }
    }

    private fun wasOwnLocked(): Boolean = java.io.File(service.noBackupFilesDir, "lock_engaged").exists()

    /** Settings load asynchronously; this file says the own lock is on before they do. */
    private fun ownLockWanted(): Boolean = java.io.File(service.noBackupFilesDir, OWN_ON_FILE).exists()

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
            strip.show(OverlayWindow.topStrip(statusBarHeight() + (STRIP_EXTRA_DP * service.resources.displayMetrics.density).toInt()))
        } else if (!want && strip.isShown) {
            strip.hide()
        }
    }

    /** False when it can't open now (locked, or a lock or always-on screen is up). */
    fun openShade(): Boolean {
        if (ownLocked || mode != null || keyguard.isKeyguardLocked) return false
        if (shade.isShown) return true
        shade.show(OverlayWindow.fullScreen(focusable = true))
        return shade.isShown
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
        private const val AOD_WAKE_LOCK_MS = 10 * 60_000L

        private const val OWN_ON_FILE = "lock_own_on"
        private const val PULL_PX = 40f
        /** Lets Android's shade start collapsing before ours goes on top. */
        private const val TAKEOVER_DELAY_MS = 120L
        private const val SYSTEM_SHADE_GRACE_MS = 30_000L
        /** SystemUI windows that aren't its shade: dialogs, the volume panel, the power menu, screenshots. */
        private val NOT_SHADE = listOf(
            "Dialog", "Volume", "GlobalActions", "PowerMenu", "Screenshot", "Recents", "Toast",
            "MediaOutput", "Keyguard", "Bouncer", "UsbDebugging", "Permission", "Chooser", "Assist",
        )
        /** The strip reaches a little below the status bar, where a finger usually lands. */
        private const val STRIP_EXTRA_DP = 32
        private const val SYSTEM_UI = "com.android.systemui"
        private val CALL_PACKAGES = setOf("com.android.dialer", "com.google.android.dialer", "com.android.incallui", "com.android.server.telecom")
        private val BLOCKED_KEYS = setOf(
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_CAMERA,
        )

        @Volatile var active: CoverHost? = null
            private set

        /** Can SysReadout's own lock and shade run: the lock service is on. */
        val available: Boolean get() = active != null

        fun canDrawOverlays(context: Context) = available || Settings.canDrawOverlays(context)
    }
}
