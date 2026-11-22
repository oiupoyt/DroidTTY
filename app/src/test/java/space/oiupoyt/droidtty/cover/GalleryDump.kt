package space.oiupoyt.droidtty.cover

import space.oiupoyt.droidtty.data.AodLook
import space.oiupoyt.droidtty.data.LockPreset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Writes every always-on look and lock preset, frame by frame, to build/gallery
 * as JSON, from the same code the phone runs. tools/gallery.py draws them into
 * the gallery's images. Runs with the other unit tests; writes nothing else.
 */
class GalleryDump {

    /** A sample phone, so every line that needs a value has one; real phones fill in their own. */
    private val sample = CoverData(
        now = ZonedDateTime.of(2026, 11, 20, 21, 42, 7, 0, ZoneOffset.UTC), is24h = true,
        battery = 76, charging = false, manufacturer = "Nothing", model = "A065", device = "Pacman",
        android = "16", api = 36, patch = "2026-10-01", kernel = "6.1.99-android14", soc = "MT6886", cores = 8,
        memTotalMb = 7_680, memAvailMb = 3_150, storageTotalGb = 238.0, storageFreeGb = 91.5, net = "wifi",
        notifications = 3, uptimeMs = (2 * 24 * 60 + 5 * 60 + 17) * 60_000L, packages = 412, display = "1080x2412 120Hz",
        coreLoad = listOf(0.42, 0.18, 0.66, 0.09, 0.3, 0.12, 0.51, 0.25),
        events = listOf("21:31 net   cell → wifi", "21:35 fg    Firefox", "21:38 ntf   Signal", "21:40 bat   76%"),
        rows = listOf("bat   76%  discharging  31.2°C  good", "net   wifi  ↓1.2M/s ↑48K/s", "mem   3.1G / 7.5G available"),
        lastUnlock = ZonedDateTime.of(2026, 11, 20, 21, 2, 0, 0, ZoneOffset.UTC),
    )

    private fun lineJson(l: CoverLine) = JSONObject()
        .put("text", l.text).put("role", l.role.name).put("align", l.align.name)
        .put("mark", l.mark).put("cursor", l.cursor)

    private fun frameJson(f: CoverFrame) = JSONObject()
        .put("centered", f.centered).put("tail", f.tail)
        .put("lines", JSONArray(f.lines.map(::lineJson)))

    @Test
    fun dump() {
        val out = File("build/gallery").apply { mkdirs() }
        val looks = JSONObject()
        AodLook.entries.forEach { look ->
            val frames = if (look.animated) {
                // Two seconds of animation at the look's own rate, for a looping GIF.
                val step = AodFaces.refreshMs(look)
                (0 until (2_000 / step).toInt()).map { AodFaces.frame(look, sample, 40_000 + it * step) }
            } else {
                listOf(AodFaces.frame(look, sample, 40_000))
            }
            looks.put(look.name, JSONObject().put("ms", AodFaces.refreshMs(look).coerceAtMost(1_000)).put("frames", JSONArray(frames.map(::frameJson))))
        }
        val presets = JSONObject()
        LockPreset.entries.forEach { p ->
            val s = LockPresets.script(p, sample)
            // One frame every 120 ms through the animation, then the final screen held.
            val times = (0..s.readyAt step 120L).toList() + List(12) { s.readyAt }
            presets.put(p.name, JSONObject().put("ms", 120).put("frames", JSONArray(times.map { frameJson(s.at(it)) })))
        }
        File(out, "frames.json").writeText(JSONObject().put("looks", looks).put("presets", presets).toString())
    }
}
