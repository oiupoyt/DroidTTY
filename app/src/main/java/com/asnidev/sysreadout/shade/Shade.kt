package com.asnidev.sysreadout.shade

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/**
 * SysReadout's notification shade: a readout header, quick toggles, what's
 * playing and the notifications, in the user's theme. [close] dismisses it;
 * [systemShade] hands over to Android's own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShadeContent(styled: Styled, toggles: Toggles, close: () -> Unit, systemShade: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by NotifListener.version.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val reader = remember { ProbeReader(context.applicationContext) }
    var header by remember { mutableStateOf(emptyList<String>()) }
    var playing by remember { mutableStateOf<MediaController?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            header = withContext(Dispatchers.IO) { runCatching { reader.sample(HEADER_ROWS) }.getOrDefault(emptyList()) }
            playing = withContext(Dispatchers.IO) { media(context) }
            tick++
            delay(2_000)
        }
    }
    val groups = remember(version, tick) { ShadeModel.group(NotifListener.active().mapNotNull(ShadeModel::item)) }

    fun send(pi: PendingIntent?, collapse: Boolean) {
        if (pi == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
                pi.send(context, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
        } catch (e: PendingIntent.CanceledException) {
            Log.w("Shade", "notification action was canceled", e)
        }
        if (collapse) close()
    }

    val dim = styled.date.color
    Column(
        Modifier
            .fillMaxSize()
            .background(styled.background)
            .systemBarsPadding()
            .pointerInput(Unit) {
                var drag = 0f
                detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { if (drag < -120f) close() }) { _, dy -> drag += dy }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("sysreadout --shade", style = styled.log, color = styled.accent)
        header.forEach { Text(it, style = styled.log, maxLines = 1, overflow = TextOverflow.Clip) }
        Spacer(Modifier.height(10.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            toggles.available().forEach { t ->
                val on = remember(tick, t) { toggles.isOn(t) }
                val label = "[${if (on == true) "x" else " "}] ${t.label}"
                Text(
                    label,
                    style = styled.log,
                    color = if (on == true) styled.background else styled.log.color,
                    modifier = Modifier
                        .then(if (on == true) Modifier.background(styled.log.color) else Modifier)
                        .border(1.dp, styled.log.color.copy(alpha = 0.5f))
                        .clickable {
                            scope.launch {
                                val panel = toggles.flip(t)
                                if (panel != null) {
                                    SystemActions.open(context, panel)
                                    close()
                                }
                                tick++
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                )
            }
        }
        toggles.brightness()?.let { level ->
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("bri ${(level * 100).toInt()}%", style = styled.log)
                listOf("-" to -0.1f, "+" to 0.1f).forEach { (sign, step) ->
                    Text(
                        "[$sign]",
                        style = styled.log,
                        modifier = Modifier.clickable {
                            scope.launch {
                                if (!toggles.setBrightness(level + step)) {
                                    SystemActions.open(context, Intent(Settings.ACTION_DISPLAY_SETTINGS))
                                    close()
                                }
                                tick++
                            }
                        }.padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                }
            }
        }

        playing?.let { mc -> MediaRow(mc, styled) { tick++ } }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("notifications", style = styled.log, color = styled.accent, modifier = Modifier.weight(1f))
            if (groups.any { g -> g.items.any { it.clearable } }) {
                Text("[clear all]", style = styled.log, modifier = Modifier.clickable { NotifListener.dismissAll() }.padding(4.dp))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when {
                !NotifLog.connected -> {
                    Text("no notification access: allow it so they show here", style = styled.log, color = dim)
                    Text(
                        "[grant]",
                        style = styled.log,
                        modifier = Modifier.clickable {
                            SystemActions.openNotificationAccess(context)
                            close()
                        }.padding(vertical = 8.dp),
                    )
                }
                groups.isEmpty() -> Text("no notifications", style = styled.log, color = dim)
                else -> groups.forEach { g ->
                    Text("## ${appName(context, g.pkg)}", style = styled.log, color = styled.accent, modifier = Modifier.padding(top = 8.dp))
                    g.items.forEach { n ->
                        Column(Modifier.fillMaxWidth().clickable { send(n.open, collapse = true) }.padding(vertical = 4.dp)) {
                            Row {
                                Text(stamp(n.time) + " ", style = styled.log, color = dim)
                                Text(n.title ?: "", style = styled.log, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (n.clearable) {
                                    Text("[x]", style = styled.log, modifier = Modifier.clickable { NotifListener.dismiss(n.key) }.padding(horizontal = 6.dp))
                                }
                            }
                            n.text?.let { Text(it, style = styled.log, color = dim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            if (n.actions.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    n.actions.forEach { (title, pi) ->
                                        Text(
                                            "[${title.lowercase(Locale.getDefault())}]",
                                            style = styled.log,
                                            color = styled.accent,
                                            modifier = Modifier.clickable { send(pi, collapse = false) }.padding(vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("[system shade]", style = styled.log, modifier = Modifier.clickable { systemShade() }.padding(8.dp))
            Text("[settings]", style = styled.log, modifier = Modifier.clickable {
                SystemActions.open(context, Intent(Settings.ACTION_SETTINGS))
                close()
            }.padding(8.dp))
            Text("[close]", style = styled.log, modifier = Modifier.clickable { close() }.padding(8.dp))
        }
    }
}

@Composable
private fun MediaRow(mc: MediaController, styled: Styled, changed: () -> Unit) {
    val meta = mc.metadata
    val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
    val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
    val isPlaying = mc.playbackState?.state == PlaybackState.STATE_PLAYING
    Column(Modifier.padding(top = 10.dp)) {
        Text("playing", style = styled.log, color = styled.accent)
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

private val HEADER_ROWS = listOf("bat", "net", "mem", "up")
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
