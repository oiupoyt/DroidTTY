package space.oiupoyt.droidtty.monitor

/**
 * Android's status-bar clock, through System UI's icon blacklist (the setting
 * behind "System UI tuner"). Other hidden icons in the list are kept.
 */
object StatusClock {
    private const val KEY = "icon_blacklist"
    const val CLOCK = "clock"

    fun withClock(current: String?, hidden: Boolean): String {
        val list = current.orEmpty().trim().takeUnless { it == "null" }.orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val next = if (hidden) (list + CLOCK).distinct() else list - CLOCK
        return next.joinToString(",")
    }

    fun hiddenIn(current: String?): Boolean =
        current.orEmpty().split(',').any { it.trim() == CLOCK }

    suspend fun isHidden(bridge: ShizukuBridge): Boolean? =
        bridge.exec("settings get secure $KEY")?.let { hiddenIn(it.trim()) }

    suspend fun setHidden(bridge: ShizukuBridge, hidden: Boolean): Boolean {
        val current = bridge.exec("settings get secure $KEY") ?: return false
        val next = withClock(current, hidden)
        val cmd = if (next.isEmpty()) "settings delete secure $KEY" else "settings put secure $KEY '$next'"
        return bridge.exec(cmd) != null
    }
}
