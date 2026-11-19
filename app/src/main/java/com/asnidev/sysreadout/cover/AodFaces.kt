package com.asnidev.sysreadout.cover

import com.asnidev.sysreadout.data.AodLook

/** The always-on display's looks. Kept sparse: most pixels stay black. */
object AodFaces {

    fun frame(look: AodLook, d: CoverData): CoverFrame = when (look) {
        AodLook.CLOCK -> clock(d)
        AodLook.STATUS -> status(d)
        AodLook.PROMPT -> prompt(d)
        AodLook.LOG -> log(d)
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

    fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

    private const val LOG_LINES = 8
}
