package space.oiupoyt.droidtty.ui.settings

import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import space.oiupoyt.droidtty.LauncherViewModel
import space.oiupoyt.droidtty.cover.CoverHost
import space.oiupoyt.droidtty.data.Presets
import space.oiupoyt.droidtty.monitor.NotifLog
import space.oiupoyt.droidtty.system.Grants
import space.oiupoyt.droidtty.system.SystemActions

/** One thing to switch on: whether it is, and the screen that switches it. */
private data class Step(val title: String, val why: String, val done: Boolean, val go: (Context) -> Unit)

/**
 * Settings › get started: the access DroidTTY's features need, each with its state
 * and one tap to the exact switch, and the ready-made setups. Steps re-check when you
 * come back from Android's settings.
 */
@Composable
fun StartPage(vm: LauncherViewModel, open: (Page) -> Unit) {
    val context = LocalContext.current
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose {}
    }
    val requestLock = rememberLockServiceRequest()
    val steps = remember(resumed) { steps(context, vm, requestLock) }
    val done = steps.count { it.done }

    Section("access · $done of ${steps.size}")
    Note("each opens the exact android switch. come back here and the list updates.")
    steps.forEach { s ->
        SettingRow((if (s.done) "[x] " else "[ ] ") + s.title, if (s.done) "on" else "turn on ›") { if (!s.done) s.go(context) }
        Note("    " + s.why)
    }

    Section("pick a look")
    Note("appearance has ready-made presets: each sets colours and fonts, and picks an always-on look and lock screen to match.")
    Link("presets", "${Presets.builtIn.size} to choose from ›") { open(Page.Appearance) }

    Section("next")
    Link("always-on display, lock screen, shade", "›") { open(Page.Cover) }
    Link("what the log shows", "›") { open(Page.Log) }
}

private fun steps(context: Context, vm: LauncherViewModel, requestLock: () -> Unit): List<Step> = listOf(
    Step("home app", "droidtty as your home screen", SystemActions.isDefaultLauncher(context)) { Grants.homeApp(it) },
    Step(
        "lock service",
        "always-on display, lock screens, the shade and double-tap to lock",
        CoverHost.available,
    ) { requestLock() },
    Step("notification access", "notifications in the shade and log, what's playing", NotifLog.connected) { Grants.notificationAccess(it) },
    Step("usage access", "screen time, app switches, data used", vm.engine.usage.hasAccess()) { Grants.usageAccess(it) },
    Step(
        "unrestricted battery",
        "so android doesn't stop the always-on display or lock in the background",
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
    ) { Grants.appInfo(it) },
)
