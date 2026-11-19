package com.asnidev.sysreadout.cover

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.asnidev.sysreadout.MainActivity
import com.asnidev.sysreadout.data.CoverPrefs
import com.asnidev.sysreadout.data.SettingsStore
import com.asnidev.sysreadout.system.CrashGuard
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches the screen turning off and on, which only a running process can
 * hear, and raises the always-on display or the lock cover. Runs only while
 * one of them is switched on, as a foreground service with its notification.
 */
class ScreenService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> Log.w(TAG, "screen service task failed", e) },
    )
    @Volatile private var prefs = CoverPrefs()

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOff()
                Intent.ACTION_SCREEN_ON -> screenOn()
                Intent.ACTION_USER_PRESENT -> {
                    CoverState.suppressed = false
                    CoverState.lastUnlock = System.currentTimeMillis()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!startInForeground()) {
            stopSelf()
            return
        }
        ContextCompat.registerReceiver(
            this, screen,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            SettingsStore(this@ScreenService).cover.collect {
                prefs = it
                if (!it.needsService) stopSelf()
            }
        }
    }

    // Every startForegroundService call must be answered with startForeground, not just the first.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startInForeground()) stopSelf()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(screen) }
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(): Boolean = try {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "always-on display and lock cover", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while SysReadout watches the screen turning off and on."
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("sysreadout screen")
            .setContentText("always-on display or lock cover is on")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        true
    } catch (e: Exception) { // ForegroundServiceStartNotAllowedException after a restart from the background
        Log.w(TAG, "couldn't start in the foreground", e)
        false
    }

    private fun now(): PhoneNow {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && s > 0) l * 100 / s else null
        }
        val mode = getSystemService(AudioManager::class.java).mode
        return PhoneNow(
            battery = level,
            charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION,
            locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            canOverlay = Settings.canDrawOverlays(this),
            safeMode = CrashGuard.isSafeMode(this),
        )
    }

    private fun screenOff() {
        val t = SystemClock.elapsedRealtime()
        // Switched off while the always-on display showed: the user wants the screen dark.
        if (CoverState.aodShowing(t)) {
            CoverState.suppressed = true
            CoverState.aodSeenAt = 0L
            return
        }
        if (ScreenPolicy.aodOnScreenOff(prefs, now(), CoverState.suppressed)) {
            CoverState.aodLaunchedAt = t
            CoverActivity.start(this, CoverActivity.MODE_AOD)
        }
    }

    private fun screenOn() {
        val t = SystemClock.elapsedRealtime()
        if (ScreenPolicy.coverOnScreenOn(prefs, now(), CoverState.aodShowing(t), t - CoverState.aodLaunchedAt)) {
            CoverActivity.start(this, CoverActivity.MODE_LOCK)
        }
    }

    companion object {
        private const val TAG = "ScreenService"
        private const val CHANNEL = "screen"
        private const val NOTIFICATION_ID = 41

        /** Starts or stops the service to match [prefs]; call while SysReadout is in the foreground. */
        fun sync(context: Context, prefs: CoverPrefs, safeMode: Boolean) {
            val intent = Intent(context, ScreenService::class.java)
            if (prefs.needsService && !safeMode) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
                    .onFailure { Log.w(TAG, "couldn't start the screen service", it) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
