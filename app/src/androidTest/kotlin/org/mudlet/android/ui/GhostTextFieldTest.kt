package org.mudlet.android.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.ui.game.GhostTextField

/**
 * Where [GhostTextField] draws its ghost, which is in no text a test can read: just after the typed text in the
 * direction that text runs, in either layout direction.
 */
@RunWith(AndroidJUnit4::class)
class GhostTextFieldTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(FakeEngineRule()).around(compose)

    @Test
    fun theGhostIsDrawnJustAfterTheTypedTextWhicheverWayEachRuns() {
        for (direction in LayoutDirection.entries) {
            assertGhostAfter(direction, "op", "en door", rightToLeft = false)
            assertGhostAfter(direction, "اب", "جد", rightToLeft = true)
        }
    }

    private fun assertGhostAfter(direction: LayoutDirection, typed: String, ghost: String, rightToLeft: Boolean) {
        val case = "'$typed' and its ghost '$ghost' in a $direction layout"
        val bare = field(direction, typed, null)
        val ghosted = field(direction, typed, ghost)
        // Inside the outline and clear of the text, which starts 16 dp in from either side
        val inset = with(compose.density) { 8.dp.roundToPx() }
        val background = bare[inset, bare.height / 2]
        val inside = inset until bare.width - inset
        val typedAt = inside.filter { x -> (bare.height / 4 until bare.height * 3 / 4).any { bare[x, it] != background } }
        val ghostAt = inside.filter { x -> (0 until bare.height).any { bare[x, it] != ghosted[x, it] } }
        assertTrue("$case: the typed text is drawn", typedAt.isNotEmpty())
        assertTrue("$case: the ghost is drawn", ghostAt.isNotEmpty())
        val gap = if (rightToLeft) typedAt.first() - ghostAt.last() else ghostAt.first() - typedAt.last()
        val most = with(compose.density) { 8.dp.toPx() }
        assertTrue("$case: the ghost is drawn ${if (rightToLeft) "left" else "right"} of the text, $gap px after it", gap >= -OVERLAP_PX && gap <= most)
    }

    private fun field(direction: LayoutDirection, typed: String, ghost: String?): PixelMap {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        GhostTextField(
                            state = remember(typed) { TextFieldState(typed) },
                            ghost = ghost,
                            errorMessage = "",
                            modifier = Modifier.width(300.dp).testTag(FIELD),
                            lineLimits = TextFieldLineLimits.SingleLine,
                        )
                    }
                }
            }
        }
        return compose.onNodeWithTag(FIELD).captureToImage().toPixelMap()
    }

    private companion object {
        const val FIELD = "ghostTextField"

        // Anti-aliased edges of neighbouring glyphs
        const val OVERLAP_PX = 2

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
