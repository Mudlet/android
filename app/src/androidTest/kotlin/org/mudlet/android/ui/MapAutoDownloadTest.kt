package org.mudlet.android.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.engine.EngineService
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.EngineMessages.areas
import org.mudlet.android.ui.game.Announcer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * The map the game offers, downloaded by the app itself with no tap (docs/architecture.md, "Downloading the map"), against
 * a fake engine: into a map with no rooms only, once in the profile's life whether it loads or fails, shown at the game
 * screen's bottom and told to TalkBack, with the player's own button left as it was.
 */
@RunWith(AndroidJUnit4::class)
class MapAutoDownloadTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    // A profile of its own for each test, so neither the stored mark nor the session of an earlier test is left
    private val game = Profile("Auto map ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)

    @Before
    fun openTheGameScreen() {
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        // A wide screen shows the map beside the text, with the download in the map's own bar
        compose.runOnIdle { Settings.mapBesideText.set(false) }
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, "the main console")
        session.synced(1)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("the main console")).fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun defaults() {
        compose.runOnIdle { Settings.mapBesideText.reset() }
        AppMapClient.forgetAutoDownload(context(), game.name)
    }

    @Test
    fun anOfferToAMapWithNoRoomsDownloadsItWithNoTapAndTalkBackIsToldOnce() {
        session.send(mapDownload())
        val ask = request("mapAreas", "the offer asks whether the map has rooms")
        session.send(areas(ask))
        request("downloadMap", "a map with no rooms downloads the offered map with no tap")

        val running = hasText(string(R.string.map_auto_download_running, HOST))
        waitFor("the game screen shows the download") { shown(running) }
        // Told once, by the Announcer, and not again by the text as a live region
        compose.onNode(running).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.runOnIdle {
            assertTrue("TalkBack is told: ${Announcer.current?.text}", Announcer.current!!.text.endsWith(string(R.string.map_auto_download_running, HOST)))
            assertFalse("a download starting does not interrupt TalkBack", Announcer.current!!.assertive)
        }
        session.send(mapDownload("downloading", cancelable = true))
        val cancel = hasText(string(R.string.map_download_cancel)) and hasClickAction()
        waitFor("Cancel shows while Mudlet can stop the download") { shown(cancel) }
        assertTouchTarget(cancel)
        checkAccessibility()

        session.send(mapDownload("loaded"))
        val loaded = hasText(string(R.string.map_download_loaded, HOST))
        waitFor("the game screen says the map loaded") { shown(loaded) }
        compose.runOnIdle { assertTrue("TalkBack is told: ${Announcer.current?.text}", Announcer.current!!.text.endsWith(string(R.string.map_download_loaded, HOST))) }
        assertTrue("decided, under the name players' profiles keep", compose.runOnIdle { decided() })
        waitFor("that the map loaded goes by itself") { !shown(loaded) }

        // A restarted engine has downloaded nothing, and its game offers the map again: once was the profile's one time
        restartEngine()
        session.send(mapDownload())
        noRequests()
    }

    @Test
    fun aMapWithRoomsIsKeptAndTheOfferStaysThePlayersButton() {
        session.send(mapDownload())
        val ask = request("mapAreas", "the offer asks whether the map has rooms")
        session.send(areas(ask, 1 to "Keep"))
        noRequests()
        assertFalse("nothing shows a download", shown(hasText(string(R.string.map_auto_download_running, HOST))))
        assertTrue("decided by the first offer", compose.runOnIdle { decided() })

        // The player may empty the map later, and the next offer still asks nothing: the button is theirs
        restartEngine()
        session.send(mapDownload())
        noRequests()
        assertButtonOffered()
    }

    @Test
    fun aFailedDownloadIsToldAtOnceAndNotTriedAgain() {
        session.send(mapDownload())
        session.send(areas(request("mapAreas", "the offer asks whether the map has rooms")))
        request("downloadMap", "a map with no rooms downloads the offered map with no tap")
        session.send(mapDownload("downloading", cancelable = true))
        val reason = "Map download encountered an error: Error transferring http://$HOST - server replied: Not Found"
        session.send(mapDownload("failed", error = reason))
        val failed = hasText(string(R.string.map_download_failed, reason))
        waitFor("the game screen says why it failed") { shown(failed) }
        compose.onNode(failed).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.runOnIdle {
            assertTrue("TalkBack is told: ${Announcer.current?.text}", Announcer.current!!.text.endsWith(string(R.string.map_download_failed, reason)))
            assertTrue("a failure interrupts TalkBack", Announcer.current!!.assertive)
        }
        checkAccessibility()
        activate(hasContentDescription(string(R.string.map_download_dismiss)))
        waitFor("the failure goes once dismissed") { !shown(failed) }
        assertTrue("a failure decides too", compose.runOnIdle { decided() })

        restartEngine()
        session.send(mapDownload())
        noRequests()
        assertButtonOffered()
    }

    @Test
    fun aRefusedDownloadShowsAsItsFailure() {
        session.send(mapDownload())
        session.send(areas(request("mapAreas", "the offer asks whether the map has rooms")))
        val why = "The game has offered no map to download."
        session.refuse(request("downloadMap", "a map with no rooms downloads the offered map with no tap"), why)
        val failed = hasText(string(R.string.map_download_failed, why))
        waitFor("the game screen says the download was refused") { shown(failed) }
        compose.runOnIdle {
            assertTrue("TalkBack is told: ${Announcer.current?.text}", Announcer.current!!.text.endsWith(string(R.string.map_download_failed, why)))
            assertTrue("a failure interrupts TalkBack", Announcer.current!!.assertive)
        }
        checkAccessibility()
    }

    // The engine was killed and a new one attached, which has downloaded nothing
    private fun restartEngine() {
        val attaches = compose.runOnIdle { EngineClient.attachCount }
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        session.close()
        session = fake.engine.nextSession()
        waitFor("the UI attached to the new engine") { EngineClient.attachCount > attaches }
    }

    // The map in front offers the download, its button as it is with no app download
    private fun assertButtonOffered() {
        activate(hasContentDescription(string(R.string.app_map_show)))
        val download = hasText(string(R.string.map_download, HOST)) and hasClickAction()
        waitFor("the map in front offers the download") { shown(download) }
        compose.onNode(download).assertIsEnabled()
    }

    // Read by the stored name itself (CLAUDE.md), which renaming would orphan
    private fun decided(): Boolean = context().getSharedPreferences(EngineService.PREFS, Context.MODE_PRIVATE)
        .getBoolean("mapAutoDownload:" + game.name.lowercase(), false)

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun shown(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun noRequests() = whileComposing("the UI asks nothing of the map by itself") { session.assertNoRequest(setOf("mapAreas", "downloadMap"), QUIET_MS) }

    private fun request(type: String, what: String): JSONObject = whileComposing(what) { session.receive(type) }

    // A read of the engine's socket on another thread while this one waits on Compose, which composes, and so lets the
    // UI make the request it owes, only while the test waits on it
    private fun <T> whileComposing(what: String, read: () -> T): T {
        val result = CompletableFuture.supplyAsync { read() }
        waitFor("$what: the socket read ends", READ_MS) { result.isDone }
        return try {
            result.get()
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            throw if (cause is AssertionError) AssertionError("$what: ${cause.message}", cause) else cause
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = WAIT_MS, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("$what: not within $timeoutMs ms. ${screen()}", e)
        }
    }

    private fun screen(): String = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }.getOrElse { "$it" }

    private fun checkAccessibility() = try {
        compose.onRoot().tryPerformAccessibilityChecks()
    } catch (e: Throwable) {
        throw AssertionError("${e.message}\n${screen()}", e)
    }

    private fun assertTouchTarget(matcher: SemanticsMatcher) {
        val touch = compose.onNode(matcher).fetchSemanticsNode().touchBoundsInRoot
        val min = with(compose.density) { 48.dp.toPx() }
        assertTrue("${touch.width} by ${touch.height}", touch.width >= min - 1 && touch.height >= min - 1)
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        waitFor("$label shows") { shown(label) }
        checkAccessibility()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun mapDownload(state: String = "none", cancelable: Boolean = false, error: String = ""): JSONObject = JSONObject()
        .put("t", "mapDownload").put("offered", true).put("host", HOST).put("state", state).put("label", "")
        .put("value", 0).put("maximum", 0).put("cancelable", cancelable).put("error", error)

    private companion object {
        const val INSTANCE = "fake-map-auto-download"

        // The host the game offers its map from
        const val HOST = "maps.example.org"

        const val QUIET_MS = 1_000
        const val WAIT_MS = 10_000L

        // A receive's 10 s, and a frame that comes just before its end
        const val READ_MS = 30_000L

        const val TIMEOUT_MS = 240_000L
    }
}
