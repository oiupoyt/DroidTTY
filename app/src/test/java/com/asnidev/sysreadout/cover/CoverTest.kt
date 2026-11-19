package com.asnidev.sysreadout.cover

import com.asnidev.sysreadout.data.AodLook
import com.asnidev.sysreadout.data.AodMode
import com.asnidev.sysreadout.data.CoverPrefs
import com.asnidev.sysreadout.data.LockPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class CoverPrefsTest {

    @Test
    fun roundTrips() {
        val p = CoverPrefs(AodMode.ALWAYS, AodLook.PROMPT, listOf("bat", "ip"), 35, true, LockPreset.GRUB)
        assertEquals(p, CoverPrefs.fromJson(p.toJson()))
    }

    @Test
    fun defaultsAreOffAndSurviveJunk() {
        assertEquals(CoverPrefs(), CoverPrefs.fromJson(null))
        assertEquals(CoverPrefs(), CoverPrefs.fromJson("not json"))
        assertFalse(CoverPrefs().needsService)
        val odd = CoverPrefs.fromJson("""{"aodMode":"SOMETIMES","lockPreset":"AMIGA","aodMinBattery":1}""")
        assertEquals(AodMode.OFF, odd.aodMode)
        assertEquals(LockPreset.BOOTLINE, odd.lockPreset)
        assertEquals(CoverPrefs.MIN_BATTERY_LOW, odd.aodMinBattery)
    }

    @Test
    fun serviceRunsForEitherFeature() {
        assertTrue(CoverPrefs(aodMode = AodMode.CHARGING).needsService)
        assertTrue(CoverPrefs(lockCover = true).needsService)
    }
}

class ScreenPolicyTest {

    private val idle = PhoneNow(battery = 60, charging = false, inCall = false, locked = true, canOverlay = true, safeMode = false)

    @Test
    fun aodFollowsTheMode() {
        assertFalse(ScreenPolicy.aodOnScreenOff(CoverPrefs(), idle, false))
        assertFalse(ScreenPolicy.aodOnScreenOff(CoverPrefs(aodMode = AodMode.CHARGING), idle, false))
        assertTrue(ScreenPolicy.aodOnScreenOff(CoverPrefs(aodMode = AodMode.CHARGING), idle.copy(charging = true), false))
        assertTrue(ScreenPolicy.aodOnScreenOff(CoverPrefs(aodMode = AodMode.ALWAYS), idle, false))
    }

    @Test
    fun aodWaitsForTheChargerOnLowBattery() {
        val p = CoverPrefs(aodMode = AodMode.ALWAYS, aodMinBattery = 20)
        assertFalse(ScreenPolicy.aodOnScreenOff(p, idle.copy(battery = 19), false))
        assertTrue(ScreenPolicy.aodOnScreenOff(p, idle.copy(battery = 19, charging = true), false))
        assertFalse("unknown level counts as low", ScreenPolicy.aodOnScreenOff(p, idle.copy(battery = null), false))
    }

    @Test
    fun nothingDuringCallsWithoutAccessInSafeModeOrAfterTheUserSaidDark() {
        val p = CoverPrefs(aodMode = AodMode.ALWAYS, lockCover = true)
        assertFalse(ScreenPolicy.aodOnScreenOff(p, idle.copy(inCall = true), false))
        assertFalse(ScreenPolicy.aodOnScreenOff(p, idle.copy(canOverlay = false), false))
        assertFalse(ScreenPolicy.aodOnScreenOff(p, idle.copy(safeMode = true), false))
        assertFalse(ScreenPolicy.aodOnScreenOff(p, idle, suppressed = true))
        assertFalse(ScreenPolicy.coverOnScreenOn(p, idle.copy(inCall = true), false, 60_000))
        assertFalse(ScreenPolicy.coverOnScreenOn(p, idle.copy(canOverlay = false), false, 60_000))
        assertFalse(ScreenPolicy.coverOnScreenOn(p, idle.copy(safeMode = true), false, 60_000))
    }

    @Test
    fun coverOnlyWhenLockedAndNotOverTheAod() {
        val p = CoverPrefs(lockCover = true)
        assertTrue(ScreenPolicy.coverOnScreenOn(p, idle, false, 60_000))
        assertFalse(ScreenPolicy.coverOnScreenOn(p, idle.copy(locked = false), false, 60_000))
        assertFalse(ScreenPolicy.coverOnScreenOn(p, idle, aodShowing = true, sinceAodLaunchMs = 60_000))
        assertFalse("the aod's own wake", ScreenPolicy.coverOnScreenOn(p, idle, false, 500))
        assertFalse(ScreenPolicy.coverOnScreenOn(CoverPrefs(), idle, false, 60_000))
    }

    @Test
    fun aodTapGoesToTheCoverOnlyWhenLocked() {
        assertTrue(ScreenPolicy.coverAfterAod(CoverPrefs(lockCover = true), locked = true))
        assertFalse(ScreenPolicy.coverAfterAod(CoverPrefs(lockCover = true), locked = false))
        assertFalse(ScreenPolicy.coverAfterAod(CoverPrefs(), locked = true))
    }
}

class CoverFacesTest {

    private val now = ZonedDateTime.of(2026, 11, 19, 22, 5, 9, 0, ZoneOffset.UTC)

    private val full = CoverData(
        now = now, is24h = true, battery = 84, charging = false,
        manufacturer = "Nothing", model = "A065", device = "Pacman", android = "16", api = 36,
        patch = "2026-10-01", kernel = "6.1.99-android14", soc = "MediaTek MT6886", cores = 8,
        memTotalMb = 7_680, memAvailMb = 3_100, storageTotalGb = 238.0, storageFreeGb = 91.5,
        net = "wifi", notifications = 3, events = listOf("22:01 net   wifi → cell"),
        rows = listOf("bat   84%"),
    )

    /** Only what the phone gave: nothing but time and identity. */
    private val bare = CoverData(now = now, is24h = true, manufacturer = "Nothing", model = "A065", device = "Pacman", android = "16", api = 36)

    private fun LockScript.lastText() = beats.last().second.lines.joinToString("\n") { it.mark + it.text }
    private fun LockScript.allText() = beats.joinToString("\n") { (_, f) -> f.lines.joinToString("\n") { it.mark + it.text } }

    @Test
    fun everyPresetEndsOnATapPrompt() {
        LockPreset.entries.forEach { p ->
            val s = LockPresets.script(p, full)
            assertTrue("$p runs forward", s.beats.zipWithNext().all { (a, b) -> a.first < b.first })
            assertTrue("$p finishes in a few seconds", s.readyAt in 1L..6_000L)
            assertTrue("$p says how to go on", s.lastText().contains("tap", ignoreCase = true) || p == LockPreset.GRUB)
        }
    }

    @Test
    fun presetsShowThePhonesOwnValues() {
        val boot = LockPresets.script(LockPreset.BOOTLINE, full).allText()
        assertTrue(boot.contains("Linux version 6.1.99-android14"))
        assertTrue(boot.contains("smp: Brought up 8 CPUs"))
        assertTrue(boot.contains("Memory: 7680M total"))
        assertTrue(boot.contains(LockPresets.OK + "Reached target battery: 84%"))
        assertTrue(boot.contains("pacman login: "))
        val bios = LockPresets.script(LockPreset.BIOS, full).lastText()
        assertTrue(bios.contains("Memory : 7680M OK"))
        assertTrue(bios.contains("security patch 2026-10-01"))
        assertTrue(bios.contains(LockPresets.bar(100)))
        val dos = LockPresets.script(LockPreset.DOS, full).lastText()
        assertTrue(dos.contains("Current time is 22:05:09"))
        assertTrue(dos.contains("3100M free of 7680M"))
    }

    @Test
    fun unknownValuesAreLeftOutNotInvented() {
        LockPreset.entries.forEach { p ->
            val text = LockPresets.script(p, bare).allText()
            listOf("Memory", "Battery", "battery", "notification", "Storage", "/data", "CPU").forEach { word ->
                assertFalse("$p invents $word", text.contains(word))
            }
        }
        AodLook.entries.forEach { look ->
            val text = AodFaces.frame(look, bare).lines.joinToString("\n") { it.text }
            assertFalse("$look invents a battery level", text.contains("%"))
        }
    }

    @Test
    fun grubEntriesAreRealActions() {
        val actions = LockPresets.script(LockPreset.GRUB, full).beats.last().second.lines.mapNotNull { it.action }
        assertEquals(listOf(LockPresets.ACTION_UNLOCK, LockPresets.ACTION_CAMERA, LockPresets.ACTION_EMERGENCY), actions)
    }

    @Test
    fun scriptFramesFollowTime() {
        val s = LockPresets.script(LockPreset.BIOS, full)
        assertEquals(s.beats.first().second, s.at(-5))
        assertEquals(s.beats.last().second, s.at(s.readyAt + 10_000))
    }

    @Test
    fun progressBar() {
        assertEquals("[....................]   0%", LockPresets.bar(0))
        assertEquals("[##########..........]  50%", LockPresets.bar(50))
        assertEquals("[####################] 100%", LockPresets.bar(100))
    }

    @Test
    fun aodLooks() {
        val clock = AodFaces.frame(AodLook.CLOCK, full).lines.map { it.text }
        assertEquals(listOf("22:05", "Thu 19 Nov", "", "84%", "3 notifications"), clock)
        val prompt = AodFaces.frame(AodLook.PROMPT, full).lines
        assertTrue(prompt.last().cursor)
        assertEquals("root@pacman:~$ ", prompt.last().text)
        assertEquals("22:01 net   wifi → cell", AodFaces.frame(AodLook.LOG, full).lines.last().text)
        assertEquals("bat   84%", AodFaces.frame(AodLook.STATUS, full).lines.last().text)
        assertEquals(
            "no events since the launcher started",
            AodFaces.frame(AodLook.LOG, bare).lines.last().text,
        )
    }

    @Test
    fun burnInShiftCyclesThroughEightSpots() {
        val spots = (0L until 16L).map { burnInShift(it) }
        assertEquals(8, spots.toSet().size)
        assertEquals(spots[0], spots[8])
    }
}
