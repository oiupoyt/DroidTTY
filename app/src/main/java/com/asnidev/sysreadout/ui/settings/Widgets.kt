package com.asnidev.sysreadout.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asnidev.sysreadout.data.Theme
import com.asnidev.sysreadout.ui.Palette
import com.asnidev.sysreadout.ui.Type

// Settings chrome keeps a fixed look so no theme can make it unreadable.

/** Opens a file picker or system screen; some phones have none, which must not crash the launcher. */
fun <I> ActivityResultLauncher<I>.launchOr(context: Context, missing: String, input: I) {
    try {
        launch(input)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, missing, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun Section(title: String) =
    Text("# $title", style = Type.row, color = Palette.log, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))

@Composable
fun Note(text: String) =
    Text(text, style = Type.small, color = Palette.dim, modifier = Modifier.padding(bottom = 6.dp))

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingRow(
    label: String,
    value: String,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.row, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = Type.row, color = Palette.log, maxLines = 1)
    }
}

@Composable
fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    SettingRow(label, if (value) "[x]" else "[ ]") { onChange(!value) }

/** Tapping cycles through [options]. */
@Composable
fun <T> Cycle(label: String, value: T, options: List<T>, show: (T) -> String = { it.toString().lowercase() }, onChange: (T) -> Unit) =
    SettingRow(label, show(value)) { onChange(options[(options.indexOf(value) + 1) % options.size]) }

@Composable
fun Link(label: String, value: String = "›", onClick: () -> Unit) = SettingRow(label, value, onClick = onClick)

/** label ....... value  [−] [+] */
@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.row, modifier = Modifier.weight(1f))
        Text(value, style = Type.row, color = Palette.log)
        Text(" [−] ", style = Type.row, modifier = Modifier.clickable(onClick = onMinus).padding(vertical = 6.dp))
        Text("[+]", style = Type.row, modifier = Modifier.clickable(onClick = onPlus).padding(vertical = 6.dp))
    }
}

@Composable
fun ColorRow(label: String, color: Long, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, style = Type.row, modifier = Modifier.weight(1f))
        Swatch(color, 18)
        Text(Theme.hex(color), style = Type.row, color = Palette.log)
    }
}

@Composable
fun Swatch(color: Long, sizeDp: Int, modifier: Modifier = Modifier, selected: Boolean = false) {
    Box(
        modifier
            .size(sizeDp.dp)
            .background(Color(color))
            .border(if (selected) 2.dp else 1.dp, if (selected) Palette.fg else Palette.dim.copy(alpha = 0.5f)),
    )
}

@Composable
fun Header(path: List<String>, onBack: (() -> Unit)?) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(
            "droidtty --config" + path.joinToString("") { " › $it" },
            style = Type.entry,
            color = Palette.log,
            modifier = if (onBack != null) Modifier.clickable(onClick = onBack) else Modifier,
        )
        if (onBack != null) Note("tap here or press back to go up")
    }
}

/**
 * A setting with many options: the row shows the current one; a tap lists them
 * all, each with what it is, and one tap picks. [group] splits the list into
 * headed sections.
 */
@Composable
fun <T> Choose(
    label: String,
    value: T,
    options: List<T>,
    title: (T) -> String,
    detail: (T) -> String? = { null },
    group: ((T) -> String)? = null,
    onChange: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(label, title(value) + " ›") { open = true }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(label, style = Type.row) },
        text = {
            // Bounded height: a lazy list given unlimited height throws, which is what crashed the look picker.
            LazyColumn(Modifier.heightIn(max = CHOOSER_MAX_HEIGHT)) {
                chooserSections(options, group).forEachIndexed { s, (g, members) ->
                    if (g != null) {
                        item(key = "g$s") {
                            Text("## $g", style = Type.small, color = Palette.log, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                        }
                    }
                    members.forEachIndexed { i, option ->
                        item(key = "o$s.$i") {
                            val selected = option == value
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onChange(option)
                                        open = false
                                    }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(
                                    (if (selected) "> " else "  ") + title(option),
                                    style = Type.row,
                                    color = if (selected) Palette.log else Palette.fg,
                                )
                                detail(option)?.let { Text("  $it", style = Type.small, color = Palette.dim) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("close", style = Type.row) } },
    )
}

private val CHOOSER_MAX_HEIGHT = 460.dp

/**
 * Options gathered per group, groups in first-seen order. A group's header shows
 * once even when its members aren't next to each other: the look list has still
 * looks on both sides of matrix rain, and a header per run gave two lists the same
 * key, which crashed the picker.
 */
fun <T> chooserSections(options: List<T>, group: ((T) -> String)?): List<Pair<String?, List<T>>> =
    if (group == null) listOf(null to options) else options.groupBy(group).toList()

/** Every key the chooser's list uses, in order; all distinct. */
fun <T> chooserKeys(options: List<T>, group: ((T) -> String)?): List<String> =
    chooserSections(options, group).flatMapIndexed { s, (g, members) ->
        (if (g != null) listOf("g$s") else emptyList()) + members.indices.map { "o$s.$it" }
    }
