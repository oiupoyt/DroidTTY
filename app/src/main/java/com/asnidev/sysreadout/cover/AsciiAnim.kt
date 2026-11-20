package com.asnidev.sysreadout.cover

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The animated always-on looks. Each is a pure function of the phone's data and
 * the time in ms, so a frame can be drawn (or tested, or rendered for the gallery)
 * without any state. Randomness comes from hashes of position and time, never from
 * a generator, so the same instant always draws the same frame.
 */
object AsciiAnim {

    const val W = 32
    const val H = 22

    /** Frames per second each look is drawn at; the battery cost follows. */
    const val FPS = 10

    /** Density ramp, dark to bright. */
    private const val RAMP = " .:-=+*#%@"

    fun fire(d: CoverData, ms: Long): CoverFrame {
        val t = ms / 100.0
        val rows = Array(H) { CharArray(W) { ' ' } }
        for (y in 0 until H) {
            for (x in 0 until W) {
                // Heat falls off with height; flickering tongues from layered noise rising over time.
                val h = (H - 1 - y).toDouble() / (H - 1)
                val n = noise(x * 0.35, y * 0.45 + t * 0.9) * 0.6 + noise(x * 0.9, y * 0.9 + t * 1.7) * 0.4
                val heat = (1.15 - h * 1.35) + (n - 0.5) * 0.9 - abs(x - W / 2.0) / (W * 1.6)
                rows[y][x] = ramp(heat)
            }
        }
        return withClock(rows, d, top = true)
    }

    fun starfield(d: CoverData, ms: Long): CoverFrame {
        val rows = blank()
        val t = ms / 1000.0
        for (i in 0 until 70) {
            // Each star has a fixed direction and speed and loops from the centre outward.
            val a = hash(i, 1) * 2 * PI
            val speed = 0.25 + hash(i, 2) * 0.75
            val z = (hash(i, 3) + t * speed * 0.35) % 1.0
            val r = z * z * 1.4
            val x = (W / 2.0 + cos(a) * r * W / 2.0).roundToInt()
            val y = (H / 2.0 + sin(a) * r * H / 2.0).roundToInt()
            if (x in 0 until W && y in 0 until H) rows[y][x] = when {
                z > 0.8 -> '*'
                z > 0.5 -> '+'
                z > 0.25 -> '.'
                else -> '`'
            }
        }
        return withClock(rows, d)
    }

    /** Conway's Life from a fixed seed; the hour (in live cells) is planted and re-seeded every minute. */
    fun life(d: CoverData, ms: Long): CoverFrame {
        val gen = ((ms / 1000L) % 60L) * 2 + (ms % 1000L) / 500L
        var grid = Array(H) { y -> BooleanArray(W) { x -> hash(x + y * W, d.now.minute + 7) > 0.68 } }
        repeat(gen.toInt()) { grid = step(grid) }
        val rows = Array(H) { y -> CharArray(W) { x -> if (grid[y][x]) 'o' else ' ' } }
        return withClock(rows, d)
    }

    private fun step(g: Array<BooleanArray>): Array<BooleanArray> = Array(H) { y ->
        BooleanArray(W) { x ->
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                if (g[(y + dy + H) % H][(x + dx + W) % W]) n++
            }
            n == 3 || (n == 2 && g[y][x])
        }
    }

    /** A wireframe cube turning on two axes, drawn with line characters by slope. */
    fun cube(d: CoverData, ms: Long): CoverFrame {
        val rows = blank()
        val a = ms / 1000.0 * 0.9
        val b = ms / 1000.0 * 0.6
        val verts = listOf(-1, 1).flatMap { x -> listOf(-1, 1).flatMap { y -> listOf(-1, 1).map { z -> Triple(x.toDouble(), y.toDouble(), z.toDouble()) } } }
        val p = verts.map { (x, y, z) ->
            val x1 = x * cos(a) - z * sin(a)
            val z1 = x * sin(a) + z * cos(a)
            val y1 = y * cos(b) - z1 * sin(b)
            val z2 = y * sin(b) + z1 * cos(b)
            val k = 7.5 / (4 + z2)
            Pair(W / 2.0 + x1 * k * 1.9, H / 2.0 - 1 + y1 * k)
        }
        for (i in verts.indices) for (j in i + 1 until verts.size) {
            val (a1, b1, c1) = verts[i]
            val (a2, b2, c2) = verts[j]
            if (abs(a1 - a2) + abs(b1 - b2) + abs(c1 - c2) == 2.0) line(rows, p[i], p[j])
        }
        p.forEach { (x, y) -> put(rows, x.roundToInt(), y.roundToInt(), '+') }
        return withClock(rows, d, top = false, below = true)
    }

    /** Drops slide down the glass at their own speeds and leave short trails. */
    fun rain(d: CoverData, ms: Long): CoverFrame {
        val rows = blank()
        val t = ms / 1000.0
        for (i in 0 until 26) {
            val x = (hash(i, 11) * W).toInt()
            val speed = 4 + hash(i, 12) * 9
            val len = 2 + (hash(i, 13) * 4).toInt()
            val y0 = ((hash(i, 14) * (H + 10)) + t * speed) % (H + 10) - 5
            for (k in 0 until len) {
                val y = (y0 - k).toInt()
                put(rows, x, y, if (k == 0) 'o' else if (k == 1) '|' else '\'')
            }
        }
        // Puddle along the bottom, rippling.
        for (x in 0 until W) {
            rows[H - 1][x] = if (sin(x * 0.7 + t * 3) > 0.6) '~' else '_'
        }
        return withClock(rows, d)
    }

    /** Fish swimming both ways, bubbles rising, seaweed swaying. */
    fun aquarium(d: CoverData, ms: Long): CoverFrame {
        val rows = blank()
        val t = ms / 1000.0
        // Seaweed.
        for (s in 0 until 5) {
            val x0 = 3 + s * 6
            val tall = 5 + (hash(s, 21) * 6).toInt()
            for (k in 0 until tall) {
                val sway = sin(t * 1.4 + k * 0.6 + s) * (k / 4.0)
                put(rows, (x0 + sway).roundToInt(), H - 2 - k, if ((k + (t * 2).toInt()) % 2 == 0) '(' else ')')
            }
        }
        // Sand.
        for (x in 0 until W) rows[H - 1][x] = if (hash(x, 22) > 0.5) '.' else '_'
        // Fish.
        val fish = listOf("><>", "><(((°>", "<><", "<°)))><")
        for (f in 0 until 5) {
            val right = f % 2 == 0
            val shape = if (right) fish[(f / 2) % 2] else fish[2 + (f / 2) % 2]
            val speed = 2.5 + hash(f, 23) * 4
            val span = W + shape.length
            val pos = (hash(f, 24) * span + t * speed) % span
            val x = if (right) (pos - shape.length).toInt() else (W - pos).toInt()
            val y = 2 + (hash(f, 25) * (H - 8)).toInt() + (sin(t * 1.2 + f) * 1.0).roundToInt()
            shape.forEachIndexed { i, c -> put(rows, x + i, y, c) }
        }
        // Bubbles.
        for (b in 0 until 8) {
            val x = 2 + (hash(b, 26) * (W - 4)).toInt() + (sin(t * 2 + b) * 0.8).roundToInt()
            val y = H - 2 - ((hash(b, 27) * H + t * (3 + hash(b, 28) * 3)) % H).toInt()
            put(rows, x, y, if (b % 3 == 0) 'O' else 'o')
        }
        return withClock(rows, d)
    }

    /**
     * A scope trace scrolling left. With per-core load from Shizuku it traces the
     * cores' average with a little ripple; without, a sine sweep, labelled as such.
     */
    fun scope(d: CoverData, ms: Long): CoverFrame {
        val rows = blank()
        val t = ms / 1000.0
        val load = d.coreLoad.takeIf { it.isNotEmpty() }?.average()
        for (y in listOf(H / 4, H / 2, 3 * H / 4)) for (x in 0 until W) if (x % 2 == 0) rows[y][x] = '.'
        for (x in 0 until W) if (x % 4 == 0) for (y in 0 until H) if (rows[y][x] == ' ') rows[y][x] = '\''
        var last: Int? = null
        for (x in 0 until W) {
            val ph = (x * 0.42) - t * 4.5
            val amp = load?.let { 0.25 + it * 0.7 } ?: 0.8
            val v = sin(ph) * amp + sin(ph * 2.7) * 0.12
            val y = (H / 2 - v * (H / 2 - 2)).roundToInt().coerceIn(0, H - 1)
            last?.let { ly -> for (yy in minOf(ly, y) + 1 until maxOf(ly, y)) put(rows, x, yy, '|') }
            put(rows, x, y, '*')
            last = y
        }
        val label = load?.let { String.format(Locale.US, "cpu %d%%", (it * 100).roundToInt()) } ?: "sweep 1kHz"
        label.forEachIndexed { i, c -> put(rows, W - label.length + i, 0, c) }
        return withClock(rows, d, top = false, below = true)
    }

    /** Two paddles that never miss; the score is the time. */
    fun pong(d: CoverData, ms: Long): CoverFrame {
        val rows = blank()
        val t = ms / 1000.0
        val fieldW = W - 2
        val fieldH = H - 2
        // The ball bounces as two triangle waves, so its path never repeats quickly.
        val bx = 1 + tri(t * 0.55) * (fieldW - 1)
        val by = 1 + tri(t * 0.83 + 0.3) * (fieldH - 1)
        for (x in 0 until W) {
            rows[0][x] = '-'
            rows[H - 1][x] = '-'
        }
        for (y in 1 until H - 1) if (y % 2 == 0) rows[y][W / 2] = ':'
        val left = (by - 1.5).roundToInt().coerceIn(1, H - 5)
        val right = (by - 1.5 + sin(t * 2) * 1.2).roundToInt().coerceIn(1, H - 5)
        for (k in 0 until 4) {
            rows[left + k][1] = '#'
            rows[right + k][W - 2] = '#'
        }
        put(rows, bx.roundToInt().coerceIn(2, W - 3), by.roundToInt().coerceIn(1, H - 2), 'o')
        val score = String.format(Locale.US, "%02d   %02d", if (d.is24h) d.now.hour else (d.now.hour + 11) % 12 + 1, d.now.minute)
        score.forEachIndexed { i, c -> if (c != ' ') put(rows, W / 2 - score.length / 2 + i, 2, c) }
        return CoverFrame(rows.map { CoverLine(String(it)) } + listOf(CoverLine(d.date, Role.DIM, LineAlign.CENTER)), centered = true)
    }

    /** Classic demoscene plasma in ASCII density. */
    fun plasma(d: CoverData, ms: Long): CoverFrame {
        val t = ms / 1000.0
        val rows = Array(H) { y ->
            CharArray(W) { x ->
                val v = sin(x * 0.32 + t) + sin(y * 0.45 - t * 1.3) + sin((x + y) * 0.21 + t * 0.7) +
                    sin(sqrt(((x - W / 2.0) * (x - W / 2.0) + (y - H / 2.0) * (y - H / 2.0) * 4)) * 0.35 - t * 1.6)
                ramp((v + 4) / 8)
            }
        }
        return withClock(rows, d)
    }

    /** A double helix scrolling up, base pairs between the strands. */
    fun helix(d: CoverData, ms: Long): CoverFrame {
        val rows = blank()
        val t = ms / 1000.0
        for (y in 0 until H) {
            val ph = y * 0.5 + t * 2.2
            val a = (W / 2 + sin(ph) * (W / 2 - 3)).roundToInt()
            val b = (W / 2 - sin(ph) * (W / 2 - 3)).roundToInt()
            val front = cos(ph) > 0
            if (y % 2 == 0) for (x in minOf(a, b) + 1 until maxOf(a, b)) rows[y][x] = '-'
            put(rows, a, y, if (front) '@' else 'o')
            put(rows, b, y, if (front) 'o' else '@')
            val base = "ATCG"[((y + (t * 2.2 / 0.5).toInt()) % 4 + 4) % 4]
            if (y % 2 == 0 && abs(a - b) > 4) put(rows, (a + b) / 2, y, base)
        }
        return withClock(rows, d)
    }

    // --- helpers ---

    private fun blank() = Array(H) { CharArray(W) { ' ' } }

    private fun put(rows: Array<CharArray>, x: Int, y: Int, c: Char) {
        if (y in rows.indices && x in rows[y].indices) rows[y][x] = c
    }

    private fun ramp(v: Double): Char = RAMP[(v.coerceIn(0.0, 0.999) * RAMP.length).toInt()]

    private fun tri(x: Double): Double {
        val f = x - kotlin.math.floor(x)
        return if (f < 0.5) f * 2 else 2 - f * 2
    }

    private fun line(rows: Array<CharArray>, a: Pair<Double, Double>, b: Pair<Double, Double>) {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val steps = maxOf(abs(dx), abs(dy) * 2).toInt().coerceAtLeast(1)
        val c = when {
            abs(dy) < abs(dx) * 0.25 -> '-'
            abs(dx) < abs(dy) * 0.6 -> '|'
            dx * dy > 0 -> '\\'
            else -> '/'
        }
        for (i in 0..steps) {
            val x = (a.first + dx * i / steps).roundToInt()
            val y = (a.second + dy * i / steps).roundToInt()
            put(rows, x, y, c)
        }
    }

    /** A stable pseudo-random value in 0..1 for (i, salt). */
    fun hash(i: Int, salt: Int): Double {
        var h = i * 374761393 + salt * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h.toLong() and 0x7fffffff) / 2147483647.0
    }

    /** Smooth value noise in 0..1. */
    private fun noise(x: Double, y: Double): Double {
        val xi = kotlin.math.floor(x).toInt()
        val yi = kotlin.math.floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        fun v(a: Int, b: Int) = hash(a * 57 + b * 131, 5)
        val u = xf * xf * (3 - 2 * xf)
        val w = yf * yf * (3 - 2 * yf)
        val top = v(xi, yi) * (1 - u) + v(xi + 1, yi) * u
        val bot = v(xi, yi + 1) * (1 - u) + v(xi + 1, yi + 1) * u
        return top * (1 - w) + bot * w
    }

    /**
     * The art with the clock and date: a band cut through the middle (or under the
     * art) so the time is always readable over the motion.
     */
    private fun withClock(rows: Array<CharArray>, d: CoverData, top: Boolean = false, below: Boolean = false): CoverFrame {
        val art = rows.map { CoverLine(String(it), Role.DIM) }
        val clock = listOf(CoverLine(d.clock, Role.BIG, LineAlign.CENTER), CoverLine(d.date, align = LineAlign.CENTER))
        val lines = when {
            below -> art + listOf(blank()) + clock
            top -> clock + listOf(blank()) + art
            else -> {
                val mid = art.size / 2
                art.take(mid - 1) + clock + art.drop(mid + 1)
            }
        }
        return CoverFrame(lines, centered = true)
    }
}
