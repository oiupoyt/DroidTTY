package com.asnidev.sysreadout.shade

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.asnidev.sysreadout.data.CoverPrefs
import com.asnidev.sysreadout.data.ShadeSection
import com.asnidev.sysreadout.log.ProbeReader
import com.asnidev.sysreadout.monitor.NotifListener
import com.asnidev.sysreadout.monitor.NotifLog
import com.asnidev.sysreadout.system.SystemActions
import com.asnidev.sysreadout.ui.Styled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which drop-down panel is open under the toggles. */
private enum class Panel { NONE, BLUETOOTH, WIFI }

/**
 * DroidTTY's notification shade, in ASCII boxes: a readout header, toggles with
 * Bluetooth and Wi-Fi panels, brightness and volume sliders, what's playing, and
 * notifications that expand, snooze, reply and run their actions. [close]
 * dismisses it; [systemShade] hands over to Android's own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShadeContent(
    styled: Styled,
    toggles: Toggles,
    close: () -> Unit,
    systemShade: () -> Unit,
    prefs: CoverPrefs = CoverPrefs(),
    /** Height of the status bar or camera cutout, whichever reaches lower. */
    topInset: Dp = 0.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by NotifListener.version.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val reader = remember { ProbeReader(context.applicationContext) }
    var header by remember { mutableStateOf(emptyList<String>()) }
    var playing by remember { mutableStateOf<MediaController?>(null) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var cols by remember { mutableIntStateOf(36) }

    // The readout refreshes every 2 s; the rest re-reads on a change or a tap.
    LaunchedEffect(Unit) {
        while (true) {
            header = withContext(Dispatchers.IO) { runCatching { reader.sample(prefs.shadeRows) }.getOrDefault(emptyList()) }
            playing = withContext(Dispatchers.IO) { media(context) }
            tick++
            delay(2_000)
        }
    }
    val groups = remember(version, tick) { ShadeModel.group(NotifListener.active().mapNotNull(ShadeModel::item)) }

    fun open(intent: Intent) {
        SystemActions.open(context, intent)
        if (prefs.shadeCloseOnOpen) close()
    }
    val shown = prefs.shadeSections.toSet()
    val tiles = remember(prefs.shadeToggles) {
        val have = toggles.available().toSet()
        prefs.shadeToggles.mapNotNull { n -> Toggle.entries.firstOrNull { it.name == n } }.filter { it in have }
    }

    val dim = styled.date.color
    val charW = remember(styled) { styled.log.fontSize.value * 0.6f }
    Column(
        Modifier
            .fillMaxSize()
            .background(styled.background)
            // Overlay windows often get no insets, so the space above is measured from the display:
            // below the camera hole and status bar, or the bare edge when switched off.
            .padding(top = if (prefs.shadeBelowCutout) topInset else 0.dp)
            .navigationBarsPadding()
            .pointerInput(Unit) {
                var drag = 0f
                detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { if (drag < -160f) close() }) { _, dy -> drag += dy }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .onSizeChanged { cols = (it.width / (charW * context.resources.displayMetrics.scaledDensity)).toInt().coerceIn(24, 60) },
    ) {
        LazyColumn(Modifier.weight(1f)) {
            // Each part in the order chosen in settings › screen › notification shade; left-out parts are hidden.
            prefs.shadeSections.forEach { section ->
                when (section) {
                    ShadeSection.READOUT -> if (prefs.shadeRows.isNotEmpty()) {
                        item(key = "head") {
                            Section(styled, "droidtty --shade", cols) {
                                header.forEach { Text(it, style = styled.log, maxLines = 1, overflow = TextOverflow.Clip) }
                            }
                        }
                    }
                    ShadeSection.TOGGLES -> {
                        item(key = "toggles") {
                            Section(styled, "toggles", cols) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    tiles.forEach { t ->
                                        val on = remember(tick, t) { toggles.isOn(t) }
                                        Tile(styled, "${if (on == true) "[x]" else if (on == null) "[?]" else "[ ]"} ${t.label}", on == true) {
                                            scope.launch {
                                                toggles.flip(t)?.let(::open)
                                                tick++
                                            }
                                        }
                                    }
                                }
                                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        (if (panel == Panel.BLUETOOTH) "v" else ">") + " bluetooth devices",
                                        style = styled.log,
                                        color = styled.accent,
                                        modifier = Modifier.clickable { panel = if (panel == Panel.BLUETOOTH) Panel.NONE else Panel.BLUETOOTH }.padding(vertical = 6.dp),
                                    )
                                    Text(
                                        (if (panel == Panel.WIFI) "v" else ">") + " wi-fi networks",
                                        style = styled.log,
                                        color = styled.accent,
                                        modifier = Modifier.clickable { panel = if (panel == Panel.WIFI) Panel.NONE else Panel.WIFI }.padding(vertical = 6.dp),
                                    )
                                }
                                if (panel == Panel.BLUETOOTH) BluetoothPanel(styled, toggles, tick, ::open) { scope.launch { toggles.bluetoothTap(it)?.let(::open); tick++ } }
                                if (panel == Panel.WIFI) WifiPanel(styled, toggles, tick, ::open)
                                if (!toggles.direct) Text("connect shizuku to switch directly; without it a tap opens android's panel.", style = styled.log, color = dim)
                            }
                        }
                    }
                    ShadeSection.LEVELS -> {
                        item(key = "sliders") {
                            Section(styled, "levels", cols) {
                                val bri = remember(tick) { toggles.brightness() }
                                val auto = remember(tick) { toggles.autoBrightness() }
                                if (bri != null) {
                                    Slider(styled, "bri", bri, cols) { level ->
                                        scope.launch { if (!toggles.setBrightness(level)) open(Intent(Settings.ACTION_DISPLAY_SETTINGS)) else tick++ }
                                    }
                                    Text(
                                        "${if (auto == true) "[x]" else "[ ]"} auto brightness",
                                        style = styled.log,
                                        modifier = Modifier.clickable {
                                            scope.launch { if (!toggles.setAutoBrightness(auto != true)) open(Intent(Settings.ACTION_DISPLAY_SETTINGS)) else tick++ }
                                        }.padding(vertical = 6.dp),
                                    )
                                }
                                Volume.entries.forEach { v ->
                                    val level = remember(tick, v) { toggles.volume(v) }
                                    Slider(styled, v.label.take(5), level, cols) { l ->
                                        if (!toggles.setVolume(v, l)) open(Intent(Settings.ACTION_SOUND_SETTINGS))
                                        tick++
                                    }
                                }
                            }
                        }
                    }
                    ShadeSection.MEDIA -> {
                        playing?.let { mc ->
                            item(key = "media") { Section(styled, "playing", cols) { MediaRow(mc, styled) { tick++ } } }
                        }
                    }
                    ShadeSection.NOTIFICATIONS -> {
                        item(key = "ntf-head") {
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Text(Box.top("notifications", cols), style = styled.log, color = styled.accent, maxLines = 1, modifier = Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth()) {
                                if (groups.any { g -> g.items.any { it.clearable } }) {
                                    Text("[clear all]", style = styled.log, modifier = Modifier.clickable { NotifListener.dismissAll() }.padding(6.dp))
                                }
                            }
                            when {
                                !NotifLog.connected -> Column {
                                    Text("| no notification access: allow it so they show here", style = styled.log, color = dim)
                                    Text(
                                        "| [grant]",
                                        style = styled.log,
                                        modifier = Modifier.clickable { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.padding(vertical = 8.dp),
                                    )
                                }
                                groups.isEmpty() -> Text("| no notifications", style = styled.log, color = dim)
                            }
                        }
                        groups.forEach { g ->
                            item(key = "g:${g.pkg}") {
                                Text("|## ${appName(context, g.pkg)}", style = styled.log, color = styled.accent, modifier = Modifier.padding(top = 6.dp))
                            }
                            items(g.items, key = { it.key }) { n ->
                                NotificationRow(styled, n, cols, context, changed = { tick++ }, opened = { if (prefs.shadeCloseOnOpen) close() })
                            }
                        }
                        item(key = "ntf-foot") { Text(Box.bottom(cols), style = styled.log, color = styled.accent, maxLines = 1) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("[system shade]", style = styled.log, modifier = Modifier.clickable { systemShade() }.padding(8.dp))
            Text("[settings]", style = styled.log, modifier = Modifier.clickable { open(Intent(Settings.ACTION_SETTINGS)) }.padding(8.dp))
            Text("[close]", style = styled.log, modifier = Modifier.clickable { close() }.padding(8.dp))
        }
    }
}

/** `+-- title ---+` over the content, `+------+` under it. */
@Composable
private fun Section(styled: Styled, title: String, cols: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(Box.top(title, cols), style = styled.log, color = styled.accent, maxLines = 1, overflow = TextOverflow.Clip)
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { content() }
        Text(Box.bottom(cols), style = styled.log, color = styled.accent, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun Tile(styled: Styled, label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = styled.log,
        color = if (on) styled.background else styled.log.color,
        modifier = Modifier
            .then(if (on) Modifier.background(styled.log.color) else Modifier)
            .border(1.dp, styled.log.color.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

/**
 * `bri [=======-----] 58%`: drag along it or tap a spot. The value follows the
 * finger at once and is set when the finger lifts, so the shell isn't run per pixel.
 */
@Composable
private fun Slider(styled: Styled, label: String, value: Float, cols: Int, set: (Float) -> Unit) {
    // One state for the life of the slider: the gesture keeps writing to it, and outside
    // changes (the level read back after setting it) are copied in.
    var shown by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { shown = value }
    var width by remember { mutableIntStateOf(1) }
    val cells = (cols - 14).coerceIn(8, 40)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label.padEnd(6), style = styled.log)
        // Only the bar takes touches, so its width maps straight to 0..100%.
        Text(
            Box.slider(shown, cells),
            style = styled.log,
            color = styled.accent,
            maxLines = 1,
            modifier = Modifier
                .onSizeChanged { width = it.width.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(onDragEnd = { set(shown) }) { change, _ ->
                        shown = (change.position.x / width).coerceIn(0f, 1f)
                        change.consume()
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        shown = (pos.x / width).coerceIn(0f, 1f)
                        set(shown)
                    }
                }
                .padding(vertical = 8.dp),
        )
        Text(" ${(shown * 100).toInt()}%", style = styled.log, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun BluetoothPanel(styled: Styled, toggles: Toggles, tick: Int, open: (Intent) -> Unit, tap: (BtDevice) -> Unit) {
    val devices = remember(tick) { toggles.bluetooth() }
    val dim = styled.date.color
    Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
        when {
            !toggles.canListBluetooth() -> Text("needs the nearby devices permission: settings › log › bt row asks for it.", style = styled.log, color = dim)
            devices.isEmpty() -> Text("no paired devices", style = styled.log, color = dim)
            else -> devices.forEach { d ->
                Text(
                    (if (d.connected) "* " else "  ") + d.name.take(22) + (d.battery?.let { "  $it%" } ?: "") +
                        if (d.connected) "  [disconnect]" else "  [connect]",
                    style = styled.log,
                    color = if (d.connected) styled.accent else styled.log.color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { tap(d) }.padding(vertical = 6.dp),
                )
            }
        }
        Text("[bluetooth settings]", style = styled.log, modifier = Modifier.clickable { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }.padding(vertical = 6.dp))
    }
}

@Composable
private fun WifiPanel(styled: Styled, toggles: Toggles, tick: Int, open: (Intent) -> Unit) {
    val nets = remember(tick) { toggles.wifi() }
    val dim = styled.date.color
    Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
        when {
            !toggles.canListWifi() -> Text("needs the location permission: settings › log › aps row asks for it.", style = styled.log, color = dim)
            nets.isEmpty() -> Text("no networks in the last scan", style = styled.log, color = dim)
            else -> nets.forEach { n ->
                Text(
                    (if (n.current) "* " else "  ") + n.ssid.take(20).padEnd(20) + " " + "|".repeat(n.level + 1).padEnd(5) +
                        if (n.secured) " #" else "",
                    style = styled.log,
                    color = if (n.current) styled.accent else styled.log.color,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().clickable { open(toggles.wifiTap()) }.padding(vertical = 6.dp),
                )
            }
        }
        Text("[wi-fi panel]", style = styled.log, modifier = Modifier.clickable { open(toggles.wifiTap()) }.padding(vertical = 6.dp))
    }
}

/** A notification: tap to expand; open, actions, snooze, reply and dismiss under it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationRow(styled: Styled, n: ShadeItem, cols: Int, context: Context, changed: () -> Unit, opened: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var replying by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    val dim = styled.date.color
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp)) {
        Row {
            Text("| ${stamp(n.time)} ", style = styled.log, color = dim)
            Text(n.title ?: "", style = styled.log, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (n.clearable) Text("[x]", style = styled.log, modifier = Modifier.clickable { NotifListener.dismiss(n.key) }.padding(horizontal = 6.dp))
        }
        val body = if (expanded) n.full else n.text
        body?.let {
            Text(
                it.lines().joinToString("\n") { l -> "|   $l" },
                style = styled.log,
                color = dim,
                maxLines = if (expanded) 30 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Act(styled, "[open]") {
                    send(context, n.open)
                    // As Android's shade does: an auto-cancel notification goes once it's opened.
                    if (n.autoCancel) NotifListener.dismiss(n.key)
                    opened()
                }
                n.actions.forEach { (title, pi) ->
                    Act(styled, "[${title.lowercase(Locale.getDefault())}]") {
                        send(context, pi)
                        changed()
                    }
                }
                n.reply?.let { Act(styled, "[${it.label.lowercase(Locale.getDefault())}]") { replying = !replying } }
                if (n.clearable) {
                    Act(styled, "[snooze 15m]") { NotifListener.snooze(n.key, 15 * 60_000L) }
                    Act(styled, "[snooze 1h]") { NotifListener.snooze(n.key, 60 * 60_000L) }
                }
            }
        }
        val r = n.reply
        if (expanded && replying && r != null) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("| > ", style = styled.log, color = styled.accent)
                BasicTextField(
                    reply,
                    onValueChange = { reply = it },
                    textStyle = styled.log,
                    cursorBrush = SolidColor(styled.log.color),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (reply.isNotBlank() && sendReply(context, r, reply)) {
                            reply = ""
                            replying = false
                            changed()
                        }
                    }),
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                )
                Act(styled, "[send]") {
                    if (reply.isNotBlank() && sendReply(context, r, reply)) {
                        reply = ""
                        replying = false
                        changed()
                    }
                }
            }
        }
    }
}

@Composable
private fun Act(styled: Styled, label: String, onClick: () -> Unit) =
    Text(label, style = styled.log, color = styled.accent, modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp))

@Composable
private fun MediaRow(mc: MediaController, styled: Styled, changed: () -> Unit) {
    val meta = mc.metadata
    val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
    val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
    val isPlaying = mc.playbackState?.state == PlaybackState.STATE_PLAYING
    Column {
        Text(title + (artist?.let { " · $it" } ?: ""), style = styled.log, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val t = mc.transportControls
            val controls: List<Pair<String, () -> Unit>> = listOf(
                "[|<]" to { t.skipToPrevious() },
                (if (isPlaying) "[||]" else "[>]") to { if (isPlaying) t.pause() else t.play() },
                "[>|]" to { t.skipToNext() },
            )
            controls.forEach { (label, act) ->
                Text(
                    label,
                    style = styled.log,
                    modifier = Modifier.clickable {
                        runCatching { act() }
                        changed()
                    }.padding(8.dp),
                )
            }
        }
    }
}

/** Runs a notification's intent the way Android's shade does, allowed to open an app from this visible window. */
private fun send(context: Context, pi: PendingIntent?, fill: Intent? = null) {
    if (pi == null) return
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val mode = if (Build.VERSION.SDK_INT >= 36) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            } else {
                @Suppress("DEPRECATION")
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            val opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode)
            pi.send(context, 0, fill, null, null, null, opts.toBundle())
        } else {
            pi.send(context, 0, fill)
        }
    } catch (e: PendingIntent.CanceledException) {
        Log.w("Shade", "notification action was canceled", e)
    }
}

/** Fills the app's reply field and fires its reply action, the way Android's inline reply does. */
private fun sendReply(context: Context, r: Reply, text: String): Boolean = runCatching {
    val fill = Intent()
    RemoteInput.addResultsToIntent(arrayOf(r.input), fill, Bundle().apply { putCharSequence(r.input.resultKey, text) })
    send(context, r.intent, fill)
    true
}.getOrDefault(false)

private val STAMP = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

private fun stamp(t: Long) = STAMP.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))

private fun media(context: Context): MediaController? = runCatching {
    if (!NotifLog.connected) return@runCatching null
    context.getSystemService(MediaSessionManager::class.java)
        .getActiveSessions(ComponentName(context, NotifListener::class.java))
        .firstOrNull { it.metadata != null }
}.getOrNull()

private fun appName(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)
