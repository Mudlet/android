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
import androidx.compose.ui.graphics.luminance
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
 * Where and how [GhostTextField] draws its ghost, which is in no text a test can read: greyed, just after the typed text
 * in the direction that text runs, in either layout direction, and cut off at the end of the text's box.
 */
@RunWith(AndroidJUnit4::class)
class GhostTextFieldTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(FakeEngineRule()).around(compose)

    private val overlap get() = with(compose.density) { 1.dp.toPx() }

    @Test
    fun theGhostIsDrawnJustAfterTheTypedTextWhicheverWayEachRuns() {
        for (direction in LayoutDirection.entries) {
            for ((typed, ghost) in listOf("op" to "en door", "اب" to "جد")) {
                val gap = draw(direction, typed, ghost).gap
                val most = with(compose.density) { 8.dp.toPx() }
                assertTrue("'$typed' and its ghost '$ghost' in a $direction layout: the ghost is $gap px after the text", gap >= -overlap && gap <= most)
            }
        }
    }

    @Test
    fun aGhostThatStartsTheNextWordKeepsItsSpace() {
        // Measured the layout's way, a space before a ghost that runs the other way goes to the ghost's far end
        val space = with(compose.density) { 2.dp.toPx() }
        for (direction in LayoutDirection.entries) {
            for ((typed, word) in listOf("open" to "door", "افتح" to "الباب")) {
                val joined = draw(direction, typed, word).gap
                val spaced = draw(direction, typed, " $word").gap
                assertTrue("'$typed' and its ghost ' $word' in a $direction layout: $spaced px apart, $joined px without the space", spaced - joined >= space)
            }
        }
    }

    @Test
    fun aGhostTooLongForTheFieldIsCutAtTheEndOfTheText() {
        val padding = with(compose.density) { 16.dp.toPx() }
        for (direction in LayoutDirection.entries) {
            for ((typed, ghost) in listOf("op" to "en door".repeat(10), "اب" to "جد ".repeat(30))) {
                val drawn = draw(direction, typed, ghost)
                val outside = drawn.ghostAt.filter { it < padding - overlap || it > drawn.width - padding + overlap }
                assertTrue("'$typed' and a long ghost in a $direction layout: drawn outside the text's box at $outside", outside.isEmpty())
            }
        }
    }

    @Test
    fun theGhostIsLighterThanTheTypedText() {
        for ((typed, ghost) in listOf("op" to "en door", "اب" to "جد")) {
            val drawn = draw(LayoutDirection.Ltr, typed, ghost)
            assertTrue("'$typed' and its ghost '$ghost': typed text ${drawn.typedInk}, ghost ${drawn.ghostInk}", drawn.ghostInk - drawn.typedInk >= 0.02f)
        }
    }

    /** The columns of the typed text and of the ghost, and the luminance of the darkest pixel of each. */
    private class Drawn(val width: Int, val typedAt: List<Int>, val ghostAt: List<Int>, val typedInk: Float, val ghostInk: Float, rightToLeft: Boolean) {
        val gap = if (rightToLeft) typedAt.first() - ghostAt.last() else ghostAt.first() - typedAt.last()
    }

    private fun draw(direction: LayoutDirection, typed: String, ghost: String): Drawn {
        val bare = field(direction, typed, null)
        val ghosted = field(direction, typed, ghost)
        // Inside the outline and clear of the text, which starts 16 dp in from either side
        val inset = with(compose.density) { 8.dp.roundToPx() }
        val background = bare[inset, bare.height / 2]
        val rows = bare.height / 4 until bare.height * 3 / 4
        val typedAt = (inset until bare.width - inset).filter { x -> rows.any { bare[x, it] != background } }
        val ghostAt = (0 until bare.width).filter { x -> (0 until bare.height).any { bare[x, it] != ghosted[x, it] } }
        assertTrue("'$typed' in a $direction layout: the typed text is drawn", typedAt.isNotEmpty())
        assertTrue("'$typed' and its ghost '$ghost' in a $direction layout: the ghost is drawn", ghostAt.isNotEmpty())
        val typedInk = typedAt.minOf { x -> rows.minOf { bare[x, it].luminance() } }
        val ghostInk = ghostAt.minOf { x -> (0 until bare.height).filter { bare[x, it] != ghosted[x, it] }.minOf { ghosted[x, it].luminance() } }
        return Drawn(bare.width, typedAt, ghostAt, typedInk, ghostInk, rightToLeft = Character.getDirectionality(typed.first()) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC)
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

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
