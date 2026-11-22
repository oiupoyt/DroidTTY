package space.oiupoyt.droidtty.monitor

import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A process that Shizuku's own server started for us; stdout and stderr arrive merged on [output]. */
interface RemoteProc {
    val output: InputStream
    fun destroy()
}

/**
 * Shell commands run by Shizuku's server process instead of DroidTTY's helper.
 * Some Android 16 ROMs can't start Shizuku "user services" at all (app_process
 * dies in LoadedApk.makeApplication), while the server itself works fine.
 *
 * [start] may throw (a dead binder): that reaches the caller, which treats the
 * whole route as broken. Everything else fails as null, never as empty output.
 */
class RemoteShell(private val start: (Array<String>) -> RemoteProc?) {

    private val readers: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "shizuku-direct").apply { isDaemon = true }
    }

    /** stdout+stderr of `sh -c command`, or null when it didn't start or ran past [timeoutMs]. */
    fun exec(command: String, timeoutMs: Long): String? {
        val process = start(arrayOf("sh", "-c", "($command) 2>&1")) ?: return null
        val output = readers.submit<String> {
            process.output.bufferedReader().use { ShellExec.readCapped(it, ShellExec.MAX_OUTPUT) }
        }
        return try {
            output.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) { // timeout, interrupted, read failure
            null
        } finally {
            // Killing the process closes the pipe, which is what ends a blocked read.
            runCatching { process.destroy() }
            output.cancel(true)
        }
    }

    /** The file's contents, or null when it doesn't exist or isn't readable as the shell user. */
    fun readFile(path: String, timeoutMs: Long): String? {
        val out = exec("cat ${quote(path)} 2>/dev/null && printf %s $EOF", timeoutMs) ?: return null
        return if (out.endsWith(EOF)) out.removeSuffix(EOF) else null
    }

    companion object {
        /** Printed only after a successful cat, so a failed read can't pass for file contents. */
        internal const val EOF = "__droidtty_eof__"

        internal fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}

/** When to stop waiting for the helper and run commands through Shizuku's server instead. */
object ShizukuRoute {
    /** Long enough for a healthy helper to start; short enough that a broken one isn't waited on for minutes. */
    const val DIRECT_AFTER_MS = 8_000L

    /**
     * [helperWaitingMs]: how long the helper has been asked for without connecting, null when it wasn't.
     * [directPaused]: the direct route itself failed recently.
     */
    fun useDirect(
        permitted: Boolean,
        connected: Boolean,
        helperWaitingMs: Long?,
        helperPaused: Boolean,
        directPaused: Boolean,
    ): Boolean = permitted && !connected && !directPaused &&
        (helperPaused || (helperWaitingMs != null && helperWaitingMs >= DIRECT_AFTER_MS))
}

/**
 * The helper's interface served by [RemoteShell] inside DroidTTY's own process,
 * so the bridge calls it like the real helper. Reverse DNS isn't offered: the
 * lookups would run under DroidTTY's network permission, which only the DNS
 * monitor uses.
 */
class DirectShellService(private val shell: RemoteShell) : IShellService.Stub() {
    override fun destroy() {}
    override fun protocol(): Int = ShellProtocol.VERSION
    override fun exec(command: String, timeoutMs: Long): String? = shell.exec(command, timeoutMs)
    override fun readFile(path: String): String? = shell.readFile(path, READ_TIMEOUT_MS)
    override fun resolve(ips: String): String = ""

    private companion object {
        const val READ_TIMEOUT_MS = 2_500L
    }
}
