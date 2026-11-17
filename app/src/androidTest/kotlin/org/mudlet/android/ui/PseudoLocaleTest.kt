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
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
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
 * editor, the settings, the game screen, with the connection chip's states and the password field, the script library,
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
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
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
        for (kind in listOf("trigger", "alias", "script")) {
            val ask = session.receive("scriptItems")
            val items = if (ask.getString("kind") == "trigger") JSONArray().put(node) else JSONArray()
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
        waitFor(hasContentDescription(string(R.string.lua_indent)))
        assertTextFits("a trigger's page with its Lua being typed")
        diag()
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasText(string(R.string.scripts_off)))
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasText(string(R.string.game_title)))

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

    private fun diag() {
        compose.waitForIdle()
        Thread.sleep(1500)
        compose.waitForIdle()
        val out = StringBuilder("DIAG $locale\n")
        val decor = compose.activity.window.decorView
        compose.runOnUiThread {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
            out.append("decor=${decor.width}x${decor.height} density=${decor.resources.displayMetrics.density}")
            out.append(" ime=${insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())}")
            out.append(" imeVisible=${insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())}")
            out.append(" bars=${insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())}\n")
            fun views(view: android.view.View, depth: Int) {
                val at = IntArray(2)
                view.getLocationOnScreen(at)
                out.append(" ".repeat(depth)).append("V ").append(view.javaClass.name).append(" [${at[0]},${at[1]}][${at[0] + view.width},${at[1] + view.height}]")
                    .append(" vis=${view.visibility} a11y=${view.importantForAccessibility}\n")
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) views(view.getChildAt(i), depth + 1)
            }
            views(decor.rootView, 0)
        }
        fun Any.call(name: String): Any? = try {
            javaClass.getMethod(name).invoke(this)
        } catch (e: Throwable) {
            "<$name: $e>"
        }
        fun element(el: Any, depth: Int) {
            out.append(" ".repeat(depth)).append("E")
            for (m in listOf(
                "getClassName", "getAccessibilityClassName", "getText", "getContentDescription", "getStateDescription", "getHintText", "getBoundsInScreen", "isClickable",
                "isFocusable", "isEditable", "isScrollable", "isVisibleToUser", "isImportantForAccessibility", "isScreenReaderFocusable", "getChildViewCount", "getResourceName",
            )) {
                out.append(' ').append(m.removePrefix("get")).append('=').append(el.call(m).toString().take(60))
            }
            out.append('\n')
        }
        try {
            compose.onRoot().tryPerformAccessibilityChecks()
            out.append("ATF passed\n")
        } catch (e: Throwable) {
            out.append("ATF: ${e.javaClass.name}: ${e.message}\n")
            val results = e.call("getResults") as? List<*>
            out.append("results=${results?.size}\n")
            for (r in results.orEmpty()) {
                if (r == null) continue
                out.append("result view=${r.call("getView")}\n")
                val h = r.call("getAccessibilityHierarchyCheckResult")
                out.append("hierarchy result=${h.toString().take(300)}\n")
                val el = h?.call("getElement")
                if (el != null && el !is String) {
                    element(el, 0)
                    val count = el.call("getChildViewCount") as? Int ?: 0
                    for (i in 0 until count) {
                        val child = try {
                            el.javaClass.getMethod("getChildView", Int::class.javaPrimitiveType).invoke(el, i)
                        } catch (x: Throwable) {
                            null
                        }
                        if (child != null) element(child, 2)
                    }
                    var up = el.call("getParentView")
                    var n = 0
                    while (up != null && up !is String && n < 12) {
                        out.append("parent ")
                        element(up, 0)
                        up = up.call("getParentView")
                        n++
                    }
                }
            }
        }
        fun node(n: SemanticsNode, depth: Int) {
            out.append(" ".repeat(depth)).append('#').append(n.id).append(' ').append(n.boundsInWindow.toString().removePrefix("Rect.fromLTRB")).append(" size=").append(n.size)
            for ((key, value) in n.config) out.append(' ').append(key.name).append('=').append(value.toString().replace('\n', '|').take(36))
            out.append('\n')
            n.children.forEach { node(it, depth + 1) }
        }
        for (root in compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes()) node(root, 0)
        throw AssertionError(out.toString())
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
