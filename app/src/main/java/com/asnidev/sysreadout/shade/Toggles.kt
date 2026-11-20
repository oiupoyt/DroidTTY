package com.asnidev.sysreadout.shade

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import com.asnidev.sysreadout.monitor.ShizukuBridge

/**
 * The shade's quick toggles. Each one either switches for real (the torch
 * always; the rest through Shizuku's shell) or opens the matching Android
 * panel, so no tile is decoration.
 */
enum class Toggle(val label: String) {
    WIFI("wifi"),
    BLUETOOTH("bt"),
    DATA("data"),
    AIRPLANE("plane"),
    LOCATION("gps"),
    DND("dnd"),
    TORCH("torch"),
    ROTATE("rotate"),
}

class Toggles(private val context: Context, private val shizukuOf: () -> ShizukuBridge?) {

    private val camera = context.getSystemService(CameraManager::class.java)
    @Volatile private var torchOn = false
    private val torchId: String? = runCatching {
        camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) torchOn = enabled
        }
    }

    init {
        runCatching { camera.registerTorchCallback(torchCallback, null) }
    }

    fun close() {
        runCatching { camera.unregisterTorchCallback(torchCallback) }
    }

    /** Toggles this phone has; the torch only with a flash. */
    fun available(): List<Toggle> = Toggle.entries.filter { it != Toggle.TORCH || torchId != null }

    /** Null when the state can't be read. */
    fun isOn(t: Toggle): Boolean? = runCatching {
        when (t) {
            Toggle.WIFI -> context.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled
            Toggle.BLUETOOTH -> context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
            Toggle.DATA -> dataEnabled()
            Toggle.AIRPLANE -> Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            Toggle.LOCATION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) context.getSystemService(LocationManager::class.java).isLocationEnabled else null
            Toggle.DND -> context.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
            Toggle.TORCH -> torchOn
            Toggle.ROTATE -> Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        }
    }.getOrNull()

    /** Reading it needs the phone permission (the link row asks for it); unknown without. */
    private fun dataEnabled(): Boolean? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        return if (granted) context.getSystemService(TelephonyManager::class.java).isDataEnabled else null
    }

    /** Shell commands that flip [t] to [on]; null where Android has none. */
    fun command(t: Toggle, on: Boolean): String? = when (t) {
        Toggle.WIFI -> "svc wifi ${if (on) "enable" else "disable"}"
        Toggle.BLUETOOTH -> "svc bluetooth ${if (on) "enable" else "disable"}"
        Toggle.DATA -> "svc data ${if (on) "enable" else "disable"}"
        Toggle.AIRPLANE -> "cmd connectivity airplane-mode ${if (on) "enable" else "disable"}"
        Toggle.LOCATION -> "cmd location set-location-enabled $on"
        Toggle.DND -> "cmd notification set_dnd ${if (on) "priority" else "off"}"
        Toggle.ROTATE -> "settings put system accelerometer_rotation ${if (on) 1 else 0}"
        Toggle.TORCH -> null
    }

    /**
     * Flips [t]. Returns an intent to open when it can't be flipped directly
     * (no Shizuku, or the shell refused); null when it was flipped.
     */
    suspend fun flip(t: Toggle): Intent? {
        val now = isOn(t)
        if (t == Toggle.TORCH) {
            val id = torchId ?: return null
            runCatching { camera.setTorchMode(id, !(now ?: torchOn)) }
            return null
        }
        val shizuku = shizukuOf()
        if (shizuku != null && shizuku.ready && now != null) {
            val cmd = command(t, !now)
            if (cmd != null && shizuku.exec(cmd) != null) return null
        }
        return panel(t)
    }

    /** Android's own place for [t]: the quick panel where one exists, the settings screen otherwise. */
    fun panel(t: Toggle): Intent = when (t) {
        Toggle.WIFI -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
        Toggle.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        Toggle.DATA -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
        Toggle.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        Toggle.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        Toggle.DND -> Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
        Toggle.ROTATE -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        Toggle.TORCH -> Intent(Settings.ACTION_SETTINGS)
    }

    /** Screen brightness 0..1 as Android's slider shows it; null when unreadable. */
    fun brightness(): Float? = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    }.getOrNull()

    /** Sets the brightness through Shizuku (apps can't write system settings otherwise); false without it. */
    suspend fun setBrightness(level: Float): Boolean {
        val shizuku = shizukuOf()?.takeIf { it.ready } ?: return false
        val v = (level.coerceIn(0.01f, 1f) * 255).toInt()
        return shizuku.exec("settings put system screen_brightness_mode 0; settings put system screen_brightness $v") != null
    }
}
