package org.mudlet.android.ui

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.KeyBinding
import org.mudlet.android.protocol.KeyModifier
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.HardwareKeyboard
import org.mudlet.android.ui.game.MOVEMENT_PAD_TAG
import org.mudlet.android.ui.settings.SETTINGS_HUB_TAG

/**
 * The game screen's movement pad against a fake engine: it is there from the start, below the input line, and covers
 * neither the game's text nor the input line; a key sends its direction's word as typed, or presses the
 * profile's key binding on that direction's number pad key; it is away while the game hides input, with a hardware
 * keyboard and while the keyboard is up; and its own button and its switch in the settings put it away for good.
 * Keys are found by the names TalkBack reads and pressed through their accessibility actions.
 */
@RunWith(AndroidJUnit4::class)
class MovementPadTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val pad = hasTestTag(MOVEMENT_PAD_TAG)
    private val console = hasScrollToIndexAction()
    private val input = hasSetTextAction()

    @Before
    fun openTheGameScreen() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        compose.runOnIdle {
            // The emulator's own keyboard, where it has one, is not the test's subject
            HardwareKeyboard.attachedOverride = false
            // The fake engine's rule turns the pad off for the tests that are not about it
            Settings.movementPad.reset()
        }
        session = fake.engine.nextSession()
        // EngineClient lives as long as the process, so an earlier test's lines and key bindings would still be there
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        bind()
        session.line(1, CONSOLE_LINE)
        waitFor(hasText(CONSOLE_LINE))
    }

    // FakeEngineRule puts the setting back
    @After
    fun reset() = compose.runOnIdle { HardwareKeyboard.attachedOverride = null }

    @Test
    fun thePadIsThereFromTheStartBelowTheInputLineAndCoversNeitherTheTextNorTheInputLine() {
        waitFor(pad)
        assertTrue("on unless the player turns it off", Settings.movementPad.default)
        compose.onRoot().tryPerformAccessibilityChecks()

        val padBounds = bounds(pad)
        val line = bounds(input)
        val text = bounds(console)
        assertTrue("the pad $padBounds is below the input line $line", padBounds.top >= line.bottom)
        assertTrue("the input line $line is below the game's text $text", line.top >= text.bottom)
        val least = with(compose.density) { MIN_TEXT.toPx() }
        assertTrue("the game's text $text keeps $MIN_TEXT of the screen", text.height >= least)
        compose.onNode(hasText(CONSOLE_LINE)).assertIsDisplayed()

        val target = with(compose.density) { 48.dp.toPx() }
        val keys = (KEYS.map { it.first } + R.string.movement_pad_hide).map { name ->
            val node = compose.onNode(key(name)).assertIsDisplayed().fetchSemanticsNode()
            val touch = node.touchBoundsInRoot
            assertTrue("${string(name)} is $touch to touch", touch.width >= target - 0.5f && touch.height >= target - 0.5f)
            assertTrue("${string(name)} $touch is in the pad $padBounds", touch.left >= padBounds.left - 0.5f && touch.right <= padBounds.right + 0.5f && touch.top >= padBounds.top - 0.5f && touch.bottom <= padBounds.bottom + 0.5f)
            string(name) to touch
        }
        assertEquals("each key has a name of its own", keys.size, keys.map { it.first }.toSet().size)
        for ((i, a) in keys.withIndex()) {
            for (b in keys.drop(i + 1)) {
                assertFalse("${a.first} ${a.second} and ${b.first} ${b.second} are apart", a.second.deflate(0.5f).overlaps(b.second.deflate(0.5f)))
            }
        }

        // As on a map, and on the number pad
        val north = bounds(key(R.string.movement_north))
        val south = bounds(key(R.string.movement_south))
        val west = bounds(key(R.string.movement_west))
        val east = bounds(key(R.string.movement_east))
        assertTrue("north $north is over south $south", north.bottom <= south.top && north.left == south.left)
        assertTrue("west $west is left of east $east", west.right <= east.left && west.top == east.top)
        assertTrue("north-east is right of north, and over east", bounds(key(R.string.movement_northeast)).let { it.left == east.left && it.top == north.top })
        assertTrue("south-west is left of south, and under west", bounds(key(R.string.movement_southwest)).let { it.left == west.left && it.top == south.top })
    }

    @Test
    fun aKeySendsItsDirectionsWordAsTyped() {
        waitFor(pad)
        for ((name, word) in KEYS) {
            activate(key(name))
            assertEquals(string(name), word, session.receive("send").getString("text"))
        }
        session.assertNoRequest("pressKey", QUIET_MS)
        // As typed: the history has them, the last sent first
        compose.runOnIdle { assertEquals(KEYS.map { it.second }.reversed(), EngineClient.front.history.take(KEYS.size)) }
    }

    @Test
    fun aKeyPressesTheProfilesBindingOnItsNumberPadKey() {
        val keypad8 = KeyBinding('8'.code, KeyModifier.KEYPAD.bit)
        // Its own north, and a binding on the 2 of the top row, which is not the number pad's
        bind(keypad8, KeyBinding('2'.code, 0))
        waitFor(pad)

        val history = compose.runOnIdle { EngineClient.front.history }
        activate(key(R.string.movement_north))
        val press = session.receive("pressKey")
        assertEquals(keypad8.key, press.getInt("key"))
        assertEquals(keypad8.modifiers, press.getInt("modifiers"))
        assertEquals("8", press.getString("text"))
        session.assertNoRequest("send", QUIET_MS)
        compose.runOnIdle { assertEquals("a binding's press is no typed command", history, EngineClient.front.history) }

        activate(key(R.string.movement_south))
        assertEquals("s", session.receive("send").getString("text"))
        activate(key(R.string.movement_up))
        assertEquals("u", session.receive("send").getString("text"))
        session.assertNoRequest("pressKey", QUIET_MS)
    }

    @Test
    fun thePadIsAwayWhileTheGameHidesInput() {
        waitFor(pad)
        session.hiddenInput(true)
        waitForNo(pad)
        session.hiddenInput(false)
        waitFor(pad)
    }

    @Test
    fun thePadIsAwayWithAHardwareKeyboard() {
        waitFor(pad)
        compose.runOnIdle { HardwareKeyboard.attachedOverride = true }
        waitForNo(pad)
        compose.runOnIdle {
            assertTrue("the setting stays on", Settings.movementPad.value)
            HardwareKeyboard.attachedOverride = false
        }
        waitFor(pad)
    }

    @Test
    fun withNoOverrideThePadFollowsTheDevicesOwnAnswer() {
        // Not assumed away: CI's API 36 emulator reports a QWERTY keyboard and API 28's none, and a skip fails the run
        val configuration = compose.activity.resources.configuration
        val attached = configuration.keyboard == Configuration.KEYBOARD_QWERTY && configuration.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
        waitFor(pad)
        compose.runOnIdle { HardwareKeyboard.attachedOverride = null }
        compose.waitForIdle()
        if (attached) compose.onNode(pad).assertDoesNotExist() else compose.onNode(pad).assertExists()
    }

    @Test
    fun thePadIsAwayWhileTheKeyboardIsUp() {
        waitFor(pad)
        compose.onNode(input).performClick()
        val came = try {
            compose.waitUntil(KEYBOARD_MS) { keyboardShown() }
            true
        } catch (_: ComposeTimeoutException) {
            false
        }
        assumeTrue("a device with keys of its own shows no keyboard", came)
        waitForNo(pad)

        compose.runOnUiThread {
            val keyboard = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        waitFor(pad)
    }

    @Test
    fun itsButtonPutsThePadAwayForGoodAndUndoBringsItBack() {
        waitFor(pad)
        activate(key(R.string.movement_pad_hide))
        waitForNo(pad)
        waitFor(hasText(string(R.string.movement_pad_hidden)))
        compose.runOnIdle { assertFalse(Settings.movementPad.value) }
        assertFalse("stored, so it stays away", stored())

        activate(hasText(string(R.string.movement_pad_undo)))
        waitFor(pad)
        compose.runOnIdle { assertTrue(Settings.movementPad.value) }
        assertTrue(stored())
        waitForNo(hasText(string(R.string.movement_pad_hidden)))

        // Put away and left so, it is still away for the screen made anew, as after a rotation
        activate(key(R.string.movement_pad_hide))
        waitForNo(pad)
        compose.activityRule.scenario.recreate()
        waitFor(hasText(CONSOLE_LINE))
        compose.waitForIdle()
        waitForNo(pad)
        compose.runOnIdle { assertFalse(Settings.movementPad.value) }
    }

    @Test
    fun theSettingsSwitchTurnsThePadOffAndOn() {
        waitFor(pad)
        val row = hasText(string(R.string.settings_movement_pad)) and hasClickAction()
        fun openItsPage() {
            compose.pickFromGameMenu(string(R.string.settings_title))
            waitFor(hasTestTag(SETTINGS_HUB_TAG))
            compose.scrollSettingsHubTo(hasText(string(R.string.settings_appearance)))
            activate(hasText(string(R.string.settings_appearance)))
            waitFor(row)
            // The settings screen scrolls, and TalkBack scrolls to what it moves to
            compose.onNode(row).performScrollTo()
        }

        fun backToTheGame() {
            activate(hasContentDescription(string(R.string.navigate_back)))
            activate(hasContentDescription(string(R.string.navigate_back)))
            waitFor(hasText(CONSOLE_LINE))
        }

        openItsPage()
        compose.onNode(row).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        compose.onNode(hasText(string(R.string.settings_movement_pad_hint))).assertExists()
        activate(row)
        compose.onNode(row).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        compose.runOnIdle { assertFalse(Settings.movementPad.value) }
        backToTheGame()
        compose.waitForIdle()
        waitForNo(pad)

        openItsPage()
        activate(row)
        compose.onNode(row).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        backToTheGame()
        waitFor(pad)
    }

    private fun bind(vararg keys: KeyBinding) {
        val list = JSONArray()
        for (key in keys) list.put(JSONObject().put("key", key.key).put("modifiers", key.modifiers))
        session.send(JSONObject().put("t", "keyBindings").put("keys", list))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.windows.keyBindings } == keys.toSet() }
    }

    private fun stored(): Boolean = compose.activity.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("movementPad", true)

    private fun keyboardShown(): Boolean {
        val view = compose.activity.window.decorView
        return compose.runOnUiThread<Boolean> { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
    }

    private fun key(@StringRes name: Int) = hasContentDescription(string(name)) and hasClickAction()

    private fun bounds(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForNo(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(label and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val GAME = Profile("Walk", "127.0.0.1", 4000, false, "", false)

        // Each key's name for TalkBack, and the word it sends where the profile binds no number pad key
        val KEYS = listOf(
            R.string.movement_north to "n",
            R.string.movement_northeast to "ne",
            R.string.movement_east to "e",
            R.string.movement_southeast to "se",
            R.string.movement_south to "s",
            R.string.movement_southwest to "sw",
            R.string.movement_west to "w",
            R.string.movement_northwest to "nw",
            R.string.movement_up to "u",
            R.string.movement_down to "d",
            R.string.movement_in to "in",
            R.string.movement_out to "out",
        )

        const val CONSOLE_LINE = "You stand at a crossroads."

        // What the pad must leave the game's text of the CI emulator's 640 dp: eight lines at the text size it starts at
        val MIN_TEXT = 160.dp

        const val WAIT_MS = 10_000L
        const val KEYBOARD_MS = 5_000L
        const val QUIET_MS = 500

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
