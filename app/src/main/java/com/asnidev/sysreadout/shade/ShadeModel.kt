package com.asnidev.sysreadout.shade

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification

/** One notification as SysReadout's shade shows it. */
data class ShadeItem(
    val key: String,
    val pkg: String,
    val time: Long,
    val title: String?,
    val text: String?,
    val ongoing: Boolean,
    val clearable: Boolean,
    val open: PendingIntent?,
    val actions: List<Pair<String, PendingIntent>>,
)

/** Notifications grouped by app, newest group first; group summaries and empty ones left out. */
data class ShadeGroup(val pkg: String, val items: List<ShadeItem>)

object ShadeModel {

    fun item(sbn: StatusBarNotification): ShadeItem? {
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        // Reading another app's extras can throw on a class only that app has.
        val extras = runCatching { n.extras }.getOrNull()
        val title = runCatching { extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() }.getOrNull()
        val text = runCatching {
            (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        }.getOrNull()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return null
        val actions = n.actions.orEmpty()
            // Inline replies need a text field SysReadout's shade doesn't have.
            .filter { it.remoteInputs.isNullOrEmpty() && it.actionIntent != null && !it.title.isNullOrBlank() }
            .take(3)
            .map { it.title.toString() to it.actionIntent }
        return ShadeItem(
            key = sbn.key,
            pkg = sbn.packageName,
            time = sbn.postTime,
            title = title?.takeIf { it.isNotBlank() },
            text = text?.takeIf { it.isNotBlank() }?.replace('\n', ' '),
            ongoing = sbn.isOngoing,
            clearable = sbn.isClearable,
            open = n.contentIntent,
            actions = actions,
        )
    }

    fun group(items: List<ShadeItem>): List<ShadeGroup> =
        items.groupBy { it.pkg }
            .map { (pkg, list) -> ShadeGroup(pkg, list.sortedByDescending { it.time }) }
            .sortedByDescending { g -> g.items.first().time }
}
