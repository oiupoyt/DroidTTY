package com.asnidev.sysreadout.data

import org.json.JSONArray
import org.json.JSONObject

/** When SysReadout's own always-on display comes up after the screen turns off. */
enum class AodMode { OFF, CHARGING, ALWAYS }

enum class AodLook {
    CLOCK, STATUS, PROMPT, LOG, NEOFETCH, TTY, HTOP, MATRIX, BINARY,
    FIRE, STARFIELD, LIFE, CUBE, RAIN, AQUARIUM, SCOPE, PONG, PLASMA, HELIX;

    /** Redrawn several times a second: livelier, and it costs more battery. */
    val animated: Boolean get() = ordinal >= FIRE.ordinal || this == MATRIX
}

/**
 * Where an always-on or lock screen sits. [vertical] null keeps the look's own
 * placement (boot logs scroll from the bottom, menus sit in the middle). Offsets
 * and [margin] are dp.
 */
data class Placement(
    val vertical: VAlign? = VAlign.CENTER,
    val horizontal: HAlign = HAlign.CENTER,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val margin: Int = 20,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        vertical?.let { put("v", it.name) }
        put("h", horizontal.name).put("x", offsetX).put("y", offsetY).put("margin", margin)
    }

    companion object {
        const val STEP_X = 8
        const val STEP_Y = 16
        const val STEP_MARGIN = 4
        val RANGE_X = -64..64
        val RANGE_Y = -320..320
        val RANGE_MARGIN = 0..64

        fun fromJson(o: JSONObject?, d: Placement): Placement {
            if (o == null) return d
            return Placement(
                vertical = if (o.has("v")) runCatching { VAlign.valueOf(o.getString("v")) }.getOrDefault(d.vertical) else null,
                horizontal = runCatching { HAlign.valueOf(o.getString("h")) }.getOrDefault(d.horizontal),
                offsetX = o.optInt("x", d.offsetX).coerceIn(RANGE_X),
                offsetY = o.optInt("y", d.offsetY).coerceIn(RANGE_Y),
                margin = o.optInt("margin", d.margin).coerceIn(RANGE_MARGIN),
            )
        }
    }
}

/** The own lock's keypad: across the screen, or narrower on one side for one-handed use. */
enum class KeypadLayout { FULL, LEFT, CENTER, RIGHT }

/** THEME keeps the look's colours; the others paint every line in one colour. */
enum class AodColor { THEME, ACCENT, DIM, MONO }

enum class LockPreset { ARCH, BOOTLINE, BIOS, DOS, LOGIN, GRUB, C64, PANIC, SSH, WAKEUP }

/**
 * OFF: Android's lock screen as it is. COVER: a boot screen over Android's lock,
 * which still does the unlocking. OWN: SysReadout's own PIN lock, for phones
 * whose Android screen lock is set to none.
 */
enum class LockScreenMode { OFF, COVER, OWN }

/** Always-on display, lock screen and notification shade. */
data class CoverPrefs(
    val aodMode: AodMode = AodMode.OFF,
    val aodLook: AodLook = AodLook.CLOCK,
    /** Status rows for the terminal-status look (ids from ProbeCatalog). */
    val aodRows: List<String> = listOf("bat", "net", "mem"),
    /** On battery, the always-on display waits for the charger below this level. */
    val aodMinBattery: Int = 20,
    val lockScreen: LockScreenMode = LockScreenMode.OFF,
    val lockPreset: LockPreset = LockPreset.ARCH,
    /** SysReadout's shade opens from a swipe down on the status bar and on home. */
    val shade: Boolean = false,
    /** Text size multiplier for the always-on display. */
    val aodScale: Float = 1f,
    /** Screen brightness while it shows, in percent. */
    val aodBrightness: Int = 2,
    val aodPlace: Placement = Placement(),
    val aodColor: AodColor = AodColor.THEME,
    /** Move a few dp each minute against burn-in. */
    val aodShift: Boolean = true,
    /** Go dark after this many minutes; 0 keeps it on. */
    val aodTimeoutMin: Int = 0,
    /** Lock screens keep each preset's own vertical placement until the user picks one. */
    val lockPlace: Placement = Placement(vertical = null, horizontal = HAlign.START),
    val keypad: KeypadLayout = KeypadLayout.FULL,
    /** Raises the own lock's keypad off the bottom edge, in dp. */
    val keypadLift: Int = 0,
    /** Digits in a new random order each time the own lock shows. */
    val shuffleKeypad: Boolean = true,
) {
    /** The foreground service hears screen on/off for these; the own lock and shade use the accessibility service. */
    val needsService: Boolean get() = aodMode != AodMode.OFF || lockScreen == LockScreenMode.COVER

    fun toJson(): String = JSONObject()
        .put("aodMode", aodMode.name).put("aodLook", aodLook.name)
        .put("aodRows", JSONArray(aodRows)).put("aodMinBattery", aodMinBattery)
        .put("lockScreen", lockScreen.name).put("lockPreset", lockPreset.name)
        .put("shade", shade)
        .put("aodScale", aodScale.toDouble()).put("aodBrightness", aodBrightness)
        .put("aodPlace", aodPlace.toJson()).put("aodColor", aodColor.name)
        .put("aodShift", aodShift).put("aodTimeoutMin", aodTimeoutMin)
        .put("lockPlace", lockPlace.toJson()).put("keypad", keypad.name).put("keypadLift", keypadLift)
        .put("shuffleKeypad", shuffleKeypad)
        .toString()

    companion object {
        const val MIN_BATTERY_LOW = 5
        const val MIN_BATTERY_HIGH = 80
        val SCALES = listOf(0.6f, 0.8f, 1f, 1.25f, 1.5f, 2f)
        val BRIGHTNESS = listOf(1, 2, 5, 10, 20, 40)
        val TIMEOUTS = listOf(0, 1, 5, 15, 30, 60)
        const val STEP_LIFT = 16
        val RANGE_LIFT = 0..240

        fun fromJson(s: String?): CoverPrefs {
            val d = CoverPrefs()
            val o = runCatching { JSONObject(s ?: return d) }.getOrElse { return d }
            val rows = o.optJSONArray("aodRows")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
            // 0.3.0 saved only an on/off switch for the cover.
            val legacy = if (o.optBoolean("lockCover", false)) LockScreenMode.COVER else d.lockScreen
            return CoverPrefs(
                aodMode = enumOr(o.optString("aodMode"), d.aodMode),
                aodLook = enumOr(o.optString("aodLook"), d.aodLook),
                aodRows = rows ?: d.aodRows,
                aodMinBattery = o.optInt("aodMinBattery", d.aodMinBattery).coerceIn(MIN_BATTERY_LOW, MIN_BATTERY_HIGH),
                lockScreen = enumOr(o.optString("lockScreen"), legacy),
                lockPreset = enumOr(o.optString("lockPreset"), d.lockPreset),
                shade = o.optBoolean("shade", d.shade),
                aodScale = o.optDouble("aodScale", d.aodScale.toDouble()).toFloat().coerceIn(SCALES.first(), SCALES.last()),
                aodBrightness = o.optInt("aodBrightness", d.aodBrightness).coerceIn(1, 100),
                // 0.4.1 saved only the vertical position, as "aodPosition".
                aodPlace = Placement.fromJson(
                    o.optJSONObject("aodPlace"),
                    d.aodPlace.copy(vertical = enumOr(o.optString("aodPosition"), VAlign.CENTER)),
                ),
                aodColor = enumOr(o.optString("aodColor"), d.aodColor),
                aodShift = o.optBoolean("aodShift", d.aodShift),
                aodTimeoutMin = o.optInt("aodTimeoutMin", d.aodTimeoutMin).coerceIn(0, 24 * 60),
                lockPlace = Placement.fromJson(o.optJSONObject("lockPlace"), d.lockPlace),
                keypad = enumOr(o.optString("keypad"), d.keypad),
                keypadLift = o.optInt("keypadLift", d.keypadLift).coerceIn(RANGE_LIFT),
                shuffleKeypad = o.optBoolean("shuffleKeypad", d.shuffleKeypad),
            )
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
            runCatching { enumValueOf<E>(name) }.getOrDefault(default)
    }
}
