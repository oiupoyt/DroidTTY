package space.oiupoyt.droidtty.ui.settings

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import space.oiupoyt.droidtty.system.Grants
import space.oiupoyt.droidtty.ui.Type

/**
 * What the lock service does with Android's accessibility API, shown before Android's
 * own switch every time DroidTTY sends someone there, so turning it on is an informed
 * choice. Google Play requires this for apps that aren't accessibility tools.
 */
@Composable
fun LockServiceDisclosure(onAgree: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("the lock service", style = Type.row) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Note(
                    "droidtty uses android's accessibility service for this. it isn't an accessibility tool for people " +
                        "with disabilities; it needs the api to:",
                )
                Note("* draw the always-on display, the lock screens and its notification shade over other apps and over the lock screen")
                Note("* turn the screen off when you double-tap home")
                Note(
                    "* see which kind of window opened and how big it is (android's own shade, or an app opening over " +
                        "droidtty's lock), to close it or put the lock back",
                )
                Note("* while droidtty's own pin lock is up, block the home, back, recents, search, assistant and camera keys")
                Note(
                    "it never reads what's on the screen or what you type. nothing it sees is stored or leaves the phone. " +
                        "you can switch it off any time in android's accessibility settings.",
                )
            }
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("agree, turn it on", style = Type.row) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("not now", style = Type.row) } },
    )
}

/**
 * A request to turn the lock service on: shows [LockServiceDisclosure] and opens
 * Android's setting only after "agree". Call the returned function from any tap.
 */
@Composable
fun rememberLockServiceRequest(): () -> Unit {
    val context = LocalContext.current
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        LockServiceDisclosure(
            onAgree = {
                asking = false
                Grants.lockService(context)
            },
            onDismiss = { asking = false },
        )
    }
    return remember { { asking = true } }
}
