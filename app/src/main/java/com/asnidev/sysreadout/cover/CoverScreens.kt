package com.asnidev.sysreadout.cover

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.asnidev.sysreadout.data.AodLook
import com.asnidev.sysreadout.data.LockPreset
import com.asnidev.sysreadout.data.Presets
import com.asnidev.sysreadout.data.SettingsStore
import com.asnidev.sysreadout.system.CrashGuard
import com.asnidev.sysreadout.ui.Styled
import com.asnidev.sysreadout.ui.rememberStyled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The user's look, or the default one in safe mode (an imported font may be what crashed). */
@Composable
fun rememberCoverStyled(): Styled {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    val saved by store.theme.collectAsState(initial = Presets.default.theme)
    val theme = if (CrashGuard.isSafeMode(context)) Presets.default.theme else saved
    return rememberStyled(theme, 0)
}

/**
 * The always-on display: re-reads the phone every 30 s, redraws once a minute,
 * or once a second when the look has a blinking cursor. Animated CRT effects
 * (flicker, grain) are left out: they would redraw 20 times a second.
 */
@Composable
fun AodContent(look: AodLook, rows: List<String>, onTap: (() -> Unit)?, onShown: () -> Unit = {}) {
    val context = LocalContext.current
    val styled = rememberCoverStyled()
    val reader = remember { CoverReader(context.applicationContext) }
    var data by remember { mutableStateOf<CoverData?>(null) }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(look, rows) {
        while (true) {
            data = withContext(Dispatchers.IO) { reader.read(if (look == AodLook.STATUS) rows else emptyList()) }
            delay(DATA_MS)
        }
    }
    val blinks = look == AodLook.PROMPT
    LaunchedEffect(blinks) {
        while (true) {
            tick = System.currentTimeMillis()
            onShown()
            delay(if (blinks) 1_000L else 1_000L - tick % 1_000L)
        }
    }

    val d = data ?: return
    // The clock follows the tick, the rest follows the last read.
    val shown = d.copy(now = CoverData.at(tick))
    val tap by rememberUpdatedState(onTap)
    CoverView(
        AodFaces.frame(look, shown),
        styled,
        blinkOn = (tick / 1_000L) % 2L == 0L,
        modifier = Modifier.pointerInput(Unit) { detectTapGestures(onDoubleTap = { tap?.invoke() }, onTap = { tap?.invoke() }) },
        shift = burnInShift(tick / 60_000L),
        crt = styled.theme.crt.copy(flicker = 0f, grain = 0f),
    )
}

/** The lock cover: plays [preset]'s script once, then a tap anywhere (or on a GRUB entry) unlocks. */
@Composable
fun LockContent(preset: LockPreset, onUnlock: () -> Unit, onAction: (String) -> Unit) {
    val context = LocalContext.current
    val styled = rememberCoverStyled()
    val reader = remember { CoverReader(context.applicationContext) }
    var script by remember { mutableStateOf<LockScript?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(preset) {
        val d = withContext(Dispatchers.IO) { reader.read() }
        script = LockPresets.script(preset, d)
        val start = System.currentTimeMillis()
        while (true) {
            elapsed = System.currentTimeMillis() - start
            // Fast while the animation plays, then just the cursor blink.
            delay(if (elapsed < (script?.readyAt ?: 0L)) FRAME_MS else BLINK_MS)
        }
    }

    val s = script ?: return
    val ready = elapsed >= s.readyAt
    val unlock by rememberUpdatedState(onUnlock)
    CoverView(
        s.at(elapsed),
        styled,
        blinkOn = (elapsed / BLINK_MS) % 2L == 0L,
        modifier = Modifier.pointerInput(ready) {
            // Taps before the animation ends do nothing, so a tap in a pocket can't unlock.
            if (ready) detectTapGestures(onTap = { unlock() })
        },
        onAction = if (ready) onAction else null,
    )
}

private const val DATA_MS = 30_000L
private const val FRAME_MS = 40L
private const val BLINK_MS = 530L
