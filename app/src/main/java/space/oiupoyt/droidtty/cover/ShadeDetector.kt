package space.oiupoyt.droidtty.cover

/**
 * Decides when Android's shade really opened, from the window list. On many phones
 * (Nothing OS among them) the lock screen is drawn in the same full-height SystemUI
 * window as the shade, so that window coming and going while unlocking (fingerprint,
 * face, a swipe) looks like the shade. Only a change from closed to open counts, only
 * while unlocked, and not in the moments around unlocking or the screen turning on.
 */
class ShadeDetector(private val graceMs: Long = GRACE_MS) {

    private var wasOpen = false
    private var quietUntil = 0L

    /** The screen turned on or off, or the phone unlocked: the window list is in flux for a moment. */
    fun lockEvent(now: Long) {
        quietUntil = now + graceMs
    }

    /** True when this window change is the shade being pulled down. */
    fun opened(open: Boolean, unlocked: Boolean, now: Long): Boolean {
        val edge = open && !wasOpen
        wasOpen = open
        return edge && unlocked && now >= quietUntil
    }

    companion object {
        /** Long enough for the unlock animation and the keyguard window to go away. */
        const val GRACE_MS = 1_500L

        /** How long the shade has to stay open before DroidTTY replaces it; unlock flashes are shorter. */
        const val CONFIRM_MS = 250L
    }
}
