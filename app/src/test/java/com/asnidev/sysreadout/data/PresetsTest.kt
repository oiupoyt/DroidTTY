package com.asnidev.sysreadout.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {

    @Test
    fun everyBuiltInHasItsOwnNameThemeAndScreen() {
        val all = Presets.builtIn
        assertEquals(all.size, all.map { it.name }.toSet().size)
        assertEquals(all.size, all.map { it.theme }.toSet().size)
        all.forEach { assertNotNull("${it.name} has no always-on look", it.screen) }
    }

    @Test
    fun aScreenMatchesOnlyWhatItSets() {
        val s = PresetScreen(AodLook.FIRE, LockPreset.BIOS)
        assertTrue(s.matches(CoverPrefs(aodLook = AodLook.FIRE, lockPreset = LockPreset.BIOS, aodMode = AodMode.ALWAYS)))
        assertFalse(s.matches(CoverPrefs(aodLook = AodLook.FIRE, lockPreset = LockPreset.DOS)))
        assertTrue("own presets have no screen", (null as PresetScreen?).matches(CoverPrefs()))
    }

    @Test
    fun userPresetsStillRoundTripWithoutAScreen() {
        val mine = listOf(Preset("mine", Presets.amber.theme))
        assertEquals(mine, presetsFromJson(mine.toJson()))
    }
}
