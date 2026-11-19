package com.asnidev.sysreadout.data

import org.json.JSONArray
import org.json.JSONObject

/** When SysReadout's own always-on display comes up after the screen turns off. */
enum class AodMode { OFF, CHARGING, ALWAYS }

enum class AodLook { CLOCK, STATUS, PROMPT, LOG }

enum class LockPreset { BOOTLINE, BIOS, DOS, LOGIN, GRUB }

/** Always-on display and lock-screen cover; both need the screen service. */
data class CoverPrefs(
    val aodMode: AodMode = AodMode.OFF,
    val aodLook: AodLook = AodLook.CLOCK,
    /** Status rows for the terminal-status look (ids from ProbeCatalog). */
    val aodRows: List<String> = listOf("bat", "net", "mem"),
    /** On battery, the always-on display waits for the charger below this level. */
    val aodMinBattery: Int = 20,
    val lockCover: Boolean = false,
    val lockPreset: LockPreset = LockPreset.BOOTLINE,
) {
    val needsService: Boolean get() = aodMode != AodMode.OFF || lockCover

    fun toJson(): String = JSONObject()
        .put("aodMode", aodMode.name).put("aodLook", aodLook.name)
        .put("aodRows", JSONArray(aodRows)).put("aodMinBattery", aodMinBattery)
        .put("lockCover", lockCover).put("lockPreset", lockPreset.name)
        .toString()

    companion object {
        const val MIN_BATTERY_LOW = 5
        const val MIN_BATTERY_HIGH = 80

        fun fromJson(s: String?): CoverPrefs {
            val d = CoverPrefs()
            val o = runCatching { JSONObject(s ?: return d) }.getOrElse { return d }
            val rows = o.optJSONArray("aodRows")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
            return CoverPrefs(
                aodMode = enumOr(o.optString("aodMode"), d.aodMode),
                aodLook = enumOr(o.optString("aodLook"), d.aodLook),
                aodRows = rows ?: d.aodRows,
                aodMinBattery = o.optInt("aodMinBattery", d.aodMinBattery).coerceIn(MIN_BATTERY_LOW, MIN_BATTERY_HIGH),
                lockCover = o.optBoolean("lockCover", d.lockCover),
                lockPreset = enumOr(o.optString("lockPreset"), d.lockPreset),
            )
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
            runCatching { enumValueOf<E>(name) }.getOrDefault(default)
    }
}
