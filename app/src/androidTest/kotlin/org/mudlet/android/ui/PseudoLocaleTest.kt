package org.mudlet.android.ui

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.scripts.LUA_EDITOR_TAG
import java.util.Locale
import kotlin.math.abs

/**
 * The app in the debug build's pseudo-locales (docs/translations.md, "Pseudo-locales"): en-XA, whose accented text
 * runs longer than the English, and ar-XB, right to left. On the profile list, empty and with a game, the profile
 * editor, new and at a profile's Export, the settings, the game screen, with the connection chip's states and the password field, the script library,
 * and a trigger's page, with its Lua being typed, no text is
 * cut off, overflows its box or leaves the screen, and in ar-XB the screens are mirrored. The app's language is set as the system's per-app language setting sets it,
 * which Android 13 brought.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class PseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val rtl = locale == AR_XB

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    @Before
    fun enableChecks() {
        compose.enableAccessibilityChecksOfWhatShows()
    }

    @Test
    fun everyScreenFitsItsTextAndFollowsTheLayoutDirection() {
        // The pseudo-locale is in force: its text is not the English
        assertNotEquals("$locale's title", english(R.string.profiles_title), string(R.string.profiles_title))

        compose.onNodeWithText(string(R.string.profiles_title)).assertIsDisplayed()
        val session = fake.engine.nextSession()
        // EngineClient lives as long as the process, so an earlier test's lines would still be there
        session.clear()
        session.synced(0)
        session.profiles(emptyList(), session.receive("listProfiles"))
        waitFor(hasText(string(R.string.profiles_empty)))
        assertTextFits("the empty profile list")
        assertAtStart("the empty profile list's text", hasText(string(R.string.profiles_empty)))

        activate(hasContentDescription(string(R.string.profiles_add)))
        waitFor(hasText(string(R.string.profile_new_title)))
        assertTextFits("the profile editor")
        assertAtStart("the profile editor's Back button", hasContentDescription(string(R.string.navigate_back)))
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasText(string(R.string.profiles_title)))

        activate(hasContentDescription(string(R.string.settings_title)))
        waitFor(hasText(string(R.string.settings_new_games)))
        assertTextFits("the settings")
        assertAtStart("the settings' first heading", hasText(string(R.string.settings_new_games)))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_appearance)))
        activate(hasText(string(R.string.settings_appearance)))
        waitFor(hasText(string(R.string.settings_text_size)))
        assertTextFits("a settings page")
        assertAtStart("the page's first setting", hasText(string(R.string.settings_text_size)))
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasText(string(R.string.profiles_title)))

        session.profiles(listOf(GAME))
        waitFor(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        assertTextFits("the profile list")
        assertAtStart("a profile", hasContentDescription(string(R.string.profile_description, GAME.name, GAME.host, GAME.port)))

        activate(hasContentDescription(string(R.string.profile_edit, GAME.name)))
        waitFor(hasText(string(R.string.profile_export_text)))
        compose.onNodeWithText(string(R.string.profile_export_text)).performScrollTo()
        assertTextFits("a profile's editor, at its Export")
        assertAtStart("the Export heading", hasText(string(R.string.profile_export_title)))
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))

        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        waitFor(hasText(string(R.string.game_title)))
        assertTextFits("the game screen")
        assertAtStart("the Back button", hasContentDescription(string(R.string.navigate_back)))

        session.status("connection", "connecting $ADDRESS")
        waitFor(hasText(string(R.string.connection_stop)))
        assertTextFits("the game pill's row, connecting")
        session.status("connection", "error: $REASON")
        waitFor(hasText(string(R.string.connection_try_again)))
        assertTextFits("the game pill's row, failed")

        session.hiddenInput(true)
        waitFor(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertTextFits("the password field")
        session.hiddenInput(false)

        activate(hasContentDescription(string(R.string.scripts_open, GAME.name)))
        val node = JSONObject().put("id", 1).put("parent", 0).put("name", "Greet").put("folder", false).put("enabled", false)
            .put("active", false).put("package", "").put("error", "")
        // The library asks as it opens, from its composition, which goes on while the test waits on it
        compose.waitForIdle()
        for (kind in listOf("trigger", "alias", "script", "timer", "key", "button")) {
            val ask = session.receive("scriptItems")
            val items = when (ask.getString("kind")) {
                "trigger", "timer", "key" -> JSONArray().put(node)
                "button" -> JSONArray().put(JSONObject(node.toString()).put("name", "Bar").put("folder", true)).put(JSONObject(node.toString()).put("id", 2).put("parent", 1))
                else -> JSONArray()
            }
            session.send(JSONObject().put("t", "scriptItems").put("kind", ask.getString("kind")).put("items", items).put("re", ask.get("id")))
        }
        waitFor(hasText(string(R.string.scripts_off)))
        assertTextFits("the script library")
        assertAtStart("the triggers' chip", hasText(string(R.string.scripts_kind_count, string(R.string.scripts_triggers), 1)))

        activate(hasText("Greet"))
        compose.waitForIdle()
        val open = session.receive("scriptItem")
        val trigger = JSONObject(node.toString()).put("script", "").put("command", "").put("lineDelta", 2).put("matchAll", true)
            .put("filter", false).put("fireLength", 0).put("sound", true).put("soundFile", "").put("highlight", true).put("highlightFg", "#ff0000")
            .put("highlightBg", "").put("patterns", JSONArray().put(JSONObject().put("text", "You wave").put("type", "substring")))
        session.send(JSONObject().put("t", "scriptItem").put("kind", "trigger").put("fields", trigger).put("re", open.get("id")))
        waitFor(hasText(string(R.string.scripts_pattern, 1)))
        assertTextFits("a trigger's page")
        assertAtStart("the patterns' heading", hasText(string(R.string.scripts_patterns)))

        // The keys over the keyboard come once the Lua has it
        val lua = hasTestTag(LUA_EDITOR_TAG)
        compose.onNode(lua).performScrollTo()
        compose.onNode(lua).performSemanticsAction(SemanticsActions.RequestFocus)
        val keys = hasContentDescription(string(R.string.lua_indent))
        waitFor(keys)
        // Measured once the page has stopped moving over the keyboard that comes with them
        compose.keyboardHasCome(keys, WAIT_MS)
        assertTextFits("a trigger's page with its Lua being typed")
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasText(string(R.string.scripts_off)))

        // The fields have text: an empty one whose top alone shows at the page's foot has no name for the checks
        // (docs/roadmap.md, "A known gap, the toolkit's")
        fun open(kind: String, fields: JSONObject) {
            compose.waitForIdle()
            val ask = session.receive("scriptItem")
            session.send(JSONObject().put("t", "scriptItem").put("kind", kind).put("fields", fields.put("script", "")).put("re", ask.get("id")))
        }
        activate(hasText(string(R.string.scripts_kind_count, string(R.string.scripts_timers), 1)))
        activate(hasText("Greet"))
        open("timer", JSONObject(node.toString()).put("command", "look").put("time", 3_723_004).put("offset", true))
        waitFor(hasText(string(R.string.scripts_timer_offset)))
        assertTextFits("a timer's page")
        assertAtStart("the time's heading", hasText(string(R.string.scripts_timer_after)))
        activate(hasContentDescription(string(R.string.navigate_back)))

        activate(hasText(string(R.string.scripts_kind_count, string(R.string.scripts_keys), 1)))
        activate(hasText("Greet"))
        open("key", JSONObject(node.toString()).put("command", "north").put("key", 0x38).put("modifiers", 0x20000000).put("keyName", "keypad + 8"))
        waitFor(hasText(string(R.string.scripts_key_note)))
        assertTextFits("a key's page")
        activate(hasContentDescription(string(R.string.navigate_back)))

        val button = JSONObject(node.toString()).put("commandDown", "wield sword").put("commandUp", "remove sword").put("pushDown", true).put("location", "top")
            .put("orientation", "horizontal").put("columns", 1).put("css", "color: red;")
        activate(hasText(string(R.string.scripts_kind_count, string(R.string.scripts_buttons), 1)))
        activate(hasText("Bar"))
        activate(hasContentDescription(string(R.string.scripts_edit_folder)))
        open("button", JSONObject(button.toString()).put("name", "Bar").put("folder", true))
        waitFor(hasText(string(R.string.scripts_bar_vertical_hint)))
        assertTextFits("a toolbar's page")
        assertAtStart("the toolbar's heading", hasText(string(R.string.scripts_bar_place)))
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasText("Greet"))
        open("button", JSONObject(button.toString()).put("id", 2).put("parent", 1))
        waitFor(hasText(string(R.string.scripts_button_push_down_hint)))
        assertTextFits("a button's page")
        activate(hasContentDescription(string(R.string.navigate_back)))

        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasText(string(R.string.game_title)))

        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasText(string(R.string.profiles_title)))

        activate(hasContentDescription(string(R.string.about_title)))
        waitFor(hasText(string(R.string.about_report)))
        activate(hasText(string(R.string.about_report)))
        waitFor(hasText(string(R.string.report_contents_heading)))
        assertTextFits("the problem report")
        assertAtStart("the problem report's heading", hasText(string(R.string.report_contents_heading)))
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasText(string(R.string.profiles_title)))
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun english(@StringRes id: Int): String {
        val config = Configuration(compose.activity.resources.configuration).apply { setLocales(LocaleList(Locale.US)) }
        return compose.activity.createConfigurationContext(config).getString(id)
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    /**
     * Every text on the screen is laid out whole within its box (no ellipsis, no line left out, nothing clipped below
     * its last line) and within the screen's width, after the accessibility checks. Not hasVisualOverflow: the layout
     * the semantics hand over is laid out across the width the text was offered, so a single line narrower than that
     * would count as overflowing its box.
     */
    private fun assertTextFits(where: String) {
        compose.onRoot().tryPerformAccessibilityChecks()
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val width = root.size.width
        val problems = ArrayList<String>()
        var checked = 0
        fun visit(node: SemanticsNode) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                ?: node.config.getOrNull(SemanticsProperties.EditableText)?.text
            val layouts = ArrayList<TextLayoutResult>()
            if (!text.isNullOrEmpty()) node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            checked += layouts.size
            for (layout in layouts) {
                if ((0 until layout.lineCount).any { layout.isLineEllipsized(it) }) problems += "\"$text\" is cut short"
                if (layout.multiParagraph.didExceedMaxLines) problems += "\"$text\" has more lines than it may show"
                if (layout.multiParagraph.height > layout.size.height + SLACK_PX) {
                    problems += "\"$text\" is ${layout.multiParagraph.height} px tall in its ${layout.size} box"
                }
            }
            val bounds = node.boundsInRoot
            if (layouts.isNotEmpty() && (bounds.left < -SLACK_PX || bounds.right > width + SLACK_PX)) {
                problems += "\"$text\" at $bounds is outside the screen's width $width"
            }
            node.children.forEach(::visit)
        }
        visit(root)
        // A screen with no text laid out would pass without being checked
        assertTrue("$locale, $where: no text on the screen gave its layout", checked > 0)
        assertTrue("$locale, $where: ${problems.joinToString("; ")}", problems.isEmpty())
    }

    /**
     * The node is nearer the start of a line than its end: the left in en-XA, the right in ar-XB. A node as wide as the
     * screen allows, such as text that wraps, is at neither side, so its lines must start at the start of its box.
     */
    private fun assertAtStart(what: String, matcher: SemanticsMatcher) {
        val node = compose.onNode(matcher).fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val width = compose.onRoot().fetchSemanticsNode().size.width
        val fromLeft = bounds.left
        val fromRight = width - bounds.right
        val side = if (rtl) "right" else "left"
        val atStart = if (abs(fromLeft - fromRight) <= SLACK_PX) {
            linesStartAtStart(node)
        } else if (rtl) {
            fromRight < fromLeft
        } else {
            fromLeft < fromRight
        }
        assertTrue("$locale: $what at $bounds is not on the $side of the $width px screen", atStart)
    }

    private fun linesStartAtStart(node: SemanticsNode): Boolean {
        val layouts = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.firstOrNull() ?: return false
        val width = layout.multiParagraph.width
        return (0 until layout.lineCount).all { line ->
            val left = layout.getLineLeft(line)
            val right = width - layout.getLineRight(line)
            if (rtl) right <= left + SLACK_PX else left <= right + SLACK_PX
        }
    }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    companion object {
        private const val EN_XA = "en-XA"
        private const val AR_XB = "ar-XB"
        private const val ADDRESS = "mud.example.org:4000"
        private val GAME = Profile("Game", "mud.example.org", 4000, false, "", false)
        private const val REASON = "Connection refused by the remote host"

        // Rounding of the bounds to pixels
        private const val SLACK_PX = 1f

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        private const val TIMEOUT_MS = 240_000L
        private const val WAIT_MS = 10_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf(EN_XA, AR_XB)
    }
}
