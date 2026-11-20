package com.asnidev.sysreadout.cover

import com.asnidev.sysreadout.data.LockPreset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A timed sequence of frames; the last one waits for the tap or the PIN. */
data class LockScript(val beats: List<Pair<Long, CoverFrame>>) {
    val readyAt: Long get() = beats.last().first

    /** The frame showing [ms] after the cover appeared. */
    fun at(ms: Long): CoverFrame = beats.last { it.first <= ms.coerceAtLeast(0) }.second
}

private class Script(private val centered: Boolean = false, private val tail: Boolean = false) {
    private var t = 0L
    private val lines = ArrayList<CoverLine>()
    val beats = ArrayList<Pair<Long, CoverFrame>>()

    init { snap() }

    private fun snap() {
        if (beats.isNotEmpty() && beats.last().first == t) beats.removeAt(beats.lastIndex)
        beats += t to CoverFrame(lines.toList(), centered, tail)
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

    fun clear(delayMs: Long) {
        t += delayMs
        lines.clear()
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
 * The lock-screen presets. Values about the phone all come from [CoverData]; one
 * the phone didn't give is left out, never invented. Counters that animate (boot
 * seconds, the memory count, the flash bar) are the animation's own progress.
 *
 * With [own] (SysReadout's own lock), a preset ends ready for a password instead
 * of a "tap" hint; [prompt] supplies the password line in the preset's voice.
 */
object LockPresets {

    fun script(preset: LockPreset, d: CoverData, own: Boolean = false): LockScript = when (preset) {
        LockPreset.ARCH -> arch(d, own)
        LockPreset.BOOTLINE -> bootline(d, own)
        LockPreset.BIOS -> bios(d, own)
        LockPreset.DOS -> dos(d, own)
        LockPreset.LOGIN -> login(d, own)
        LockPreset.GRUB -> grub(d, own)
        LockPreset.C64 -> c64(d, own)
        LockPreset.PANIC -> panic(d, own)
        LockPreset.SSH -> ssh(d, own)
        LockPreset.WAKEUP -> wakeup(d, own)
    }

    /**
     * The own lock's password line(s) under the finished script. [typed] is the
     * number of digits entered (shown as `*`), [status] a failure or wait message.
     */
    fun prompt(preset: LockPreset, d: CoverData, typed: Int, status: String?): List<CoverLine> {
        val stars = "*".repeat(typed)
        val ask = when (preset) {
            LockPreset.BIOS -> "Enter Password: "
            LockPreset.DOS -> "C:\\>unlock "
            LockPreset.C64 -> "PASSWORD? "
            LockPreset.PANIC -> "Passphrase to reboot: "
            LockPreset.SSH -> "${USER}@${d.host}'s password: "
            LockPreset.WAKEUP -> "> "
            LockPreset.GRUB -> "Enter password: "
            LockPreset.ARCH, LockPreset.BOOTLINE, LockPreset.LOGIN -> "Password: "
        }
        val fail = when (preset) {
            LockPreset.BIOS -> "Invalid Password"
            LockPreset.DOS -> "Access denied"
            LockPreset.C64 -> "?SYNTAX  ERROR"
            LockPreset.SSH -> "Permission denied, please try again."
            LockPreset.GRUB -> "error: access denied."
            else -> "Login incorrect"
        }
        return listOfNotNull(
            status?.let { CoverLine(if (it == FAILED) fail else it, Role.DIM) },
            CoverLine(ask + stars, cursor = true),
        )
    }

    /** Pass as [prompt]'s status after a wrong PIN; each preset words it its own way. */
    const val FAILED = "\u0000failed"

    const val OK = "[  OK  ] "
    const val USER = "user"

    const val ACTION_UNLOCK = "unlock"
    const val ACTION_CAMERA = "camera"
    const val ACTION_EMERGENCY = "emergency"

    /** A full Arch Linux boot: GRUB, early userspace, systemd, then the login prompt. */
    private fun arch(d: CoverData, own: Boolean): LockScript {
        val s = Script(tail = true)
        val kernel = d.kernel ?: "linux"
        s.line("Loading Linux $kernel ...", 0)
        s.line("Loading initial ramdisk ...", 260)
        s.clear(420)
        s.line(":: running early hook [udev]", 0)
        s.line("Starting systemd-udevd", 90)
        s.line(":: running hook [udev]", 90)
        s.line(":: Triggering uevents...", 90)
        s.line(":: running hook [keymap]", 160)
        s.line(":: Loading keymap...done.", 60)
        s.line(":: performing fsck on '/dev/block/by-name/userdata'", 120)
        s.line("userdata: clean", 160)
        s.line(":: mounting '/dev/block/by-name/userdata' on real root", 90)
        s.line(":: running cleanup hook [udev]", 120)
        s.line(blank(), 60)
        s.line(CoverLine("Welcome to Arch Linux!"), 140)
        s.line(blank())
        val units = buildList {
            add("Created slice Slice /system/getty.")
            add("Created slice User and Session Slice.")
            add("Started Dispatch Password Requests to Console Directory Watch.")
            add("Reached target Local Encrypted Volumes.")
            add("Reached target Path Units.")
            add("Reached target Slice Units.")
            add("Listening on Journal Socket.")
            add("Listening on udev Control Socket.")
            add("Mounted Huge Pages File System.")
            add("Mounted Temporary Directory /tmp.")
            add("Finished Load Kernel Modules.")
            add("Started Journal Service.")
            add("Started Rule-based Manager for Device Events and Files.")
            d.memTotalMb?.let { add("Reached target Memory: ${it}M.") }
            add("Reached target Swaps.")
            add("Reached target Local File Systems.")
            if (d.storageFreeGb != null && d.storageTotalGb != null) {
                add(String.format(Locale.US, "Mounted /data (%.1fG free of %.0fG).", d.storageFreeGb, d.storageTotalGb))
            }
            add("Started Network Time Synchronization.")
            add("Reached target System Initialization.")
            add("Started Daily Cleanup of Temporary Directories.")
            add("Reached target Timer Units.")
            add("Listening on D-Bus System Message Bus Socket.")
            add("Reached target Basic System.")
            add("Started D-Bus System Message Bus.")
            add("Started User Login Management.")
            d.net?.let { add(if (it == "offline") "Network Manager: no connection." else "Started Network Manager ($it).") }
            add("Reached target Network.")
            d.batteryText?.let { add("Started Power Profiles daemon (battery $it).") }
            d.cores.takeIf { it > 0 }?.let { add("Started CPU frequency scaling ($it cores).") }
            add("Started Getty on tty1.")
            add("Reached target Login Prompts.")
            add("Reached target Multi-User System.")
        }
        units.forEachIndexed { i, u -> s.line(u, if (i < 4) 70 else 45, mark = OK) }
        s.clear(380)
        s.line("Arch Linux $kernel (tty1)", 0)
        s.line(blank())
        if (own) {
            s.line("${d.host} login: $USER", 160)
        } else {
            s.line(CoverLine("${d.host} login: ", cursor = true), 160)
            s.line(CoverLine("tap to log in", Role.DIM))
        }
        return s.build()
    }

    private fun bootline(d: CoverData, own: Boolean): LockScript {
        val s = Script(tail = true)
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
        if (own) {
            s.line("${d.host} login: $USER", 120)
        } else {
            s.line(CoverLine("${d.host} login: ", cursor = true), 120)
            s.line(CoverLine("tap to log in", Role.DIM), 0)
        }
        return s.build()
    }

    private fun bios(d: CoverData, own: Boolean): LockScript {
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
        if (!own) s.line(CoverLine("Tap to continue", cursor = true))
        return s.build()
    }

    /** `[######......] 40%`, BAR_WIDTH cells. */
    fun bar(percent: Int): String {
        val full = BAR_WIDTH * percent / 100
        return "[" + "#".repeat(full) + ".".repeat(BAR_WIDTH - full) + "] " + "$percent%".padStart(4)
    }

    private fun dos(d: CoverData, own: Boolean): LockScript {
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
        if (!own) {
            s.line(CoverLine(ps, cursor = true), 150)
            s.line(CoverLine("tap to unlock", Role.DIM))
        }
        return s.build()
    }

    private fun login(d: CoverData, own: Boolean): LockScript {
        val s = Script()
        s.line("Android ${d.android} (${d.host}) (tty1)")
        s.line(blank())
        d.lastUnlock?.let { s.line("Last unlock: ${it.format(LAST_LOGIN)} on tty1", 0, Role.DIM) }
        d.notifications?.takeIf { it > 0 }?.let { s.line("You have ${AodFaces.plural(it, "notification")}.", 0, Role.DIM) }
        s.line(blank())
        if (own) {
            s.line("${d.host} login: $USER", 200)
        } else {
            s.line(CoverLine("${d.host} login: ", cursor = true), 200)
            s.line(CoverLine("tap to log in", Role.DIM))
        }
        return s.build()
    }

    private fun grub(d: CoverData, own: Boolean): LockScript {
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
        // The own lock has no secure camera to hand over to: Android's own camera would open unlocked.
        if (!own) s.line(row("Camera", false, ACTION_CAMERA), 60)
        s.line(row("Emergency call", false, ACTION_EMERGENCY), 60)
        s.line("+" + "-".repeat(w) + "+", 60)
        s.line(blank())
        if (!own) {
            s.line(CoverLine("Tap an entry to boot it, or anywhere", Role.DIM), 100)
            s.line(CoverLine("else to boot the highlighted one.", Role.DIM))
            s.line(blank())
        }
        s.line(CoverLine("${d.clock}  ${d.date}", Role.DIM, LineAlign.CENTER))
        return s.build()
    }

    /** The Commodore 64 power-on screen, with the phone's own memory in place of 64K. */
    private fun c64(d: CoverData, own: Boolean): LockScript {
        val s = Script()
        s.line(CoverLine("**** ${d.manufacturer.uppercase(Locale.US).ifEmpty { "ANDROID" }} ${d.android} BASIC V2 ****", align = LineAlign.CENTER))
        s.line(blank())
        if (d.memTotalMb != null && d.memAvailMb != null) {
            s.line(CoverLine(" ${d.memTotalMb}M RAM SYSTEM  ${d.memAvailMb}M BYTES FREE", align = LineAlign.CENTER))
        }
        s.line(blank())
        s.line("READY.", 300)
        s.type("", "LOAD \"*\",8,1", 400)
        s.line(blank())
        s.line("SEARCHING FOR *", 300)
        s.line("LOADING", 700)
        s.line("READY.", 900)
        s.type("", "RUN", 250)
        if (!own) {
            s.line(blank())
            s.line(CoverLine("PRESS ANY KEY (TAP)", cursor = true), 200)
        }
        return s.build()
    }

    /** A Linux kernel panic, then the reboot it asks for. */
    private fun panic(d: CoverData, own: Boolean): LockScript {
        val s = Script(tail = true)
        s.line("Kernel panic - not syncing: Attempted to kill init!", 0)
        s.line("CPU: 0 PID: 1 Comm: init Not tainted ${d.kernel ?: ""}".trimEnd(), 40)
        s.line("Hardware name: ${d.manufacturer} ${d.model}".trimEnd(), 40)
        s.line("Call trace:", 40)
        listOf("dump_backtrace", "show_stack", "dump_stack_lvl", "panic", "do_exit", "do_group_exit", "get_signal", "do_notify_resume", "el0_svc")
            .forEach { s.line(" $it", 35, Role.DIM) }
        s.line("SMP: stopping secondary CPUs", 120)
        s.line("Kernel Offset: disabled", 60)
        s.line("---[ end Kernel panic - not syncing: Attempted to kill init! ]---", 200)
        s.line(blank())
        for (n in 3 downTo 1) s.line("Rebooting in $n seconds..", if (n == 3) 400 else 700)
        s.clear(700)
        s.line("Android ${d.android} (${d.host}) recovered.", 300)
        s.line(blank())
        if (!own) s.line(CoverLine("tap to continue", Role.DIM, cursor = true))
        return s.build()
    }

    /** Logging in to the phone over ssh, with the motd built from its real state. */
    private fun ssh(d: CoverData, own: Boolean): LockScript {
        val s = Script(tail = true)
        s.type("$ ", "ssh $USER@${d.host}", 0)
        s.line("The authenticity of host '${d.host}' can't be established.", 350)
        s.line("Are you sure you want to continue connecting (yes/no)? yes", 300)
        s.line("Warning: Permanently added '${d.host}' to the list of known hosts.", 300)
        if (own) return s.build()
        s.line(blank(), 300)
        s.line("Welcome to Android ${d.android} (${d.kernel ?: "Linux"})", 0)
        s.line(blank())
        s.line("  System information as of ${d.date} ${d.clock}", 0, Role.DIM)
        s.line(blank())
        d.memUsedMb?.let { s.line("  Memory usage: ${it * 100 / (d.memTotalMb ?: it).coerceAtLeast(1)}%", 60) }
        d.batteryText?.let { s.line("  Battery:      $it", 60) }
        d.uptimeText?.let { s.line("  Uptime:       $it", 60) }
        if (d.storageFreeGb != null && d.storageTotalGb != null) {
            s.line(String.format(Locale.US, "  Storage:      %.1fG free of %.0fG", d.storageFreeGb, d.storageTotalGb), 60)
        }
        d.notifications?.takeIf { it > 0 }?.let { s.line("  ${AodFaces.plural(it, "notification")} waiting", 60) }
        s.line(blank())
        d.lastUnlock?.let { s.line("Last login: ${it.format(LAST_LOGIN)}", 0, Role.DIM) }
        s.line(CoverLine("$USER@${d.host}:~$ ", cursor = true), 200)
        s.line(CoverLine("tap to continue", Role.DIM))
        return s.build()
    }

    /** "Wake up, Neo." with the phone's name. */
    private fun wakeup(d: CoverData, own: Boolean): LockScript {
        val s = Script()
        s.type("", "Wake up, ${d.host}...", 400)
        s.line(blank(), 900)
        s.type("", "The Matrix has you...", 300)
        s.line(blank(), 900)
        s.type("", "Follow the white rabbit.", 300)
        s.line(blank(), 900)
        s.type("", "Knock, knock, ${d.host}.", 300)
        if (!own) {
            s.line(blank(), 600)
            s.line(CoverLine("tap to answer", Role.DIM, cursor = true))
        }
        return s.build()
    }

    private val LAST_LOGIN = DateTimeFormatter.ofPattern("EEE MMM d HH:mm", Locale.US)
    private const val MEM_STEPS = 8
    const val BAR_WIDTH = 20
    private const val BOX_WIDTH = 30
}
