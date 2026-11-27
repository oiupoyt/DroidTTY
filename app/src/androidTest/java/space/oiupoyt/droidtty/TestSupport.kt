package space.oiupoyt.droidtty

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule

// The app's screens run endless loops (log ticks, CRT flicker, AOD redraws), so tests drive
// the clock by hand: autoAdvance off, then one frame per real frame, keeping virtual and
// wall time roughly together (lock scripts measure elapsed time with System.currentTimeMillis).

private const val FRAME_MS = 16L

fun ComposeTestRule.pumpFrame() {
    mainClock.advanceTimeByFrame()
    SystemClock.sleep(FRAME_MS)
}

/** Pumps frames for [ms] of real time. */
fun ComposeTestRule.pumpFor(ms: Long) {
    val end = SystemClock.uptimeMillis() + ms
    while (SystemClock.uptimeMillis() < end) pumpFrame()
}

/** Pumps frames until [done], failing after [timeoutMs] of real time. */
fun ComposeTestRule.pumpUntil(what: String, timeoutMs: Long = 15_000, done: () -> Boolean) {
    val end = SystemClock.uptimeMillis() + timeoutMs
    while (!done()) {
        if (SystemClock.uptimeMillis() > end) throw AssertionError("timed out after ${timeoutMs}ms waiting for $what")
        pumpFrame()
    }
}

fun ComposeTestRule.exists(matcher: SemanticsMatcher, unmerged: Boolean = false): Boolean =
    onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

/** Any node carrying text: proof that something composed and laid out. */
val hasAnyText: SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
