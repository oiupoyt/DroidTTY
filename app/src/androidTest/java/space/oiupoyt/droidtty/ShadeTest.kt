package space.oiupoyt.droidtty

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.oiupoyt.droidtty.data.CoverPrefs
import space.oiupoyt.droidtty.data.Presets
import space.oiupoyt.droidtty.data.ShadeSection
import space.oiupoyt.droidtty.shade.ShadeContent
import space.oiupoyt.droidtty.shade.Toggles
import space.oiupoyt.droidtty.ui.rememberStyled

/** DroidTTY's notification shade, without Shizuku or notification access. */
@RunWith(AndroidJUnit4::class)
class ShadeTest {

    @get:Rule
    val rule = createComposeRule()

    private val toggles = Toggles(InstrumentationRegistry.getInstrumentation().targetContext) { null }

    @After
    fun closeToggles() = toggles.close()

    private fun render(prefs: CoverPrefs) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ShadeContent(rememberStyled(Presets.default.theme, 0), toggles, close = {}, systemShade = {}, prefs = prefs)
        }
        rule.pumpUntil("toggles section") { rule.exists(hasText("toggles", substring = true), unmerged = true) }
        // Two readout refreshes (every 2 s).
        rule.pumpFor(4_500)
        rule.pumpUntil("shade still shown") { rule.exists(hasText("toggles", substring = true), unmerged = true) }
    }

    @Test
    fun rendersDefault() = render(CoverPrefs())

    @Test
    fun rendersTogglesFirstWithoutReadout() =
        render(CoverPrefs(shadeSections = listOf(ShadeSection.TOGGLES, ShadeSection.NOTIFICATIONS), shadeRows = emptyList()))
}
