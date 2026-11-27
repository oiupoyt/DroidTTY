package space.oiupoyt.droidtty.update

import android.util.Log
import org.json.JSONObject
import space.oiupoyt.droidtty.BuildConfig
import space.oiupoyt.droidtty.monitor.ShellExec
import java.net.HttpURLConnection
import java.net.URL

/**
 * A newer version. [versionName], [apkUrl] and [notes] are empty when the source doesn't
 * give them (Google Play only says which version code is available).
 */
data class Release(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String) {

    fun toJson(): String = JSONObject()
        .put("versionCode", versionCode).put("versionName", versionName).put("apk", apkUrl).put("notes", notes).toString()

    companion object {
        /**
         * Null for anything that isn't a well-formed release description. With [requireApk]
         * (anything read from the network) the APK must come from this project's own releases.
         */
        fun parse(json: String, requireApk: Boolean = true): Release? = runCatching {
            val o = JSONObject(json)
            val url = o.optString("apk")
            if (requireApk && !trusted(url)) return null
            Release(o.getInt("versionCode"), o.optString("versionName"), url, o.optString("notes"))
        }.getOrNull()

        /** Only an APK from this project's own releases is ever downloaded. */
        fun trusted(url: String): Boolean = TRUSTED.any { url.startsWith(it) } && url.endsWith(".apk")

        val TRUSTED = listOf(
            "https://github.com/oiupoyt/DroidTTY/releases/download/",
            "https://git.oiupoyt.space/oiupoyt/DroidTTY/releases/download/",
        )
    }
}

object Updates {
    /** The rolling build of main: what the sideloaded build follows. */
    const val VERSION_URL = "https://github.com/oiupoyt/DroidTTY/releases/download/latest/version.json"

    /** The newest tagged release only (GitHub's "latest" non-pre-release): what F-Droid builds. */
    const val TAGGED_VERSION_URL = "https://github.com/oiupoyt/DroidTTY/releases/latest/download/version.json"

    const val RELEASES_PAGE = "https://github.com/oiupoyt/DroidTTY/releases"

    /** One check a day is plenty; a failed one tries again after a few hours. */
    const val INTERVAL_MS = 24 * 60 * 60_000L
    const val RETRY_MS = 4 * 60 * 60_000L

    fun isNewer(release: Release, installed: Int): Boolean = release.versionCode > installed

    /** Time for a check, given the last one and whether it worked. */
    fun due(now: Long, lastCheck: Long, lastOk: Boolean): Boolean =
        now - lastCheck >= if (lastOk) INTERVAL_MS else RETRY_MS

    /** Notify about a version once: not again for the same one, even if it stays the newest. */
    fun shouldNotify(release: Release, installed: Int, alreadyNotified: Int): Boolean =
        isNewer(release, installed) && release.versionCode > alreadyNotified

    /** The notification's title; Play gives no version name, so then it names none. */
    fun title(r: Release): String = if (r.versionName.isBlank()) "a DroidTTY update is out" else "DroidTTY ${r.versionName} is out"

    /** Reads a version.json from the project's releases; null on any failure. Runs on the caller's thread. */
    fun fetch(url: String): Release? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "DroidTTY/${BuildConfig.VERSION_NAME}")
        try {
            if (conn.responseCode != 200) return@runCatching null
            // A version file is a few hundred bytes; reading stops well past that.
            val text = conn.inputStream.bufferedReader().use { r -> ShellExec.readCapped(r, MAX_BYTES) }
            Release.parse(text)
        } finally {
            conn.disconnect()
        }
    }.onFailure { Log.i("Updates", "update check failed: $it") }.getOrNull()

    private const val MAX_BYTES = 16 * 1024
}
