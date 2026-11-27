package space.oiupoyt.droidtty

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    @Before
    fun manualClock() {
        rule.mainClock.autoAdvance = false
    }

    @Test
    fun homeComposes() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.pumpUntil("home text") { rule.exists(hasAnyText) }
            // A few seconds of log ticks and CRT frames.
            rule.pumpFor(3_000)
            rule.pumpUntil("home still composed") { rule.exists(hasAnyText) }
        }
    }

    @Test
    fun longPressOpensSettings() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.pumpUntil("home text") { rule.exists(hasAnyText) }
            rule.pumpFor(500)
            val root = rule.onAllNodes(isRoot())[0]
            // Low on the screen, clear of the clock and the menu entries.
            root.performTouchInput { down(Offset(width / 2f, height * 0.85f)) }
            rule.pumpFor(1_000)
            root.performTouchInput { up() }
            rule.pumpUntil("settings root") { rule.exists(hasText("droidtty --config")) }
        }
    }

    @Test
    fun screenExtraOpensSettingsAndBackCloses() {
        // Same entry point as `--es screen settings`: the settings root, then back home.
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java)
            .putExtra("screen", "settings")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            rule.pumpUntil("settings root") { rule.exists(hasText("droidtty --config")) }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.pumpUntil("settings closed") { !rule.exists(hasText("droidtty --config")) }
        }
    }
}
