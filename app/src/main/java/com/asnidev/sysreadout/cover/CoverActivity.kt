package com.asnidev.sysreadout.cover

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.window.SplashScreen
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.asnidev.sysreadout.data.CoverPrefs
import com.asnidev.sysreadout.data.SettingsStore
import com.asnidev.sysreadout.system.LockService
import com.asnidev.sysreadout.system.SystemActions

/**
 * Shown over Android's lock screen: the always-on display (screen dimmed, kept
 * on) or the lock cover (a boot-style animation, then a tap hands over to
 * Android's own unlock). The phone's PIN, pattern or fingerprint is never skipped:
 * unlocking goes through [KeyguardManager.requestDismissKeyguard].
 */
class CoverActivity : ComponentActivity() {

    private var mode by mutableStateOf(MODE_LOCK)
    private var aodBrightness = CoverPrefs().aodBrightness / 100f
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val sensors by lazy { getSystemService(SensorManager::class.java) }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Screen off over the always-on display: the user wants it dark until the next wake.
            if (intent.action == Intent.ACTION_SCREEN_OFF && mode == MODE_AOD) {
                CoverState.suppressed = true
                CoverState.aodSeenAt = 0L
            }
            // Either way this cover is done; the next wake starts a fresh one.
            finish()
        }
    }

    private val proximity = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val sensor = e.sensor ?: return
            // In a pocket or face down: dark until the next wake.
            if (mode == MODE_AOD && e.values.firstOrNull()?.let { it < sensor.maximumRange } == true) {
                CoverState.suppressed = true
                CoverState.aodSeenAt = 0L
                LockService.lock()
                finish()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LOCK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        applyMode()
        ContextCompat.registerReceiver(
            this, screen,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Back doesn't skip the cover; only the tap that unlocks does.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        val store = SettingsStore(applicationContext)
        setContent {
            val prefs by store.cover.collectAsState(initial = null)
            val p = prefs ?: return@setContent
            LaunchedEffect(p.aodBrightness) {
                aodBrightness = p.aodBrightness / 100f
                applyMode()
            }
            if (mode == MODE_AOD) {
                AodContent(p, onTap = { leaveAod(p) }) {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        CoverState.aodSeenAt = SystemClock.elapsedRealtime()
                    }
                }
            } else {
                LockContent(p.lockPreset, onUnlock = ::unlock, onAction = ::grubAction)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_MODE)?.let {
            mode = it
            applyMode()
        }
    }

    override fun onResume() {
        super.onResume()
        // Unlocked meanwhile (fingerprint on the way in): nothing left to cover.
        if (mode == MODE_LOCK && !keyguard.isKeyguardLocked) finish()
        if (mode == MODE_AOD) {
            sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
                sensors.registerListener(proximity, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    override fun onPause() {
        sensors.unregisterListener(proximity)
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screen) }
        if (mode == MODE_AOD) CoverState.aodSeenAt = 0L
        super.onDestroy()
    }

    private fun applyMode() {
        val aod = mode == MODE_AOD
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(aod)
        } else {
            @Suppress("DEPRECATION")
            if (aod) window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.attributes = window.attributes.apply {
            screenBrightness = if (aod) aodBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (aod) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            sensors.unregisterListener(proximity)
        }
    }

    private fun leaveAod(p: CoverPrefs) {
        CoverState.aodSeenAt = 0L
        if (ScreenPolicy.coverAfterAod(p, keyguard.isKeyguardLocked)) {
            mode = MODE_LOCK
            applyMode()
        } else {
            unlock()
        }
    }

    private fun unlock() {
        if (!keyguard.isKeyguardLocked) {
            finish()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = finish()
        })
    }

    private fun grubAction(action: String) {
        when (action) {
            LockPresets.ACTION_UNLOCK -> unlock()
            LockPresets.ACTION_CAMERA -> {
                if (SystemActions.open(this, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE))) finish()
            }
            // The emergency dialer's own action; phones without it fall back to the dialer.
            LockPresets.ACTION_EMERGENCY -> {
                if (SystemActions.open(this, Intent(EMERGENCY_DIAL)) || SystemActions.open(this, Intent(Intent.ACTION_DIAL))) finish()
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_AOD = "aod"
        const val MODE_LOCK = "lock"
        private const val EMERGENCY_DIAL = "com.android.phone.EmergencyDialer.DIAL"


        /** From the screen service: needs "display over other apps" to start from the background. */
        fun start(context: Context, mode: String) {
            val intent = Intent(context, CoverActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // No splash screen (Android 13+ draws the app icon first otherwise).
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityOptions.makeBasic().apply { splashScreenStyle = SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR }.toBundle()
            } else {
                null
            }
            runCatching { context.startActivity(intent, options) }.onFailure { Log.w("CoverActivity", "couldn't show the $mode cover", it) }
        }
    }
}
