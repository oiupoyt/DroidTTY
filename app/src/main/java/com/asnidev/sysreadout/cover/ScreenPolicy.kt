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
}
