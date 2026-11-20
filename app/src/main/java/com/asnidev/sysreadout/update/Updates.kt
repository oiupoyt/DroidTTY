package com.asnidev.sysreadout.update

import org.json.JSONObject

/** What the release publishes beside the APK, as version.json. */
data class Release(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String) {
    companion object {
        /** Null for anything that isn't a well-formed release description. */
        fun parse(json: String): Release? = runCatching {
            val o = JSONObject(json)
            val url = o.getString("apk")
            // Only an APK from this project's own releases is ever offered.
            if (!TRUSTED.any { url.startsWith(it) } || !url.endsWith(".apk")) return null
            Release(o.getInt("versionCode"), o.getString("versionName"), url, o.optString("notes"))
        }.getOrNull()

        val TRUSTED = listOf(
            "https://github.com/oiupoyt/DroidTTY/releases/download/",
            "https://git.oiupoyt.space/oiupoyt/DroidTTY/releases/download/",
        )
    }
}

object Updates {
    const val VERSION_URL = "https://github.com/oiupoyt/DroidTTY/releases/download/latest/version.json"
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
}
