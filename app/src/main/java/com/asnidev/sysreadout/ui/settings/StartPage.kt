package com.asnidev.sysreadout.ui.settings

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.asnidev.sysreadout.LauncherViewModel
import com.asnidev.sysreadout.cover.CoverHost
import com.asnidev.sysreadout.data.AodMode
import com.asnidev.sysreadout.data.Setup
import com.asnidev.sysreadout.data.Setups
import com.asnidev.sysreadout.monitor.NotifLog
import com.asnidev.sysreadout.system.Grants
import com.asnidev.sysreadout.system.SystemActions
import com.asnidev.sysreadout.ui.Palette
import com.asnidev.sysreadout.ui.Type

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
    val steps = remember(resumed) { steps(context, vm) }
    val done = steps.count { it.done }

    Section("access · $done of ${steps.size}")
    Note("each opens the exact android switch. come back here and the list updates.")
    steps.forEach { s ->
        SettingRow((if (s.done) "[x] " else "[ ] ") + s.title, if (s.done) "on" else "turn on ›") { if (!s.done) s.go(context) }
        Note("    " + s.why)
    }

    SetupList(vm)

    Section("next")
    Link("fonts, colours and crt", "›") { open(Page.Appearance) }
    Link("always-on display, lock screen, shade", "›") { open(Page.Cover) }
    Link("what the log shows", "›") { open(Page.Log) }
}

private fun steps(context: Context, vm: LauncherViewModel): List<Step> = listOf(
    Step("home app", "droidtty as your home screen", SystemActions.isDefaultLauncher(context)) { Grants.homeApp(it) },
    Step(
        "lock service",
        "always-on display, lock screens, the shade and double-tap to lock",
        CoverHost.available,
    ) { Grants.lockService(it) },
    Step("notification access", "notifications in the shade and log, what's playing", NotifLog.connected) { Grants.notificationAccess(it) },
    Step("usage access", "screen time, app switches, data used", vm.engine.usage.hasAccess()) { Grants.usageAccess(it) },
    Step(
        "unrestricted battery",
        "so android doesn't stop the always-on display or lock in the background",
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
    ) { Grants.appInfo(it) },
)

/** The ready-made setups, each a theme with the always-on look and lock preset that suit it. */
@Composable
fun SetupList(vm: LauncherViewModel) {
    val theme by vm.theme.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val cover by vm.cover.collectAsState()
    Section("ready-made setups")
    Note("one tap sets the look, the always-on display and the lock screen together. your apps, pin and access stay as they are.")
    Setups.all.forEach { s ->
        val on = s.isApplied(theme, prefs, cover)
        Column(Modifier.fillMaxWidth().clickable { apply(vm, s) }.padding(vertical = 8.dp)) {
            Text((if (on) "> " else "  ") + s.name, style = Type.row, color = if (on) Palette.log else Palette.fg)
            Text("    " + s.summary, style = Type.small, color = Palette.dim)
        }
    }
    if (cover.aodMode == AodMode.OFF) {
        Note("the always-on display is off: switch it on under always-on display, lock screen, shade.")
    }
}

private fun apply(vm: LauncherViewModel, s: Setup) {
    vm.updateTheme { s.theme }
    vm.update(s::applyTo)
    vm.updateCover(s::applyTo)
}
