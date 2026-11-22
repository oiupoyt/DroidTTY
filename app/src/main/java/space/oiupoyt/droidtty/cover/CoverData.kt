package space.oiupoyt.droidtty.cover

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the always-on display and lock cover show, read once from the phone.
 * A null field is unknown (no permission, not readable) and its line is left
 * out: these screens never show a made-up value.
 */
data class CoverData(
    val now: ZonedDateTime,
    val is24h: Boolean,
    val battery: Int? = null,
    val charging: Boolean = false,
    val manufacturer: String = "",
    val model: String = "",
    /** Build.DEVICE, the codename; doubles as the host name in prompts. */
    val device: String = "android",
    val android: String = "",
    val api: Int = 0,
    val patch: String? = null,
    val kernel: String? = null,
    val soc: String? = null,
    val cores: Int = 0,
    val memTotalMb: Long? = null,
    val memAvailMb: Long? = null,
    val storageTotalGb: Double? = null,
    val storageFreeGb: Double? = null,
    /** wifi, cell, eth, vpn, other or offline. */
    val net: String? = null,
    /** Notifications showing now; null without notification access. */
    val notifications: Int? = null,
    val nextAlarm: ZonedDateTime? = null,
    val lastUnlock: ZonedDateTime? = null,
    /** Newest stream lines of the log, oldest first, as "HH:mm text". */
    val events: List<String> = emptyList(),
    /** Sampled status rows ("bat   85% ..."), for the terminal-status look. */
    val rows: List<String> = emptyList(),
    val uptimeMs: Long? = null,
    /** Installed packages, as neofetch counts them. */
    val packages: Int? = null,
    /** "1080x2412 120Hz" */
    val display: String? = null,
    /** Per-core load 0..1 from the last Shizuku sample; empty without it. */
    val coreLoad: List<Double> = emptyList(),
    /** "Title · Artist" of what's playing; null when nothing is or there's no notification access. */
    val playing: String? = null,
) {
    val clock: String get() = now.format(if (is24h) H24 else H12)
    val clockSeconds: String get() = now.format(if (is24h) H24S else H12S)
    val date: String get() = now.format(DATE)
    val host: String get() = device.lowercase(Locale.US).replace(Regex("[^a-z0-9-]"), "").ifEmpty { "android" }

    /** "85%" or "85% charging"; null when unknown. */
    val batteryText: String? get() = battery?.let { "$it%" + if (charging) " charging" else "" }

    fun alarmText(): String? = nextAlarm?.format(if (is24h) DAY_H24 else DAY_H12)

    /** "3 days, 4 hours, 12 mins" the way uptime(1) and neofetch print it. */
    val uptimeText: String? get() = uptimeMs?.let { ms ->
        val mins = ms / 60_000
        val d = mins / 1_440
        val h = mins / 60 % 24
        val m = mins % 60
        listOfNotNull(
            d.takeIf { it > 0 }?.let { "$it day" + if (it == 1L) "" else "s" },
            h.takeIf { it > 0 }?.let { "$it hour" + if (it == 1L) "" else "s" },
            "$m min" + if (m == 1L) "" else "s",
        ).joinToString(", ")
    }

    /** The memory in use, when both totals are known. */
    val memUsedMb: Long? get() = if (memTotalMb != null && memAvailMb != null) memTotalMb - memAvailMb else null

    companion object {
        private val H24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        private val H12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        private val H24S = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
        private val H12S = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)
        private val DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
        private val DAY_H24 = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US)
        private val DAY_H12 = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)

        fun at(millis: Long, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
    }
}

/** State shared between the launcher, the screen service and the cover activity (one process). */
object CoverState {
    /** Last time the always-on display was seen on screen; refreshed every second while it is. */
    @Volatile var aodSeenAt = 0L

    /** When the service last started the always-on display (its own screen-on must not raise the cover). */
    @Volatile var aodLaunchedAt = 0L

    /** The always-on display was started and its own screen-on hasn't arrived yet. */
    @Volatile var awaitingAodWake = false

    /** The user switched the screen off on purpose: no always-on display until the next wake. */
    @Volatile var suppressed = false

    @Volatile var lastUnlock: Long? = null

    /** Newest log events, oldest first; the log engine sets how to make them while the launcher runs. */
    @Volatile var eventSource: () -> List<String> = { emptyList() }
    val events: List<String> get() = eventSource()

    /** Per-core load from the log engine's last Shizuku sample. */
    @Volatile var coreLoad: List<Double> = emptyList()

    fun aodShowing(now: Long): Boolean = now - aodSeenAt < AOD_SEEN_MS

    private const val AOD_SEEN_MS = 2_000L
}
