package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.topRight
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import kotlin.math.abs

/**
 * The dialog that changes a colour, on the Colours page for new games: colours to tap, a square and a hue bar to touch,
 * the hex field, and what TalkBack reaches each by, which all pick the same colours; OK alone changes the setting.
 */
@RunWith(AndroidJUnit4::class)
class ColourPickerTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private val red = Settings.ansiColours[1]
    private val redRow get() = hasText(string(R.string.settings_colour_red)) and hasClickAction()
    private val inDialog = hasAnyAncestor(isDialog())
    private val hex get() = hasSetTextAction() and inDialog
    private val hue get() = hasContentDescription(string(R.string.settings_colour_hue)) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
    private val square get() = hasContentDescription(string(R.string.settings_colour_saturation_brightness)) and inDialog
    private val fineTune get() = hasText(string(R.string.settings_colour_fine_tune)) and hasClickAction() and inDialog
    private val redAmount get() = hasContentDescription(string(R.string.settings_colour_red_amount)) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
    private val ok get() = hasText(string(R.string.settings_colour_ok)) and hasClickAction()

    @Before
    fun openTheColoursPage() {
        compose.runOnIdle { Settings.colours.forEach { it.reset() } }
        compose.enableAccessibilityChecksOfWhatShows()
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(emptyList(), session.receive("listProfiles"))
        waitFor(hasText(string(R.string.profiles_empty)))
        activate(hasContentDescription(string(R.string.settings_title)))
        waitFor(hasText(string(R.string.settings_new_games)))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_colours)) and hasClickAction())
        activate(hasText(string(R.string.settings_colours)))
        waitFor(redRow)
        compose.onNode(redRow).performScrollTo()
    }

    @After
    fun putTheColoursBack() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { Settings.colours.forEach { it.reset() } }
    }

    @Test
    fun theColourIsMarkedAmongTheQuickPicksAndATapOnAnotherPicksIt() {
        openRed()
        compose.onNode(isRoot() and hasAnyDescendant(ok)).tryPerformAccessibilityChecks()
        // Red's Mudlet colour is the palette's red, so that one swatch says both
        val mudlets = swatch(string(R.string.settings_colour_mudlets_named, string(R.string.settings_colour_red)))
        compose.onNode(mudlets).assertIsSelected()
        val lightRed = swatch(string(R.string.settings_colour_light_red))
        compose.onNode(lightRed).assertIsNotSelected()

        compose.onNode(lightRed).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(lightRed).assertIsSelected()
        compose.onNode(mudlets).assertIsNotSelected()
        compose.onNode(hex).assert(hasText("#ff0000"))
        assertEquals("one colour marked", 1, compose.onAllNodes(isSelectable() and inDialog).fetchSemanticsNodes().count { it.config.getOrElse(SemanticsProperties.Selected) { false } })
        pressOk()
        compose.runOnIdle { assertEquals(0xFF0000, red.value) }
        compose.onNode(redRow).assert(hasText("#ff0000"))
    }

    @Test
    fun aMudletColourOutsideThePaletteIsOfferedFirst() {
        val commandLine = hasText(string(R.string.settings_colour_command_foreground)) and hasClickAction()
        compose.onNode(commandLine).performScrollTo()
        activate(commandLine)
        waitFor(ok)
        val mudlets = swatch(string(R.string.settings_colour_mudlets))
        compose.onNode(mudlets).assertIsSelected()
        val first = compose.onAllNodes(isSelectable() and inDialog).fetchSemanticsNodes().first()
        assertEquals(string(R.string.settings_colour_mudlets), first.config[SemanticsProperties.ContentDescription].single())
        compose.onNode(swatch(string(R.string.settings_colour_yellow))).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(mudlets).assertIsNotSelected()
        compose.onNode(hex).assert(hasText("#808000"))
    }

    @Test
    fun theHueBarAndTheSquareChangeTheColourByTouch() {
        openRed()
        // Mudlet's red, #800000, half way round the hue bar: the same brightness of cyan
        compose.onNode(hue).performScrollTo().performTouchInput { click(center) }
        val (r, g, b) = channels(shownHex())
        assertTrue(shownHex(), r == 0 && abs(g - 0x80) <= 2 && abs(b - 0x80) <= 2)

        // Dragged from the middle of the square to its top corner at the end, the thumb follows, not the dialog's
        // scrolling: full saturation and brightness
        compose.onNode(square).performScrollTo().performTouchInput { swipe(center, topRight, durationMillis = 300) }
        compose.onNode(square).assert(reads(string(R.string.settings_colour_saturation_brightness_value, 100, 100)))
        val shown = shownHex()
        val (r2, g2, b2) = channels(shown)
        assertTrue(shown, r2 <= 3 && g2 >= 0xFC && b2 >= 0xFC)
        pressOk()
        compose.runOnIdle { assertEquals(shown, Settings.colourName(red.value)) }
    }

    @Test
    fun theHexFieldTakesOnlyAWholeColour_andTheOtherControlsFollowIt() {
        openRed()
        compose.onNode(hex).performTextClearance()
        compose.onNode(hex).performTextInput("#12AB3")
        compose.onNode(ok).assertIsNotEnabled()
        compose.onNode(hex).performTextInput("4")
        compose.onNode(ok).assertIsEnabled()
        compose.onNode(hue).assert(reads(degrees(133)))
        compose.onNode(square).assert(reads(string(R.string.settings_colour_saturation_brightness_value, 89, 67)))
        pressOk()
        compose.runOnIdle { assertEquals(0x12AB34, red.value) }
    }

    @Test
    fun talkBacksControlsReachTheSameColours() {
        openRed()
        compose.onNode(hue).performSemanticsAction(SemanticsActions.SetProgress) { it(240f) }
        compose.onNode(hue).assert(reads(degrees(240)))
        compose.onNode(hex).assert(hasText("#000080"))

        squareAction(R.string.settings_colour_brighter)
        compose.onNode(square).assert(reads(string(R.string.settings_colour_saturation_brightness_value, 100, 55)))
        compose.onNode(hex).assert(hasText("#00008c"))
        squareAction(R.string.settings_colour_less_saturated)
        compose.onNode(hex).assert(hasText("#07078c"))

        compose.onNode(fineTune).assert(reads(string(R.string.profile_section_collapsed)))
        compose.onNode(fineTune).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(fineTune).assert(reads(string(R.string.profile_section_expanded)))
        compose.onNode(isRoot() and hasAnyDescendant(ok)).tryPerformAccessibilityChecks()
        compose.onNode(redAmount).assert(reads("7"))
        compose.onNode(redAmount).performSemanticsAction(SemanticsActions.SetProgress) { it(255f) }
        compose.onNode(hex).assert(hasText("#ff078c"))
        pressOk()
        compose.runOnIdle { assertEquals(0xFF078C, red.value) }
    }

    @Test
    fun theDialogKeepsItsColourThroughARotation() {
        openRed()
        compose.onNode(swatch(string(R.string.settings_colour_light_green))).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(fineTune).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.activityRule.scenario.recreate()
        // The new Activity attaches to the engine again
        val again = fake.engine.nextSession()
        again.profiles(emptyList(), again.receive("listProfiles"))
        waitFor(ok)
        compose.onNode(hex).assert(hasText("#00ff00"))
        compose.onNode(redAmount).assert(reads("0"))
        compose.onNode(hue).assert(reads(degrees(120)))
        pressOk()
        compose.runOnIdle { assertEquals(0x00FF00, red.value) }
    }

    @Test
    fun cancelLeavesTheColourAsItWas() {
        openRed()
        compose.onNode(swatch(string(R.string.settings_colour_blue))).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hue).performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
        compose.onNode(hasText(string(R.string.settings_choice_cancel)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        waitForNoDialog()
        compose.runOnIdle { assertEquals(0x800000, red.value) }
        compose.onNode(redRow).assert(hasText("#800000"))
    }

    private fun openRed() {
        activate(redRow)
        waitFor(ok)
    }

    private fun pressOk() {
        compose.onNode(ok).performSemanticsAction(SemanticsActions.OnClick)
        waitForNoDialog()
    }

    private fun reads(state: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)

    private fun swatch(name: String) = hasContentDescription(name) and isSelectable() and inDialog

    private fun squareAction(@StringRes label: Int) {
        val action = compose.onNode(square).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == string(label) }
        compose.runOnIdle { action.action() }
    }

    private fun shownHex() = compose.onNode(hex).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun channels(hex: String): Triple<Int, Int, Int> {
        val rgb = Settings.parseColourName(hex) ?: error("not a colour: $hex")
        return Triple(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
    }

    private fun degrees(n: Int) = compose.activity.resources.getQuantityString(R.plurals.settings_colour_hue_value, n, n)

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForNoDialog() = compose.waitUntil(WAIT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click. */
    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L
    }
}
