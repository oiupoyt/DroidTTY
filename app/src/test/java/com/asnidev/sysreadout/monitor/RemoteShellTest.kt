package com.asnidev.sysreadout.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/** Runs the command locally, standing in for Shizuku's server. */
private class LocalProc(cmd: Array<String>) : RemoteProc {
    private val process = ProcessBuilder(*cmd).start()
    var destroyed = false
    override val output: InputStream = process.inputStream
    override fun destroy() {
        destroyed = true
        process.destroyForcibly()
    }
}

class RemoteShellTest {

    private val started = ArrayList<LocalProc>()
    private val shell = RemoteShell { cmd -> LocalProc(cmd).also { started += it } }

    @Test
    fun returnsOutputWithErrorsMerged() {
        assertEquals("out\nerr\n", shell.exec("echo out; echo err >&2", 5_000))
        assertTrue("process cleaned up", started.single().destroyed)
    }

    @Test
    fun aCommandThatRunsTooLongIsNullNotEmpty() {
        val t = System.nanoTime()
        assertNull(shell.exec("sleep 10", 300))
        assertTrue("gave up in time", (System.nanoTime() - t) / 1_000_000 < 3_000)
    }

    @Test
    fun noProcessIsNull() {
        assertNull(RemoteShell { null }.exec("echo hi", 1_000))
    }

    @Test
    fun readsAFile() {
        val f = kotlin.io.path.createTempFile().toFile().apply { writeText("a b\nc") }
        assertEquals("a b\nc", shell.readFile(f.path, 5_000))
    }

    @Test
    fun anUnreadableFileIsNullNotEmpty() {
        assertNull(shell.readFile("/nonexistent/sysreadout", 5_000))
    }

    @Test
    fun quotesPathsWithQuotes() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val f = java.io.File(dir, "it's here").apply { writeText("ok") }
        assertEquals("ok", shell.readFile(f.path, 5_000))
    }
}

class ShizukuRouteTest {

    private fun direct(
        permitted: Boolean = true,
        connected: Boolean = false,
        waiting: Long? = ShizukuRoute.DIRECT_AFTER_MS,
        helperPaused: Boolean = false,
        directPaused: Boolean = false,
    ) = ShizukuRoute.useDirect(permitted, connected, waiting, helperPaused, directPaused)

    @Test
    fun helperThatNeverConnectsFallsBack() {
        assertTrue(direct())
        assertTrue(direct(waiting = null, helperPaused = true))
    }

    @Test
    fun givesTheHelperTimeFirst() {
        assertFalse(direct(waiting = ShizukuRoute.DIRECT_AFTER_MS - 1))
        assertFalse(direct(waiting = null))
    }

    @Test
    fun neverWithoutPermissionOrWhileTheHelperWorks() {
        assertFalse(direct(permitted = false))
        assertFalse(direct(connected = true))
    }

    @Test
    fun backsOffAfterDirectFailures() {
        assertFalse(direct(directPaused = true))
    }
}
