package com.asnidev.sysreadout.update

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.asnidev.sysreadout.BuildConfig
import com.asnidev.sysreadout.MainActivity
import com.asnidev.sysreadout.monitor.ShellExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the fork's latest release once a day while the launcher runs. A newer
 * version posts one notification; tapping it downloads the APK and opens
 * Android's installer, which asks before replacing anything. Off in settings
 * › about › check for updates.
 */
class UpdateChecker(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Android 13+ drops the update notification without this permission; the about page asks for it. */
    fun canNotify(): Boolean = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** The release seen at the last check, if it's newer than what's installed. */
    val available: Release?
        get() = prefs.getString(KEY_RELEASE, null)?.let(Release::parse)?.takeIf { Updates.isNewer(it, BuildConfig.VERSION_CODE) }

    val lastCheck: Long get() = prefs.getLong(KEY_LAST, 0L)

    /** Called when the launcher comes to the front; does nothing until a check is due. */
    suspend fun maybeCheck() {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (!Updates.due(now, lastCheck, prefs.getBoolean(KEY_OK, true))) return
        check()
    }

    /** Checks now; the result, or null when the check failed. */
    suspend fun check(): Release? {
        val now = System.currentTimeMillis()
        val release = withContext(Dispatchers.IO) { fetch() }
        prefs.edit().putLong(KEY_LAST, now).putBoolean(KEY_OK, release != null).apply()
        if (release == null) return null
        prefs.edit().putString(KEY_RELEASE, release.toJson()).apply()
        if (Updates.shouldNotify(release, BuildConfig.VERSION_CODE, prefs.getInt(KEY_NOTIFIED, 0))) {
            notify(release)
            prefs.edit().putInt(KEY_NOTIFIED, release.versionCode).apply()
        }
        return release
    }

    private fun fetch(): Release? = runCatching {
        val conn = URL(Updates.VERSION_URL).openConnection() as HttpURLConnection
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
    }.onFailure { Log.i(TAG, "update check failed: $it") }.getOrNull()

    private fun notify(r: Release) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A new DroidTTY version is out."
            },
        )
        // Opens the launcher, which starts the download: the install-permission screen can only open from the front.
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).setAction(ACTION_DOWNLOAD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DroidTTY ${r.versionName} is out")
            .setContentText(r.notes.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "tap to download and install")
            .setStyle(android.app.Notification.BigTextStyle().bigText((r.notes + "\n\ntap to download and install").trim()))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    /**
     * Downloads the APK with Android's download manager into the app's own folder,
     * then opens the installer. Without "install unknown apps" for DroidTTY, opens
     * that setting first; the next tap continues.
     */
    fun download(): Boolean {
        val r = available ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
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

    /** The download manager finished: open the installer for our download, if it worked. */
    fun downloaded(id: Long) {
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
        runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "couldn't open the installer", it) }
    }

    private fun Release.toJson(): String = org.json.JSONObject()
        .put("versionCode", versionCode).put("versionName", versionName).put("apk", apkUrl).put("notes", notes).toString()

    companion object {
        private const val TAG = "Updates"
        private const val PREFS = "updates"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST = "last_check"
        private const val KEY_OK = "last_ok"
        private const val KEY_RELEASE = "release"
        private const val KEY_NOTIFIED = "notified_code"
        private const val KEY_DOWNLOAD = "download_id"
        private const val KEY_FILE = "download_file"
        private const val KEY_DONE = "download_done"
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 77
        private const val MAX_BYTES = 16 * 1024
        private const val APK_MIME = "application/vnd.android.package-archive"
        const val ACTION_DOWNLOAD = "space.oiupoyt.droidtty.UPDATE_DOWNLOAD"

        /** The download-complete broadcast comes from the system; registered while the launcher runs. */
        fun register(context: Context, checker: UpdateChecker): BroadcastReceiver {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    checker.downloaded(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L))
                }
            }
            ContextCompat.registerReceiver(context, r, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
            return r
        }
    }
}
