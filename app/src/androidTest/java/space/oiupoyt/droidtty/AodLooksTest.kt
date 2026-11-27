package space.oiupoyt.droidtty

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import space.oiupoyt.droidtty.cover.AodContent
import space.oiupoyt.droidtty.data.AodColor
import space.oiupoyt.droidtty.data.AodLook
import space.oiupoyt.droidtty.data.CoverPrefs

/** Each always-on look draws and keeps redrawing for a couple of seconds. */
@RunWith(Parameterized::class)
class AodLooksTest(private val look: AodLook) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun looks() = AodLook.entries.toList()
    }

    @get:Rule
    val rule = createComposeRule()

    @Before
    fun manualClock() {
        rule.mainClock.autoAdvance = false
    }

    @Test
    fun rendersAndAnimates() {
        val shown = AtomicInteger()
        rule.setContent {
            AodContent(CoverPrefs(aodLook = look), onTap = null, onShown = { shown.incrementAndGet() })
        }
        rule.pumpUntil("$look first frame") { rule.exists(hasAnyText, unmerged = true) }
        val before = shown.get()
        rule.pumpFor(2_500)
        assertTrue("$look stopped redrawing ($before -> $shown)", shown.get() > before)
        assertTrue("$look lost its text", rule.exists(hasAnyText, unmerged = true))
    }

    @Test
    fun rendersScaledMono() {
        rule.setContent {
            AodContent(CoverPrefs(aodLook = look, aodScale = 2f, aodColor = AodColor.MONO, aodShift = false), onTap = null)
        }
        rule.pumpUntil("$look first frame") { rule.exists(hasAnyText, unmerged = true) }
        rule.pumpFor(500)
    }
}
