package com.asnidev.sysreadout.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.asnidev.sysreadout.LauncherViewModel
import com.asnidev.sysreadout.cover.CoverHost
import com.asnidev.sysreadout.data.CoverPrefs
import com.asnidev.sysreadout.data.ShadeSection
import com.asnidev.sysreadout.log.ProbeCatalog
import com.asnidev.sysreadout.shade.Toggle
import com.asnidev.sysreadout.ui.Palette
import com.asnidev.sysreadout.ui.Type

private fun ShadeSection.title() = when (this) {
    ShadeSection.READOUT -> "readout rows"
    ShadeSection.TOGGLES -> "toggles"
    ShadeSection.LEVELS -> "brightness and volume"
    ShadeSection.MEDIA -> "what's playing"
    ShadeSection.NOTIFICATIONS -> "notifications"
}

/** Settings › screen › notification shade: which parts show, in what order, and what's in them. */
@Composable
fun ShadePage(vm: LauncherViewModel) {
    val c by vm.cover.collectAsState()
    fun set(t: (CoverPrefs) -> CoverPrefs) = vm.updateCover(t)

    Section("shade")
    Toggle("droidtty shade", c.shade) { v -> set { it.copy(shade = v) } }
    if (c.shade && !CoverHost.available) {
        Note("the status-bar swipe needs the lock service (settings › screen › access); swiping down on home still opens it.")
    }
    Toggle("close after opening something", c.shadeCloseOnOpen) { v -> set { it.copy(shadeCloseOnOpen = v) } }
    Note(if (c.shadeCloseOnOpen) "opening a notification or a settings panel closes the shade." else "the shade stays open behind what you opened.")
    Toggle("replace android's shade", c.shadeTakeover) { v -> set { it.copy(shadeTakeover = v) } }
    Note(
        if (c.shadeTakeover) "when android's own shade opens anyway (its swipe sometimes wins), droidtty closes it and opens its own."
        else "only the status-bar strip and a swipe down on home open droidtty's shade; android's opens as usual.",
    )
    Toggle("start below the camera hole", c.shadeBelowCutout) { v -> set { it.copy(shadeBelowCutout = v) } }
    Note(
        if (c.shadeBelowCutout) "the shade starts under the front camera's cutout, so no text sits behind it."
        else "the shade uses the whole screen, up to the top edge; text can sit behind the camera.",
    )

    Section("parts")
    Note("top to bottom. tap a name to hide or show it, ↑ ↓ to move it.")
    Ordered(
        all = ShadeSection.entries,
        shown = c.shadeSections,
        title = { it.title() },
        onChange = { v -> set { it.copy(shadeSections = v) } },
    )

    Section("toggles")
    Note("the tiles, left to right. hidden ones stay switchable from android's own shade.")
    Ordered(
        all = Toggle.entries.map { it.name },
        shown = c.shadeToggles,
        title = { n -> Toggle.entries.firstOrNull { it.name == n }?.label ?: n },
        onChange = { v -> set { it.copy(shadeToggles = v) } },
    )
    Link("reset toggles", "›") { set { it.copy(shadeToggles = CoverPrefs.DEFAULT_TOGGLES) } }

    Section("readout rows")
    Note("rows at the top of the shade, from the same list as the log.")
    Ordered(
        all = ProbeCatalog.all.map { it.id }.filter { it in SHADE_ROWS },
        shown = c.shadeRows,
        title = { id -> "$id  " + (ProbeCatalog.byId[id]?.summary ?: "") },
        onChange = { v -> set { it.copy(shadeRows = v) } },
    )
    Link("reset rows", "›") { set { it.copy(shadeRows = CoverPrefs().shadeRows) } }
}

/**
 * A list to pick and order from: the shown ones first in their order with ↑ ↓,
 * then the rest; tapping one moves it between the two.
 */
@Composable
private fun <T> Ordered(all: List<T>, shown: List<T>, title: (T) -> String, onChange: (List<T>) -> Unit) {
    shown.forEachIndexed { i, item ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "[x] " + title(item),
                style = Type.row,
                maxLines = 1,
                modifier = Modifier.weight(1f).clickable { onChange(shown - item) }.padding(vertical = 8.dp),
            )
            Text(
                "↑",
                style = Type.row,
                color = if (i > 0) Palette.log else Palette.dim,
                modifier = Modifier.clickable(enabled = i > 0) { onChange(shown.toMutableList().apply { add(i - 1, removeAt(i)) }) }.padding(8.dp),
            )
            Text(
                "↓",
                style = Type.row,
                color = if (i < shown.lastIndex) Palette.log else Palette.dim,
                modifier = Modifier.clickable(enabled = i < shown.lastIndex) { onChange(shown.toMutableList().apply { add(i + 1, removeAt(i)) }) }.padding(8.dp),
            )
        }
    }
    all.filter { it !in shown }.forEach { item ->
        Text(
            "[ ] " + title(item),
            style = Type.row,
            color = Palette.dim,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().clickable { onChange(shown + item) }.padding(vertical = 8.dp),
        )
    }
}

/** Rows that read cheaply without listeners or extra permissions. */
private val SHADE_ROWS = setOf("bat", "pwr", "chg", "net", "wifi", "ip", "data", "mem", "swap", "cpu", "therm", "fs", "alarm", "up", "mode", "audio", "ntf", "time")
