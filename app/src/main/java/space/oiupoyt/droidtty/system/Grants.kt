package space.oiupoyt.droidtty.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import space.oiupoyt.droidtty.monitor.NotifListener

/**
 * Opens the most direct screen Android has for each kind of access DroidTTY asks
 * for, so the user lands on DroidTTY's own switch rather than a long list. Each
 * falls back to the general screen where a phone lacks the direct one.
 */
object Grants {

    /** The highlight Android's settings use to scroll to one entry and flash it. */
    private const val FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    /** Accessibility list scrolled to DroidTTY's lock service (the details page itself is system-only). */
    fun lockService(context: Context): Boolean {
        val component = ComponentName(context, LockService::class.java).flattenToString()
        val highlighted = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(FRAGMENT_ARG_KEY, component)
            .putExtra(SHOW_FRAGMENT_ARGS, Bundle().apply { putString(FRAGMENT_ARG_KEY, component) })
        return SystemActions.open(context, highlighted) || SystemActions.openAccessibilitySettings(context)
    }

    /** DroidTTY's own notification-access switch (Android 11+), else the list. */
    fun notificationAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, NotifListener::class.java).flattenToString())
            if (SystemActions.open(context, detail)) return true
        }
        return SystemActions.openNotificationAccess(context)
    }

    /** DroidTTY's usage-access switch where the phone supports the package link, else the list. */
    fun usageAccess(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
        return SystemActions.open(context, direct) || SystemActions.open(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    /** DroidTTY's "display over other apps" switch. */
    fun overlay(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        return SystemActions.open(context, direct) || SystemActions.open(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
    }

    /** DroidTTY's "install unknown apps" switch, for updates. */
    fun installUpdates(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        return SystemActions.open(context, direct)
    }

    /** DroidTTY's notification settings (posting notifications at all). */
    fun notifications(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        return SystemActions.open(context, direct) || appInfo(context)
    }

    /** DroidTTY's app info: battery "unrestricted", and "allow restricted settings" in its menu. */
    fun appInfo(context: Context): Boolean =
        SystemActions.open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    /** Android's "default home app" picker. */
    fun homeApp(context: Context): Boolean = SystemActions.openHomeSettings(context)
}
