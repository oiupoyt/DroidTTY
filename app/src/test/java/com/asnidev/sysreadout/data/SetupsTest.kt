package com.asnidev.sysreadout.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupsTest {

    @Test
    fun namesAreUniqueAndEachCombinationDiffers() {
        assertEquals(Setups.all.size, Setups.all.map { it.name }.toSet().size)
        assertEquals(Setups.all.size, Setups.all.map { Triple(it.theme, it.aodLook, it.lockPreset) }.toSet().size)
    }

    @Test
    fun applyingSetsOnlyWhatItNamesAndIsThenRecognised() {
        val pinned = listOf(AppKey("a.b", "a.b.Main", 0))
        val p = LauncherPrefs(pinned = pinned, logRows = listOf("bat"))
        val c = CoverPrefs(aodMode = AodMode.ALWAYS, lockScreen = LockScreenMode.OWN, shade = true)
        Setups.all.forEach { s ->
            val p2 = s.applyTo(p)
            val c2 = s.applyTo(c)
            assertTrue(s.name, s.isApplied(s.theme, p2, c2))
            // Apps, rows, the lock mode and on/off switches are the user's.
            assertEquals(pinned, p2.pinned)
            assertEquals(listOf("bat"), p2.logRows)
            assertEquals(AodMode.ALWAYS, c2.aodMode)
            assertEquals(LockScreenMode.OWN, c2.lockScreen)
            assertTrue(c2.shade)
        }
    }

    @Test
    fun anotherSetupIsntMistakenForThisOne() {
        val a = Setups.all[0]
        val b = Setups.all[1]
        assertFalse(a.isApplied(b.theme, b.applyTo(LauncherPrefs()), b.applyTo(CoverPrefs())))
    }
}
