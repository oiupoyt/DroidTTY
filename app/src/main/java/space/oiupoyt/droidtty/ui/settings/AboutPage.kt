package space.oiupoyt.droidtty.ui.settings

import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Build
import android.Manifest
import space.oiupoyt.droidtty.update.Updates
import space.oiupoyt.droidtty.update.UpdateChecker
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
import space.oiupoyt.droidtty.BuildConfig
import space.oiupoyt.droidtty.system.CrashGuard
import space.oiupoyt.droidtty.system.SystemActions
import space.oiupoyt.droidtty.ui.FontOption
import space.oiupoyt.droidtty.ui.Fonts
import space.oiupoyt.droidtty.ui.Type

private const val REPO = "https://github.com/oiupoyt/DroidTTY"
private const val PRIVACY = "https://droidtty.oiupoyt.space/privacy"

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
    Link("privacy policy", "›") { SystemActions.openUrl(context, PRIVACY) }

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

/** The update check: on by default, once a day; what a new version's link does depends on the build. */
@Composable
private fun UpdatesSection(updates: UpdateChecker) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(updates.enabled) }
    var available by remember { mutableStateOf(updates.available) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    Section("updates")
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Toggle("check for updates", enabled) { v ->
        updates.enabled = v
        enabled = v
        if (v && !updates.canNotify() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if (enabled && !updates.canNotify()) {
        Note("notifications are off for droidtty, so a new version only shows here.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Link("allow notifications", "›") { askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
    Note(updates.channel.about)
    val r = available
    if (r != null) {
        Link(if (r.versionName.isBlank()) "update droidtty" else "update to ${r.versionName}", updates.channel.actionLabel) {
            if (!updates.act()) status = "couldn't open the update"
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
                found == null -> "couldn't reach ${updates.channel.sourceName}; try again later"
                updates.available == null -> "you have the newest version (${BuildConfig.VERSION_NAME})"
                else -> Updates.title(found)
            }
        }
    }
    status?.let { Note(it) }
    if (updates.lastCheck > 0) {
        Note("last checked " + DateUtils.getRelativeTimeSpanString(updates.lastCheck, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
    }
    Link("all releases", "›") { SystemActions.openUrl(context, Updates.RELEASES_PAGE) }
}
