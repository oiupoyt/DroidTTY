package com.asnidev.sysreadout.cover

import com.asnidev.sysreadout.data.AodLook
import java.util.Locale
import kotlin.random.Random

/** The always-on display's looks. Kept sparse: most pixels stay black. */
object AodFaces {

    fun frame(look: AodLook, d: CoverData, tick: Long = 0L): CoverFrame = when (look) {
        AodLook.CLOCK -> clock(d)
        AodLook.STATUS -> status(d)
        AodLook.PROMPT -> prompt(d)
        AodLook.LOG -> log(d)
        AodLook.NEOFETCH -> neofetch(d)
        AodLook.TTY -> tty(d)
        AodLook.HTOP -> htop(d)
        AodLook.MATRIX -> matrix(d, tick)
        AodLook.BINARY -> binary(d)
        AodLook.FIRE -> AsciiAnim.fire(d, tick)
        AodLook.STARFIELD -> AsciiAnim.starfield(d, tick)
        AodLook.LIFE -> AsciiAnim.life(d, tick)
        AodLook.CUBE -> AsciiAnim.cube(d, tick)
        AodLook.RAIN -> AsciiAnim.rain(d, tick)
        AodLook.AQUARIUM -> AsciiAnim.aquarium(d, tick)
        AodLook.SCOPE -> AsciiAnim.scope(d, tick)
        AodLook.PONG -> AsciiAnim.pong(d, tick)
        AodLook.PLASMA -> AsciiAnim.plasma(d, tick)
        AodLook.HELIX -> AsciiAnim.helix(d, tick)
    }

    /** How often each look needs a redraw, in ms. */
    fun refreshMs(look: AodLook): Long = when {
        look == AodLook.MATRIX -> 500L
        look.animated -> 1_000L / AsciiAnim.FPS
        else -> when (look) {
            AodLook.PROMPT, AodLook.TTY, AodLook.BINARY -> 1_000L
            else -> 60_000L
        }
    }

    private fun clock(d: CoverData) = CoverFrame(
        listOfNotNull(
            CoverLine(d.clock, Role.BIG, LineAlign.CENTER),
            CoverLine(d.date, Role.DIM, LineAlign.CENTER),
            blank(),
            d.batteryText?.let { CoverLine(it, align = LineAlign.CENTER) },
            d.notifications?.takeIf { it > 0 }?.let { CoverLine(plural(it, "notification"), Role.DIM, LineAlign.CENTER) },
        ),
        centered = true,
    )

    private fun status(d: CoverData) = CoverFrame(
        listOf(CoverLine(d.clock, Role.BIG), CoverLine(d.date, Role.DIM), blank()) +
            d.rows.map { CoverLine(it) },
    )

    private fun prompt(d: CoverData): CoverFrame {
        val ps = "root@${d.host}:~$ "
        val lines = mutableListOf(CoverLine(ps + "date"), CoverLine("${d.date}  ${d.clock}", Role.DIM))
        d.batteryText?.let {
            lines += CoverLine(ps + "battery")
            lines += CoverLine(it, Role.DIM)
        }
        d.notifications?.let {
            lines += CoverLine(ps + "notifications")
            lines += CoverLine(if (it == 0) "none" else plural(it, "notification"), Role.DIM)
        }
        lines += CoverLine(ps, cursor = true)
        return CoverFrame(lines, centered = true)
    }

    private fun log(d: CoverData): CoverFrame {
        val tail = d.events.takeLast(LOG_LINES)
        return CoverFrame(
            listOf(CoverLine(d.clock, Role.BIG), CoverLine(d.date, Role.DIM), blank()) +
                (if (tail.isEmpty()) listOf(CoverLine("no events since the launcher started", Role.DIM)) else tail.map { CoverLine(it) }),
        )
    }

    /** The Arch logo beside the phone's real system facts, as neofetch prints them. */
    private fun neofetch(d: CoverData): CoverFrame {
        val title = "${d.host}@android"
        val facts = listOfNotNull(
            title to "",
            "-".repeat(title.length) to "",
            "OS" to "Android ${d.android} (API ${d.api})",
            "Host" to "${d.manufacturer} ${d.model}".trim(),
            d.kernel?.let { "Kernel" to it },
            d.uptimeText?.let { "Uptime" to it },
            d.packages?.let { "Packages" to "$it (pm)" },
            d.display?.let { "Display" to it },
            d.soc?.let { "CPU" to it + if (d.cores > 0) " (${d.cores})" else "" },
            d.memUsedMb?.let { "Memory" to "${it}MiB / ${d.memTotalMb}MiB" },
            d.batteryText?.let { "Battery" to it },
            "Time" to "${d.clock}  ${d.date}",
        )
        val lines = ArrayList<CoverLine>()
        for (i in 0 until maxOf(ARCH_LOGO.size, facts.size)) {
            val logo = ARCH_LOGO.getOrElse(i) { "" }.padEnd(LOGO_WIDTH)
            val fact = facts.getOrNull(i)
            val text = when {
                fact == null -> ""
                fact.second.isEmpty() -> fact.first
                else -> "${fact.first}: ${fact.second}"
            }
            lines += CoverLine(text, mark = logo)
        }
        return CoverFrame(lines, centered = true)
    }

    /** tty-clock: the time in big block digits, seconds included. */
    private fun tty(d: CoverData): CoverFrame {
        val t = d.now
        val text = String.format(Locale.US, "%02d:%02d", if (d.is24h) t.hour else (t.hour + 11) % 12 + 1, t.minute)
        return CoverFrame(
            bigDigits(text).map { CoverLine(it, align = LineAlign.CENTER) } +
                listOf(
                    blank(),
                    CoverLine(String.format(Locale.US, ":%02d", t.second), Role.DIM, LineAlign.CENTER),
                    CoverLine(d.date, Role.DIM, LineAlign.CENTER),
                ),
            centered = true,
        )
    }

    /** htop's meters: per-core load (with Shizuku), memory and battery as bars. */
    private fun htop(d: CoverData): CoverFrame {
        val lines = mutableListOf(CoverLine(d.clock, Role.BIG), CoverLine(d.date, Role.DIM), blank())
        if (d.coreLoad.isEmpty()) {
            lines += CoverLine("cpu bars need shizuku (settings › shizuku)", Role.DIM)
        } else {
            d.coreLoad.forEachIndexed { i, load -> lines += CoverLine(meter(String.format(Locale.US, "%2d", i), load, pct(load))) }
        }
        val used = d.memUsedMb
        val total = d.memTotalMb
        if (used != null && total != null && total > 0) {
            lines += CoverLine(meter("Mem", used.toDouble() / total, String.format(Locale.US, "%.1fG/%.1fG", used / 1024.0, total / 1024.0)))
        }
        d.battery?.let { lines += CoverLine(meter("Bat", it / 100.0, "$it%")) }
        d.uptimeText?.let {
            lines += blank()
            lines += CoverLine("Uptime: $it", Role.DIM)
        }
        return CoverFrame(lines)
    }

    /** Falling green characters with the clock held steady in the middle. */
    private fun matrix(d: CoverData, tick: Long): CoverFrame {
        val step = tick / 500L
        val rows = (0 until MATRIX_ROWS).map { r ->
            CoverLine(
                (0 until MATRIX_COLS).joinToString("") { c ->
                    // Each column is a drop whose head moves down one row per step.
                    val speed = 1 + (c * 7 % 3)
                    val head = ((step * speed + c * 13) % (MATRIX_ROWS + 8)).toInt()
                    val dist = head - r
                    if (dist in 0..5) MATRIX_CHARS[Random(c * 131 + r * 17 + step).nextInt(MATRIX_CHARS.length)].toString() else " "
                },
                Role.DIM,
            )
        }.toMutableList()
        val mid = MATRIX_ROWS / 2
        rows[mid - 1] = CoverLine("")
        rows[mid] = CoverLine(d.clock, Role.BIG, LineAlign.CENTER)
        rows[mid + 1] = CoverLine(d.date, align = LineAlign.CENTER)
        return CoverFrame(rows, centered = true)
    }

    /** Hours, minutes and seconds as binary-coded decimal columns, the classic binary clock. */
    private fun binary(d: CoverData): CoverFrame {
        val t = d.now
        val digits = String.format(Locale.US, "%02d%02d%02d", t.hour, t.minute, t.second).map { it - '0' }
        val lines = (3 downTo 0).map { bit ->
            CoverLine(binaryRow(digits, bit) + "   ${1 shl bit}", align = LineAlign.CENTER)
        }
        return CoverFrame(
            lines + listOf(
                CoverLine("h h   m m   s s" + " ".repeat(4), Role.DIM, LineAlign.CENTER),
                blank(),
                CoverLine("${d.clockSeconds}  ${d.date}", Role.DIM, LineAlign.CENTER),
            ),
            centered = true,
        )
    }

    /** One bit row: `#` set, `.` clear, digits in pairs (hh mm ss). */
    fun binaryRow(digits: List<Int>, bit: Int): String =
        digits.chunked(2).joinToString("   ") { pair -> pair.joinToString(" ") { if (it shr bit and 1 == 1) "#" else "." } }

    fun bigDigits(text: String): List<String> = (0 until 5).map { row ->
        text.map { c -> (BIG[c] ?: BIG.getValue(' '))[row] }.joinToString(" ")
    }

    /** `Mem[||||||      3.1G/7.5G]` htop style. */
    fun meter(label: String, fraction: Double, value: String, width: Int = METER_WIDTH): String {
        val inner = width - value.length
        val bars = (fraction.coerceIn(0.0, 1.0) * inner).toInt()
        return "$label[" + "|".repeat(bars) + " ".repeat((inner - bars).coerceAtLeast(0)) + value + "]"
    }

    private fun pct(f: Double) = String.format(Locale.US, "%.0f%%", f * 100)

    fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

    private const val LOG_LINES = 8
    private const val METER_WIDTH = 28
    private const val MATRIX_ROWS = 24
    private const val MATRIX_COLS = 30
    private const val MATRIX_CHARS = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄ0123456789Z:."
    private const val LOGO_WIDTH = 20

    private val ARCH_LOGO = listOf(
        "         /\\",
        "        /  \\",
        "       /    \\",
        "      /      \\",
        "     /   ,,   \\",
        "    /   |  |   \\",
        "   /_-''    ''-_\\",
    )

    private val BIG = mapOf(
        '0' to listOf("###", "# #", "# #", "# #", "###"),
        '1' to listOf("  #", "  #", "  #", "  #", "  #"),
        '2' to listOf("###", "  #", "###", "#  ", "###"),
        '3' to listOf("###", "  #", "###", "  #", "###"),
        '4' to listOf("# #", "# #", "###", "  #", "  #"),
        '5' to listOf("###", "#  ", "###", "  #", "###"),
        '6' to listOf("###", "#  ", "###", "# #", "###"),
        '7' to listOf("###", "  #", "  #", "  #", "  #"),
        '8' to listOf("###", "# #", "###", "# #", "###"),
        '9' to listOf("###", "# #", "###", "  #", "###"),
        ':' to listOf(" ", "#", " ", "#", " "),
        ' ' to listOf("   ", "   ", "   ", "   ", "   "),
    )
}
