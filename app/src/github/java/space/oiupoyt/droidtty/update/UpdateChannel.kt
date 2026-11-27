package space.oiupoyt.droidtty.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * The sideloaded build (GitHub and Forgejo releases): follows the rolling build of
 * main, and installs its own updates. Tapping downloads the APK with Android's
 * download manager and opens Android's installer, which asks before replacing anything.
 */
class UpdateChannel(private val context: Context, private val prefs: SharedPreferences) {

    val sourceName = "github"
    val tapAction = "tap to download and install"
    val actionLabel = "download ›"
    val about =
        "once a day, droidtty reads one small file from its github releases to see if there's a newer version. " +
            "nothing about you or your phone is sent. a new version shows a notification; tapping it downloads the " +
            "apk and android's installer asks before replacing anything."

    fun latest(): Release? = Updates.fetch(Updates.VERSION_URL)

    /**
     * Downloads [r] into the app's own folder, then opens the installer. Without
     * "install unknown apps" for DroidTTY, opens that setting first; the next tap continues.
     */
    fun act(r: Release): Boolean {
        if (!Release.trusted(r.apkUrl)) return false
        if (!context.packageManager.canRequestPackageInstalls()) {
            val ask = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.startActivity(ask) }.isSuccess
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
        val file = File(dir, "DroidTTY-${r.versionName}.apk")
        // Only a download the manager reported finished is reused; anything else starts over.
        val done = prefs.getString(KEY_DONE, null) == file.path
        dir.listFiles()?.filter { it.name.endsWith(".apk") && !(done && it == file) }?.forEach { it.delete() }
        if (done && file.exists() && file.length() > 0) {
            install(file)
            return true
        }
        val dm = context.getSystemService(DownloadManager::class.java)
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(r.apkUrl))
                .setTitle("DroidTTY ${r.versionName}")
                .setDescription("update")
                .setMimeType(APK_MIME)
                // Its "complete" notification opens the APK if the installer couldn't open by itself.
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(file)),
        )
        prefs.edit().putLong(KEY_DOWNLOAD, id).putString(KEY_FILE, file.path).remove(KEY_DONE).apply()
        return true
    }

    /** The download-complete broadcast comes from the system; registered while the launcher runs. */
    fun register(): BroadcastReceiver {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                downloaded(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L))
            }
        }
        ContextCompat.registerReceiver(context, r, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        return r
    }

    /** The download manager finished: open the installer for our download, if it worked. */
    private fun downloaded(id: Long) {
        if (id != prefs.getLong(KEY_DOWNLOAD, -1L)) return
        val dm = context.getSystemService(DownloadManager::class.java)
        val ok = dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        } == true
        val file = prefs.getString(KEY_FILE, null)?.let(::File)
        if (ok && file != null && file.exists()) {
            prefs.edit().putString(KEY_DONE, file.path).apply()
            install(file)
        } else {
            file?.delete()
        }
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { Log.w("Updates", "couldn't open the installer", it) }
    }

    private companion object {
        const val KEY_DOWNLOAD = "download_id"
        const val KEY_FILE = "download_file"
        const val KEY_DONE = "download_done"
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
