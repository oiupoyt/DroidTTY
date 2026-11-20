package com.asnidev.sysreadout.ui.settings

import com.asnidev.sysreadout.update.Updates
import com.asnidev.sysreadout.update.UpdateChecker
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.text.format.DateUtils
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.asnidev.sysreadout.BuildConfig
import com.asnidev.sysreadout.system.CrashGuard
import com.asnidev.sysreadout.system.SystemActions
import com.asnidev.sysreadout.ui.FontOption
import com.asnidev.sysreadout.ui.Fonts
import com.asnidev.sysreadout.ui.Type

private const val REPO = "https://github.com/oiupoyt/DroidTTY"

@Composable
fun AboutPage(updates: UpdateChecker? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showing by remember { mutableStateOf<FontOption?>(null) }
    var report by remember { mutableStateOf(CrashGuard.report(context)) }
    var reading by remember { mutableStateOf(false) }

    Section("droidtty launcher")
    Note("v${BuildConfig.VERSION_NAME} · free software under the GNU GPL v3")
    Link("source code and issues", "github ›") { SystemActions.openUrl(context, REPO) }

    if (updates != null) UpdatesSection(updates)

    report?.let { text ->
        Section("last crash")
        Note(text.lineSequence().take(2).joinToString("\n"))
        Link("read the report", "›") { reading = true }
        Link("copy it", "›") {
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, "crash report copied", Toast.LENGTH_SHORT).show()
        }
        Link("share it", "›") {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "DroidTTY crash report")
                .putExtra(Intent.EXTRA_TEXT, text)
            SystemActions.open(context, Intent.createChooser(send, "share the crash report"))
        }
        Link("delete it", "›") {
            CrashGuard.clearReport(context)
            report = null
        }
        Note("only the last crash is kept, on this phone. nothing is sent unless you share it.")
    }

    Section("bundled fonts")
    Note("tap for the license text.")
    Fonts.bundled.filter { it.licenseFile != null }.forEach { font ->
        Link(font.name, font.license ?: "") { showing = font }
    }

    if (reading) {
        report?.let { TextDialog("last crash", it) { reading = false } }
    }
    showing?.let { font ->
        val text = remember(font) {
            runCatching { context.assets.open("licenses/${font.licenseFile}").bufferedReader().use { it.readText() } }
                .getOrDefault("license text missing")
        }
        TextDialog(font.name, text) { showing = null }
    }
}

@Composable
private fun TextDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Type.row) },
        text = {
            Text(text, style = Type.small, modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()))
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("close", style = Type.row) } },
    )
}

/** The update check: on by default, once a day; a newer version downloads and opens Android's installer. */
@Composable
private fun UpdatesSection(updates: UpdateChecker) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(updates.enabled) }
    var available by remember { mutableStateOf(updates.available) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    Section("updates")
    Toggle("check for updates", enabled) { v ->
        updates.enabled = v
        enabled = v
    }
    Note(
        "once a day, droidtty reads one small file from its github releases to see if there's a newer version. " +
            "nothing about you or your phone is sent. a new version shows a notification; tapping it downloads the " +
            "apk and android's installer asks before replacing anything.",
    )
    val r = available
    if (r != null) {
        Link("install ${r.versionName}", "download ›") {
            if (!updates.download()) status = "couldn't start the download"
        }
        if (r.notes.isNotBlank()) Note(r.notes)
    }
    Link(if (checking) "checking…" else "check now", "›") {
        if (checking) return@Link
        checking = true
        scope.launch {
            val found = updates.check()
            checking = false
            available = updates.available
            status = when {
                found == null -> "couldn't reach github; try again later"
                updates.available == null -> "you have the newest version (${BuildConfig.VERSION_NAME})"
                else -> "${found.versionName} is out"
            }
        }
    }
    status?.let { Note(it) }
    if (updates.lastCheck > 0) {
        Note("last checked " + DateUtils.getRelativeTimeSpanString(updates.lastCheck, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
    }
    Link("all releases", "›") { SystemActions.openUrl(context, Updates.RELEASES_PAGE) }
}
