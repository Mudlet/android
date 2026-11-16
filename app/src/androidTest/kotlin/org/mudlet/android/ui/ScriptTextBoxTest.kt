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
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.Profile

/**
 * The text boxes a profile's scripts make with createTextEdit() (docs/phase-f.md, "Text boxes"), against a fake engine:
 * what the player types reaches the engine as `setTextBoxText`, once, and is not taken back; a script's new text
 * replaces the field's; a read-only box cannot be typed in; a refusal puts back what the engine holds; a small box is
 * touched within 48 dp; and each is read by its placeholder or its name.
 */
@RunWith(AndroidJUnit4::class)
class ScriptTextBoxTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    @Before
    fun openTheGameScreen() {
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        // A new engine instance for each test, so no window of an earlier test is left
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, "the main console")
        session.synced(1)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        compose.onRoot().tryPerformAccessibilityChecks()
        val connect = hasContentDescription(string(R.string.profile_connect_to, GAME.name)) and hasClickAction()
        compose.onNode(connect).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("the main console")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("the main console").assertIsDisplayed()
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        session.mainArea(800, 600, 100, 40, 0, 0, 800, 600)
    }

    @After
    fun automaticLayout() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.AUTO }
    }

    @Test
    fun whatThePlayerTypesTheEngineGetsOnceAndAScriptsNewTextReplacesIt() {
        session.windows(listOf(textBox("notes", w = 400, h = 300)), full = true)
        session.textBox("notes", text = "first", placeholder = "Write here")
        val field = field("Write here")
        awaitNode(field and hasText("first"))

        compose.onNode(field).performTextReplacement("the player typed this")
        val typed = receiveTyping()
        assertEquals("notes", typed.getString("name"))
        assertEquals("the player typed this", typed.getString("text"))
        // Typing goes on from where it was
        compose.onNode(field).performTextInput(", and this")
        assertEquals("the player typed this, and this", receiveTyping().getString("text"))
        // What was sent is not sent again, nor is the field's text taken back
        assertNoTypingSent()
        compose.onNode(field).assert(hasText("the player typed this, and this"))

        // A script's new text is the field's, and is not sent back
        session.textBox("notes", text = "the script's", placeholder = "Write here")
        awaitNode(field and hasText("the script's"))
        assertNoTypingSent()
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aReadOnlyBoxCannotBeTypedInAndIsReadAsSuch() {
        session.windows(listOf(textBox("log", w = 400, h = 300)), full = true)
        session.textBox("log", text = "read me", readOnly = true)
        // With no placeholder, read by its name
        val field = field(string(R.string.text_box_read_only, "log"))
        awaitNode(field and hasText("read me"))

        compose.onNode(field).assertIsEnabled().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.InsertTextAtCursor))
        assertNoTypingSent()
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aRefusalPutsBackWhatTheEngineHolds() {
        session.windows(listOf(textBox("notes", w = 400, h = 300)), full = true)
        session.textBox("notes", text = "kept")
        val field = field("notes")
        awaitNode(field and hasText("kept"))

        compose.onNode(field).performTextReplacement("not taken")
        val typed = receiveTyping()
        // As the engine answers a box a script made read-only as the player typed
        session.refuse(typed, "the text box notes is read-only")
        session.textBox("notes", text = "kept", readOnly = true)
        val readOnly = field(string(R.string.text_box_read_only, "notes"))
        awaitNode(readOnly and hasText("kept"))
        compose.onNode(readOnly).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        assertNoTypingSent()
    }

    @Test
    fun aSmallBoxIsTouchedWithin48dpAroundIt() {
        session.windows(listOf(textBox("tiny", x = 395, y = 295, w = 10, h = 10)), full = true)
        session.textBox("tiny", placeholder = "Say")
        val field = field("Say")
        awaitNode(field)

        val touch = compose.onNode(field).fetchSemanticsNode().boundsInRoot
        val min = with(compose.density) { 48.dp.toPx() }
        assertTrue("touch width ${touch.width} < $min", touch.width >= min - 1)
        assertTrue("touch height ${touch.height} < $min", touch.height >= min - 1)
        // Its name is on a node as big as the field, which the screen reader reads with it: inside the box's frame and
        // margins it would have no size, and so not be read
        val name = compose.onNode(hasContentDescription("Say"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("name width ${name.width} < $min", name.width >= min - 1)
        assertTrue("name height ${name.height} < $min", name.height >= min - 1)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    // The editable field read by [label], which it carries itself or, in the unmerged tree, a node inside it does
    private fun field(label: String): SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and
        (hasContentDescription(label) or hasAnyDescendant(hasContentDescription(label)))

    // The field sends the player's typing once they stop, a wait on the test's clock, which no idling advances while a
    // socket read blocks; so the clock is moved on past it first
    private fun receiveTyping(): JSONObject {
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        return session.receive("setTextBoxText")
    }

    // Nor is a send that would come once the typing settles missed
    private fun assertNoTypingSent() {
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        session.assertNoRequest("setTextBoxText", QUIET_MS)
    }

    private fun awaitNode(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun textBox(name: String, x: Int = 0, y: Int = 0, w: Int, h: Int) = FakeEngine.window(name, kind = "textBox", x = x, y = y, w = w, h = h)

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private companion object {
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)

        const val INSTANCE = "fake-text-boxes"
        const val WAIT_MS = 10_000L

        // How long the UI stays quiet for it to have sent nothing, in real time
        const val QUIET_MS = 1_000

        // Longer than the field waits, on the test's clock, for the player's typing to settle
        const val SETTLED_MS = 400L
        const val TIMEOUT_MS = 240_000L
    }
}
