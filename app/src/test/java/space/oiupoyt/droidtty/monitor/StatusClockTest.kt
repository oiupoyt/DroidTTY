package space.oiupoyt.droidtty.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusClockTest {

    @Test
    fun hidesAndShowsKeepingOtherIcons() {
        assertEquals("clock", StatusClock.withClock(null, hidden = true))
        assertEquals("clock", StatusClock.withClock("null", hidden = true))
        assertEquals("rotate,clock", StatusClock.withClock("rotate", hidden = true))
        assertEquals("rotate,clock", StatusClock.withClock("rotate,clock", hidden = true))
        assertEquals("rotate", StatusClock.withClock("rotate, clock", hidden = false))
        assertEquals("", StatusClock.withClock("clock", hidden = false))
    }

    @Test
    fun readsTheState() {
        assertTrue(StatusClock.hiddenIn("rotate,clock"))
        assertFalse(StatusClock.hiddenIn("rotate,clockface"))
        assertFalse(StatusClock.hiddenIn("null"))
    }
}
