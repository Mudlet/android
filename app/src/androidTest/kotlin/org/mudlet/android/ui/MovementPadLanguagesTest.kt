package org.mudlet.android.ui

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.data.Settings.MovementPadDesign
import org.mudlet.android.ui.MovementPadDesignsPseudoLocaleTest.Companion.DIALOG_TEXTS
import org.mudlet.android.ui.MovementPadDesignsPseudoLocaleTest.Companion.GAME
import org.mudlet.android.ui.MovementPadDesignsPseudoLocaleTest.Companion.KEYS
import org.mudlet.android.ui.game.HardwareKeyboard
import org.mudlet.android.ui.game.MOVEMENT_PAD_TAG
import java.util.Locale

/**
 * The movement pad in each language Mudlet desktop ships (docs/translations.md, "The movement pad's translations"): the
 * pad is in it, not in English; the Swipe pad's strip, its list of the twelve ways and the list of designs show their
 * text whole; each control of the Thumb pad has a name of its own; west stays left of east where the language is
 * written from the right; and each passes the accessibility checks.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class MovementPadLanguagesTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private val pad = hasTestTag(MOVEMENT_PAD_TAG)

    @Before
    fun openTheGameScreen() {
        compose.enableAccessibilityChecksOfWhatShows()
        compose.runOnIdle {
            HardwareKeyboard.attachedOverride = false
            Settings.movementPad.reset()
        }
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        waitFor(pad)
    }

    // FakeEngineRule puts the settings back
    @After
    fun reset() = compose.runOnIdle { HardwareKeyboard.attachedOverride = null }

    @Test
    fun thePadIsInTheLanguageAndShowsItsTextWhole() {
        val english = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        for (id in KEYS) assertNotEquals("$locale: ${english.getString(id)}", english.getString(id), compose.string(id))

        compose.onRoot().tryPerformAccessibilityChecks()
        assertFits(R.string.movement_swipe)
        activate(hasText(compose.string(R.string.movement_swipe)) and hasClickAction())
        waitFor(hasText(compose.string(R.string.movement_northwest)) and hasClickAction())
        compose.onRoot().tryPerformAccessibilityChecks()
        for (id in KEYS) assertFits(id)

        design(MovementPadDesign.THUMB)
        compose.onRoot().tryPerformAccessibilityChecks()
        val names = (KEYS + listOf(R.string.movement_pad_fold, R.string.movement_pad_to_left, R.string.settings_movement_pad_design, R.string.movement_again_none)).map { compose.string(it) }
        assertEquals("$locale: each control has a name of its own: $names", names.size, names.toSet().size)
        val west = bounds(key(R.string.movement_west))
        val east = bounds(key(R.string.movement_east))
        val north = bounds(key(R.string.movement_north))
        val south = bounds(key(R.string.movement_south))
        assertTrue("$locale: west $west is left of east $east", west.right <= east.left && west.top == east.top)
        assertTrue("$locale: north $north is over south $south", north.bottom <= south.top && north.left == south.left)

        activate(key(R.string.settings_movement_pad_design))
        waitFor(hasText(compose.string(R.string.settings_movement_pad_exits_hint)))
        compose.onRoot().tryPerformAccessibilityChecks()
        for (id in DIALOG_TEXTS) assertFits(id)
    }

    private fun design(design: MovementPadDesign) {
        compose.runOnIdle { Settings.movementPadDesign.set(design) }
        waitFor(pad)
        compose.waitForIdle()
    }

    private fun assertFits(@StringRes id: Int) {
        val text = compose.string(id)
        val node = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull() ?: throw AssertionError("$locale: \"$text\" is not shown")
        val layouts = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
        assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
    }

    private fun key(@StringRes name: Int) = hasContentDescription(compose.string(name)) and hasClickAction()

    private fun bounds(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(label and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private companion object {
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L

        // Mudlet's translations but its English and test ones, as BCP 47 tags, which AppLocaleRule takes
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf(
            "ar-EG", "ar-SA", "cs-CZ", "de-DE", "el-GR", "es-ES", "fi-FI", "fr-FR", "he-IL", "it-IT",
            "ko-KR", "nl-NL", "pl-PL", "pt-BR", "pt-PT", "ru-RU", "sv-SE", "tr-TR", "zh-CN", "zh-TW",
        )
    }
}
