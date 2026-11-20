package com.asnidev.sysreadout.shade

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.service.notification.StatusBarNotification

/** A notification's inline reply: the action to fire and the field it fills. */
data class Reply(val label: String, val intent: PendingIntent, val input: RemoteInput)

/** One notification as DroidTTY's shade shows it. */
data class ShadeItem(
    val key: String,
    val pkg: String,
    val time: Long,
    val title: String?,
    /** One line for the collapsed row. */
    val text: String?,
    /** Everything: big text or the message lines, for the expanded row. */
    val full: String?,
    val ongoing: Boolean,
    val clearable: Boolean,
    /** Android removes it once it's opened. */
    val autoCancel: Boolean,
    val open: PendingIntent?,
    val actions: List<Pair<String, PendingIntent>>,
    val reply: Reply?,
)

/** Notifications grouped by app, newest group first; group summaries and empty ones left out. */
data class ShadeGroup(val pkg: String, val items: List<ShadeItem>)

object ShadeModel {

    fun item(sbn: StatusBarNotification): ShadeItem? {
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        // Reading another app's extras can throw on a class only that app has.
        val extras = runCatching { n.extras }.getOrNull()
        fun cs(key: String) = runCatching { extras?.getCharSequence(key)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
        val title = cs(Notification.EXTRA_TITLE)
        val text = cs(Notification.EXTRA_TEXT)
        val big = cs(Notification.EXTRA_BIG_TEXT)
        val lines = runCatching { extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") }.getOrNull()
        val messages = runCatching {
            extras?.getParcelableArray(Notification.EXTRA_MESSAGES)?.mapNotNull { m ->
                (m as? android.os.Bundle)?.let { b ->
                    val who = b.getCharSequence("sender")?.toString()
                    val what = b.getCharSequence("text")?.toString() ?: return@let null
                    if (who != null) "$who: $what" else what
                }
            }?.takeIf { it.isNotEmpty() }?.takeLast(MAX_MESSAGES)?.joinToString("\n")
        }.getOrNull()
        if (title == null && text == null && big == null) return null
        val all = n.actions.orEmpty().filter { it.actionIntent != null && !it.title.isNullOrBlank() }
        val replyAction = all.firstOrNull { a -> a.remoteInputs.orEmpty().any { it.allowFreeFormInput } }
        val reply = replyAction?.let { a ->
            Reply(a.title.toString(), a.actionIntent, a.remoteInputs.first { it.allowFreeFormInput })
        }
        val actions = all.filter { it !== replyAction && it.remoteInputs.isNullOrEmpty() }
            .take(3)
            .map { it.title.toString() to it.actionIntent }
        return ShadeItem(
            key = sbn.key,
            pkg = sbn.packageName,
            time = sbn.postTime,
            title = title,
            text = (text ?: big)?.replace('\n', ' '),
            full = messages ?: lines ?: big ?: text,
            ongoing = sbn.isOngoing,
            clearable = sbn.isClearable,
            autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
            open = n.contentIntent,
            actions = actions,
            reply = reply,
        )
    }

    fun group(items: List<ShadeItem>): List<ShadeGroup> =
        items.groupBy { it.pkg }
            .map { (pkg, list) -> ShadeGroup(pkg, list.sortedByDescending { it.time }) }
            .sortedByDescending { g -> g.items.first().time }

    private const val MAX_MESSAGES = 6
}

/** ASCII box drawing for the shade, so every section reads as a terminal panel. */
object Box {
    /** `+-- title -----+` sized to [width] characters. */
    fun top(title: String, width: Int): String {
        val label = if (title.isEmpty()) "" else "-- $title "
        return "+" + label.take(width - 2) + "-".repeat((width - 2 - label.length).coerceAtLeast(0)) + "+"
    }

    fun bottom(width: Int): String = "+" + "-".repeat((width - 2).coerceAtLeast(0)) + "+"

    /** `[=========-------] 60%` */
    fun slider(fraction: Float, cells: Int): String {
        val full = (fraction.coerceIn(0f, 1f) * cells).toInt()
        return "[" + "=".repeat(full) + "-".repeat(cells - full) + "]"
    }
}
