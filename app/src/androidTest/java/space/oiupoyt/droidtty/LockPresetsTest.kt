package space.oiupoyt.droidtty

import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import space.oiupoyt.droidtty.cover.CoverReader
import space.oiupoyt.droidtty.cover.LockContent
import space.oiupoyt.droidtty.cover.LockPresets
import space.oiupoyt.droidtty.cover.OwnLockContent
import space.oiupoyt.droidtty.cover.PinStore
import space.oiupoyt.droidtty.data.KeypadLayout
import space.oiupoyt.droidtty.data.LockPreset
import space.oiupoyt.droidtty.data.Placement
import space.oiupoyt.droidtty.data.Presets
import space.oiupoyt.droidtty.ui.rememberStyled
import java.util.concurrent.atomic.AtomicBoolean

/** Each lock preset plays in the boot-screen cover and in the own PIN lock. */
@RunWith(Parameterized::class)
class LockPresetsTest(private val preset: LockPreset) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun presets() = LockPreset.entries.toList()

        // A script longer than this is waited for only this long, keeping the suite short.
        private const val MAX_PLAY_MS = 12_000L
    }

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PinStore(context)

    @Before
    fun setUp() {
        rule.mainClock.autoAdvance = false
        // No PIN and no lockout left from an earlier test or run.
        store.clear()
        store.clearFailures()
    }

    @After
    fun tearDown() {
        store.clearFailures()
    }

    @Test
    fun coverPlaysThenTapUnlocks() {
        val readyAt = LockPresets.script(preset, CoverReader(context).read()).readyAt
        val unlocked = AtomicBoolean(false)
        val action = AtomicBoolean(false)
        rule.setContent {
            LockContent(preset, Placement(vertical = null), onUnlock = { unlocked.set(true) }, onAction = { action.set(true) })
        }
        rule.pumpUntil("$preset first frame") { rule.exists(hasAnyText, unmerged = true) }
        // Real time: the script measures elapsed time on the wall clock.
        rule.pumpFor(minOf(readyAt, MAX_PLAY_MS) + 600)
        if (readyAt > MAX_PLAY_MS) {
            rule.onAllNodes(isRoot())[0].performTouchInput { doubleClick(center) }
            rule.pumpFor(600)
        }
        rule.onAllNodes(isRoot())[0].performTouchInput { click(center) }
        rule.pumpFor(200)
        // GRUB's entries are actions; a tap on one counts as well as a tap on the screen.
        assertTrue("$preset: tap after the script neither unlocked nor acted", unlocked.get() || action.get())
    }

    @Test
    fun ownLockSkipsToKeypadAndRejectsWrongPin() {
        val readyAt = LockPresets.script(preset, CoverReader(context).read(), own = true).readyAt
        val unlocked = AtomicBoolean(false)
        rule.setContent {
            val styled = rememberStyled(Presets.default.theme, 0)
            OwnLockContent(
                preset, styled, store,
                onUnlocked = { unlocked.set(true) },
                keypad = KeypadLayout.FULL,
                shuffle = false,
            )
        }
        rule.pumpUntil("$preset first frame") { rule.exists(hasAnyText, unmerged = true) }
        // A double tap jumps to the prompt; if it misses, the script still ends in real time.
        rule.onAllNodes(isRoot())[0].performTouchInput { doubleClick(center) }
        rule.pumpUntil("$preset keypad", timeoutMs = readyAt + 8_000) { rule.exists(hasContentDescription("enter")) }

        rule.onNodeWithContentDescription("5").performClick()
        rule.pumpFor(100)
        rule.onNodeWithContentDescription("enter").performClick()
        rule.pumpUntil("$preset failure recorded") { store.failures().first == 1 }
        // Shake and status line.
        rule.pumpFor(600)
        assertEquals(false, unlocked.get())
    }
}
