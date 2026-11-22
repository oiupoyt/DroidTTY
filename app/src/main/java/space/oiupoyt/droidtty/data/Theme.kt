package space.oiupoyt.droidtty.data

import org.json.JSONArray
import org.json.JSONObject

enum class TextCase {
    AS_IS, LOWER, UPPER;

    fun apply(s: String): String = when (this) {
        AS_IS -> s
        LOWER -> s.lowercase()
        UPPER -> s.uppercase()
    }
}

enum class StyleElement(val title: String) {
    CLOCK("clock"), DATE("date"), MENU("menu entries"), DRAWER("drawer"), LOG("log"), BANNER("banner")
}

/**
 * CRT screen effects, each 0..1. Glow, scanlines and vignette work everywhere;
 * curvature and fringe need Android 13's shaders. Flicker and grain redraw
 * continuously, so they cost battery and default to off.
 */
data class Crt(
    val glow: Float = 0f,
    val scanlines: Float = 0f,
    val curvature: Float = 0f,
    val vignette: Float = 0f,
    val fringe: Float = 0f,
    val flicker: Float = 0f,
    val grain: Float = 0f,
    /** Also run the menu, clock and date through the effects, not just the log. */
    val menuToo: Boolean = false,
) {
    val isOff: Boolean get() = glow + scanlines + curvature + vignette + fringe + flicker + grain == 0f
    val animated: Boolean get() = flicker > 0f || grain > 0f

    fun toJson(): JSONObject = JSONObject()
        .put("glow", glow.toDouble()).put("scanlines", scanlines.toDouble()).put("curvature", curvature.toDouble())
        .put("vignette", vignette.toDouble()).put("fringe", fringe.toDouble()).put("flicker", flicker.toDouble())
        .put("grain", grain.toDouble()).put("menuToo", menuToo)

    companion object {
        fun fromJson(o: JSONObject?): Crt {
            val d = Crt()
            if (o == null) return d
            fun f(key: String, def: Float) = o.optDouble(key, def.toDouble()).toFloat().coerceIn(0f, 1f)
            return Crt(
                glow = f("glow", d.glow), scanlines = f("scanlines", d.scanlines), curvature = f("curvature", d.curvature),
                vignette = f("vignette", d.vignette), fringe = f("fringe", d.fringe), flicker = f("flicker", d.flicker),
                grain = f("grain", d.grain), menuToo = o.optBoolean("menuToo", d.menuToo),
            )
        }
    }
}

/** Typography and colour of one element. [color] is ARGB. */
data class TextSpec(
    val font: String,
    val size: Float,
    val bold: Boolean = false,
    val spacing: Float = 0f, // em
    val case: TextCase = TextCase.AS_IS,
    val color: Long,
)

data class Theme(
    val background: Long,
    val accent: Long,
    val backing: Long,
    val clock: TextSpec,
    val date: TextSpec,
    val menu: TextSpec,
    val drawer: TextSpec,
    val log: TextSpec,
    val banner: TextSpec = log,
    /** Put before each menu entry; `{n}` becomes the entry's position. */
    val prefix: String = "",
    val crt: Crt = Crt(),
) {
    fun spec(e: StyleElement): TextSpec = when (e) {
        StyleElement.CLOCK -> clock
        StyleElement.DATE -> date
        StyleElement.MENU -> menu
        StyleElement.DRAWER -> drawer
        StyleElement.LOG -> log
        StyleElement.BANNER -> banner
    }

    fun with(e: StyleElement, s: TextSpec): Theme = when (e) {
        StyleElement.CLOCK -> copy(clock = s)
        StyleElement.DATE -> copy(date = s)
        StyleElement.MENU -> copy(menu = s)
        StyleElement.DRAWER -> copy(drawer = s)
        StyleElement.LOG -> copy(log = s)
        StyleElement.BANNER -> copy(banner = s)
    }

    /**
     * Sets the accent and recolours every text to match it, the way the built-in presets
     * are made: clock, menu, drawer and banner in the accent, the log a little toward the
     * background, the date further. Mixing toward the background keeps it right on light
     * themes too.
     */
    fun withAccentPalette(accent: Long): Theme {
        val log = mix(accent, background, LOG_MIX)
        val date = mix(accent, background, DATE_MIX)
        return copy(
            accent = accent,
            clock = clock.copy(color = accent),
            menu = menu.copy(color = accent),
            drawer = drawer.copy(color = accent),
            banner = banner.copy(color = accent),
            log = this.log.copy(color = log),
            date = this.date.copy(color = date),
        )
    }

    /** Swaps every use of a font, e.g. after the user deletes an imported one. */
    fun replacingFont(old: String, new: String): Theme =
        StyleElement.entries.fold(this) { t, e ->
            val s = t.spec(e)
            if (s.font == old) t.with(e, s.copy(font = new)) else t
        }

    fun toJson(): JSONObject = JSONObject()
        .put("background", hex(background))
        .put("accent", hex(accent))
        .put("backing", hex(backing))
        .put("prefix", prefix)
        .put("crt", crt.toJson())
        .apply { StyleElement.entries.forEach { put(it.name.lowercase(), spec(it).toJson()) } }

    companion object {
        /** Missing or malformed fields fall back to [fallback], so old saves keep loading. */
        fun fromJson(o: JSONObject, fallback: Theme = Presets.default.theme): Theme {
            var t = fallback.copy(
                background = color(o.optString("background"), fallback.background),
                accent = color(o.optString("accent"), fallback.accent),
                backing = color(o.optString("backing"), fallback.backing),
                prefix = o.optString("prefix", fallback.prefix),
                crt = if (o.has("crt")) Crt.fromJson(o.optJSONObject("crt")) else fallback.crt,
            )
            StyleElement.entries.forEach { e ->
                o.optJSONObject(e.name.lowercase())?.let { t = t.with(e, specFromJson(it, fallback.spec(e))) }
            }
            // Saved before the banner existed: style it like that theme's log.
            if (!o.has(StyleElement.BANNER.name.lowercase())) t = t.copy(banner = t.log)
            return t
        }

        fun hex(c: Long): String = "#%08X".format(c and 0xFFFFFFFFL)

        private const val LOG_MIX = 0.18f
        private const val DATE_MIX = 0.32f

        /** [a] moved [t] of the way to [b], per channel; opaque. */
        fun mix(a: Long, b: Long, t: Float): Long {
            fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF).toInt()
            fun m(shift: Int) = (ch(a, shift) + (ch(b, shift) - ch(a, shift)) * t).toInt().coerceIn(0, 255).toLong()
            return 0xFF000000L or (m(16) shl 16) or (m(8) shl 8) or m(0)
        }

        /** Accepts #RRGGBB or #AARRGGBB. */
        fun parseColor(s: String): Long? {
            val h = s.trim().removePrefix("#")
            val v = h.toLongOrNull(16) ?: return null
            return when (h.length) {
                6 -> 0xFF000000L or v
                8 -> v
                else -> null
            }
        }

        private fun color(s: String, fallback: Long) = parseColor(s) ?: fallback

        private fun TextSpec.toJson(): JSONObject = JSONObject()
            .put("font", font)
            .put("size", size.toDouble())
            .put("bold", bold)
            .put("spacing", spacing.toDouble())
            .put("case", case.name)
            .put("color", hex(color))

        private fun specFromJson(o: JSONObject, f: TextSpec) = TextSpec(
            font = o.optString("font", f.font),
            size = o.optDouble("size", f.size.toDouble()).toFloat(),
            bold = o.optBoolean("bold", f.bold),
            spacing = o.optDouble("spacing", f.spacing.toDouble()).toFloat(),
            case = runCatching { TextCase.valueOf(o.optString("case")) }.getOrDefault(f.case),
            color = color(o.optString("color"), f.color),
        )
    }
}

/**
 * The always-on look and lock preset that go with a built-in theme, applied with it.
 * Applying never switches the always-on display or a lock screen on: those keep
 * their own switches, so a preset only changes how they look.
 */
data class PresetScreen(val aodLook: AodLook, val lockPreset: LockPreset, val aodColor: AodColor = AodColor.THEME)

/** True when the always-on look and lock preset are this screen's (no screen: always). */
fun PresetScreen?.matches(c: CoverPrefs): Boolean =
    this == null || (c.aodLook == aodLook && c.lockPreset == lockPreset && c.aodColor == aodColor)

/** [screen] is null only for presets saved before presets carried the always-on look and lock screen. */
data class Preset(val name: String, val theme: Theme, val screen: PresetScreen? = null)

fun List<Preset>.toJson(): String = JSONArray().apply {
    forEach { p ->
        val o = JSONObject().put("name", p.name).put("theme", p.theme.toJson())
        p.screen?.let { s ->
            o.put("screen", JSONObject().put("aodLook", s.aodLook.name).put("lockPreset", s.lockPreset.name).put("aodColor", s.aodColor.name))
        }
        put(o)
    }
}.toString()

/** Presets saved before they carried a screen load with none, and apply colours and fonts only. */
fun presetsFromJson(s: String?): List<Preset> = runCatching {
    val a = JSONArray(s ?: return emptyList())
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val screen = o.optJSONObject("screen")?.let { sc ->
            val look = AodLook.entries.firstOrNull { it.name == sc.optString("aodLook") }
            val lock = LockPreset.entries.firstOrNull { it.name == sc.optString("lockPreset") }
            val color = AodColor.entries.firstOrNull { it.name == sc.optString("aodColor") } ?: AodColor.THEME
            if (look != null && lock != null) PresetScreen(look, lock, color) else null
        }
        Preset(o.getString("name"), Theme.fromJson(o.getJSONObject("theme")), screen)
    }
}.getOrDefault(emptyList())

object Presets {
    private fun spec(font: String, size: Float, color: Long, case: TextCase = TextCase.AS_IS, bold: Boolean = false, spacing: Float = 0f) =
        TextSpec(font = font, size = size, bold = bold, spacing = spacing, case = case, color = color)

    val default = Preset(
        "droidtty",
        Theme(
            background = 0xFF050807, accent = 0xFF3DDC84, backing = 0xFF050807,
            clock = spec("system-mono", 48f, 0xFFE8ECE9),
            date = spec("system-mono", 16f, 0xFF8A938D, TextCase.LOWER),
            menu = spec("system-mono", 20f, 0xFFE8ECE9),
            drawer = spec("system-mono", 20f, 0xFFE8ECE9),
            log = spec("system-mono", 11f, 0xFF3DDC84),
        ),
        screen = PresetScreen(AodLook.CLOCK, LockPreset.BOOTLINE),
    )

    /** P1 green phosphor, the classic monochrome terminal, with the full CRT treatment. */
    val greenP1 = Preset(
        "green p1",
        Theme(
            background = 0xFF020E05, accent = 0xFF1AFF80, backing = 0xFF031A09,
            clock = spec("vt323", 88f, 0xFF1AFF80),
            date = spec("vt323", 26f, 0xFF12B85C, TextCase.UPPER, spacing = 0.05f),
            menu = spec("vt323", 32f, 0xFF1AFF80, TextCase.UPPER, spacing = 0.04f),
            drawer = spec("vt323", 30f, 0xFF1AFF80, TextCase.UPPER),
            log = spec("vt323", 16f, 0xFF16D96C),
            banner = spec("vt323", 20f, 0xFF1AFF80, TextCase.UPPER),
            prefix = "> ",
            crt = Crt(glow = 0.6f, scanlines = 0.45f, curvature = 0.35f, vignette = 0.6f, fringe = 0.2f, menuToo = true),
        ),
        screen = PresetScreen(AodLook.NEOFETCH, LockPreset.ARCH),
    )

    val amber = Preset(
        "amber p3",
        Theme(
            background = 0xFF0E0800, accent = 0xFFFFB000, backing = 0xFF140C00,
            clock = spec("px437-vga", 60f, 0xFFFFB000),
            date = spec("px437-vga", 16f, 0xFFB37B00, TextCase.UPPER),
            menu = spec("px437-vga", 22f, 0xFFFFB000),
            drawer = spec("px437-vga", 22f, 0xFFFFB000),
            log = spec("px437-vga", 12f, 0xFFE09A00),
            banner = spec("px437-vga", 14f, 0xFFFFB000, TextCase.UPPER),
            prefix = "C:\\> ",
            crt = Crt(glow = 0.5f, scanlines = 0.5f, curvature = 0.25f, vignette = 0.5f, menuToo = true),
        ),
        screen = PresetScreen(AodLook.PONG, LockPreset.DOS),
    )

    val paper = Preset(
        "paper p4",
        Theme(
            background = 0xFF0A0C10, accent = 0xFFE8F0FF, backing = 0xFF0A0C10,
            clock = spec("ibm-plex-mono", 52f, 0xFFE8F0FF),
            date = spec("ibm-plex-mono", 15f, 0xFF7F8AA3, TextCase.LOWER),
            menu = spec("ibm-plex-mono", 20f, 0xFFE8F0FF),
            drawer = spec("ibm-plex-mono", 20f, 0xFFE8F0FF),
            log = spec("ibm-plex-mono", 10.5f, 0xFF8FA3C8),
            crt = Crt(glow = 0.25f, scanlines = 0.2f, vignette = 0.3f),
        ),
        screen = PresetScreen(AodLook.HTOP, LockPreset.SSH),
    )

    val minimal = Preset(
        "minimal",
        Theme(
            background = 0xFF000000, accent = 0xFFFFFFFF, backing = 0xFF000000,
            clock = spec("jetbrains-mono", 44f, 0xFFFFFFFF),
            date = spec("jetbrains-mono", 14f, 0xFF7A7A7A, TextCase.LOWER),
            menu = spec("jetbrains-mono", 22f, 0xFFFFFFFF, TextCase.LOWER),
            drawer = spec("jetbrains-mono", 20f, 0xFFFFFFFF, TextCase.LOWER),
            log = spec("jetbrains-mono", 10f, 0xFF4A4A4A),
        ),
        screen = PresetScreen(AodLook.CLOCK, LockPreset.LOGIN, AodColor.DIM),
    )

    val arcade = Preset(
        "arcade",
        Theme(
            background = 0xFF0B0221, accent = 0xFFFF3EA5, backing = 0xFF12052E,
            clock = spec("press-start", 34f, 0xFFFF3EA5),
            date = spec("silkscreen", 16f, 0xFF7A5CFF, TextCase.UPPER),
            menu = spec("press-start", 14f, 0xFF00F0FF, TextCase.UPPER),
            drawer = spec("silkscreen", 20f, 0xFF00F0FF, TextCase.UPPER),
            log = spec("silkscreen", 11f, 0xFF7A5CFF),
            prefix = "{n} ",
        ),
        screen = PresetScreen(AodLook.PLASMA, LockPreset.C64),
    )

    val lime = Preset(
        "lime",
        Theme(
            background = 0xFF0A0A0A, accent = 0xFFB4FF39, backing = 0xFF0A0A0A,
            clock = spec("major-mono", 46f, 0xFFB4FF39),
            date = spec("space-mono", 14f, 0xFF6E7B5A, TextCase.LOWER),
            menu = spec("major-mono", 22f, 0xFFE6F5CC),
            drawer = spec("space-mono", 19f, 0xFFE6F5CC, TextCase.LOWER),
            log = spec("space-mono", 10f, 0xFF5E6B48),
            prefix = "./",
        ),
        screen = PresetScreen(AodLook.AQUARIUM, LockPreset.GRUB),
    )

    /** Doom fire in the dark: deep red phosphor, warm and low. */
    val ember = Preset(
        "ember",
        Theme(
            background = 0xFF0C0302, accent = 0xFFFF5A1F, backing = 0xFF180604,
            clock = spec("vt323", 84f, 0xFFFF7A3D),
            date = spec("vt323", 24f, 0xFFB8452A, TextCase.UPPER, spacing = 0.05f),
            menu = spec("vt323", 30f, 0xFFFF8F5A, TextCase.UPPER, spacing = 0.04f),
            drawer = spec("vt323", 28f, 0xFFFF8F5A, TextCase.UPPER),
            log = spec("vt323", 15f, 0xFFD9542B),
            banner = spec("vt323", 19f, 0xFFFF7A3D, TextCase.UPPER),
            prefix = "~ ",
            crt = Crt(glow = 0.55f, scanlines = 0.35f, vignette = 0.6f, menuToo = true),
        ),
        screen = PresetScreen(AodLook.FIRE, LockPreset.BIOS),
    )

    /** Deep space: ice blue on near black, a starfield always-on. */
    val deepSpace = Preset(
        "deep space",
        Theme(
            background = 0xFF03060E, accent = 0xFF7FD3FF, backing = 0xFF070D1C,
            clock = spec("share-tech-mono", 58f, 0xFFCDEBFF),
            date = spec("share-tech-mono", 15f, 0xFF5E7A99, TextCase.LOWER),
            menu = spec("share-tech-mono", 21f, 0xFFCDEBFF, TextCase.LOWER),
            drawer = spec("share-tech-mono", 20f, 0xFFCDEBFF, TextCase.LOWER),
            log = spec("share-tech-mono", 11f, 0xFF4F87B8),
            prefix = "· ",
            crt = Crt(glow = 0.3f, vignette = 0.45f),
        ),
        screen = PresetScreen(AodLook.STARFIELD, LockPreset.WAKEUP),
    )

    /** The matrix: bright green on black, katakana rain always-on. */
    val matrix = Preset(
        "matrix",
        Theme(
            background = 0xFF000000, accent = 0xFF00FF41, backing = 0xFF001A08,
            clock = spec("jetbrains-mono", 50f, 0xFF00FF41, bold = true),
            date = spec("jetbrains-mono", 14f, 0xFF008F11, TextCase.LOWER),
            menu = spec("jetbrains-mono", 20f, 0xFF00FF41, TextCase.LOWER),
            drawer = spec("jetbrains-mono", 19f, 0xFF00FF41, TextCase.LOWER),
            log = spec("jetbrains-mono", 10f, 0xFF00B82E),
            prefix = "$ ",
            crt = Crt(glow = 0.45f, scanlines = 0.2f),
        ),
        screen = PresetScreen(AodLook.MATRIX, LockPreset.WAKEUP),
    )

    /** An oscilloscope bench: cyan trace on dark teal, the scope always-on. */
    val scope = Preset(
        "scope",
        Theme(
            background = 0xFF021012, accent = 0xFF3FF5E8, backing = 0xFF04191C,
            clock = spec("ibm-plex-mono", 52f, 0xFF7DFFF5),
            date = spec("ibm-plex-mono", 14f, 0xFF2E8C88, TextCase.UPPER, spacing = 0.08f),
            menu = spec("ibm-plex-mono", 20f, 0xFF7DFFF5),
            drawer = spec("ibm-plex-mono", 19f, 0xFF7DFFF5),
            log = spec("ibm-plex-mono", 10.5f, 0xFF2BC4BA),
            prefix = "[{n}] ",
            crt = Crt(glow = 0.5f, scanlines = 0.3f, curvature = 0.2f, vignette = 0.4f),
        ),
        screen = PresetScreen(AodLook.SCOPE, LockPreset.BOOTLINE),
    )

    val builtIn = listOf(default, greenP1, amber, paper, minimal, arcade, lime, ember, deepSpace, matrix, scope)

    /** Choices offered for the menu-entry prefix. */
    val prefixes = listOf("", "> ", "$ ", "./", "- ", "• ", "~/", "C:\\> ", "[{n}] ", "{n}. ", "{n} ")
}
