package space.oiupoyt.droidtty

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.oiupoyt.droidtty.data.AodLook
import space.oiupoyt.droidtty.ui.DroidTTYTheme
import space.oiupoyt.droidtty.ui.settings.Choose
import space.oiupoyt.droidtty.ui.settings.detail
import space.oiupoyt.droidtty.ui.settings.title

/**
 * The AOD look picker. Grouping the looks into still/animated once gave two LazyColumn
 * items the same key and crashed the picker as soon as it opened or scrolled.
 */
@RunWith(AndroidJUnit4::class)
class LookPickerTest {

    @get:Rule
    val rule = createComposeRule()

    /** Option rows read "> title" when selected, "  title" otherwise. */
    private fun option(look: AodLook) = hasText("  ${look.title()}") and hasClickAction()

    @Test
    fun everyLookListedAndPickable() {
        var value by mutableStateOf(AodLook.CLOCK)
        val picked = mutableListOf<AodLook>()
        rule.setContent {
            DroidTTYTheme {
                // Same arguments as CoverPage's "look" row.
                Choose(
                    "look",
                    value,
                    AodLook.entries,
                    title = { it.title() },
                    detail = { it.detail() },
                    group = { if (it.animated) "animated (more battery)" else "still" },
                ) { v ->
                    picked += v
                    value = v
                }
            }
        }
        rule.onNodeWithText("${AodLook.CLOCK.title()} ›").performClick()

        val list = rule.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText("## still"))
        // Walking the whole list composes every item, so a repeated key would throw here.
        AodLook.entries.filter { it != AodLook.CLOCK }.forEach { list.performScrollToNode(option(it)) }
        list.performScrollToNode(hasText("## animated (more battery)"))
        rule.onAllNodes(hasText("## still")).assertCountEquals(1)
        rule.onAllNodes(hasText("## animated (more battery)")).assertCountEquals(1)

        list.performScrollToNode(option(AodLook.HELIX))
        rule.onNode(option(AodLook.HELIX)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(AodLook.HELIX), picked)
        // Dialog closed, row shows the pick.
        rule.onAllNodes(hasScrollToIndexAction()).assertCountEquals(0)
        rule.onNodeWithText("${AodLook.HELIX.title()} ›").assertExists()

        // Reopen: the pick is marked, and a still look can be chosen back.
        rule.onNodeWithText("${AodLook.HELIX.title()} ›").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("> ${AodLook.HELIX.title()}"))
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(option(AodLook.MATRIX))
        rule.onNode(option(AodLook.MATRIX)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(AodLook.HELIX, AodLook.MATRIX), picked)
    }
}

/** The same picker inside the real settings › screen page, saving through the view model. */
@RunWith(AndroidJUnit4::class)
class CoverPagePickerTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private fun row(look: AodLook) = hasText("${look.title()} ›") and hasClickAction()
    private fun option(look: AodLook) = hasText("  ${look.title()}") and hasClickAction()

    private fun pick(from: AodLook, to: AodLook) {
        rule.pumpUntil("look row showing $from") { rule.exists(row(from)) }
        // Semantic clicks: the row may sit below the fold of the page's scroll.
        rule.onNode(row(from)).performSemanticsAction(SemanticsActions.OnClick)
        rule.pumpUntil("picker open") { rule.exists(hasText("## still")) }
        rule.pumpUntil("option $to") { rule.exists(option(to)) }
        rule.onNode(option(to)).performSemanticsAction(SemanticsActions.OnClick)
        rule.pumpUntil("look row showing $to") { rule.exists(row(to)) && !rule.exists(hasText("## still")) }
    }

    @Test
    fun pickLookOnScreenPage() {
        rule.mainClock.autoAdvance = false
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java)
            .putExtra("page", "screen")
        ActivityScenario.launch<MainActivity>(intent).use {
            rule.pumpUntil("screen page") { rule.exists(hasText("droidtty --config › screen")) }
            rule.pumpUntil("look row") { AodLook.entries.any { rule.exists(row(it)) } }
            val saved = AodLook.entries.first { rule.exists(row(it)) }
            // CLOCK and STATUS head the list, visible without scrolling; then back to what was saved.
            val other = if (saved == AodLook.STATUS) AodLook.CLOCK else AodLook.STATUS
            pick(saved, other)
            pick(other, saved)
        }
    }
}
