package com.asnidev.sysreadout.data

import org.json.JSONArray
import org.json.JSONObject

/** When SysReadout's own always-on display comes up after the screen turns off. */
enum class AodMode { OFF, CHARGING, ALWAYS }

enum class AodLook { CLOCK, STATUS, PROMPT, LOG, NEOFETCH, TTY, HTOP, MATRIX, BINARY }

enum class AodPosition { TOP, CENTER, BOTTOM }

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
    val aodPosition: AodPosition = AodPosition.CENTER,
    val aodColor: AodColor = AodColor.THEME,
    /** Move a few dp each minute against burn-in. */
    val aodShift: Boolean = true,
    /** Go dark after this many minutes; 0 keeps it on. */
    val aodTimeoutMin: Int = 0,
) {
    /** The foreground service hears screen on/off for these; the own lock and shade use the accessibility service. */
    val needsService: Boolean get() = aodMode != AodMode.OFF || lockScreen == LockScreenMode.COVER

    fun toJson(): String = JSONObject()
        .put("aodMode", aodMode.name).put("aodLook", aodLook.name)
        .put("aodRows", JSONArray(aodRows)).put("aodMinBattery", aodMinBattery)
        .put("lockScreen", lockScreen.name).put("lockPreset", lockPreset.name)
        .put("shade", shade)
        .put("aodScale", aodScale.toDouble()).put("aodBrightness", aodBrightness)
        .put("aodPosition", aodPosition.name).put("aodColor", aodColor.name)
        .put("aodShift", aodShift).put("aodTimeoutMin", aodTimeoutMin)
        .toString()

    companion object {
        const val MIN_BATTERY_LOW = 5
        const val MIN_BATTERY_HIGH = 80
        val SCALES = listOf(0.6f, 0.8f, 1f, 1.25f, 1.5f, 2f)
        val BRIGHTNESS = listOf(1, 2, 5, 10, 20, 40)
        val TIMEOUTS = listOf(0, 1, 5, 15, 30, 60)

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
                aodPosition = enumOr(o.optString("aodPosition"), d.aodPosition),
                aodColor = enumOr(o.optString("aodColor"), d.aodColor),
                aodShift = o.optBoolean("aodShift", d.aodShift),
                aodTimeoutMin = o.optInt("aodTimeoutMin", d.aodTimeoutMin).coerceIn(0, 24 * 60),
            )
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
            runCatching { enumValueOf<E>(name) }.getOrDefault(default)
    }
}
