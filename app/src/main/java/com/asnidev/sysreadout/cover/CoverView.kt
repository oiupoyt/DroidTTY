package com.asnidev.sysreadout.cover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
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
import com.asnidev.sysreadout.data.HAlign
import com.asnidev.sysreadout.data.Placement
import com.asnidev.sysreadout.data.VAlign
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
    /** The user's placement; null keeps the frame's own. */
    place: Placement? = null,
) {
    val margin = place?.margin?.dp ?: DEFAULT_MARGIN
    val nudgeX = (place?.offsetX ?: 0).dp + shift.first
    val nudgeY = (place?.offsetY ?: 0).dp + shift.second
    val vertical = place?.vertical?.let {
        when (it) {
            VAlign.TOP -> Arrangement.Top
            VAlign.CENTER -> Arrangement.Center
            VAlign.BOTTOM -> Arrangement.Bottom
        }
    }
    val horizontal = when (place?.horizontal) {
        HAlign.CENTER -> Alignment.CenterHorizontally
        HAlign.END -> Alignment.End
        else -> Alignment.Start
    }
    val quiet = remember { MutableInteractionSource() }
    // ART rows are a character grid: one font size for the whole grid, the largest at which
    // its widest row fills the space between the margins (monospace, so width = chars × advance).
    val artChars = remember(frame) { frame.lines.filter { it.role == Role.ART }.maxOfOrNull { it.text.length } ?: 0 }
    val measurer = rememberTextMeasurer()
    var widthPx by remember { mutableIntStateOf(0) }
    val artStyle = remember(styled, artChars, widthPx) {
        val base = styled.log.copy(color = styled.date.color, lineHeight = TextUnit.Unspecified)
        if (artChars == 0 || widthPx == 0) {
            base
        } else {
            val advance = measurer.measure("M".repeat(ART_PROBE), base).size.width.toFloat() / ART_PROBE
            val target = widthPx / artChars.toFloat()
            val size = (base.fontSize.value * target / advance).coerceIn(ART_MIN_SP, ART_MAX_SP)
            base.copy(fontSize = size.sp, lineHeight = (size * ART_LINE_HEIGHT).sp)
        }
    }
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
                .padding(horizontal = margin, vertical = 24.dp)
                .onSizeChanged { widthPx = it.width }
                .offset(nudgeX, nudgeY),
        ) {
            // A boot log keeps its newest line at the bottom edge and lets older ones scroll off the top.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .then(
                        // A boot log that overflows still scrolls from the bottom; one that fits goes where asked.
                        if (frame.tail) Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true) else Modifier,
                    ),
                verticalArrangement = vertical ?: when {
                    frame.centered -> Arrangement.Center
                    frame.tail -> Arrangement.Bottom
                    else -> Arrangement.Top
                },
                horizontalAlignment = horizontal,
            ) {
                // The lines move as one block as wide as the widest line: left edges stay lined
                // up, and centred lines (clocks) centre within the block wherever it's placed.
                Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.Start) {
                    frame.lines.forEach { line -> CoverText(line, styled, blinkOn, quiet, onAction, artStyle) }
                }
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
    artStyle: TextStyle,
) {
    val style = when (line.role) {
        Role.BIG -> styled.clock
        Role.DIM -> styled.log.copy(color = styled.date.color)
        Role.TEXT, Role.INVERSE -> styled.log
        Role.ART -> artStyle
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

private val DEFAULT_MARGIN = 20.dp
private const val ART_PROBE = 20
private const val ART_MIN_SP = 6f
private const val ART_MAX_SP = 28f
/** Rows close together, so the art reads as one picture rather than lines of text. */
private const val ART_LINE_HEIGHT = 1.0f

/** Burn-in shift for minute [minute]: steps around a small square, one position per minute. */
fun burnInShift(minute: Long): Pair<Dp, Dp> {
    val steps = listOf(0 to 0, 4 to 0, 8 to 0, 8 to 4, 8 to 8, 4 to 8, 0 to 8, 0 to 4)
    val (x, y) = steps[(minute % steps.size).toInt()]
    return x.dp to y.dp
}
