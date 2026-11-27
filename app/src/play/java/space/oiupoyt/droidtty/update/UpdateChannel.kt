package space.oiupoyt.droidtty.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import kotlinx.coroutines.runBlocking
import space.oiupoyt.droidtty.BuildConfig

/**
 * The Google Play build: Play installs the updates. It asks Play itself whether a newer
 * version is available (so it never names one Play doesn't have yet), and tapping
 * opens DroidTTY's Play listing. Nothing is downloaded or installed by the app.
 */
class UpdateChannel(private val context: Context, @Suppress("UNUSED_PARAMETER") prefs: SharedPreferences) {

    val sourceName = "google play"
    val tapAction = "tap to update on google play"
    val actionLabel = "open google play ›"
    val about =
        "once a day, droidtty asks google play whether a newer version is available. a new version shows a " +
            "notification; tapping it opens droidtty on google play."

    /** Called off the main thread by [UpdateChecker]. */
    fun latest(): Release? = runCatching {
        val info = runBlocking { AppUpdateManagerFactory.create(context).requestAppUpdateInfo() }
        val code = if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) info.availableVersionCode() else BuildConfig.VERSION_CODE
        Release(code, "", "", "")
    }.onFailure { Log.i("Updates", "play update check failed: $it") }.getOrNull()

    fun act(@Suppress("UNUSED_PARAMETER") r: Release): Boolean {
        val tries = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}")).setPackage(PLAY),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")),
        )
        return tries.any { i -> runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
    }

    fun register(): BroadcastReceiver? = null

    private companion object {
        const val PLAY = "com.android.vending"
    }
}
