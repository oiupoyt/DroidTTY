package space.oiupoyt.droidtty.data

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

class PresetSaveTest {

    @Test
    fun savedPresetsKeepTheirAlwaysOnLookAndLockScreen() {
        val mine = listOf(Preset("mine", Presets.amber.theme, PresetScreen(AodLook.FIRE, LockPreset.GRUB, AodColor.ACCENT)))
        val back = presetsFromJson(mine.toJson())
        assertEquals(mine, back)
        assertTrue(back[0].screen.matches(CoverPrefs(aodLook = AodLook.FIRE, lockPreset = LockPreset.GRUB, aodColor = AodColor.ACCENT)))
    }

    @Test
    fun presetsSavedBeforeScreensStillLoad() {
        val old = """[{"name":"old","theme":${Presets.lime.theme.toJson()}}]"""
        val back = presetsFromJson(old)
        assertEquals(1, back.size)
        assertEquals(null, back[0].screen)
    }

    @Test
    fun anUnknownLookDropsOnlyTheScreen() {
        val json = """[{"name":"x","theme":${Presets.amber.theme.toJson()},"screen":{"aodLook":"GONE","lockPreset":"DOS"}}]"""
        val back = presetsFromJson(json)
        assertEquals("x", back[0].name)
        assertEquals(null, back[0].screen)
    }
}

class AccentPaletteTest {

    @Test
    fun accentRecoloursEveryText() {
        val t = Presets.default.theme.withAccentPalette(0xFFFF00AAL)
        assertEquals(0xFFFF00AAL, t.accent)
        listOf(t.clock, t.menu, t.drawer, t.banner).forEach { assertEquals(0xFFFF00AAL, it.color) }
        // Log and date are shades of the accent, between it and the background.
        assertTrue(t.log.color != t.accent && t.date.color != t.log.color)
        assertEquals(Theme.mix(0xFFFF00AAL, t.background, 0.18f), t.log.color)
    }

    @Test
    fun mixIsOpaqueAndHitsItsEnds() {
        assertEquals(0xFFFF0000L, Theme.mix(0xFFFF0000L, 0xFF000000L, 0f))
        assertEquals(0xFF000000L, Theme.mix(0xFFFF0000L, 0xFF000000L, 1f))
        assertEquals(0xFF7F0000L, Theme.mix(0xFFFF0000L, 0xFF000000L, 0.5f))
    }
}
