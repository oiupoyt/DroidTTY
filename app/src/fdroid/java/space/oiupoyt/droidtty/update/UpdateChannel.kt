package space.oiupoyt.droidtty.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri

/**
 * The F-Droid build: F-Droid builds and installs the updates, so this only says one
 * is out. It follows tagged releases, the only ones F-Droid builds, and tapping opens
 * DroidTTY in the F-Droid app.
 */
class UpdateChannel(private val context: Context, @Suppress("UNUSED_PARAMETER") prefs: SharedPreferences) {

    val sourceName = "github"
    val tapAction = "tap to open it in f-droid; f-droid usually builds it within a few days"
    val actionLabel = "open in f-droid ›"
    val about =
        "once a day, droidtty reads one small file from its github releases to see if a new version has been " +
            "released. nothing about you or your phone is sent. a new version shows a notification; f-droid " +
            "installs it once it has built it, usually within a few days."

    fun latest(): Release? = Updates.fetch(Updates.TAGGED_VERSION_URL)

    /** Opens DroidTTY's page in F-Droid, or another store that handles the link, or the releases page. */
    fun act(@Suppress("UNUSED_PARAMETER") r: Release): Boolean {
        val details = Uri.parse("market://details?id=${context.packageName}")
        val tries = listOf(
            Intent(Intent.ACTION_VIEW, details).setPackage(FDROID),
            Intent(Intent.ACTION_VIEW, details),
            Intent(Intent.ACTION_VIEW, Uri.parse(Updates.RELEASES_PAGE)),
        )
        return tries.any { i -> runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
    }

    fun register(): BroadcastReceiver? = null

    private companion object {
        const val FDROID = "org.fdroid.fdroid"
    }
}
