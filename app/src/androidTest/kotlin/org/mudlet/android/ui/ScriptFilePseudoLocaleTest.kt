package org.mudlet.android.ui

import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile

/**
 * The dialog a game script asks for a file with (ScriptFileRequestTest) in the debug build's pseudo-locales, as
 * PseudoLocaleTest checks the screens: none of its text is cut short.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ScriptFilePseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = scriptFileRules(fake, compose, AppLocaleRule(locale))

    @Before
    fun attach() {
        compose.enableAccessibilityChecksOfWhatShows()
        val session = fake.engine.nextSession()
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        session.send(JSONObject().put("t", "pickFile").put("id", 1).put("title", "").put("into", compose.activity.filesDir.path))
    }

    @After
    fun endThePick() = InstrumentationRegistry.getInstrumentation().runOnMainSync { EngineClient.answerFilePick("") }

    @Test
    fun theTextFits() {
        compose.waitForScriptFileDialog(true)
        compose.onNode(isDialog()).tryPerformAccessibilityChecks()
        for (id in listOf(R.string.script_file_title, R.string.script_file_text, R.string.script_file_choose, R.string.script_file_cancel)) {
            val text = compose.string(id)
            val layouts = ArrayList<TextLayoutResult>()
            compose.onNodeWithText(text).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
            assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
        }
    }

    companion object {
        private val GAME = Profile("File Game", "f.example", 4000, false, "", false)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
