package com.asnidev.sysreadout.cover

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.asnidev.sysreadout.data.LockPreset
import com.asnidev.sysreadout.ui.Styled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * SysReadout's own lock: the preset plays, then asks for the PIN in its own
 * voice with a terminal keypad. Wrong PINs count toward an escalating wait that
 * survives restarts. Pure UI: [check] does the verifying.
 */
@Composable
fun OwnLockContent(
    preset: LockPreset,
    styled: Styled,
    store: PinStore,
    onUnlocked: () -> Unit,
    onEmergency: () -> Unit,
) {
    val context = LocalContext.current
    val reader = remember { CoverReader(context.applicationContext) }
    var data by remember { mutableStateOf<CoverData?>(null) }
    var script by remember { mutableStateOf<LockScript?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var typed by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(preset) {
        val d = withContext(Dispatchers.IO) { reader.read() }
        data = d
        script = LockPresets.script(preset, d, own = true)
        val start = System.currentTimeMillis()
        while (true) {
            now = System.currentTimeMillis()
            elapsed = now - start
            delay(if (elapsed < (script?.readyAt ?: 0L)) FRAME_MS else BLINK_MS)
        }
    }

    val d = data ?: return
    val s = script ?: return
    val ready = elapsed >= s.readyAt
    val wait = store.waitMs(now)
    val waitText = if (wait > 0) "try again in ${(wait + 999) / 1000}s" else null

    // The PIN is checked on the enter key only, so its length isn't given away.
    if (checking) {
        LaunchedEffect(Unit) {
            val ok = withContext(Dispatchers.Default) { store.read()?.matches(typed) == true }
            if (ok) {
                store.clearFailures()
                onUnlocked()
            } else {
                store.recordFailure(System.currentTimeMillis())
                status = LockPresets.FAILED
            }
            typed = ""
            checking = false
        }
    }

    val frame = s.at(elapsed).let { f ->
        if (!ready) f else f.copy(lines = f.lines + LockPresets.prompt(preset, d, typed.length, waitText ?: status))
    }
    CoverView(
        frame,
        styled,
        blinkOn = (elapsed / BLINK_MS) % 2L == 0L,
        onAction = { if (it == LockPresets.ACTION_EMERGENCY) onEmergency() },
        bottom = if (!ready) null else {
            {
                Keypad(
                    styled,
                    enabled = wait == 0L && !checking,
                    onDigit = { if (typed.length < PinHash.MAX_LENGTH) typed += it; status = null },
                    onDelete = { typed = typed.dropLast(1) },
                    onEnter = { if (typed.isNotEmpty()) checking = true },
                    onEmergency = onEmergency,
                )
            }
        },
    )
}

/** Digits in a terminal grid; `del` and `ret` in place of symbols, `sos` for the emergency dialer. */
@Composable
private fun Keypad(
    styled: Styled,
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onEnter: () -> Unit,
    onEmergency: () -> Unit,
) {
    val rows = listOf("123", "456", "789")
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { c -> Key(c.toString(), styled, enabled, Modifier.weight(1f)) { onDigit(c) } }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key("del", styled, enabled, Modifier.weight(1f), description = "delete", onClick = onDelete)
            Key("0", styled, enabled, Modifier.weight(1f)) { onDigit('0') }
            Key("ret", styled, enabled, Modifier.weight(1f), description = "enter", onClick = onEnter)
        }
        Key("sos", styled, true, Modifier.fillMaxWidth(), description = "emergency call", onClick = onEmergency)
    }
}

@Composable
private fun Key(
    label: String,
    styled: Styled,
    enabled: Boolean,
    modifier: Modifier,
    description: String = label,
    onClick: () -> Unit,
) {
    val ink = if (enabled) styled.log.color else styled.date.color
    Box(
        modifier
            .height(KEY_HEIGHT)
            .border(1.dp, ink.copy(alpha = 0.6f))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text("[ $label ]", style = styled.log, color = ink)
    }
}

private val KEY_HEIGHT = 52.dp
private const val FRAME_MS = 40L
private const val BLINK_MS = 530L
