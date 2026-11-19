package com.asnidev.sysreadout.cover

/** How a line is drawn: BIG uses the clock style, the rest the log style (DIM in the date colour). */
enum class Role { BIG, TEXT, DIM, INVERSE }

enum class LineAlign { START, CENTER }

/**
 * One line of the always-on display or lock cover. [mark] is drawn first in the
 * accent colour ("[  OK  ]"); [cursor] adds a blinking "_"; [action] makes the
 * line a tap target (see CoverActivity).
 */
data class CoverLine(
    val text: String,
    val role: Role = Role.TEXT,
    val align: LineAlign = LineAlign.START,
    val mark: String = "",
    val cursor: Boolean = false,
    val action: String? = null,
)

data class CoverFrame(val lines: List<CoverLine>, val centered: Boolean = false)

fun blank() = CoverLine("")
