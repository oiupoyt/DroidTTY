package space.oiupoyt.droidtty

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import space.oiupoyt.droidtty.ui.settings.Page

/** Every page `--es page <name>` can open (Page.byName) composes inside the real activity. */
@RunWith(Parameterized::class)
class SettingsPagesTest(private val name: String) {

    companion object {
        // First word of each title Page.byName accepts.
        @JvmStatic
        @Parameterized.Parameters(name = "page={0}")
        fun names() = listOf("log", "appearance", "about", "crt", "shizuku", "screen", "notification", "get")
    }

    @get:Rule
    val rule = createEmptyComposeRule()

    @Before
    fun manualClock() {
        rule.mainClock.autoAdvance = false
    }

    @Test
    fun pageOpens() {
        val page = Page.byName(name)
        assertNotNull("Page.byName($name)", page)
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java)
            .putExtra("page", name)
        ActivityScenario.launch<MainActivity>(intent).use {
            val header = "droidtty --config › ${page!!.title}"
            rule.pumpUntil("header \"$header\"") { rule.exists(hasText(header)) }
            // Let the page's own effects (reads, permission checks) run a while.
            rule.pumpFor(1_500)
            rule.pumpUntil("header still shown") { rule.exists(hasText(header)) }
        }
    }
}
