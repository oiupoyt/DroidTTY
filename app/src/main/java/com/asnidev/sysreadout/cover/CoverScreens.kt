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
import androidx.compose.ui.unit.dp
import com.asnidev.sysreadout.data.AodColor
import com.asnidev.sysreadout.data.AodLook
import com.asnidev.sysreadout.data.Placement
import com.asnidev.sysreadout.data.CoverPrefs
import com.asnidev.sysreadout.data.TextSpec
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
fun AodContent(p: CoverPrefs, onTap: (() -> Unit)?, onShown: () -> Unit = {}) {
    val look = p.aodLook
    val rows = p.aodRows
    val context = LocalContext.current
    val styled = aodStyled(rememberCoverStyled(), p.aodScale, p.aodColor)
    val reader = remember { CoverReader(context.applicationContext) }
    var data by remember { mutableStateOf<CoverData?>(null) }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(look, rows) {
        while (true) {
            data = withContext(Dispatchers.IO) { reader.read(if (look == AodLook.STATUS) rows else emptyList()) }
            delay(DATA_MS)
        }
    }
    val every = AodFaces.refreshMs(look)
    val blinks = look == AodLook.PROMPT
    LaunchedEffect(every) {
        while (true) {
            tick = System.currentTimeMillis()
            onShown()
            delay(if (every < 1_000L) every else 1_000L - tick % 1_000L)
        }
    }

    val d = data ?: return
    // The clock follows the tick, rounded to the look's own refresh so nothing redraws in between.
    val shown = d.copy(now = CoverData.at(tick - tick % every.coerceAtMost(60_000L)))
    val tap by rememberUpdatedState(onTap)
    CoverView(
        AodFaces.frame(look, shown, tick).copy(centered = true),
        styled,
        // Only the prompt blinks; a constant here spares the other looks a redraw every second.
        blinkOn = !blinks || (tick / 1_000L) % 2L == 0L,
        modifier = Modifier.pointerInput(Unit) { detectTapGestures(onDoubleTap = { tap?.invoke() }, onTap = { tap?.invoke() }) },
        shift = if (p.aodShift) burnInShift(tick / 60_000L) else 0.dp to 0.dp,
        crt = styled.theme.crt.copy(flicker = 0f, grain = 0f),
        place = p.aodPlace,
    )
}

/** The theme resized and, unless THEME, recoloured to one colour for the always-on display. */
@Composable
private fun aodStyled(base: Styled, scale: Float, color: AodColor): Styled {
    val context = LocalContext.current
    return remember(base, scale, color) {
        fun TextSpec.adjust(): TextSpec {
            val c = when (color) {
                AodColor.THEME -> this.color
                AodColor.ACCENT -> base.theme.accent
                AodColor.DIM -> base.theme.date.color
                AodColor.MONO -> 0xFFFFFFFF
            }
            return copy(size = size * scale, color = c)
        }
        val t = base.theme
        Styled(
            t.copy(clock = t.clock.adjust(), date = t.date.adjust(), log = t.log.adjust(), banner = t.banner.adjust(),
                accent = if (color == AodColor.THEME) t.accent else t.log.adjust().color),
            context.applicationContext,
        )
    }
}

/** The lock cover: plays [preset]'s script once, then a tap anywhere (or on a GRUB entry) unlocks. */
@Composable
fun LockContent(
    preset: LockPreset,
    place: Placement?,
    onUnlock: () -> Unit,
    onAction: (String) -> Unit,
    skipOnDoubleTap: Boolean = true,
) {
    val context = LocalContext.current
    val styled = rememberCoverStyled()
    val reader = remember { CoverReader(context.applicationContext) }
    var script by remember { mutableStateOf<LockScript?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }

    // Moved forward by a double tap that skips the animation.
    var start by remember { mutableLongStateOf(0L) }
    LaunchedEffect(preset) {
        val d = withContext(Dispatchers.IO) { reader.read() }
        script = LockPresets.script(preset, d)
        start = System.currentTimeMillis()
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
        modifier = Modifier.pointerInput(ready, skipOnDoubleTap) {
            // Single taps before the animation ends do nothing, so a tap in a pocket can't unlock;
            // a deliberate double tap can jump to the end instead.
            if (ready) {
                detectTapGestures(onTap = { unlock() })
            } else if (skipOnDoubleTap) {
                detectTapGestures(onDoubleTap = {
                    start -= (s.readyAt - elapsed).coerceAtLeast(0L)
                    elapsed = s.readyAt
                })
            }
        },
        onAction = if (ready) onAction else null,
        place = place,
    )
}

private const val DATA_MS = 30_000L
private const val FRAME_MS = 40L
private const val BLINK_MS = 530L
