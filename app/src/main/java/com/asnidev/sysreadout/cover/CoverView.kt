package com.asnidev.sysreadout.cover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.asnidev.sysreadout.data.Crt
import com.asnidev.sysreadout.data.StyleElement
import com.asnidev.sysreadout.ui.Styled
import com.asnidev.sysreadout.ui.crt

/**
 * Draws a [CoverFrame] with the user's theme: the clock style for BIG lines,
 * the log style for the rest, the accent for marks. [shift] moves everything a
 * few dp each minute so an always-on display doesn't burn in one spot.
 */
@Composable
fun CoverView(
    frame: CoverFrame,
    styled: Styled,
    blinkOn: Boolean,
    modifier: Modifier = Modifier,
    shift: Pair<Dp, Dp> = 0.dp to 0.dp,
    crt: Crt? = styled.theme.crt,
    onAction: ((String) -> Unit)? = null,
    /** Drawn under the lines, e.g. the own lock's keypad. */
    bottom: (@Composable () -> Unit)? = null,
    /** Where a centered frame sits; the always-on display lets the user choose. */
    position: Arrangement.Vertical = Arrangement.Center,
) {
    val quiet = remember { MutableInteractionSource() }
    Box(
        modifier
            .fillMaxSize()
            .background(styled.background)
            .then(if (crt != null) Modifier.crt(crt) else Modifier),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .offset(shift.first, shift.second),
        ) {
            // A boot log keeps its newest line at the bottom edge and lets older ones scroll off the top.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .then(if (frame.tail) Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true) else Modifier),
                verticalArrangement = when {
                    frame.centered -> position
                    frame.tail -> Arrangement.Bottom
                    else -> Arrangement.Top
                },
            ) {
                frame.lines.forEach { line -> CoverText(line, styled, blinkOn, quiet, onAction) }
            }
            bottom?.invoke()
        }
    }
}

@Composable
private fun ColumnScope.CoverText(
    line: CoverLine,
    styled: Styled,
    blinkOn: Boolean,
    quiet: MutableInteractionSource,
    onAction: ((String) -> Unit)?,
) {
    val style = when (line.role) {
        Role.BIG -> styled.clock
        Role.DIM -> styled.log.copy(color = styled.date.color)
        Role.TEXT, Role.INVERSE -> styled.log
    }
    val element = if (line.role == Role.BIG) StyleElement.CLOCK else StyleElement.LOG
    val inverse = line.role == Role.INVERSE
    val action = line.action
    Text(
        annotated(line, styled.text(element, line.text), styled.accent, blinkOn),
        style = style,
        color = if (inverse) styled.background else style.color,
        textAlign = if (line.align == LineAlign.CENTER) TextAlign.Center else TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        softWrap = false,
        modifier = Modifier
            .then(if (line.align == LineAlign.CENTER) Modifier.fillMaxWidth() else Modifier)
            .then(if (inverse) Modifier.background(style.color) else Modifier)
            .then(if (action != null && onAction != null) Modifier.clickable(quiet, null) { onAction(action) } else Modifier)
            .align(if (line.align == LineAlign.CENTER) Alignment.CenterHorizontally else Alignment.Start),
    )
}

private fun annotated(line: CoverLine, text: String, accent: Color, blinkOn: Boolean): AnnotatedString = buildAnnotatedString {
    if (line.mark.isNotEmpty()) withStyle(SpanStyle(color = accent)) { append(line.mark) }
    append(text)
    // The cursor keeps its width while hidden, so the line doesn't jump.
    if (line.cursor) withStyle(SpanStyle(color = if (blinkOn) Color.Unspecified else Color.Transparent)) { append("_") }
}

/** Burn-in shift for minute [minute]: steps around a small square, one position per minute. */
fun burnInShift(minute: Long): Pair<Dp, Dp> {
    val steps = listOf(0 to 0, 4 to 0, 8 to 0, 8 to 4, 8 to 8, 4 to 8, 0 to 8, 0 to 4)
    val (x, y) = steps[(minute % steps.size).toInt()]
    return x.dp to y.dp
}
