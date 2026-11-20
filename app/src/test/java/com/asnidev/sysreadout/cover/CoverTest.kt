package com.asnidev.sysreadout.cover

import com.asnidev.sysreadout.data.AodLook
import com.asnidev.sysreadout.data.AodMode
import com.asnidev.sysreadout.data.CoverPrefs
import com.asnidev.sysreadout.data.LockPreset
import com.asnidev.sysreadout.data.LockScreenMode
import com.asnidev.sysreadout.data.AodColor
import com.asnidev.sysreadout.data.HAlign
import com.asnidev.sysreadout.data.KeypadLayout
import com.asnidev.sysreadout.data.Placement
import com.asnidev.sysreadout.data.VAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class CoverPrefsTest {

    @Test
    fun roundTrips() {
        val p = CoverPrefs(AodMode.ALWAYS, AodLook.NEOFETCH, listOf("bat", "ip"), 35, LockScreenMode.OWN, LockPreset.C64, shade = true)
        assertEquals(p, CoverPrefs.fromJson(p.toJson()))
    }

    @Test
    fun defaultsAreOffAndSurviveJunk() {
        assertEquals(CoverPrefs(), CoverPrefs.fromJson(null))
        assertEquals(CoverPrefs(), CoverPrefs.fromJson("not json"))
        assertFalse(CoverPrefs().needsService)
        val odd = CoverPrefs.fromJson("""{"aodMode":"SOMETIMES","lockPreset":"AMIGA","aodMinBattery":1}""")
        assertEquals(AodMode.OFF, odd.aodMode)
        assertEquals(LockPreset.ARCH, odd.lockPreset)
        assertEquals(CoverPrefs.MIN_BATTERY_LOW, odd.aodMinBattery)
    }

    @Test
    fun serviceRunsForTheAodAndCover() {
        assertTrue(CoverPrefs(aodMode = AodMode.CHARGING).needsService)
        assertTrue(CoverPrefs(lockScreen = LockScreenMode.COVER).needsService)
        assertFalse("own lock runs in the lock service", CoverPrefs(lockScreen = LockScreenMode.OWN).needsService)
    }

    @Test
    fun migratesTheOldCoverSwitch() {
        assertEquals(LockScreenMode.COVER, CoverPrefs.fromJson("""{"lockCover":true,"lockPreset":"DOS"}""").lockScreen)
        assertEquals(LockScreenMode.OFF, CoverPrefs.fromJson("""{"lockCover":false}""").lockScreen)
        assertEquals(LockPreset.DOS, CoverPrefs.fromJson("""{"lockCover":true,"lockPreset":"DOS"}""").lockPreset)
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
        val p = CoverPrefs(aodMode = AodMode.ALWAYS, lockScreen = LockScreenMode.COVER)
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
        val p = CoverPrefs(lockScreen = LockScreenMode.COVER)
        assertTrue(ScreenPolicy.coverOnScreenOn(p, idle, false, 60_000))
        assertFalse(ScreenPolicy.coverOnScreenOn(CoverPrefs(lockScreen = LockScreenMode.OWN), idle, false, 60_000))
        assertFalse(ScreenPolicy.coverOnScreenOn(p, idle.copy(locked = false), false, 60_000))
        assertFalse(ScreenPolicy.coverOnScreenOn(p, idle, aodShowing = true, sinceAodLaunchMs = 60_000))
        assertFalse("the aod's own wake", ScreenPolicy.coverOnScreenOn(p, idle, false, 500))
        assertFalse(ScreenPolicy.coverOnScreenOn(CoverPrefs(), idle, false, 60_000))
    }

    @Test
    fun aodTapGoesToTheCoverOnlyWhenLocked() {
        assertTrue(ScreenPolicy.coverAfterAod(CoverPrefs(lockScreen = LockScreenMode.COVER), locked = true))
        assertFalse(ScreenPolicy.coverAfterAod(CoverPrefs(lockScreen = LockScreenMode.COVER), locked = false))
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
            val own = LockPresets.script(p, full, own = true)
            assertFalse("$p own lock has no tap hint", own.lastText().contains("tap ", ignoreCase = true))
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
        AodLook.entries.filter { it != AodLook.MATRIX }.forEach { look ->
            val text = AodFaces.frame(look, bare).lines.joinToString("\n") { it.mark + it.text }
            assertFalse("$look invents a battery level", text.contains("%"))
            listOf("Memory", "Mem[", "Bat[", "Uptime", "Packages").forEach { word ->
                assertFalse("$look invents $word", text.contains(word))
            }
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

class NewLooksTest {

    private val now = ZonedDateTime.of(2026, 11, 19, 13, 7, 42, 0, ZoneOffset.UTC)
    private val d = CoverData(
        now = now, is24h = true, battery = 61, manufacturer = "Nothing", model = "A065", device = "Pacman",
        android = "16", api = 36, kernel = "6.1.99", soc = "MT6886", cores = 8, memTotalMb = 8_000, memAvailMb = 2_000,
        storageTotalGb = 238.0, storageFreeGb = 91.5, net = "wifi", uptimeMs = (2 * 24 * 60 + 3 * 60 + 5) * 60_000L,
        packages = 412, coreLoad = listOf(0.5, 0.25),
    )

    private fun text(look: AodLook) = AodFaces.frame(look, d).lines.joinToString("\n") { it.mark + it.text }

    @Test
    fun ttyClockDrawsTheTime() {
        val rows = AodFaces.bigDigits("13:07")
        assertEquals(5, rows.size)
        assertEquals("  # ###   ### ###", rows[0])
        assertEquals(rows, AodFaces.frame(AodLook.TTY, d).lines.take(5).map { it.text })
    }

    @Test
    fun binaryClockReadsBack() {
        // 13:07:42 -> digits 1 3 0 7 4 2; the 1-bit row has 1,3,7 set.
        assertEquals("# #   . #   . .", AodFaces.binaryRow(listOf(1, 3, 0, 7, 4, 2), 0))
        assertEquals(". .   . #   # .", AodFaces.binaryRow(listOf(1, 3, 0, 7, 4, 2), 2))
    }

    @Test
    fun neofetchUsesThePhonesValues() {
        val t = text(AodLook.NEOFETCH)
        assertTrue(t.contains("pacman@android"))
        assertTrue(t.contains("Kernel: 6.1.99"))
        assertTrue(t.contains("Uptime: 2 days, 3 hours, 5 mins"))
        assertTrue(t.contains("Packages: 412 (pm)"))
        assertTrue(t.contains("Memory: 6000MiB / 8000MiB"))
    }

    @Test
    fun htopMeters() {
        assertEquals("Mem[||||    50%]", AodFaces.meter("Mem", 0.5, "50%", width = 11))
        val t = text(AodLook.HTOP)
        assertTrue(t.contains(" 0["))
        assertTrue(t.contains("Bat["))
        assertTrue(AodFaces.frame(AodLook.HTOP, d.copy(coreLoad = emptyList())).lines.any { it.text.contains("shizuku") })
    }

    @Test
    fun matrixMovesAndKeepsTheClock() {
        val a = AodFaces.frame(AodLook.MATRIX, d, tick = 0)
        val b = AodFaces.frame(AodLook.MATRIX, d, tick = 500)
        assertTrue(a.lines.any { it.text == "13:07" })
        assertFalse(a == b)
    }

    @Test
    fun archBootsToTheLoginPrompt() {
        val s = LockPresets.script(LockPreset.ARCH, d)
        val all = s.beats.joinToString("\n") { (_, f) -> f.lines.joinToString("\n") { it.mark + it.text } }
        assertTrue(all.contains("Loading Linux 6.1.99 ..."))
        assertTrue(all.contains(":: running early hook [udev]"))
        assertTrue(all.contains("Welcome to Arch Linux!"))
        assertTrue(all.contains(LockPresets.OK + "Reached target Multi-User System."))
        assertTrue(s.beats.last().second.lines.any { it.text == "pacman login: " })
        assertTrue(s.beats.last().second.tail || s.beats.any { it.second.tail })
    }

    @Test
    fun ownPromptHidesTheDigits() {
        LockPreset.entries.forEach { p ->
            val lines = LockPresets.prompt(p, d, typed = 4, status = LockPresets.FAILED)
            val joined = lines.joinToString("\n") { it.text }
            assertTrue("$p shows stars", joined.contains("****"))
            assertFalse("$p leaks the marker", joined.contains(LockPresets.FAILED))
        }
    }

    @Test
    fun ownGrubHasNoCameraEntry() {
        val actions = LockPresets.script(LockPreset.GRUB, d, own = true).beats.last().second.lines.mapNotNull { it.action }
        assertFalse(LockPresets.ACTION_CAMERA in actions)
        assertTrue(LockPresets.ACTION_EMERGENCY in actions)
    }
}

class PinTest {

    @Test
    fun verifiesOnlyTheRightPin() {
        val h = PinHash.of("2580", iterations = 1_000)
        assertTrue(h.matches("2580"))
        assertFalse(h.matches("2581"))
        assertFalse(h.matches(""))
        assertEquals(h, PinHash.decode(h.encode()))
        assertTrue(PinHash.decode(h.encode())!!.matches("2580"))
    }

    @Test
    fun saltsDiffer() {
        val a = PinHash.of("1234", iterations = 1_000)
        val b = PinHash.of("1234", iterations = 1_000)
        assertFalse(a.encode() == b.encode())
        assertFalse("the pin itself isn't stored", a.encode().contains("1234"))
    }

    @Test
    fun onlyDigitsOfSaneLength() {
        assertFalse(PinHash.valid("123"))
        assertFalse(PinHash.valid("12a4"))
        assertTrue(PinHash.valid("1234"))
        assertFalse(PinHash.valid("1".repeat(17)))
        assertEquals(null, PinHash.decode("garbage"))
    }

    @Test
    fun lockoutEscalatesAndCaps() {
        assertEquals(0L, Lockout.delayAfter(4))
        assertEquals(30_000L, Lockout.delayAfter(5))
        assertEquals(60_000L, Lockout.delayAfter(6))
        assertEquals(15 * 60_000L, Lockout.delayAfter(50))
    }
}

class HardeningTest {

    @Test
    fun keypadHasEveryDigitOnceInChangingOrders() {
        val r = java.util.Random(7)
        val orders = (0 until 20).map { Keypad.shuffled(r) }
        orders.forEach { assertEquals(('0'..'9').toList(), it.sorted()) }
        assertTrue(orders.toSet().size > 1)
    }

    @Test
    fun aodOptionsRoundTripAndClamp() {
        val p = CoverPrefs(aodScale = 1.5f, aodBrightness = 10, aodPlace = Placement(VAlign.TOP),
            aodColor = AodColor.MONO, aodShift = false, aodTimeoutMin = 15)
        assertEquals(p, CoverPrefs.fromJson(p.toJson()))
        val wild = CoverPrefs.fromJson("""{"aodScale":9,"aodBrightness":0,"aodTimeoutMin":-3}""")
        assertEquals(CoverPrefs.SCALES.last(), wild.aodScale)
        assertEquals(1, wild.aodBrightness)
        assertEquals(0, wild.aodTimeoutMin)
    }
}

class PlacementTest {
    @Test
    fun roundTripsBothScreensAndTheKeypad() {
        val p = CoverPrefs(
            aodPlace = Placement(VAlign.BOTTOM, HAlign.END, offsetX = -16, offsetY = 48, margin = 8),
            lockPlace = Placement(null, HAlign.CENTER, offsetY = -32),
            keypad = KeypadLayout.RIGHT,
            keypadLift = 64,
        )
        assertEquals(p, CoverPrefs.fromJson(p.toJson()))
    }

    @Test
    fun lockKeepsThePresetsOwnPlacementByDefault() {
        assertEquals(null, CoverPrefs().lockPlace.vertical)
        assertEquals(null, CoverPrefs.fromJson(CoverPrefs().toJson()).lockPlace.vertical)
    }

    @Test
    fun migratesTheOldAodPosition() {
        assertEquals(VAlign.TOP, CoverPrefs.fromJson("""{"aodPosition":"TOP"}""").aodPlace.vertical)
        assertEquals(VAlign.CENTER, CoverPrefs.fromJson("{}").aodPlace.vertical)
    }

    @Test
    fun clampsWildValues() {
        val p = CoverPrefs.fromJson("""{"aodPlace":{"x":999,"y":-9999,"margin":-5,"h":"SIDEWAYS"},"keypadLift":5000}""")
        assertEquals(Placement.RANGE_X.last, p.aodPlace.offsetX)
        assertEquals(Placement.RANGE_Y.first, p.aodPlace.offsetY)
        assertEquals(0, p.aodPlace.margin)
        assertEquals(HAlign.CENTER, p.aodPlace.horizontal)
        assertEquals(CoverPrefs.RANGE_LIFT.last, p.keypadLift)
    }
}
