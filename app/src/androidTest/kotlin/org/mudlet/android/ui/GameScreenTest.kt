package org.mudlet.android.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.inputmethod.EditorInfo
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityViewCheckException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.protocol.Profile
import org.mudlet.android.protocol.RUN_REVERSE
import org.mudlet.android.protocol.RefusedCertificate
import org.mudlet.android.ui.game.GAME_PILL_TAG
import org.mudlet.android.ui.game.GAME_SHEET_TAG
import kotlin.math.abs
import androidx.compose.ui.R as ComposeUiR

/**
 * The game screen against a fake engine: the console's lines and how it follows them, the input line, the game pill
 * and its connection chip, what a rotation keeps, and the order TalkBack meets them in. Controls are found by the labels TalkBack reads
 * and activated through their accessibility actions.
 */
@RunWith(AndroidJUnit4::class)
class GameScreenTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val console = hasScrollToIndexAction()
    private val input = hasSetTextAction()

    @Before
    fun openTheGameScreen() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) {
            compose.enableAccessibilityChecks(AccessibilityValidator().setRunChecksFromRootView(true).setSuppressingResultMatcher(ScrimBelowTheSheet(string(ComposeUiR.string.close_sheet))))
        }
        session = fake.engine.nextSession()
        // EngineClient lives as long as the process, so an earlier test's lines would still be there
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        waitUntilDisplayed(string(R.string.game_title))
        compose.onNodeWithText(string(R.string.game_title)).assertIsDisplayed()
    }

    @Test
    fun linesAppearInOrderWithThePromptBelowThem() {
        session.line(1, "first")
        session.line(2, "second")
        session.line(3, "third")
        session.tail("HP:100> ")
        waitUntilDisplayed("HP:100> ")

        val tops = listOf("first", "second", "third", "HP:100> ").map { bounds(hasText(it)).top }
        assertEquals("top to bottom in the order they came: $tops", tops.sorted(), tops)
        assertEquals(tops, tops.distinct())
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun followsNewLinesAtTheBottomAndHoldsWithACountWhenScrolledUp() {
        for (n in 1..MANY) session.line(n.toLong(), "line $n")
        waitUntilDisplayed("line $MANY")
        session.line(MANY + 1L, "line ${MANY + 1}")
        waitUntilDisplayed("line ${MANY + 1}")
        assertNoJumpButton()

        // As TalkBack's scroll actions do, through the list's accessibility action
        compose.onNode(console).performScrollToIndex(0)
        waitUntilDisplayed("line 1")
        for (n in MANY + 2..MANY + 6) session.line(n.toLong(), "line $n")
        val jump = waitForJumpButton(newLines = 5)
        compose.onNodeWithText("line 1").assertIsDisplayed()
        assertFalse("the newest line is not in view while scrolled up", displayed("line ${MANY + 6}"))

        activate(hasText(jump))
        waitUntilDisplayed("line ${MANY + 6}")
        assertNoJumpButton()
        session.line(MANY + 7L, "line ${MANY + 7}")
        waitUntilDisplayed("line ${MANY + 7}")
    }

    @Test
    fun atTheLineLimitItStillFollowsAndHolds() {
        val total = ProfileSession.MAX_LINES + 5
        for (n in 1..total) session.line(n.toLong(), "line $n")
        waitUntilDisplayed("line $total")
        session.line(total + 1L, "line ${total + 1}")
        waitUntilDisplayed("line ${total + 1}")

        // The oldest lines are dropped as new ones come: the lines in view must not move with them
        compose.onNode(console).performScrollToIndex(100)
        val held = compose.runOnIdle { EngineClient.front.lines[100].text }
        waitUntilDisplayed(held)
        for (n in total + 2..total + 4) session.line(n.toLong(), "line $n")
        waitForJumpButton(newLines = 3)
        compose.onNodeWithText(held).assertIsDisplayed()
    }

    @Test
    fun aSendReachesTheEngineAndLeavesFocusOnTheInputLine() {
        compose.onNode(input).performTextInput("look")
        compose.onNode(input).performImeAction()
        assertEquals("look", session.receive("send").getString("text"))
        compose.onNode(input).assertIsFocused().assert(editableText(""))

        compose.onNode(input).performTextInput("north")
        activate(hasContentDescription(string(R.string.input_send)))
        assertEquals("north", session.receive("send").getString("text"))
        compose.onNode(input).assertIsFocused().assert(editableText(""))
    }

    @Test
    fun anEditTheKeyboardSendsJustAfterASendLeavesTheLineEmpty() {
        compose.onNode(input).performTextInput("north")
        val send = compose.onNode(input).assertIsFocused().fetchSemanticsNode().config[SemanticsActions.OnImeAction].action
        compose.runOnUiThread {
            val keyboard = checkNotNull(compose.activity.window.decorView.findFocus()?.onCreateInputConnection(EditorInfo())) { "no input connection" }
            // In one turn of the main thread, so no frame comes between them: a keyboard's edit that reaches the field
            // before it is drawn again, as the keyboard marking the word before the cursor does after a send
            checkNotNull(send)()
            keyboard.setComposingRegion(0, "north".length)
        }
        assertEquals("north", session.receive("send").getString("text"))
        compose.onNode(input).assert(editableText(""))
    }

    @Test
    fun theHistoryButtonsRecallEarlierCommands() {
        for (command in listOf("look", "north")) {
            compose.onNode(input).performTextInput(command)
            compose.onNode(input).performImeAction()
            assertEquals(command, session.receive("send").getString("text"))
            compose.onNode(input).assert(editableText(""))
        }
        compose.onNode(input).performTextInput("draft")

        activate(hasContentDescription(string(R.string.input_history_previous)))
        compose.onNode(input).assert(editableText("north"))
        activate(hasContentDescription(string(R.string.input_history_previous)))
        compose.onNode(input).assert(editableText("look"))
        activate(hasContentDescription(string(R.string.input_history_next)))
        compose.onNode(input).assert(editableText("north"))
        activate(hasContentDescription(string(R.string.input_history_next)))
        compose.onNode(input).assert(editableText("draft"))
    }

    @Test
    fun aRotationKeepsTheTypedTextAndTheScrollPosition() {
        for (n in 1..MANY) session.line(n.toLong(), "line $n")
        waitUntilDisplayed("line $MANY")
        compose.onNode(console).performScrollToIndex(0)
        waitUntilDisplayed("line 1")
        compose.onNode(input).performTextInput("half a comm")

        compose.activityRule.scenario.recreate()

        compose.onNode(input).assert(editableText("half a comm"))
        compose.onNodeWithText("line 1").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.console_jump_to_bottom)).assertIsDisplayed()
    }

    @Test
    fun theGamePillShowsTheStateAndItsSheetReconnectsAndDisconnects() {
        session.status("connection", "disconnected")
        waitForPill(R.string.connection_disconnected)
        compose.runOnIdle { EngineClient.front.connect("127.0.0.1", 4000, false) }
        session.receive("connect")
        session.status("connection", "connecting 127.0.0.1:4000")
        waitForPill(compose.activity.getString(R.string.connection_connecting_to, "127.0.0.1:4000"))
        session.status("connection", "connected")
        waitForPill(string(R.string.connection_connected))

        activate(hasTestTag(GAME_PILL_TAG))
        activate(hasContentDescription(compose.activity.getString(R.string.connection_reconnect_to, GAME.name)))
        val connect = session.receive("connect")
        assertEquals("127.0.0.1", connect.getString("host"))
        assertEquals(4000, connect.getInt("port"))
        assertEquals(false, connect.getBoolean("tls"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(GAME_SHEET_TAG)).fetchSemanticsNodes().isEmpty() }

        activate(hasTestTag(GAME_PILL_TAG))
        activate(hasContentDescription(compose.activity.getString(R.string.connection_disconnect_from, GAME.name)))
        session.receive("disconnect")
    }

    @Test
    fun theGamesOfferOfASecurePortIsAskedAboutAndAnswered() {
        // An address Reconnect remembers, which a yes must not leave it going back to
        compose.runOnIdle { EngineClient.front.connect(GAME.host, GAME.port, false) }
        session.receive("connect")
        session.status("connection", "connected")
        waitForPill(string(R.string.connection_connected))
        assertFalse("no question before the game offers", displayed(string(R.string.secure_offer_title)))

        session.secureOffer(true)
        waitUntilDisplayed(string(R.string.secure_offer_title))
        activate(hasText(string(R.string.secure_offer_accept)))
        assertEquals(true, session.receive("answerSecureOffer").getBoolean("accept"))
        session.secureOffer(false)
        compose.waitUntil(WAIT_MS) { !displayed(string(R.string.secure_offer_title)) }
        session.profiles(listOf(GAME.copy(port = SECURE_PORT, tls = true)))
        compose.waitUntil(WAIT_MS) { EngineClient.front.lastAddress == GameAddress(GAME.host, SECURE_PORT, true) }

        // A refusal is said, and the offer, still standing, is asked about again
        session.secureOffer(true)
        activate(hasText(string(R.string.secure_offer_decline)))
        val no = session.receive("answerSecureOffer")
        assertEquals(false, no.getBoolean("accept"))
        session.refuse(no, "the profile is busy; answer again in a moment")
        waitUntilDisplayed(compose.activity.getString(R.string.secure_offer_refused, "the profile is busy; answer again in a moment"))
        compose.onNodeWithText(string(R.string.secure_offer_title)).assertIsDisplayed()
    }

    @Test
    fun backPutsTheSecurePortOffAndItIsAskedAgainOnlyWhenOfferedAgain() {
        session.status("connection", "connected")
        session.secureOffer(true)
        waitUntilDisplayed(string(R.string.secure_offer_title))
        // The system's Back, which the dialog's own window takes
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitUntil(WAIT_MS) { !displayed(string(R.string.secure_offer_title)) }
        session.assertNoRequest("answerSecureOffer", 1_000)
        compose.onNodeWithText(string(R.string.game_title)).assertIsDisplayed()

        // Mudlet takes the offer back with its connection, and makes it again on the next
        session.secureOffer(false)
        session.secureOffer(true)
        waitUntilDisplayed(string(R.string.secure_offer_title))
    }

    @Test
    fun aRefusedCertificateIsShownAndAcceptingTurnsOnItsSettingAndConnectsAgain() {
        compose.runOnIdle { EngineClient.front.connect(GAME.host, GAME.port, true) }
        session.receive("connect")
        session.status("connection", "disconnected")
        waitForPill(R.string.connection_disconnected)

        // Before the profile has loaded its settings cannot change: said, and still asked
        session.certificateRefused(SELF_SIGNED)
        activate(hasText(string(R.string.certificate_accept)))
        waitUntilDisplayed(compose.activity.getString(R.string.certificate_not_accepted, string(R.string.profiles_error_not_attached)))
        compose.onNodeWithText(string(R.string.certificate_refused_title)).assertIsDisplayed()
        session.assertNoRequest(setOf("setSettings", "connect"), 500)

        session.status("profile", "Game loaded")
        for (fact in listOf(
            compose.activity.getString(R.string.certificate_error, SELF_SIGNED.errors.single()),
            compose.activity.getString(R.string.certificate_issued_to, "localhost"),
            compose.activity.getString(R.string.certificate_issuer, "localhost"),
            compose.activity.getString(R.string.certificate_serial, SELF_SIGNED.serial),
            compose.activity.getString(R.string.certificate_turns_on, string(R.string.settings_accept_self_signed_certificate)),
        )) {
            compose.onNodeWithText(fact).assertIsDisplayed()
        }
        assertFalse(
            "only the switch this certificate needs",
            displayed(compose.activity.getString(R.string.certificate_turns_on, string(R.string.settings_accept_expired_certificate))),
        )

        activate(hasText(string(R.string.certificate_accept)))
        val accepted = generateSequence { session.receive("setSettings") }.first { it.getJSONObject("values").has("sslIgnoreSelfSigned") }
        assertEquals("sslIgnoreSelfSigned", accepted.getJSONObject("values").keys().asSequence().single())
        assertEquals(true, accepted.getJSONObject("values").getBoolean("sslIgnoreSelfSigned"))
        val again = session.receive("connect")
        assertEquals(GAME.port, again.getInt("port"))
        assertTrue(again.getBoolean("tls"))
        compose.waitUntil(WAIT_MS) { !displayed(string(R.string.certificate_refused_title)) }
    }

    @Test
    fun aCertificateOnlyIgnoringAllLetsPastSendsThePlayerToTheGamesSettings() {
        session.status("connection", "disconnected")
        val elsewhere = SELF_SIGNED.copy(issuedTo = "elsewhere.invalid", selfSigned = false, others = true, errors = listOf("The host name did not match any of the valid hosts for this certificate"))
        session.certificateRefused(elsewhere)
        waitUntilDisplayed(string(R.string.certificate_only_ignore_all))
        assertFalse("nothing to accept here", displayed(string(R.string.certificate_accept)))

        // Cancel puts it off; the same certificate, told again as an attach tells it, is not asked about again
        activate(hasText(string(R.string.certificate_cancel)))
        compose.waitUntil(WAIT_MS) { !displayed(string(R.string.certificate_refused_title)) }
        session.certificateRefused(elsewhere)
        session.line(1, "after")
        waitUntilDisplayed("after")
        assertFalse(displayed(string(R.string.certificate_refused_title)))

        // Refused again after another try, it is asked about again
        session.certificateRefused(null)
        session.certificateRefused(elsewhere)
        activate(hasText(string(R.string.certificate_open_settings)))
        waitUntilDisplayed(string(R.string.settings_title))
        session.assertNoRequest("setSettings", 500)
    }

    @Test
    fun reconnectConnectsTheRunningProfileAndOpensItWithItsPasswordOnAnEngineThatHasLostIt() {
        val passwords = PasswordStore(compose.activity)
        assertTrue(passwords.set(GAME.name, "game secret"))
        try {
            // The running profile gets a connect of its own, as an openProfile would leave it connected
            session.status("profile", "Game loaded")
            session.status("connection", "disconnected")
            waitForPill(R.string.connection_disconnected)
            activate(hasContentDescription(compose.activity.getString(R.string.connection_connect_to, GAME.name)))
            val connect = session.receive("connect")
            assertEquals(GAME.host, connect.getString("host"))
            assertEquals(GAME.port, connect.getInt("port"))

            // A new engine with no profile open, as after the notification's Quit: libmudlet takes a bare connect
            // there as one for MUDLET_PROFILE, which it makes with the address, a copy of this profile
            fake.engine.instance = "after quit"
            fake.engine.closeProfile(GAME.name)
            session.close()
            val next = fake.engine.nextSession()
            next.profiles(listOf(GAME), next.receive("listProfiles"))
            val none = compose.activity.getString(R.string.game_state, string(R.string.game_none), string(R.string.connection_disconnected))
            compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(GAME_PILL_TAG) and hasContentDescription(none)).fetchSemanticsNodes().isNotEmpty() }

            // The chip names the profile it opens again
            activate(hasContentDescription(compose.activity.getString(R.string.connection_connect_to, GAME.name)))
            val open = next.receive("openProfile")
            assertEquals(GAME.name, open.getString("name"))
            assertEquals("game secret", open.getString("password"))
            assertTrue(open.getBoolean("connect"))

            next.refuse(open, "there is no profile named \"Game\"")
            waitUntilDisplayed(compose.activity.getString(R.string.connection_reconnect_refused, "openProfile: there is no profile named \"Game\""))
        } finally {
            passwords.forget(GAME.name)
        }
    }

    @Test
    fun theConsoleThenTheInputLineThenTheButtonsTopToBottom() {
        session.line(1, "a line")
        waitUntilDisplayed("a line")
        val consoleBounds = bounds(console)
        val inputBounds = bounds(input)
        val buttons = listOf(R.string.input_history_previous, R.string.input_history_next, R.string.input_send)
            .map { bounds(hasContentDescription(string(it))) }
        val pill = bounds(hasTestTag(GAME_PILL_TAG))

        // TalkBack goes by position: top to bottom, then left to right within a row
        assertTrue("console $consoleBounds above the input line $inputBounds", consoleBounds.bottom <= inputBounds.top)
        var left = inputBounds.right
        for (button in buttons) {
            assertTrue(
                "button $button right of $left, beside the input line $inputBounds",
                button.left >= left && button.top < inputBounds.bottom && button.bottom > inputBounds.top,
            )
            left = button.right
        }
        assertTrue("game pill $pill below the input line $inputBounds", pill.top >= inputBounds.bottom)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun theConsoleTakesItsColoursFromTheEngine() {
        // Reversed, the spaces are drawn in the console's foreground colour
        val reversed = "[    ]"
        session.line(1, reversed, listOf(listOf(0, reversed.length, -1, -1, RUN_REVERSE)))
        session.status("consoleColors", "#102030 #405060")
        waitUntilDisplayed(reversed)
        compose.waitUntil(WAIT_MS) {
            val image = compose.onNode(console).captureToImage().toPixelMap()
            image[image.width - 2, image.height - 2] == Color(0xFF405060)
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(reversed).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val space = results.single().getBoundingBox(2).center
        val image = compose.onNodeWithText(reversed).captureToImage().toPixelMap()
        assertEquals(Color(0xFF102030), image[space.x.toInt(), space.y.toInt()])
    }

    @Test
    fun theScriptsButtonShowsAWandAndSparkles() {
        val button = hasContentDescription(compose.activity.getString(R.string.scripts_open, GAME.name)) and hasClickAction()
        var shared = 0f
        try {
            // Waited for: compose's idle does not wait for the window to show the last frame drawn
            compose.waitUntil(WAIT_MS) {
                shared = sharedShape(compose.onNode(button).captureToImage().toPixelMap(), R.drawable.ic_auto_fix_high)
                shared >= SAME_SHAPE
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the Scripts button's icon shares $shared of its shape with the wand and sparkles, of at least $SAME_SHAPE", e)
        }
    }

    /**
     * How alike the icon in the middle of [shown] and [drawable] are: of the pixels either inks, the share both do, from
     * 0 to 1. The best of the placements within a pixel, as [shown]'s layout may round the middle the other way.
     */
    private fun sharedShape(shown: PixelMap, @DrawableRes drawable: Int): Float {
        val width = shown.width
        val height = shown.height
        val icon = checkNotNull(compose.activity.getDrawable(drawable))
        val left = (width - icon.intrinsicWidth) / 2
        val top = (height - icon.intrinsicHeight) / 2
        icon.setBounds(left, top, left + icon.intrinsicWidth, top + icon.intrinsicHeight)
        val expected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        icon.draw(Canvas(expected))

        // The button's corner is the bar's colour, and the icon's the colour furthest from it: ink is nearer the icon's
        val bar = shown[0, 0]
        val fromBar = Array(height) { y ->
            FloatArray(width) { x -> shown[x, y].let { abs(it.red - bar.red) + abs(it.green - bar.green) + abs(it.blue - bar.blue) } }
        }
        val furthest = fromBar.maxOf { it.max() }

        fun inked(x: Int, y: Int) = x in 0 until width && y in 0 until height && fromBar[y][x] * 2 > furthest
        return (-1..1).maxOf { dy ->
            (-1..1).maxOf { dx ->
                var both = 0
                var either = 0
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val shows = inked(x + dx, y + dy)
                        val wanted = expected.getPixel(x, y) ushr 24 >= 128
                        if (shows && wanted) both++
                        if (shows || wanted) either++
                    }
                }
                if (either == 0) 0f else both.toFloat() / either
            }
        }
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    private fun waitForPill(@StringRes state: Int) = waitForPill(string(state))

    private fun waitForPill(state: String) {
        val reading = compose.activity.getString(R.string.game_pill_description, GAME.name, state, 1, 1)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(GAME_PILL_TAG) and hasContentDescription(reading)).fetchSemanticsNodes().isNotEmpty() }
    }

    // As plain text: the field's EditableText can carry spans, so comparing AnnotatedStrings fails on equal text
    private fun editableText(text: String) = SemanticsMatcher("EditableText = '$text'") {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text == text
    }

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun displayed(text: String) = try {
        compose.onNodeWithText(text).assertIsDisplayed()
        true
    } catch (_: AssertionError) {
        false
    }

    private fun waitUntilDisplayed(text: String) = compose.waitUntil(WAIT_MS) { displayed(text) }

    /** The jump button's label, once it shows [newLines]. */
    private fun waitForJumpButton(newLines: Int): String {
        val label = compose.activity.resources.getQuantityString(R.plurals.console_jump_new_lines, newLines, newLines)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        return label
    }

    private fun assertNoJumpButton() {
        val label = string(R.string.console_jump_to_bottom)
        assertTrue("no jump button", compose.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isEmpty())
    }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on every window. */
    private fun activate(label: SemanticsMatcher) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(label and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        // Each window's: the game sheet is a window of its own over the activity's
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) {
            try {
                roots[i].tryPerformAccessibilityChecks()
            } catch (e: AccessibilityViewCheckException) {
                val flagged = e.results.joinToString("\n") { "${it.element?.className} '${it.element?.contentDescription}' '${it.element?.text}'" }
                throw AssertionError("${e.message}\n$flagged", e)
            }
        }
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)
        val SELF_SIGNED = RefusedCertificate("localhost", "localhost", "2030-01-02T00:00:00Z", "4A:2B", false, true, false, listOf("The certificate is self-signed, and untrusted"))

        // More lines than any emulator screen shows
        const val MANY = 200
        const val SECURE_PORT = 4443
        const val WAIT_MS = 10_000L

        // Of 1 for the same shape, and 0.71 at worst when drawn part of a pixel off; the pencil, the most like the wand
        // of the app's other icons, shares 0.6 with it
        const val SAME_SHAPE = 0.65f

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
