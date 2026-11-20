package com.asnidev.sysreadout.ui.settings

import com.asnidev.sysreadout.data.AodLook
import com.asnidev.sysreadout.data.LockPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class ChooserTest {

    private fun animatedGroup(l: AodLook) = if (l.animated) "animated (more battery)" else "still"

    @Test
    fun lookListKeysAreUnique() {
        // The order that crashed: still, animated (matrix), still (binary), animated.
        val keys = chooserKeys(AodLook.entries, ::animatedGroup)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun eachGroupHeaderShowsOnceWithAllItsMembers() {
        val sections = chooserSections(AodLook.entries, ::animatedGroup)
        assertEquals(listOf("still", "animated (more battery)"), sections.map { it.first })
        assertEquals(AodLook.entries.size, sections.sumOf { it.second.size })
        assertEquals(AodLook.entries.filter { it.animated }, sections[1].second)
    }

    @Test
    fun ungroupedListsKeepTheirOrder() {
        val keys = chooserKeys(LockPreset.entries, null)
        assertEquals(LockPreset.entries.size, keys.size)
        assertEquals(keys.size, keys.toSet().size)
    }
}
