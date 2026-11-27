package space.oiupoyt.droidtty.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.oiupoyt.droidtty.BuildConfig
import space.oiupoyt.droidtty.MainActivity

/**
 * Checks for a newer version once a day while the launcher runs, and posts one
 * notification per new version. Where it looks and what tapping does depend on
 * the build ([UpdateChannel]): the sideloaded build downloads and installs, the
 * F-Droid and Play builds open their store. Off in settings › about.
 */
class UpdateChecker(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val channel = UpdateChannel(context, prefs)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Android 13+ drops the update notification without this permission; the about page asks for it. */
    fun canNotify(): Boolean = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** The release seen at the last check, if it's newer than what's installed. */
    val available: Release?
        get() = prefs.getString(KEY_RELEASE, null)
            ?.let { Release.parse(it, requireApk = false) }
            ?.takeIf { Updates.isNewer(it, BuildConfig.VERSION_CODE) }

    val lastCheck: Long get() = prefs.getLong(KEY_LAST, 0L)

    /** Called when the launcher comes to the front; does nothing until a check is due. */
    suspend fun maybeCheck() {
        if (!enabled) return
        if (!Updates.due(System.currentTimeMillis(), lastCheck, prefs.getBoolean(KEY_OK, true))) return
        check()
    }

    /** Checks now; the newest release the source knows of, or null when the check failed. */
    suspend fun check(): Release? {
        val now = System.currentTimeMillis()
        val release = withContext(Dispatchers.IO) { channel.latest() }
        prefs.edit().putLong(KEY_LAST, now).putBoolean(KEY_OK, release != null).apply()
        if (release == null) return null
        prefs.edit().putString(KEY_RELEASE, release.toJson()).apply()
        if (Updates.shouldNotify(release, BuildConfig.VERSION_CODE, prefs.getInt(KEY_NOTIFIED, 0))) {
            notify(release)
            prefs.edit().putInt(KEY_NOTIFIED, release.versionCode).apply()
        }
        return release
    }

    /** What tapping the notification or the about page's link does; false when there's nothing to do. */
    fun act(): Boolean = available?.let(channel::act) ?: false

    private fun notify(r: Release) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A new DroidTTY version is out."
            },
        )
        // Through the launcher: the install-permission screen and the stores open from the front.
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).setAction(ACTION_UPDATE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val first = r.notes.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(Updates.title(r))
            .setContentText(first ?: channel.tapAction)
            .setStyle(android.app.Notification.BigTextStyle().bigText((r.notes + "\n\n" + channel.tapAction).trim()))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    companion object {
        private const val PREFS = "updates"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST = "last_check"
        private const val KEY_OK = "last_ok"
        private const val KEY_RELEASE = "release"
        private const val KEY_NOTIFIED = "notified_code"
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 77
        const val ACTION_UPDATE = "space.oiupoyt.droidtty.UPDATE"

        /** Registered while the launcher runs; only the sideloaded build listens for anything. */
        fun register(checker: UpdateChecker): BroadcastReceiver? = checker.channel.register()
    }
}
