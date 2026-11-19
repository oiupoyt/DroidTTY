package com.asnidev.sysreadout.cover

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.text.format.DateFormat
import com.asnidev.sysreadout.log.ProbeReader
import com.asnidev.sysreadout.monitor.NotifLog

/** Reads [CoverData] from the phone. Call off the main thread; each read is cheap but adds up. */
class CoverReader(private val context: Context) {

    private val probes by lazy { ProbeReader(context) }

    fun read(rows: List<String> = emptyList()): CoverData {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && s > 0) l * 100 / s else null
        }
        val mem = runCatching {
            ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        }.getOrNull()
        val fs = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        return CoverData(
            now = CoverData.at(System.currentTimeMillis()),
            is24h = DateFormat.is24HourFormat(context),
            battery = level,
            charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            android = Build.VERSION.RELEASE.orEmpty(),
            api = Build.VERSION.SDK_INT,
            patch = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() },
            kernel = System.getProperty("os.version")?.takeIf { it.isNotBlank() },
            soc = soc(),
            cores = Runtime.getRuntime().availableProcessors(),
            memTotalMb = mem?.totalMem?.div(MB),
            memAvailMb = mem?.availMem?.div(MB),
            storageTotalGb = fs?.totalBytes?.div(GB),
            storageFreeGb = fs?.availableBytes?.div(GB),
            net = net(),
            notifications = if (NotifLog.connected) NotifLog.active else null,
            nextAlarm = runCatching {
                context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime?.let { CoverData.at(it) }
            }.getOrNull(),
            lastUnlock = CoverState.lastUnlock?.let { CoverData.at(it) },
            events = CoverState.events,
            rows = if (rows.isEmpty()) emptyList() else runCatching { probes.sample(rows) }.getOrDefault(emptyList()),
        )
    }

    private fun soc(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val model = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            if (model != null) return listOf(Build.SOC_MANUFACTURER, model).filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ")
        }
        return Build.HARDWARE.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
    }

    private fun net(): String? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching "offline"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }.getOrNull()

    private companion object {
        const val MB = 1024L * 1024
        const val GB = 1024.0 * 1024 * 1024
    }
}
