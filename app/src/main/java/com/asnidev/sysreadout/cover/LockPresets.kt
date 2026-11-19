package com.asnidev.sysreadout.cover

import com.asnidev.sysreadout.data.LockPreset
import java.util.Locale

/** A timed sequence of frames; the last one waits for the tap that unlocks. */
data class LockScript(val beats: List<Pair<Long, CoverFrame>>) {
    val readyAt: Long get() = beats.last().first

    /** The frame showing [ms] after the cover appeared. */
    fun at(ms: Long): CoverFrame = beats.last { it.first <= ms.coerceAtLeast(0) }.second
}

private class Script(private val centered: Boolean = false) {
    private var t = 0L
    private val lines = ArrayList<CoverLine>()
    val beats = ArrayList<Pair<Long, CoverFrame>>()

    init { snap() }

    private fun snap() {
        if (beats.isNotEmpty() && beats.last().first == t) beats.removeAt(beats.lastIndex)
        beats += t to CoverFrame(lines.toList(), centered)
    }

    fun line(l: CoverLine, delayMs: Long = 0) {
        t += delayMs
        lines += l
        snap()
    }

    fun line(text: String, delayMs: Long = 0, role: Role = Role.TEXT, mark: String = "") = line(CoverLine(text, role, mark = mark), delayMs)

    fun replaceLast(l: CoverLine, delayMs: Long) {
        t += delayMs
        lines[lines.lastIndex] = l
        snap()
    }

    /** Types [text] after [prefix] a few characters at a time, as a person at the keyboard would. */
    fun type(prefix: String, text: String, delayMs: Long = 0, role: Role = Role.TEXT) {
        line(CoverLine(prefix, role, cursor = true), delayMs)
        text.chunked(2).runningReduce { a, b -> a + b }.forEach { typed ->
            replaceLast(CoverLine(prefix + typed, role, cursor = true), TYPE_MS)
        }
        replaceLast(CoverLine(prefix + text, role), 0)
    }

    fun build() = LockScript(beats.toList())

    companion object {
        const val TYPE_MS = 45L
    }
}

/**
 * The lock-cover presets. Everything they print comes from [CoverData]; a value
 * the phone didn't give is left out, never invented. Timestamps and counters
 * that animate (boot seconds, the memory count, the flash bar) are the animation's
 * own clock and progress.
 */
object LockPresets {

    fun script(preset: LockPreset, d: CoverData): LockScript = when (preset) {
        LockPreset.BOOTLINE -> bootline(d)
        LockPreset.BIOS -> bios(d)
        LockPreset.DOS -> dos(d)
        LockPreset.LOGIN -> login(d)
        LockPreset.GRUB -> grub(d)
    }

    const val OK = "[  OK  ] "

    private fun bootline(d: CoverData): LockScript {
        val s = Script()
        var at = 0L
        fun kern(text: String, delay: Long) {
            at += delay
            s.line(String.format(Locale.US, "[%9.6f] %s", at / 1000.0, text), delay, Role.DIM)
        }
        fun ok(text: String) = s.line(text, 110, mark = OK)

        kern(d.kernel?.let { "Linux version $it" } ?: "Linux", 0)
        kern("Machine model: ${d.manufacturer} ${d.model}".trimEnd(), 60)
        if (d.cores > 0) kern("smp: Brought up ${d.cores} CPUs", 70)
        d.memTotalMb?.let { kern("Memory: ${it}M total", 70) }
        ok("Started Android ${d.android} (API ${d.api})")
        d.soc?.let { ok("Reached target cpu: $it") }
        if (d.storageFreeGb != null && d.storageTotalGb != null) {
            ok(String.format(Locale.US, "Mounted /data: %.1fG free of %.0fG", d.storageFreeGb, d.storageTotalGb))
        }
        d.net?.let { ok(if (it == "offline") "Network is offline" else "Started network: $it") }
        d.batteryText?.let { ok("Reached target battery: $it") }
        d.notifications?.let { ok("Reached target notifications: ${if (it == 0) "none" else "$it waiting"}") }
        s.line(blank(), 160)
        s.line("Android ${d.android} ${d.host} tty1")
        s.line(blank())
        s.line(CoverLine("${d.host} login: ", cursor = true), 120)
        s.line(CoverLine("tap to log in", Role.DIM), 0)
        return s.build()
    }

    private fun bios(d: CoverData): LockScript {
        val s = Script()
        s.line(CoverLine("${d.manufacturer.uppercase(Locale.US)} BIOS  ANDROID ${d.android}  API ${d.api}".trim(), Role.INVERSE))
        s.line(blank())
        val cpu = listOfNotNull(d.soc, d.cores.takeIf { it > 0 }?.let { "$it cores" }).joinToString(", ")
        if (cpu.isNotEmpty()) s.line("CPU    : $cpu", 120)
        d.memTotalMb?.let { total ->
            s.line("Memory : 0M", 120)
            for (i in 1..MEM_STEPS) s.replaceLast(CoverLine("Memory : ${total * i / MEM_STEPS}M"), 60)
            s.replaceLast(CoverLine("Memory : ${total}M OK"), 0)
        }
        if (d.storageFreeGb != null && d.storageTotalGb != null) {
            s.line(String.format(Locale.US, "Storage: %.0fG, %.1fG free", d.storageTotalGb, d.storageFreeGb), 120)
        }
        d.batteryText?.let { s.line("Battery: $it", 120) }
        s.line(blank())
        s.line("Flashing ${d.patch?.let { "security patch $it" } ?: "firmware"}", 200)
        s.line(bar(0), 0)
        for (p in 5..100 step 5) s.replaceLast(CoverLine(bar(p)), 70)
        s.line(CoverLine("Flash complete.", Role.TEXT, mark = "OK "), 150)
        s.line(blank())
        s.line(CoverLine("Tap to continue", cursor = true))
        return s.build()
    }

    /** `[######......] 40%`, BAR_WIDTH cells. */
    fun bar(percent: Int): String {
        val full = BAR_WIDTH * percent / 100
        return "[" + "#".repeat(full) + ".".repeat(BAR_WIDTH - full) + "] " + "$percent%".padStart(4)
    }

    private fun dos(d: CoverData): LockScript {
        val s = Script()
        val ps = "C:\\>"
        s.line("Starting ${d.manufacturer.ifEmpty { "Android" }}-DOS...", 0, Role.DIM)
        s.line(blank(), 250)
        s.type(ps, "date", 150)
        s.line("Current date is ${d.date}")
        s.type(ps, "time", 150)
        s.line("Current time is ${d.clockSeconds}")
        s.type(ps, "ver", 150)
        s.line("Android ${d.android} [API ${d.api}]")
        if (d.memAvailMb != null && d.memTotalMb != null) {
            s.type(ps, "mem", 150)
            s.line("${d.memAvailMb}M free of ${d.memTotalMb}M")
        }
        s.line(blank())
        s.line(CoverLine(ps, cursor = true), 150)
        s.line(CoverLine("tap to unlock", Role.DIM))
        return s.build()
    }

    private fun login(d: CoverData): LockScript {
        val s = Script()
        s.line("Android ${d.android} (${d.host}) (tty1)")
        s.line(blank())
        d.lastUnlock?.let { s.line("Last unlock: ${it.format(LAST_LOGIN)} on tty1", 0, Role.DIM) }
        d.notifications?.takeIf { it > 0 }?.let { s.line("You have ${AodFaces.plural(it, "notification")}.", 0, Role.DIM) }
        s.line(blank())
        s.line(CoverLine("${d.host} login: ", cursor = true), 200)
        s.line(CoverLine("tap to log in", Role.DIM))
        return s.build()
    }

    const val ACTION_UNLOCK = "unlock"
    const val ACTION_CAMERA = "camera"
    const val ACTION_EMERGENCY = "emergency"

    private fun grub(d: CoverData): LockScript {
        val s = Script(centered = true)
        val w = BOX_WIDTH
        fun row(text: String, selected: Boolean, action: String) = CoverLine(
            "|" + " $text".padEnd(w).take(w) + "|",
            if (selected) Role.INVERSE else Role.TEXT,
            action = action,
        )
        s.line(CoverLine("GRUB  version 2", align = LineAlign.CENTER))
        s.line(blank())
        s.line("+" + "-".repeat(w) + "+", 80)
        s.line(row("Android ${d.android} (${d.host})", true, ACTION_UNLOCK), 80)
        s.line(row("Camera", false, ACTION_CAMERA), 60)
        s.line(row("Emergency call", false, ACTION_EMERGENCY), 60)
        s.line("+" + "-".repeat(w) + "+", 60)
        s.line(blank())
        s.line(CoverLine("Tap an entry to boot it, or anywhere", Role.DIM), 100)
        s.line(CoverLine("else to boot the highlighted one.", Role.DIM))
        s.line(blank())
        s.line(CoverLine("${d.clock}  ${d.date}", Role.DIM, LineAlign.CENTER))
        return s.build()
    }

    private val LAST_LOGIN = java.time.format.DateTimeFormatter.ofPattern("EEE MMM d HH:mm", Locale.US)
    private const val MEM_STEPS = 8
    const val BAR_WIDTH = 20
    private const val BOX_WIDTH = 30
}
