package com.asnidev.sysreadout.cover

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.asnidev.sysreadout.data.KeypadLayout
import com.asnidev.sysreadout.data.LockPreset
import com.asnidev.sysreadout.data.Placement
import com.asnidev.sysreadout.ui.Styled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import kotlin.math.roundToInt

/**
 * SysReadout's own lock: the preset plays, then asks for the PIN in its own
 * voice on a shuffled terminal keypad. Wrong PINs shake the screen, buzz, and
 * count toward an escalating wait that survives restarts.
 */
@Composable
fun OwnLockContent(
    preset: LockPreset,
    styled: Styled,
    store: PinStore,
    onUnlocked: () -> Unit,
    place: Placement? = null,
    keypad: KeypadLayout = KeypadLayout.FULL,
    keypadLift: Int = 0,
    shuffle: Boolean = true,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val reader = remember { CoverReader(context.applicationContext) }
    var data by remember { mutableStateOf<CoverData?>(null) }
    var script by remember { mutableStateOf<LockScript?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var typed by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // A fresh digit order for every lock, so smudges and a watcher learn less; or the phone layout.
    val order = remember(shuffle) { if (shuffle) Keypad.shuffled(SecureRandom()) else Keypad.STANDARD }
    val shake = remember { Animatable(0f) }

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
    val left = Lockout.FREE_TRIES - store.failures().first
    val waitText = when {
        wait > 0 -> "locked out · try again in ${(wait + 999) / 1000}s"
        checking -> "checking..."
        else -> null
    }

    // Checked only on the enter key, so the PIN's length isn't given away.
    if (checking) {
        LaunchedEffect(Unit) {
            val ok = withContext(Dispatchers.Default) { store.read()?.matches(typed) == true }
            typed = ""
            if (ok) {
                store.clearFailures()
                granted = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                delay(GRANTED_MS)
                onUnlocked()
            } else {
                store.recordFailure(System.currentTimeMillis())
                status = LockPresets.FAILED
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                for (x in listOf(18f, -14f, 10f, -6f, 0f)) shake.animateTo(x, tween(45))
            }
            checking = false
        }
    }

    val promptLines = when {
        granted -> listOf(CoverLine("access granted", mark = LockPresets.OK))
        else -> LockPresets.prompt(preset, d, typed.length, waitText ?: status) +
            listOfNotNull(
                if (status == LockPresets.FAILED && wait == 0L && left in 1 until Lockout.FREE_TRIES) {
                    CoverLine("$left ${if (left == 1) "try" else "tries"} before a wait", Role.DIM)
                } else null,
            )
    }
    val frame = s.at(elapsed).let { f -> if (!ready) f else f.copy(lines = f.lines + promptLines) }
    CoverView(
        frame,
        styled,
        blinkOn = (elapsed / BLINK_MS) % 2L == 0L,
        modifier = Modifier.offset { IntOffset(shake.value.roundToInt(), 0) },
        place = place,
        bottom = if (!ready || granted) null else {
            {
                Keypad(
                    styled,
                    order,
                    layout = keypad,
                    lift = keypadLift,
                    enabled = wait == 0L && !checking,
                    typed = typed.length,
                    onDigit = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (typed.length < PinHash.MAX_LENGTH) typed += it
                        status = null
                    },
                    onDelete = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        typed = typed.dropLast(1)
                    },
                    onEnter = { if (typed.isNotEmpty()) checking = true },
                )
            }
        },
    )
}

/** Digits in a terminal grid, in a shuffled order; `del` and `ret` in place of symbols. */
@Composable
private fun Keypad(
    styled: Styled,
    order: List<Char>,
    layout: KeypadLayout,
    lift: Int,
    enabled: Boolean,
    typed: Int,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onEnter: () -> Unit,
) {
    // One-handed layouts are narrower and sit on the chosen side.
    val width = if (layout == KeypadLayout.FULL) 1f else ONE_HAND_WIDTH
    val side = when (layout) {
        KeypadLayout.LEFT -> Alignment.Start
        KeypadLayout.RIGHT -> Alignment.End
        else -> Alignment.CenterHorizontally
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = lift.dp), horizontalAlignment = side) {
        Column(Modifier.fillMaxWidth(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // One cell per typed digit, so each press visibly lands.
            Text(
                if (typed == 0) "pin" else "pin " + "[*]".repeat(typed),
                style = styled.log,
                color = styled.date.color,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            order.take(9).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { c -> Key(c.toString(), styled, enabled, Modifier.weight(1f)) { onDigit(c) } }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Key("del", styled, enabled, Modifier.weight(1f), description = "delete", onClick = onDelete)
                Key(order[9].toString(), styled, enabled, Modifier.weight(1f)) { onDigit(order[9]) }
                Key("ret", styled, enabled && typed > 0, Modifier.weight(1f), description = "enter", onClick = onEnter)
            }
        }
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
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val ink = if (enabled) styled.log.color else styled.date.color
    Box(
        modifier
            .height(KEY_HEIGHT)
            // The pressed key fills in, the terminal way of showing a keypress.
            .background(if (pressed && enabled) ink else Color.Transparent)
            .border(1.dp, ink.copy(alpha = 0.6f))
            .clickable(source, null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text("[ $label ]", style = styled.log, color = if (pressed && enabled) styled.background else ink)
    }
}

object Keypad {
    /** 1 to 9 in rows, 0 under 8: a phone's own layout. */
    val STANDARD: List<Char> = "1234567890".toList()

    /** The ten digits in a random order. */
    fun shuffled(random: java.util.Random): List<Char> = ('0'..'9').toMutableList().apply { shuffle(random) }
}

private val KEY_HEIGHT = 56.dp
private const val ONE_HAND_WIDTH = 0.72f
private const val FRAME_MS = 40L
private const val BLINK_MS = 530L
private const val GRANTED_MS = 450L
