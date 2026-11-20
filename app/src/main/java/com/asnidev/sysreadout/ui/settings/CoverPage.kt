package com.asnidev.sysreadout.ui.settings

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.asnidev.sysreadout.LauncherViewModel
import com.asnidev.sysreadout.cover.AodContent
import com.asnidev.sysreadout.cover.CoverHost
import com.asnidev.sysreadout.cover.LockContent
import com.asnidev.sysreadout.cover.PinHash
import com.asnidev.sysreadout.cover.PinStore
import com.asnidev.sysreadout.data.AodColor
import com.asnidev.sysreadout.data.AodLook
import com.asnidev.sysreadout.data.HAlign
import com.asnidev.sysreadout.data.KeypadLayout
import com.asnidev.sysreadout.data.Placement
import com.asnidev.sysreadout.data.VAlign
import com.asnidev.sysreadout.data.AodMode
import com.asnidev.sysreadout.data.CoverPrefs
import com.asnidev.sysreadout.data.LockPreset
import com.asnidev.sysreadout.data.LockScreenMode
import com.asnidev.sysreadout.log.ProbeCatalog
import com.asnidev.sysreadout.monitor.ShizukuState
import com.asnidev.sysreadout.system.SystemActions
import com.asnidev.sysreadout.ui.Palette
import com.asnidev.sysreadout.ui.Type

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
    AodLook.NEOFETCH -> "neofetch"
    AodLook.TTY -> "tty clock"
    AodLook.HTOP -> "htop meters"
    AodLook.MATRIX -> "matrix rain"
    AodLook.BINARY -> "binary clock"
    AodLook.FIRE -> "doom fire"
    AodLook.STARFIELD -> "starfield"
    AodLook.LIFE -> "game of life"
    AodLook.CUBE -> "spinning cube"
    AodLook.RAIN -> "rain on glass"
    AodLook.AQUARIUM -> "aquarium"
    AodLook.SCOPE -> "oscilloscope"
    AodLook.PONG -> "pong"
    AodLook.PLASMA -> "plasma"
    AodLook.HELIX -> "dna helix"
}

fun AodLook.detail(): String = when (this) {
    AodLook.CLOCK -> "clock, date, battery and how many notifications are waiting"
    AodLook.STATUS -> "clock and status rows you pick"
    AodLook.PROMPT -> "a shell prompt answering date, battery and notifications"
    AodLook.LOG -> "clock and the log's newest events"
    AodLook.NEOFETCH -> "the arch logo beside this phone's system facts"
    AodLook.TTY -> "the time in big block digits, seconds ticking"
    AodLook.HTOP -> "cpu (needs shizuku), memory and battery meters"
    AodLook.MATRIX -> "falling characters around the clock"
    AodLook.BINARY -> "binary columns, read bottom up: 1 2 4 8"
    AodLook.FIRE -> "flames licking up under the clock"
    AodLook.STARFIELD -> "stars streaming out from the centre"
    AodLook.LIFE -> "conway's cells evolving, a new seed every minute"
    AodLook.CUBE -> "a wireframe cube turning on two axes"
    AodLook.RAIN -> "drops sliding down into a rippling puddle"
    AodLook.AQUARIUM -> "fish, bubbles and swaying seaweed"
    AodLook.SCOPE -> "a scope trace; with shizuku it follows cpu load"
    AodLook.PONG -> "two paddles rallying; the score is the time"
    AodLook.PLASMA -> "demoscene plasma in ascii shading"
    AodLook.HELIX -> "a double helix twisting upward"
}

fun LockPreset.title() = when (this) {
    LockPreset.ARCH -> "arch linux"
    LockPreset.BOOTLINE -> "bootline"
    LockPreset.BIOS -> "bios flash"
    LockPreset.DOS -> "dos prompt"
    LockPreset.LOGIN -> "unix login"
    LockPreset.GRUB -> "grub menu"
    LockPreset.C64 -> "commodore 64"
    LockPreset.PANIC -> "kernel panic"
    LockPreset.SSH -> "ssh login"
    LockPreset.WAKEUP -> "wake up"
}

fun LockScreenMode.title() = when (this) {
    LockScreenMode.OFF -> "android's own"
    LockScreenMode.COVER -> "boot screen over android's"
    LockScreenMode.OWN -> "droidtty's own pin lock"
}

/** Always-on display, lock screen and shade: what each does, the access they need, and the looks. */
@Composable
fun CoverPage(vm: LauncherViewModel, open: (Page) -> Unit = {}) {
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
    val host = remember(resumed) { CoverHost.available }
    val overlay = remember(resumed) { Settings.canDrawOverlays(context) }
    val notify = remember(resumed) { canNotify(context) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumed++ }

    if (safe) Note("paused while droidtty is in safe mode (settings › safe mode › resume). the own lock stays on.")

    Section("access")
    Note(
        "the lock service (android's accessibility settings) lets droidtty draw these above the lock screen " +
            "and status bar and wake the screen. it reads no screen content. the own lock and the shade need it; " +
            "the always-on display and boot screen work without it through \"display over other apps\", less reliably.",
    )
    SettingRow("lock service", if (host) "on" else "turn on ›") { if (!host) SystemActions.openAccessibilitySettings(context) }
    if (!host) {
        SettingRow("display over other apps", if (overlay) "on" else "grant ›") { if (!overlay) openOverlaySettings(context) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SettingRow("notification", if (notify) "on" else "allow ›") {
                if (!notify) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    if (!host && shizuku == ShizukuState.READY) {
        Note("shizuku is connected: settings › shizuku › one-tap setup can switch these on for you.")
    }
    if (!host) {
        Note(
            "android 13+ greys the lock service out for apps installed from an apk: in droidtty's app info, " +
                "⋮ › allow restricted settings, then try again.",
        )
    }

    AodSection(vm, c, host || overlay)
    LockSection(vm, c, host)
    ShadeSection(vm, c, host, open)
}

@Composable
private fun AodSection(vm: LauncherViewModel, c: CoverPrefs, canShow: Boolean) {
    val context = LocalContext.current
    Section("always-on display")
    Note(
        "after you turn the screen off, droidtty turns it back on, very dim, with the look below. " +
            "tap it to go on. turning the screen off again, or covering the sensor (pocket, face down), " +
            "keeps it dark until the next unlock. it's skipped during calls. switch android's own always-on display off.",
    )
    Cycle("show", c.aodMode, AodMode.entries, show = { it.title() }) { v -> vm.updateCover { it.copy(aodMode = v) } }
    if (c.aodMode != AodMode.OFF && !canShow) Note("needs the lock service (above) before it can show.")
    if (c.aodMode == AodMode.ALWAYS) {
        Stepper(
            "on battery, down to",
            "${c.aodMinBattery}%",
            onMinus = { vm.updateCover { it.copy(aodMinBattery = (it.aodMinBattery - 5).coerceAtLeast(CoverPrefs.MIN_BATTERY_LOW)) } },
            onPlus = { vm.updateCover { it.copy(aodMinBattery = (it.aodMinBattery + 5).coerceAtMost(CoverPrefs.MIN_BATTERY_HIGH)) } },
        )
        Note("a lit screen uses battery even when dim and mostly black. below this level it waits for the charger.")
    }
    Choose(
        "look",
        c.aodLook,
        AodLook.entries,
        title = { it.title() },
        detail = { it.detail() },
        group = { if (it.animated) "animated (more battery)" else "still" },
    ) { v -> vm.updateCover { it.copy(aodLook = v) } }
    Note(c.aodLook.detail() + ".")
    if (c.aodLook.animated) {
        Note("animated looks redraw several times a second, so a lit screen costs noticeably more than a still one. pair them with \"while charging\" if battery matters.")
    }
    if (c.aodLook == AodLook.STATUS) {
        Note("rows shown: tap one to remove it, or add one from the list.")
        c.aodRows.forEach { id -> SettingRow("  $id", "[x]") { vm.updateCover { it.copy(aodRows = it.aodRows - id) } } }
        ProbeCatalog.all.filter { it.id !in c.aodRows && it.id in AOD_ROWS }.forEach { probe ->
            SettingRow("  ${probe.id}", "[ ]") { vm.updateCover { it.copy(aodRows = it.aodRows + probe.id) } }
        }
    }
    Cycle("size", c.aodScale, CoverPrefs.SCALES, show = { "${(it * 100).toInt()}%" }) { v -> vm.updateCover { it.copy(aodScale = v) } }
    Cycle("brightness", c.aodBrightness, CoverPrefs.BRIGHTNESS, show = { "$it%" }) { v -> vm.updateCover { it.copy(aodBrightness = v) } }
    Cycle("colour", c.aodColor, AodColor.entries, show = {
        when (it) {
            AodColor.THEME -> "theme"
            AodColor.ACCENT -> "accent only"
            AodColor.DIM -> "dim only"
            AodColor.MONO -> "white"
        }
    }) { v -> vm.updateCover { it.copy(aodColor = v) } }
    Toggle("burn-in shift", c.aodShift) { v -> vm.updateCover { it.copy(aodShift = v) } }
    Cycle("go dark after", c.aodTimeoutMin, CoverPrefs.TIMEOUTS, show = { if (it == 0) "never" else "$it min" }) { v ->
        vm.updateCover { it.copy(aodTimeoutMin = v) }
    }
    Note("brightness is a share of the screen's full range; most phones are readable indoors at 2%. the preview below doesn't dim.")
    PlacementRows(c.aodPlace, keepOwn = false) { v -> vm.updateCover { it.copy(aodPlace = v) } }
    Preview { AodContent(c, onTap = null) }
    Note("android's screen saver can show the same look while charging: settings › display › screen saver › droidtty.")
    Link("open screen saver settings", "›") {
        SystemActions.open(context, Intent(Settings.ACTION_DREAM_SETTINGS))
    }
}

@Composable
private fun LockSection(vm: LauncherViewModel, c: CoverPrefs, host: Boolean) {
    val context = LocalContext.current
    val pins = remember { PinStore(context.applicationContext) }
    var pinSet by remember { mutableStateOf(pins.isSet) }
    var settingPin by remember { mutableStateOf(false) }
    var confirmOwn by remember { mutableStateOf(false) }
    var replay by remember { mutableIntStateOf(0) }
    val androidSecure = remember { context.getSystemService(KeyguardManager::class.java).isDeviceSecure }

    Section("lock screen")
    SettingRow("lock screen", c.lockScreen.title()) {
        val next = LockScreenMode.entries[(c.lockScreen.ordinal + 1) % LockScreenMode.entries.size]
        if (next == LockScreenMode.OWN) confirmOwn = true else vm.updateCover { it.copy(lockScreen = next) }
    }
    when (c.lockScreen) {
        LockScreenMode.OFF -> Note("android's lock screen as it is.")
        LockScreenMode.COVER -> Note(
            "each time the screen turns on while locked, a boot screen plays over android's lock screen. once it's done, " +
                "tap anywhere and android asks for your pin or fingerprint as usual. a fingerprint straight away skips it.",
        )
        LockScreenMode.OWN -> {
            Note(
                "droidtty locks the phone itself: the preset plays, then asks for your pin on a terminal keypad. " +
                    "5 wrong pins, then a wait that doubles each time, up to 15 minutes. sos opens the emergency dialer.",
            )
            if (!host) Note("not locking: the lock service is off (access, above). turn it on, or nothing guards the phone.")
            if (androidSecure) Note("android's own screen lock is still on, so you'll unlock twice. set it to none to use only this one.")
            Link(if (pinSet) "change pin" else "set pin", "›") { settingPin = true }
            Note(
                "what this lock can't do: no fingerprint (android removes fingerprints when its own lock is none). your data " +
                    "isn't encrypted behind this pin. it can be skipped by booting android's safe mode (hold volume down while " +
                    "the phone starts, then turn the lock service off), from a computer with adb, or by turning the lock " +
                    "service off in settings while unlocked. forgot the pin: those same ways get you back in.",
            )
        }
    }
    if (c.lockScreen != LockScreenMode.OFF) {
        Choose(
            "preset",
            c.lockPreset,
            LockPreset.entries,
            title = { it.title() },
            detail = { presetNote(it, c.lockScreen == LockScreenMode.OWN) },
        ) { v -> vm.updateCover { it.copy(lockPreset = v) } }
        Note(presetNote(c.lockPreset, c.lockScreen == LockScreenMode.OWN))
        Toggle("double tap skips the animation", c.skipOnDoubleTap) { v -> vm.updateCover { it.copy(skipOnDoubleTap = v) } }
        Note(
            if (c.skipOnDoubleTap) "a double tap jumps to the end of the boot, ready to unlock. single taps during it still do nothing."
            else "the boot always plays in full before a tap does anything.",
        )
        PlacementRows(c.lockPlace, keepOwn = true) { v -> vm.updateCover { it.copy(lockPlace = v) } }
        if (c.lockScreen == LockScreenMode.OWN) {
            Cycle("keypad", c.keypad, KeypadLayout.entries, show = {
                when (it) {
                    KeypadLayout.FULL -> "full width"
                    KeypadLayout.LEFT -> "left hand"
                    KeypadLayout.CENTER -> "narrow, centred"
                    KeypadLayout.RIGHT -> "right hand"
                }
            }) { v -> vm.updateCover { it.copy(keypad = v) } }
            Toggle("shuffle digits", c.shuffleKeypad) { v -> vm.updateCover { it.copy(shuffleKeypad = v) } }
            Note(
                if (c.shuffleKeypad) "a new digit order every time: smudges and someone watching learn less, but you have to look for each key."
                else "the usual phone layout, 1 to 9 with 0 at the bottom: faster, and your thumb learns it.",
            )
            Stepper(
                "keypad height",
                if (c.keypadLift == 0) "bottom" else "+${c.keypadLift}dp",
                onMinus = { vm.updateCover { it.copy(keypadLift = (it.keypadLift - CoverPrefs.STEP_LIFT).coerceIn(CoverPrefs.RANGE_LIFT)) } },
                onPlus = { vm.updateCover { it.copy(keypadLift = (it.keypadLift + CoverPrefs.STEP_LIFT).coerceIn(CoverPrefs.RANGE_LIFT)) } },
            )
        }
        Link("replay preview", "›") { replay++ }
        key(replay, c.lockPreset) {
            Preview { LockContent(c.lockPreset, c.lockPlace, onUnlock = {}, onAction = {}) }
        }
    }

    if (confirmOwn) {
        AlertDialog(
            onDismissRequest = { confirmOwn = false },
            title = { Text("droidtty's own lock", style = Type.row) },
            text = {
                Column {
                    Note("it needs the lock service and a pin. it has no fingerprint, and it doesn't encrypt your data the way android's lock does.")
                    if (!host) Note("the lock service is off: turn it on first (access, above).")
                }
            },
            confirmButton = {
                TextButton(enabled = host, onClick = {
                    confirmOwn = false
                    if (pins.isSet) vm.updateCover { it.copy(lockScreen = LockScreenMode.OWN) } else settingPin = true
                }) { Text(if (pins.isSet) "switch on" else "set pin", style = Type.row) }
            },
            dismissButton = { TextButton(onClick = { confirmOwn = false }) { Text("cancel", style = Type.row) } },
        )
    }
    if (settingPin) {
        PinDialog(onDismiss = { settingPin = false }) { pin ->
            pins.set(pin)
            pinSet = true
            settingPin = false
            vm.updateCover { it.copy(lockScreen = LockScreenMode.OWN) }
            Toast.makeText(context, "pin set · the lock starts at the next screen off", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun ShadeSection(vm: LauncherViewModel, c: CoverPrefs, host: Boolean, open: (Page) -> Unit) {
    Section("notification shade")
    Note(
        "droidtty's own shade: battery, network and memory rows, quick toggles, what's playing and your notifications. " +
            "it opens from a swipe down on the status bar or on home. [system shade] inside opens android's.",
    )
    Toggle("droidtty shade", c.shade) { v -> vm.updateCover { it.copy(shade = v) } }
    Link("configure the shade", "parts · toggles · rows ›") { open(Page.Shade) }
    if (c.shade && !host) Note("the status-bar swipe needs the lock service (access, above); swiping down on home still opens it.")
    if (c.shade) {
        Note(
            "toggles switch directly with shizuku connected; without it, tapping one opens android's panel for it. " +
                "the torch always switches directly. notifications need notification access.",
        )
    }
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val error = when {
        first.isNotEmpty() && !PinHash.valid(first) -> "${PinHash.MIN_LENGTH} to ${PinHash.MAX_LENGTH} digits"
        second.isNotEmpty() && second != first -> "the two don't match"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("set pin", style = Type.row) },
        text = {
            Column {
                listOf("pin" to first, "again" to second).forEachIndexed { i, (label, value) ->
                    OutlinedTextField(
                        value,
                        onValueChange = { v -> if (v.all(Char::isDigit) && v.length <= PinHash.MAX_LENGTH) { if (i == 0) first = v else second = v } },
                        label = { Text(label, style = Type.small) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                error?.let { Note(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = error == null && PinHash.valid(first) && first == second, onClick = { onSet(first) }) {
                Text("set", style = Type.row)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel", style = Type.row) } },
    )
}

private fun presetNote(p: LockPreset, own: Boolean): String = when (p) {
    LockPreset.ARCH -> "grub's loading lines, the initramfs hooks, systemd's [  OK  ] units with this phone's kernel, storage, network and battery, then the arch login."
    LockPreset.BOOTLINE -> "kernel lines and [  OK  ] services from this phone's real kernel, memory and storage, then a login prompt."
    LockPreset.BIOS -> "a power-on self test that counts up this phone's memory, then flashes its security patch level."
    LockPreset.DOS -> "a C:\\> prompt types date, time, ver and mem."
    LockPreset.LOGIN -> "a getty login prompt with your last unlock time and waiting notifications."
    LockPreset.GRUB -> if (own) "a boot menu: android asks for the pin, emergency call opens the emergency dialer." else
        "a boot menu: android unlocks, camera opens the secure camera, emergency call opens the emergency dialer."
    LockPreset.C64 -> "the commodore 64 start screen with this phone's memory, then LOAD \"*\",8,1 and RUN."
    LockPreset.PANIC -> "a kernel panic with a call trace, a countdown, and the reboot."
    LockPreset.SSH -> "ssh into the phone: host key prompt, then a motd with memory, battery, uptime and storage."
    LockPreset.WAKEUP -> "wake up, <your phone's name>... the matrix has you. typed out line by line."
}

/**
 * Where the screen's text sits: vertical and horizontal position, a nudge in
 * each direction and the side margin. [keepOwn] offers "preset's own" vertically.
 */
@Composable
private fun PlacementRows(p: Placement, keepOwn: Boolean, set: (Placement) -> Unit) {
    val verticals = (if (keepOwn) listOf<VAlign?>(null) else emptyList()) + VAlign.entries
    Cycle("vertical", p.vertical, verticals, show = {
        when (it) {
            null -> "preset's own"
            VAlign.TOP -> "top"
            VAlign.CENTER -> "middle"
            VAlign.BOTTOM -> "bottom"
        }
    }) { v -> set(p.copy(vertical = v)) }
    Cycle("horizontal", p.horizontal, HAlign.entries, show = {
        when (it) {
            HAlign.START -> "left"
            HAlign.CENTER -> "centre"
            HAlign.END -> "right"
        }
    }) { v -> set(p.copy(horizontal = v)) }
    Stepper(
        "nudge up / down",
        signed(p.offsetY),
        onMinus = { set(p.copy(offsetY = (p.offsetY - Placement.STEP_Y).coerceIn(Placement.RANGE_Y))) },
        onPlus = { set(p.copy(offsetY = (p.offsetY + Placement.STEP_Y).coerceIn(Placement.RANGE_Y))) },
    )
    Stepper(
        "nudge left / right",
        signed(p.offsetX),
        onMinus = { set(p.copy(offsetX = (p.offsetX - Placement.STEP_X).coerceIn(Placement.RANGE_X))) },
        onPlus = { set(p.copy(offsetX = (p.offsetX + Placement.STEP_X).coerceIn(Placement.RANGE_X))) },
    )
    Stepper(
        "side margin",
        "${p.margin}dp",
        onMinus = { set(p.copy(margin = (p.margin - Placement.STEP_MARGIN).coerceIn(Placement.RANGE_MARGIN))) },
        onPlus = { set(p.copy(margin = (p.margin + Placement.STEP_MARGIN).coerceIn(Placement.RANGE_MARGIN))) },
    )
    if (p.offsetX != 0 || p.offsetY != 0) {
        Link("reset nudge", "›") { set(p.copy(offsetX = 0, offsetY = 0)) }
    }
}

/** "+16dp" / "-8dp" / "0". */
private fun signed(dp: Int) = when {
    dp > 0 -> "+${dp}dp"
    dp < 0 -> "${dp}dp"
    else -> "0"
}

/**
 * A framed box in the phone's proportions running the real screen. It starts on
 * a tap and stops when scrolled away, so the settings page scrolls smoothly and
 * an animated look doesn't run in the background of the page.
 */
@Composable
private fun Preview(content: @Composable () -> Unit) {
    val metrics = LocalContext.current.resources.displayMetrics
    val ratio = metrics.widthPixels.toFloat() / metrics.heightPixels.coerceAtLeast(1)
    var running by remember { mutableStateOf(false) }
    Box(
        Modifier.padding(vertical = 8.dp).fillMaxWidth().aspectRatio(ratio.coerceIn(0.3f, 1f))
            .border(1.dp, Palette.dim.copy(alpha = 0.5f))
            .clickable { running = !running },
        contentAlignment = Alignment.Center,
    ) {
        if (running) content() else Text("[ tap to preview ]", style = Type.row, color = Palette.log)
    }
    if (running) Note("tap the preview to stop it.")
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
