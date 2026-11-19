package com.asnidev.sysreadout.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.asnidev.sysreadout.LauncherViewModel
import com.asnidev.sysreadout.cover.AodContent
import com.asnidev.sysreadout.cover.LockContent
import com.asnidev.sysreadout.data.AodLook
import com.asnidev.sysreadout.data.AodMode
import com.asnidev.sysreadout.data.CoverPrefs
import com.asnidev.sysreadout.data.LockPreset
import com.asnidev.sysreadout.log.ProbeCatalog
import com.asnidev.sysreadout.monitor.ShizukuState
import com.asnidev.sysreadout.system.LockService
import com.asnidev.sysreadout.system.SystemActions
import com.asnidev.sysreadout.ui.Palette

fun AodMode.title() = when (this) {
    AodMode.OFF -> "off"
    AodMode.CHARGING -> "while charging"
    AodMode.ALWAYS -> "always"
}

fun AodLook.title() = when (this) {
    AodLook.CLOCK -> "minimal clock"
    AodLook.STATUS -> "terminal status"
    AodLook.PROMPT -> "prompt"
    AodLook.LOG -> "log tail"
}

fun LockPreset.title() = when (this) {
    LockPreset.BOOTLINE -> "bootline"
    LockPreset.BIOS -> "bios flash"
    LockPreset.DOS -> "dos prompt"
    LockPreset.LOGIN -> "unix login"
    LockPreset.GRUB -> "grub menu"
}

/** Always-on display and lock cover: what each does, the access they need, and the looks. */
@Composable
fun CoverPage(vm: LauncherViewModel) {
    val context = LocalContext.current
    val c by vm.cover.collectAsState()
    val shizuku by vm.shizuku.state.collectAsState()
    val safe by vm.safeMode.collectAsState()
    // Bumped on return from Android's settings, so the access rows re-check.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose {}
    }
    val overlay = remember(resumed) { Settings.canDrawOverlays(context) }
    val notify = remember(resumed) { canNotify(context) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumed++ }

    if (safe) Note("paused while sysreadout is in safe mode (settings › safe mode › resume).")

    Section("access")
    Note(
        "both need \"display over other apps\" to show over the lock screen when the screen turns on or off, " +
            "and a small notification while either is on. unlocking is always android's own pin, pattern or fingerprint.",
    )
    SettingRow("display over other apps", if (overlay) "on" else "grant ›") {
        if (!overlay) openOverlaySettings(context)
    }
    if (!overlay && shizuku == ShizukuState.READY) {
        Note("shizuku is connected: settings › shizuku › one-tap setup can switch it on for you.")
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        SettingRow("notification", if (notify) "on" else "allow ›") {
            if (!notify) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!notify) Note("without it android may stop the screen service; the notification itself stays silent.")
    }

    AodSection(vm, c, overlay)
    LockSection(vm, c, overlay)
}

@Composable
private fun AodSection(vm: LauncherViewModel, c: CoverPrefs, overlay: Boolean) {
    val context = LocalContext.current
    Section("always-on display")
    Note(
        "after you turn the screen off, sysreadout turns it back on, very dim, with the look below. " +
            "tap it to go on. turning the screen off again, or covering the sensor (pocket, face down), " +
            "keeps it dark until the next unlock. it's skipped during calls.",
    )
    Cycle("show", c.aodMode, AodMode.entries, show = { it.title() }) { v -> vm.updateCover { it.copy(aodMode = v) } }
    if (c.aodMode != AodMode.OFF && !overlay) Note("needs display over other apps (above) before it can show.")
    if (c.aodMode == AodMode.ALWAYS) {
        Stepper(
            "on battery, down to",
            "${c.aodMinBattery}%",
            onMinus = { vm.updateCover { it.copy(aodMinBattery = (it.aodMinBattery - 5).coerceAtLeast(CoverPrefs.MIN_BATTERY_LOW)) } },
            onPlus = { vm.updateCover { it.copy(aodMinBattery = (it.aodMinBattery + 5).coerceAtMost(CoverPrefs.MIN_BATTERY_HIGH)) } },
        )
        Note("a lit screen uses battery even when dim and mostly black. below this level it waits for the charger.")
    }
    Cycle("look", c.aodLook, AodLook.entries, show = { it.title() }) { v -> vm.updateCover { it.copy(aodLook = v) } }
    when (c.aodLook) {
        AodLook.CLOCK -> Note("clock, date, battery and how many notifications are waiting.")
        AodLook.STATUS -> Note("clock and the status rows you pick below.")
        AodLook.PROMPT -> Note("a shell prompt answering date, battery and notifications, with a blinking cursor.")
        AodLook.LOG -> Note("clock and the log's newest events, from the last time the home screen was open.")
    }
    if (c.aodLook == AodLook.STATUS) {
        Note("rows shown: tap one to remove it, or add one from the list.")
        c.aodRows.forEach { id -> SettingRow("  $id", "[x]") { vm.updateCover { it.copy(aodRows = it.aodRows - id) } } }
        ProbeCatalog.all.filter { it.id !in c.aodRows && it.id in AOD_ROWS }.forEach { probe ->
            SettingRow("  ${probe.id}", "[ ]") { vm.updateCover { it.copy(aodRows = it.aodRows + probe.id) } }
        }
    }
    Preview { AodContent(c.aodLook, c.aodRows, onTap = null) }
    Note("android's screen saver can show the same look while charging: settings › display › screen saver › sysreadout.")
    Link("open screen saver settings", "›") {
        SystemActions.open(context, Intent(Settings.ACTION_DREAM_SETTINGS))
    }
}

@Composable
private fun LockSection(vm: LauncherViewModel, c: CoverPrefs, overlay: Boolean) {
    var replay by remember { mutableIntStateOf(0) }
    Section("lock cover")
    Note(
        "each time the screen turns on while the phone is locked, a boot screen plays over android's lock screen. " +
            "once it's done, tap anywhere and android asks for your pin or fingerprint as usual. " +
            "unlocking with a fingerprint straight away skips the cover.",
    )
    Toggle("lock cover", c.lockCover) { v -> vm.updateCover { it.copy(lockCover = v) } }
    if (c.lockCover && !overlay) Note("needs display over other apps (above) before it can show.")
    Cycle("preset", c.lockPreset, LockPreset.entries, show = { it.title() }) { v -> vm.updateCover { it.copy(lockPreset = v) } }
    when (c.lockPreset) {
        LockPreset.BOOTLINE -> Note("kernel lines and [  OK  ] services from this phone's real kernel, memory and storage, then a login prompt.")
        LockPreset.BIOS -> Note("a power-on self test that counts up this phone's memory, then flashes its security patch level.")
        LockPreset.DOS -> Note("a C:\\> prompt types date, time, ver and mem.")
        LockPreset.LOGIN -> Note("a getty login prompt with your last unlock time and waiting notifications.")
        LockPreset.GRUB -> Note("a boot menu: android unlocks, camera opens the secure camera, emergency call opens the emergency dialer.")
    }
    if (!LockService.isRunning) {
        Note("pocket protection on the always-on display locks the screen through the double-tap lock service (settings › gestures).")
    }
    Link("replay preview", "›") { replay++ }
    key(replay, c.lockPreset) {
        Preview { LockContent(c.lockPreset, onUnlock = {}, onAction = {}) }
    }
}

/** A small framed box running the real screen, so the look can be judged without locking the phone. */
@Composable
private fun Preview(content: @Composable () -> Unit) {
    Box(
        Modifier.padding(vertical = 8.dp).fillMaxWidth().height(320.dp).border(1.dp, Palette.dim.copy(alpha = 0.5f)),
    ) { content() }
}

/** Rows that read cheaply without listeners or extra permissions, for the always-on display. */
private val AOD_ROWS = setOf("bat", "pwr", "net", "wifi", "ip", "mem", "swap", "therm", "fs", "alarm", "ntf", "up", "moon", "mode")

private fun canNotify(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun openOverlaySettings(context: Context) {
    val direct = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    if (!SystemActions.open(context, direct)) SystemActions.open(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
}
