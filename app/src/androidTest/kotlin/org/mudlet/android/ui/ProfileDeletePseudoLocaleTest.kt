package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.profiles.DeletedProfile

/**
 * What the profile list says after a game's profile is deleted, and what the editor says of Delete while the game is
 * open, in the debug build's pseudo-locales, as PseudoLocaleTest checks the screens: none of it is cut short or leaves
 * its window.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ProfileDeletePseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private lateinit var engine: FakeEngine.Session

    @Before
    fun attach() {
        compose.enableAccessibilityChecksOfWhatShows()
        engine = fake.engine.nextSession()
        engine.clear()
        engine.synced(0)
    }

    @After
    fun forget() {
        compose.runOnIdle { DeletedProfile.name = null }
    }

    @Test
    fun theDeletedGamesLineFits() {
        engine.profiles(listOf(Profile(GAME, "stickmud.com", 7680, true, "", false)), engine.receive("listProfiles"))
        openManage(GAME)
        val delete = hasText(string(R.string.profile_delete)) and hasClickAction()
        compose.onNode(delete).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitFor(delete and hasAnyAncestor(isDialog()))
        compose.onNode(delete and hasAnyAncestor(isDialog())).performSemanticsAction(SemanticsActions.OnClick)
        engine.profiles(emptyList(), engine.receive("deleteProfile"))
        val notice = string(R.string.profile_deleted_game_stays, GAME)
        waitFor(hasText(notice))
        compose.onRoot().tryPerformAccessibilityChecks()
        assertFits(notice)
    }

    @Test
    fun whyDeleteIsOffFits() {
        engine.profiles(listOf(Profile(NAME, "g.example", 23, false, "", false)), engine.receive("listProfiles"))
        fake.engine.openAlongside(NAME)
        fake.engine.passwordGiven(NAME, false)
        engine.openProfiles()
        compose.waitUntil(10_000) { EngineClient.sessions.size == 1 }
        val game = engine.forProfile(NAME)
        game.status("profile", "$NAME loaded")
        compose.waitUntil(10_000) { compose.runOnIdle { EngineClient.session(NAME)?.settingsAvailable == true } }
        openManage(NAME)
        game.proxy(false, "", 0, "", game.receive("getProxy").get("id"))
        val why = string(R.string.profile_delete_while_open, NAME)
        waitFor(hasText(why))
        compose.onNode(hasText(string(R.string.profile_delete)) and hasClickAction()).performScrollTo()
        compose.onRoot().tryPerformAccessibilityChecks()
        assertFits(why)
    }

    // The profile [name]'s editor, with its part that holds Delete unfolded
    private fun openManage(name: String) {
        val edit = hasContentDescription(string(R.string.profile_edit, name)) and hasClickAction()
        waitFor(edit)
        compose.onNode(edit).performSemanticsAction(SemanticsActions.OnClick)
        val manage = hasText(string(R.string.profile_manage_section)) and hasClickAction()
        waitFor(manage)
        compose.onNode(manage).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun assertFits(text: String) {
        val nodes = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$locale: \"$text\" is not shown", nodes.isNotEmpty())
        for (node in nodes) {
            val layouts = ArrayList<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
            assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
            val width = generateSequence(node) { it.parent }.last().size.width
            val bounds = node.boundsInRoot
            assertTrue("$locale: \"$text\" at $bounds leaves its $width px window", bounds.left >= 0f && bounds.right <= width)
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    companion object {
        // One of Mudlet's games (app/src/main/assets/games), which the list offers again once its profile is deleted
        private const val GAME = "StickMUD"
        private const val NAME = "Game"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
