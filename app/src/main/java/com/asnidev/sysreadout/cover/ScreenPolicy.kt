package com.asnidev.sysreadout.cover

import com.asnidev.sysreadout.data.AodMode
import com.asnidev.sysreadout.data.CoverPrefs
import com.asnidev.sysreadout.data.LockScreenMode

/** What the phone is doing when the screen turns off or on. */
data class PhoneNow(
    val battery: Int?,
    val charging: Boolean,
    val inCall: Boolean,
    val locked: Boolean,
    val canOverlay: Boolean,
    val safeMode: Boolean,
)

/** Pure decisions for [ScreenService]; kept apart so they can be tested. */
object ScreenPolicy {

    /** Show the always-on display after the screen went off. */
    fun aodOnScreenOff(p: CoverPrefs, now: PhoneNow, suppressed: Boolean): Boolean {
        if (now.safeMode || !now.canOverlay || now.inCall || suppressed) return false
        return when (p.aodMode) {
            AodMode.OFF -> false
            AodMode.CHARGING -> now.charging
            AodMode.ALWAYS -> now.charging || (now.battery ?: 0) >= p.aodMinBattery
        }
    }

    /**
     * Raise the lock cover when the screen comes on. Not while the always-on
     * display is up (it hands over to the cover itself when tapped), and not for
     * the screen-on the always-on display causes.
     */
    fun coverOnScreenOn(p: CoverPrefs, now: PhoneNow, aodShowing: Boolean, sinceAodLaunchMs: Long): Boolean =
        p.lockScreen == LockScreenMode.COVER && now.locked && now.canOverlay && !now.safeMode && !now.inCall &&
            !aodShowing && sinceAodLaunchMs > AOD_WAKE_MS

    /** The always-on display was tapped: go to the cover if it's on and the phone is locked, else unlock. */
    fun coverAfterAod(p: CoverPrefs, locked: Boolean): Boolean = p.lockScreen == LockScreenMode.COVER && locked

    /** The screen-on the always-on display itself triggers arrives within this window. */
    const val AOD_WAKE_MS = 3_000L

    /**
     * Whether a screen-on is the one the always-on display caused by turning the screen on.
     * The first screen-on after it starts is its own however late it arrives (slow phones
     * send it after several seconds, which used to read as the power key and jump straight
     * to the lock screen). Past [AOD_WAKE_MAX_MS] the display evidently never lit, so the
     * next screen-on is the user's.
     */
    fun aodOwnWake(awaitingWake: Boolean, sinceLaunchMs: Long): Boolean =
        awaitingWake && sinceLaunchMs in 0 until AOD_WAKE_MAX_MS

    const val AOD_WAKE_MAX_MS = 15_000L

    enum class Guard { IGNORE, COLLAPSE_SHADE, SEND_HOME }

    /**
     * What the own lock does when a window opens. While SysReadout's own screen (the
     * always-on display or the lock) is on top it covers everything, so nothing needs
     * doing: reacting there is what used to swap the always-on display for the lock.
     */
    fun guard(ownLocked: Boolean, ownScreenShowing: Boolean, fromSystemUi: Boolean, inCall: Boolean): Guard = when {
        !ownLocked || inCall -> Guard.IGNORE
        fromSystemUi -> if (ownScreenShowing) Guard.IGNORE else Guard.COLLAPSE_SHADE
        else -> Guard.SEND_HOME
    }

    /** Covered, for the pocket check. Sensors report "far" as their maximum, or as a few cm on some phones. */
    fun isNear(value: Float, maximumRange: Float): Boolean = value < minOf(maximumRange, NEAR_CM)

    /** A pocket has to stay covered this long, and not in the first moments after the display lights up. */
    const val POCKET_MS = 1_200L
    const val POCKET_GRACE_MS = 2_000L
    private const val NEAR_CM = 3f
}
