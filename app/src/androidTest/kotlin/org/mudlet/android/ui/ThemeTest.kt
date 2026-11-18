package org.mudlet.android.ui

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.ui.theme.MudletDarkColors
import org.mudlet.android.ui.theme.MudletLightColors

/**
 * The app's colours: on Android 12 and later, the wallpaper's while "Colours from my wallpaper" is on and Mudlet's own
 * while it is off, as drawn, the setting reached from the profile list's title bar. Earlier releases have only Mudlet's
 * colours, and no such setting.
 */
@RunWith(AndroidJUnit4::class)
class ThemeTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(FakeEngineRule())
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    @Before
    fun enableChecks() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        compose.enableAccessibilityChecksWithDump()
        compose.runOnIdle { Settings.wallpaperColours.reset() }
    }

    // The setting is stored, so it would outlast the test
    @After
    fun resetSetting() = compose.runOnIdle { Settings.wallpaperColours.reset() }

    @Test
    fun theWallpaperSettingSwitchesBetweenTheWallpapersColoursAndMudlets() {
        val dark = compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val mudlet = (if (dark) MudletDarkColors else MudletLightColors).surfaceContainer
        activate(hasContentDescription(string(R.string.settings_title)))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_appearance)))
        activate(hasText(string(R.string.settings_appearance)))
        compose.onNode(hasText(string(R.string.settings_text_size))).assertIsDisplayed()
        val row = hasText(string(R.string.settings_wallpaper_colours)) and hasClickAction()

        if (Build.VERSION.SDK_INT < 31) {
            compose.onNode(row).assertDoesNotExist()
            val map = hasText(string(R.string.settings_map_behind_text)) and hasClickAction()
            compose.onNode(map).performScrollTo()
            assertColour(mudlet, map)
            return
        }
        val context = compose.activity
        val wallpaper = (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).surfaceContainer
        assertNotEquals("the wallpaper's colours must differ from Mudlet's for this test to tell them apart", mudlet, wallpaper)

        // The settings screen scrolls, and TalkBack scrolls to what it moves to
        compose.onNode(row).performScrollTo()
        compose.onNode(row).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        assertColour(wallpaper, row)

        activate(row)
        compose.onNode(row).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        compose.runOnIdle { assertFalse(Settings.wallpaperColours.value) }
        assertColour(mudlet, row)

        activate(row)
        assertColour(wallpaper, row)
    }

    /** The colour of the container [node] is drawn on, at its start edge, clear of its text and of a rounded corner. */
    private fun assertColour(expected: Color, node: SemanticsMatcher) {
        compose.waitForIdle()
        val image = compose.onNode(node).captureToImage().toPixelMap()
        val drawn = image[EDGE_PX, image.height / 2]
        assertEquals("ARGB %08x, then %08x".format(expected.toArgb(), drawn.toArgb()), expected.toArgb(), drawn.toArgb())
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        // In from the row's start, where its container shows: the row's text starts 16 dp in
        const val EDGE_PX = 4
        const val TIMEOUT_MS = 120_000L
    }
}
