package com.asnidev.sysreadout.shade

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
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
    HOTSPOT("hotspot"),
    NFC("nfc"),
    SAVER("saver"),
    DARK("dark"),
}

/** One of Android's volume streams, as the shade's sliders show them. */
enum class Volume(val label: String, val stream: Int) {
    MEDIA("media", AudioManager.STREAM_MUSIC),
    RING("ring", AudioManager.STREAM_RING),
    ALARM("alarm", AudioManager.STREAM_ALARM),
}

data class BtDevice(val name: String, val address: String, val connected: Boolean, val battery: Int?)

data class WifiNet(val ssid: String, val level: Int, val current: Boolean, val secured: Boolean)

class Toggles(private val context: Context, private val shizukuOf: () -> ShizukuBridge?) {

    private val camera = context.getSystemService(CameraManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
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

    private val shizuku: ShizukuBridge? get() = shizukuOf()?.takeIf { it.ready }
    val direct: Boolean get() = shizuku != null

    /** Toggles this phone has: the torch only with a flash, NFC only with the chip. */
    fun available(): List<Toggle> = Toggle.entries.filter {
        when (it) {
            Toggle.TORCH -> torchId != null
            Toggle.NFC -> NfcAdapter.getDefaultAdapter(context) != null
            else -> true
        }
    }

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
            // Android gives apps no hotspot state; unknown unless Shizuku answers.
            Toggle.HOTSPOT -> null
            Toggle.NFC -> NfcAdapter.getDefaultAdapter(context)?.isEnabled
            Toggle.SAVER -> context.getSystemService(PowerManager::class.java).isPowerSaveMode
            Toggle.DARK -> context.getSystemService(UiModeManager::class.java).nightMode == UiModeManager.MODE_NIGHT_YES
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
        Toggle.NFC -> "svc nfc ${if (on) "enable" else "disable"}"
        Toggle.SAVER -> "settings put global low_power ${if (on) 1 else 0}"
        Toggle.DARK -> "cmd uimode night ${if (on) "yes" else "no"}"
        Toggle.HOTSPOT, Toggle.TORCH -> null
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
        val s = shizuku
        if (s != null && now != null) {
            val cmd = command(t, !now)
            if (cmd != null && s.exec(cmd) != null) return null
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
        Toggle.ROTATE, Toggle.DARK -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        Toggle.TORCH -> Intent(Settings.ACTION_SETTINGS)
        Toggle.HOTSPOT -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
        Toggle.NFC -> Intent(Settings.ACTION_NFC_SETTINGS)
        Toggle.SAVER -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
    }

    // --- brightness ---

    /** Screen brightness 0..1 as Android's slider shows it; null when unreadable. */
    fun brightness(): Float? = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    }.getOrNull()

    fun autoBrightness(): Boolean? = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrNull()

    /** Sets the brightness through Shizuku (apps can't write system settings otherwise); false without it. */
    suspend fun setBrightness(level: Float): Boolean {
        val s = shizuku ?: return false
        val v = (level.coerceIn(0.01f, 1f) * 255).toInt()
        return s.exec("settings put system screen_brightness_mode 0; settings put system screen_brightness $v") != null
    }

    suspend fun setAutoBrightness(on: Boolean): Boolean {
        val s = shizuku ?: return false
        return s.exec("settings put system screen_brightness_mode ${if (on) 1 else 0}") != null
    }

    // --- volume (apps may set these themselves; do-not-disturb can refuse the ring stream) ---

    fun volume(v: Volume): Float = runCatching {
        audio.getStreamVolume(v.stream).toFloat() / audio.getStreamMaxVolume(v.stream).coerceAtLeast(1)
    }.getOrDefault(0f)

    /** False when Android refused (ring volume while do-not-disturb is on). */
    fun setVolume(v: Volume, level: Float): Boolean = runCatching {
        val max = audio.getStreamMaxVolume(v.stream)
        audio.setStreamVolume(v.stream, (level.coerceIn(0f, 1f) * max).toInt(), 0)
        true
    }.getOrDefault(false)

    // --- bluetooth ---

    fun canListBluetooth(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Paired devices, connected ones first, with the battery level they report. */
    @SuppressLint("MissingPermission") // canListBluetooth() checked it
    fun bluetooth(): List<BtDevice> {
        if (!canListBluetooth()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty().map { dev ->
                BtDevice(
                    name = dev.name ?: dev.address,
                    address = dev.address,
                    connected = isConnected(dev),
                    battery = batteryOf(dev),
                )
            }.sortedWith(compareByDescending<BtDevice> { it.connected }.thenBy { it.name.lowercase() })
        }.getOrDefault(emptyList())
    }

    /** Hidden but long-standing BluetoothDevice methods; null when this phone hides them. */
    private fun isConnected(dev: BluetoothDevice): Boolean =
        runCatching { dev.javaClass.getMethod("isConnected").invoke(dev) as Boolean }.getOrDefault(false)

    private fun batteryOf(dev: BluetoothDevice): Int? =
        runCatching { (dev.javaClass.getMethod("getBatteryLevel").invoke(dev) as Int).takeIf { it in 0..100 } }.getOrNull()

    /** Connects or disconnects through Shizuku; otherwise opens Bluetooth settings. */
    suspend fun bluetoothTap(d: BtDevice): Intent? {
        val s = shizuku ?: return Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        val cmd = if (d.connected) "cmd bluetooth_manager disconnect ${d.address}" else "cmd bluetooth_manager connect ${d.address}"
        return if (s.exec(cmd)?.contains("Unknown command", ignoreCase = true) == false) null else Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    }

    // --- wi-fi ---

    fun canListWifi(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Networks from the last scan, strongest first, the connected one on top. */
    @SuppressLint("MissingPermission") // canListWifi() checked it
    fun wifi(): List<WifiNet> {
        if (!canListWifi()) return emptyList()
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        return runCatching {
            @Suppress("DEPRECATION")
            val current = wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            wm.scanResults.orEmpty()
                .filter { !it.SSID.isNullOrBlank() }
                .groupBy { it.SSID }
                .map { (ssid, list) ->
                    val best = list.maxBy { it.level }
                    WifiNet(
                        ssid = ssid,
                        level = WifiManager.calculateSignalLevel(best.level, 5),
                        current = ssid == current,
                        secured = best.capabilities.contains("WPA") || best.capabilities.contains("WEP") || best.capabilities.contains("SAE"),
                    )
                }
                .sortedWith(compareByDescending<WifiNet> { it.current }.thenByDescending { it.level })
                .take(MAX_WIFI)
        }.getOrDefault(emptyList())
    }

    /**
     * Android's Wi-Fi panel, where the network can be joined with its saved
     * password. The shell's connect command takes a password and would replace
     * the saved one, so it isn't used.
     */
    fun wifiTap(): Intent = panel(Toggle.WIFI)

    private companion object {
        const val MAX_WIFI = 8
    }
}
