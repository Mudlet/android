package org.mudlet.android.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.data.ProfileArchive
import org.mudlet.android.data.ProfileFolderCopy
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import org.mudlet.android.ui.profiles.Copies
import org.mudlet.android.ui.profiles.CopyKind
import org.mudlet.android.ui.profiles.CopyState
import org.mudlet.android.ui.profiles.DefaultGame
import org.mudlet.android.ui.profiles.DefaultGames
import org.mudlet.android.ui.profiles.Exports
import org.mudlet.android.ui.profiles.Imports
import org.mudlet.android.ui.profiles.copyProfile
import org.mudlet.android.ui.profiles.exportProfile
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The profile list and editor against a fake engine, as a TalkBack user drives them: each control found by the label
 * TalkBack reads and activated through its accessibility action. The engine's side of the profile requests is
 * checked by the host tests (native/tests/profile_checks.py).
 */
@RunWith(AndroidJUnit4::class)
class ProfilesTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var engine: FakeEngine.Session
    private lateinit var passwords: PasswordStore

    @Before
    fun attach() {
        // A copy's dialog opens over the editor scrolled to its buttons, with an empty field just above the page
        compose.enableAccessibilityChecksOfWhatShows()
        passwords = PasswordStore(compose.activity)
        forgetPasswords()
        engine = fake.engine.nextSession()
    }

    @After
    fun forgetPasswords() {
        (listOf(ALPHA, BETA, GAMMA).map { it.name } + listOf(ACHAEA, STICKMUD)).forEach {
            passwords.forget(it)
            Settings.setAwaitsDefaults(it, false)
            Settings.setAwaitsInputClear(it, false)
        }
    }

    @Test
    fun listsTheProfilesInTheEnginesOrderEachReadAsNameAndAddress() {
        listed(BETA, ALPHA)
        val beta = compose.onNodeWithContentDescription("Beta, b.example:4000").assertIsDisplayed().fetchSemanticsNode()
        val alpha = compose.onNodeWithContentDescription("Alpha, a.example:23").assertIsDisplayed().fetchSemanticsNode()
        assertTrue("the last used first", beta.boundsInRoot.top < alpha.boundsInRoot.top)
        for (name in listOf("Alpha", "Beta")) {
            compose.onNode(hasContentDescription(string(R.string.profile_connect_to, name)) and hasClickAction()).assertIsDisplayed()
            compose.onNode(hasContentDescription(string(R.string.profile_edit, name)) and hasClickAction()).assertIsDisplayed()
        }
        compose.onNode(hasContentDescription(string(R.string.profiles_add)) and hasClickAction()).assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun addsAProfileAndStoresItsPassword() {
        listed()
        compose.onNodeWithText(string(R.string.profiles_empty)).assertIsDisplayed()
        activate(hasContentDescription(string(R.string.profiles_add)))
        compose.onNodeWithText(string(R.string.profile_new_title)).assertIsDisplayed()

        type(R.string.profile_name, "Gamma")
        type(R.string.profile_host, "g.example")
        type(R.string.profile_port, "65536")
        compose.onNodeWithText(string(R.string.profile_port_invalid)).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.profile_save)) and hasClickAction()).assertIsNotEnabled()
        type(R.string.profile_port, "4242")
        compose.onNodeWithText(string(R.string.profile_port_invalid)).assertDoesNotExist()
        activate(hasText(string(R.string.profile_tls)))
        type(R.string.profile_login, "hero")
        type(R.string.profile_password, "gamma secret")
        activate(hasText(string(R.string.profile_auto_reconnect)))
        // A password field, which TalkBack does not read aloud
        field(R.string.profile_password).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onRoot().tryPerformAccessibilityChecks()
        activate(hasText(string(R.string.profile_save)))

        val save = engine.receive("saveProfile")
        assertEquals(GAMMA, profileOf(save))
        assertFalse("the password goes to the engine only with openProfile", save.has("password"))
        engine.profiles(listOf(GAMMA), save)
        waitFor(hasContentDescription("Gamma, g.example:4242"))
        compose.runOnIdle {
            assertEquals("gamma secret", passwords.get("Gamma"))
            assertTrue("a game added here is given the app's settings as it first loads", Settings.awaitsDefaults("Gamma"))
        }
        // Only a game that is open is given its password: for one that is not, the request would open it
        engine.assertNoRequest(setOf("openProfile"), 500)
    }

    @Test
    fun editsAProfileKeepingItsNameAndStoredPassword() {
        assertTrue(passwords.set("Alpha", "alpha secret"))
        listed(ALPHA)
        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        compose.onNodeWithText(string(R.string.profile_password_stored)).assertIsDisplayed()
        assertEquals("no name field", 0, compose.onAllNodes(hasSetTextAction() and hasText(string(R.string.profile_name))).fetchSemanticsNodes().size)
        field(R.string.profile_host).assert(hasText("a.example"))
        compose.onRoot().tryPerformAccessibilityChecks()

        type(R.string.profile_port, "2323")
        activate(hasText(string(R.string.profile_save)))
        val save = engine.receive("saveProfile")
        assertEquals(ALPHA.copy(port = 2323), profileOf(save))
        engine.profiles(listOf(ALPHA.copy(port = 2323)), save)
        waitFor(hasContentDescription("Alpha, a.example:2323"))
        compose.runOnIdle {
            assertEquals("an empty password field keeps the stored one", "alpha secret", passwords.get("Alpha"))
            assertFalse("a game edited keeps its own settings", Settings.awaitsDefaults("Alpha"))
        }
    }

    @Test
    fun forgetsTheStoredPassword() {
        assertTrue(passwords.set("Alpha", "alpha secret"))
        listed(ALPHA)
        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        activate(hasText(string(R.string.profile_password_forget)))
        compose.onNodeWithText(string(R.string.profile_password_stored)).assertDoesNotExist()
        compose.runOnIdle { assertFalse(passwords.has("Alpha")) }
    }

    @Test
    fun givesAnOpenGameThePasswordSavedOrForgottenForIt() {
        listed(ALPHA)
        fake.engine.openAlongside("Alpha")
        engine.openProfiles()
        compose.waitUntil(10_000) { EngineClient.sessions.size == 1 }

        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        type(R.string.profile_password, "alpha secret")
        activate(hasText(string(R.string.profile_save)))
        engine.profiles(listOf(ALPHA), engine.receive("saveProfile"))
        // The engine has a profile's password only from an openProfile, and the game's Reconnect sends none
        val given = engine.receive("openProfile")
        assertEquals("Alpha", given.getString("name"))
        assertEquals("alpha secret", given.getString("password"))
        assertFalse("the game is not connected for it", given.getBoolean("connect"))
        assertTrue("nor the other games closed", given.getBoolean("alongside"))

        waitFor(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        activate(hasText(string(R.string.profile_password_forget)))
        val withdrawn = engine.receive("openProfile")
        assertEquals("Alpha", withdrawn.getString("name"))
        assertFalse("the game is not left with a password the player had the app forget", withdrawn.has("password"))
        assertFalse(withdrawn.getBoolean("connect"))
        assertTrue(withdrawn.getBoolean("alongside"))
    }

    @Test
    fun givesAGameTheEngineHoldsNoPasswordForItsStoredOneOnce() {
        assertTrue(passwords.set("Alpha", "alpha secret"))
        listed(ALPHA, BETA)
        // As an engine started after the last was killed brings its games back: it kept no password
        fake.engine.openAlongside("Alpha")
        fake.engine.openAlongside("Beta")
        fake.engine.passwordGiven("Alpha", false)
        fake.engine.passwordGiven("Beta", false)
        engine.openProfiles()
        val given = engine.receive("openProfile")
        assertEquals("Alpha", given.getString("name"))
        assertEquals("alpha secret", given.getString("password"))
        assertFalse("the game is not connected for it", given.getBoolean("connect"))
        assertTrue("nor the other games closed", given.getBoolean("alongside"))

        // Not again while the engine has yet to say it holds one, and nothing for Beta, which has none stored
        engine.openProfiles()
        engine.assertNoRequest("openProfile", 1000)

        fake.engine.passwordGiven("Alpha", true)
        engine.openProfiles()
        engine.assertNoRequest("openProfile", 1000)

        // Lost again, as by the next engine, it is given again
        fake.engine.passwordGiven("Alpha", false)
        engine.openProfiles()
        assertEquals("alpha secret", engine.receive("openProfile").getString("password"))
    }

    @Test
    fun offersToExportAProfileSayingItsPasswordsAreLeftOut() {
        listed(ALPHA)
        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        scrollIntoView(hasText(string(R.string.profile_export)) and hasClickAction())
        compose.onNodeWithText(string(R.string.profile_export_text)).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.profile_export)) and hasClickAction()).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(string(R.string.profile_export_close_first)).assertDoesNotExist()
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun anOpenGameIsExportedOnlyOnceItHasClosed() {
        listed(ALPHA)
        fake.engine.openAlongside("Alpha")
        engine.openProfiles()
        compose.waitUntil(10_000) { EngineClient.sessions.size == 1 }
        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        scrollIntoView(hasText(string(R.string.profile_export)) and hasClickAction())
        compose.onNodeWithText(string(R.string.profile_export_close_first)).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.profile_export)) and hasClickAction()).assertIsNotEnabled()
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun exportsTheEnginesFolderForTheProfileWithoutItsPassword() {
        val context = compose.activity
        val folder = ProfileArchive.folder(context.filesDir, EXPORTED)
        val target = File(context.cacheDir, "export-test.zip")
        try {
            File(folder, "current").mkdirs()
            File(folder, "current/save.xml").writeText("<MudletPackage/>")
            File(folder, "password").writeText("hunter2")
            assertNull(exportProfile(context, EXPORTED, Uri.fromFile(target)))
            val names = ZipFile(target).use { zip -> zip.entries().toList().map { it.name } }
            assertEquals(listOf("$EXPORTED/", "$EXPORTED/current/", "$EXPORTED/current/save.xml"), names)

            folder.deleteRecursively()
            assertNotNull("a profile with no folder is refused", exportProfile(context, EXPORTED, Uri.fromFile(target)))
        } finally {
            folder.deleteRecursively()
            target.delete()
        }
    }

    @Test
    fun saysWhenTheExportIsDoneOrWhyItFailed() {
        val context = compose.activity
        val folder = ProfileArchive.folder(context.filesDir, EXPORTED)
        val target = File(context.cacheDir, "export-status-test.zip")
        try {
            File(folder, "current").mkdirs()
            File(folder, "current/save.xml").writeText("<MudletPackage/>")
            listed(Profile(EXPORTED, "e.example", 23, false, "", false))
            activate(hasContentDescription(string(R.string.profile_edit, EXPORTED)))
            compose.runOnUiThread { Exports.start(context.applicationContext, EXPORTED, Uri.fromFile(target)) }
            waitFor(hasText(string(R.string.profile_exported)))
            compose.onNodeWithText(string(R.string.profile_exported)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            compose.onRoot().tryPerformAccessibilityChecks()

            folder.deleteRecursively()
            compose.runOnUiThread { Exports.start(context.applicationContext, EXPORTED, Uri.fromFile(target)) }
            waitFor(hasText(string(R.string.profile_export_failed, ""), substring = true))
            compose.onNode(hasText(string(R.string.profile_export)) and hasClickAction()).assertIsEnabled()
        } finally {
            folder.deleteRecursively()
            target.delete()
        }
    }

    @Test
    fun offersTheTwoCopiesDesktopMakesOfAProfile() {
        listed(ALPHA)
        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        scrollIntoView(hasText(string(R.string.profile_copy_settings)) and hasClickAction())
        compose.onNodeWithText(string(R.string.profile_copy_text)).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.profile_copy)) and hasClickAction()).assertIsDisplayed().assertIsEnabled()
        compose.onNode(hasText(string(R.string.profile_copy_settings)) and hasClickAction()).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(string(R.string.profile_copy_close_first)).assertDoesNotExist()
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun anOpenGameIsCopiedOnlyOnceItHasClosed() {
        listed(ALPHA)
        fake.engine.openAlongside("Alpha")
        engine.openProfiles()
        compose.waitUntil(10_000) { EngineClient.sessions.size == 1 }
        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        scrollIntoView(hasText(string(R.string.profile_copy_settings)) and hasClickAction())
        compose.onNodeWithText(string(R.string.profile_copy_close_first)).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.profile_copy)) and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText(string(R.string.profile_copy_settings)) and hasClickAction()).assertIsNotEnabled()
        compose.onRoot().tryPerformAccessibilityChecks()
        // Nor by a tap that was already on its way as the game opened
        compose.runOnIdle {
            Copies.start(compose.activity.applicationContext, "Alpha", COPY, CopyKind.WHOLE, false)
            assertNull(Copies["Alpha"])
        }
    }

    @Test
    fun copiesAProfileUnderTheNameConfirmedAndListsIt() {
        val context = compose.activity
        val source = ProfileArchive.folder(context.filesDir, COPIED)
        val copy = ProfileArchive.folder(context.filesDir, COPY)
        // A folder the engine has not listed is taken all the same
        val unlisted = ProfileArchive.folder(context.filesDir, "${COPIED}1")
        try {
            File(source, "current").mkdirs()
            File(source, "current/save.xml").writeText("<MudletPackage/>")
            File(source, "password").writeText("hunter2")
            unlisted.mkdirs()
            compose.runOnIdle {
                Settings.setAwaitsDefaults(COPIED, true)
                Settings.setAwaitsInputClear(COPIED, true)
            }
            listed(COPIED_PROFILE)
            activate(hasContentDescription(string(R.string.profile_edit, COPIED)))
            activate(hasText(string(R.string.profile_copy)))
            compose.onNodeWithText(string(R.string.profile_copy_whole_title, COPIED)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.profile_copy_whole_text)).assertIsDisplayed()
            // The profile and the unlisted folder have the first two of desktop's names for a copy
            compose.onNode(hasSetTextAction() and hasText("${COPIED}2")).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.profile_copy_password)).assertDoesNotExist()
            val confirm = hasText(string(R.string.profile_copy)) and hasClickAction() and hasAnyAncestor(isDialog())

            type(R.string.profile_name, COPIED.lowercase())
            compose.onNodeWithText(string(R.string.profile_copy_taken, COPIED)).assertIsDisplayed()
            compose.onNode(confirm).assertIsNotEnabled()
            type(R.string.profile_name, "bad/name")
            compose.onNodeWithText(string(R.string.profile_import_name_rules)).assertIsDisplayed()
            compose.onNode(confirm).assertIsNotEnabled()
            type(R.string.profile_name, COPY)
            checkAccessibility()

            activate(confirm)
            engine.profiles(listOf(COPIED_PROFILE, COPIED_PROFILE.copy(name = COPY)), engine.receive("listProfiles"))
            waitFor(hasText(string(R.string.profile_copied, COPY)))
            compose.onNodeWithText(string(R.string.profile_copied, COPY)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            compose.onNode(isDialog()).assertDoesNotExist()
            assertEquals("<MudletPackage/>", File(copy, "current/save.xml").readText())
            assertFalse("its password file is not copied", File(copy, "password").exists())
            assertTrue("the profile copied is as it was", File(source, "password").isFile)
            assertFalse("only under the name confirmed", ProfileArchive.folder(context.filesDir, "${COPIED}2").exists())
            assertEquals("the unlisted folder is as it was", 0, unlisted.list()!!.size)
            assertTrue("a copy's staging folder is gone", File(context.cacheDir, "profile-copy").listFiles().isNullOrEmpty())
            compose.runOnIdle {
                assertFalse("no password is stored for it", passwords.has(COPY))
                assertTrue("like the profile copied, it is yet to be given the app's settings", Settings.awaitsDefaults(COPY))
                assertTrue("and the app's clearing of its input line", Settings.awaitsInputClear(COPY))
            }
            compose.onNode(hasText(string(R.string.profile_copy)) and hasClickAction()).assertIsEnabled()
        } finally {
            forgetCopies()
            source.deleteRecursively()
            copy.deleteRecursively()
            unlisted.deleteRecursively()
        }
    }

    @Test
    fun aCopyOfTheSettingsHasTheConnectionDetailsAndTheNewestSavesSettingsAlone() {
        val context = compose.activity
        val source = ProfileArchive.folder(context.filesDir, COPIED)
        val copy = ProfileArchive.folder(context.filesDir, COPY)
        val second = ProfileArchive.folder(context.filesDir, SECOND_COPY)
        try {
            File(source, "current").mkdirs()
            File(source, "url").writeText("c.example")
            File(source, "password").writeText("hunter2")
            File(source, "current/save.xml").writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
                    |<!DOCTYPE MudletPackage>
                    |<MudletPackage version="1.001">
                    |    <TriggerPackage><Trigger><name>mine</name></Trigger></TriggerPackage>
                    |    <HostPackage>
                    |        <Host autoClearCommandLineAfterSend="yes">
                    |            <name>$COPIED</name>
                    |            <mInstalledPackages><string>a-package</string></mInstalledPackages>
                    |            <url>c.example</url>
                    |        </Host>
                    |    </HostPackage>
                    |</MudletPackage>
                """.trimMargin(),
            )
            listed(COPIED_PROFILE)
            activate(hasContentDescription(string(R.string.profile_edit, COPIED)))
            activate(hasText(string(R.string.profile_copy_settings)))
            compose.onNodeWithText(string(R.string.profile_copy_settings_title, COPIED)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.profile_copy_settings_text)).assertIsDisplayed()
            type(R.string.profile_name, COPY)
            checkAccessibility()
            activate(hasText(string(R.string.profile_copy)) and hasAnyAncestor(isDialog()))
            engine.profiles(listOf(COPIED_PROFILE, COPIED_PROFILE.copy(name = COPY)), engine.receive("listProfiles"))
            waitFor(hasText(string(R.string.profile_copied, COPY)))

            assertEquals(listOf("current", "url"), copy.list()!!.sorted())
            assertEquals("c.example", File(copy, "url").readText())
            assertEquals(listOf(ProfileFolderCopy.SETTINGS_SAVE), File(copy, "current").list()!!.toList())
            // As this device's XML parser reads the save, which the JVM's test of the same does not show
            assertEquals(
                """<?xml version="1.0" encoding="UTF-8"?>
                    |<!DOCTYPE MudletPackage>
                    |<MudletPackage version="1.001">
                    |${"\t"}<HostPackage>
                    |${"\t\t"}<Host autoClearCommandLineAfterSend="yes">
                    |${"\t\t\t"}<name>$COPIED</name>
                    |${"\t\t\t"}<url>c.example</url>
                    |${"\t\t"}</Host>
                    |${"\t"}</HostPackage>
                    |</MudletPackage>
                    |
                """.trimMargin(),
                File(copy, "current/${ProfileFolderCopy.SETTINGS_SAVE}").readText(),
            )

            // A save that is not one: the copy is made, and says what it lacks
            File(source, "current/save.xml").writeText("<MudletPackage><HostPackage><Host>")
            activate(hasText(string(R.string.profile_copy_settings)))
            type(R.string.profile_name, SECOND_COPY)
            activate(hasText(string(R.string.profile_copy)) and hasAnyAncestor(isDialog()))
            engine.profiles(listOf(COPIED_PROFILE, COPIED_PROFILE.copy(name = SECOND_COPY)), engine.receive("listProfiles"))
            waitFor(hasText(string(R.string.profile_copy_settings_unreadable), substring = true))
            compose.onNode(hasText(string(R.string.profile_copied, SECOND_COPY), substring = true)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            assertEquals("no part of the save is left to load", listOf("url"), second.list()!!.toList())
        } finally {
            forgetCopies()
            source.deleteRecursively()
            copy.deleteRecursively()
            second.deleteRecursively()
        }
    }

    @Test
    fun aCopyHasTheStoredPasswordOnlyOnceThePlayerTurnsThatOn() {
        val context = compose.activity
        val source = ProfileArchive.folder(context.filesDir, COPIED)
        val copies = listOf(COPY, SECOND_COPY).map { ProfileArchive.folder(context.filesDir, it) }
        try {
            File(source, "current").mkdirs()
            assertTrue(passwords.set(COPIED, "copied secret"))
            // One a profile of the copy's name left behind
            assertTrue(passwords.set(COPY, "stale secret"))
            listed(COPIED_PROFILE)
            activate(hasContentDescription(string(R.string.profile_edit, COPIED)))
            val switch = hasText(string(R.string.profile_copy_password)) and hasAnyAncestor(isDialog())
            val confirm = hasText(string(R.string.profile_copy)) and hasAnyAncestor(isDialog())

            activate(hasText(string(R.string.profile_copy)))
            compose.onNode(switch).assertIsDisplayed().assertIsOff()
            type(R.string.profile_name, COPY)
            checkAccessibility()
            activate(confirm)
            engine.profiles(listOf(COPIED_PROFILE, COPIED_PROFILE.copy(name = COPY)), engine.receive("listProfiles"))
            waitFor(hasText(string(R.string.profile_copied, COPY)))
            compose.runOnIdle { assertFalse("off, no password is stored for the copy", passwords.has(COPY)) }

            activate(hasText(string(R.string.profile_copy_settings)))
            compose.onNode(switch).assertIsOff()
            activate(switch)
            compose.onNode(switch).assertIsOn()
            type(R.string.profile_name, SECOND_COPY)
            activate(confirm)
            engine.profiles(listOf(COPIED_PROFILE, COPIED_PROFILE.copy(name = SECOND_COPY)), engine.receive("listProfiles"))
            waitFor(hasText(string(R.string.profile_copied, SECOND_COPY)))
            compose.runOnIdle {
                assertEquals("on, the copy logs in as the profile copied does", "copied secret", passwords.get(SECOND_COPY))
                assertEquals("which keeps its own", "copied secret", passwords.get(COPIED))
            }
        } finally {
            forgetCopies()
            source.deleteRecursively()
            copies.forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun aCopyThatCannotBeMadeSaysWhyAndLeavesNothing() {
        val context = compose.activity
        val copy = ProfileArchive.folder(context.filesDir, COPY)
        try {
            // The engine lists a profile whose folder is gone
            listed(COPIED_PROFILE)
            activate(hasContentDescription(string(R.string.profile_edit, COPIED)))
            activate(hasText(string(R.string.profile_copy)))
            type(R.string.profile_name, COPY)
            activate(hasText(string(R.string.profile_copy)) and hasAnyAncestor(isDialog()))
            waitFor(hasText(string(R.string.profile_copy_failed, ""), substring = true))
            compose.onNode(hasText(string(R.string.profile_copy_failed, ""), substring = true)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            assertFalse(copy.exists())
            assertTrue("a copy's staging folder is gone", File(context.cacheDir, "profile-copy").listFiles().isNullOrEmpty())
            engine.assertNoRequest("listProfiles", 1000)
            compose.onNode(hasText(string(R.string.profile_copy)) and hasClickAction()).assertIsEnabled()

            assertEquals(
                "a name with a path in it is refused whatever asks",
                CopyState.Failed(string(R.string.profile_import_name_rules)),
                copyProfile(context, COPIED, "../$COPY", CopyKind.WHOLE),
            )
        } finally {
            forgetCopies()
            copy.deleteRecursively()
        }
    }

    @Test
    fun offersToImportAProfileBelowTheProfiles() {
        listed(ALPHA)
        scrollToGame(importButton)
        compose.onNode(importButton).assertIsEnabled()
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun importsAnArchiveUnderANameNoProfileHasAndListsIt() {
        val context = compose.activity
        val archive = archive("import-test.zip", "Alpha/current/save.xml", "Alpha/password")
        val imported = ProfileArchive.folder(context.filesDir, IMPORTED)
        // A folder the engine has not listed is taken all the same
        val unlisted = ProfileArchive.folder(context.filesDir, "Alpha1")
        try {
            unlisted.mkdirs()
            listed(ALPHA)
            compose.runOnUiThread { Imports.start(context.applicationContext, Uri.fromFile(archive)) }
            waitFor(hasText(string(R.string.profile_import_title, "Alpha")))
            compose.onNodeWithText(string(R.string.profile_import_text)).assertIsDisplayed()
            // Alpha and Alpha1 are taken, so desktop's name for a copy of it is offered
            compose.onNode(hasSetTextAction() and hasText("Alpha2")).assertIsDisplayed()
            val confirm = hasText(string(R.string.profile_import)) and hasClickAction() and hasAnyAncestor(isDialog())

            type(R.string.profile_name, "alpha")
            compose.onNodeWithText(string(R.string.profile_import_taken, "Alpha")).assertIsDisplayed()
            compose.onNode(confirm).assertIsNotEnabled()
            type(R.string.profile_name, "bad/name")
            compose.onNodeWithText(string(R.string.profile_import_name_rules)).assertIsDisplayed()
            compose.onNode(confirm).assertIsNotEnabled()
            type(R.string.profile_name, IMPORTED)
            checkAccessibility()

            activate(confirm)
            engine.profiles(listOf(ALPHA, Profile(IMPORTED, "", 23, false, "", false)), engine.receive("listProfiles"))
            waitForInList(hasText(string(R.string.profile_imported, IMPORTED)))
            compose.onNodeWithText(string(R.string.profile_imported, IMPORTED)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            assertTrue(File(imported, "current/save.xml").isFile)
            assertFalse("its password is not imported", File(imported, "password").exists())
            assertFalse("only under the name confirmed", ProfileArchive.folder(context.filesDir, "Alpha2").exists())
            assertEquals("the unlisted folder is as it was", 0, unlisted.list()!!.size)
            assertStagingEmpty()
        } finally {
            imported.deleteRecursively()
            unlisted.deleteRecursively()
            archive.delete()
        }
    }

    @Test
    fun aCancelledImportLeavesNothing() {
        val context = compose.activity
        val archive = archive("import-cancel-test.zip", "$IMPORTED/current/save.xml")
        try {
            listed(ALPHA)
            compose.runOnUiThread { Imports.start(context.applicationContext, Uri.fromFile(archive)) }
            waitFor(hasText(string(R.string.profile_import_title, IMPORTED)))
            compose.onNode(hasSetTextAction() and hasText(IMPORTED)).assertIsDisplayed()
            activate(hasText(string(R.string.profile_import_cancel)) and hasAnyAncestor(isDialog()))
            compose.onNodeWithText(string(R.string.profile_import_title, IMPORTED)).assertDoesNotExist()
            compose.waitUntil(10_000) { stagingEmpty() }
            assertFalse(ProfileArchive.folder(context.filesDir, IMPORTED).exists())
            engine.assertNoRequest("listProfiles", 1000)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun refusesAnArchiveWhosePathsLeaveItsFolderSayingWhy() {
        val context = compose.activity
        val archive = archive("import-hostile-test.zip", "Evil/current/save.xml", "Evil/../../../escaped")
        // From the staging folder, cache/profile-import/<id>/, to the cache itself
        val escaped = File(context.cacheDir, "escaped")
        try {
            listed(ALPHA)
            compose.runOnUiThread { Imports.start(context.applicationContext, Uri.fromFile(archive)) }
            waitForInList(hasText(string(R.string.profile_import_unsafe_path)))
            compose.onNodeWithText(string(R.string.profile_import_unsafe_path)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            compose.onNode(isDialog()).assertDoesNotExist()
            assertFalse(escaped.exists())
            assertFalse(ProfileArchive.folder(context.filesDir, "Evil").exists())
            assertStagingEmpty()
            compose.onNode(importButton).assertIsEnabled()
        } finally {
            archive.delete()
            escaped.delete()
        }
    }

    @Test
    fun deletesOnlyOnceConfirmed() {
        assertTrue(passwords.set("Alpha", "alpha secret"))
        compose.runOnIdle {
            Settings.setAwaitsDefaults("Alpha", true)
            Settings.setAwaitsInputClear("Alpha", true)
        }
        listed(ALPHA, BETA)
        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))

        activate(hasText(string(R.string.profile_delete)))
        compose.onNodeWithText(string(R.string.profile_delete_title, "Alpha")).assertIsDisplayed()
        activate(hasText(string(R.string.profile_delete_cancel)) and hasAnyAncestor(isDialog()))
        compose.onNodeWithText(string(R.string.profile_delete_title, "Alpha")).assertDoesNotExist()
        engine.assertNoRequest("deleteProfile", 1000)

        activate(hasText(string(R.string.profile_delete)))
        activate(hasText(string(R.string.profile_delete)) and hasAnyAncestor(isDialog()))
        val delete = engine.receive("deleteProfile")
        assertEquals("Alpha", delete.getString("name"))
        engine.profiles(listOf(BETA), delete)
        waitFor(hasContentDescription("Beta, b.example:4000"))
        compose.onNodeWithText(string(R.string.profiles_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Alpha, a.example:23").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse("its password goes with it", passwords.has("Alpha"))
            assertFalse("a new game of its name will be given the settings when added", Settings.awaitsDefaults("Alpha"))
            assertFalse("and is not one from before the input line could keep a command", Settings.awaitsInputClear("Alpha"))
        }
    }

    @Test
    fun showsTheEnginesRefusalAndStaysInTheEditor() {
        listed(ALPHA)
        activate(hasContentDescription(string(R.string.profile_edit, "Alpha")))
        activate(hasText(string(R.string.profile_delete)))
        activate(hasText(string(R.string.profile_delete)) and hasAnyAncestor(isDialog()))
        engine.refuse(engine.receive("deleteProfile"), "\"Alpha\" is running")
        waitFor(hasText("deleteProfile: \"Alpha\" is running"))
        compose.onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun opensAProfileWithItsStoredPasswordAndGoesToTheGame() {
        assertTrue(passwords.set("Alpha", "alpha secret"))
        listed(ALPHA, BETA)

        // Refused: the reason shows, and the list stays
        activate(hasContentDescription(string(R.string.profile_connect_to, "Beta")))
        val beta = engine.receive("openProfile")
        assertEquals("Beta", beta.getString("name"))
        assertFalse("Beta has no stored password", beta.has("password"))
        engine.refuse(beta, "there is no profile named \"Beta\"")
        waitFor(hasText("openProfile: there is no profile named \"Beta\""))
        compose.onNodeWithText(string(R.string.profiles_title)).assertIsDisplayed()

        activate(hasContentDescription(string(R.string.profile_connect_to, "Alpha")))
        val alpha = engine.receive("openProfile")
        assertEquals("Alpha", alpha.getString("name"))
        assertEquals("alpha secret", alpha.getString("password"))
        assertTrue(alpha.getBoolean("connect"))
        engine.profiles(listOf(ALPHA, BETA), alpha)
        waitFor(hasTestTag(GAME_TITLE_TAG))
    }

    @Test
    fun anAnswerThatComesOnceThePlayerHasMovedOnLeavesThemWhereTheyAre() {
        listed(ALPHA, BETA)
        activate(hasContentDescription(string(R.string.profile_connect_to, "Alpha")))
        val open = engine.receive("openProfile")
        activate(hasContentDescription(string(R.string.profile_edit, "Beta")))
        compose.onNodeWithText("Beta").assertIsDisplayed()

        // The same message that lists these answers the openProfile
        engine.profiles(listOf(BETA, ALPHA), open)
        compose.waitUntil(10_000) { EngineClient.profiles == listOf(BETA, ALPHA) }
        compose.onNodeWithTag(GAME_TITLE_TAG).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.profile_save)).assertIsDisplayed()
    }

    @Test
    fun gamesTheEngineTellsOfOnceTheProfilesShowAreAtTheTopOfTheList() {
        // More than the screen holds, so the list can scroll the games out of view
        val many = (1..10).map { Profile("Profile $it", "p$it.example", 23, false, "", false) }
        listed(*many.toTypedArray())
        waitFor(hasContentDescription("Profile 1, p1.example:23"))
        fake.engine.openAlongside(many[0].name)
        engine.openProfiles()
        compose.waitUntil(10_000) { EngineClient.sessions.size == 1 }
        compose.onNodeWithText(string(R.string.games_title)).assertIsDisplayed()
    }

    @Test
    fun offersMudletsGamesBelowTheProfilesAndAddsOneAtATap() {
        listed(ALPHA)
        val achaea = game(ACHAEA)
        waitFor(hasText(string(R.string.default_games_title)))
        val header = compose.onNodeWithText(string(R.string.default_games_title)).fetchSemanticsNode()
        val alpha = compose.onNodeWithContentDescription("Alpha, a.example:23").fetchSemanticsNode()
        assertTrue("below the player's own", alpha.boundsInRoot.top < header.boundsInRoot.top)
        val row = gameRow(achaea)
        scrollToGame(row)
        compose.onNode(row).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onNode(hasContentDescription(string(R.string.default_game_info, ACHAEA)) and hasClickAction()).assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()

        activate(row)
        val save = engine.receive("saveProfile")
        assertEquals(Profile(ACHAEA, "achaea.com", 23, false, "", false), profileOf(save))
        engine.profiles(listOf(ALPHA, achaea.newProfile()), save)
        val open = engine.receive("openProfile")
        assertEquals(ACHAEA, open.getString("name"))
        assertTrue(open.getBoolean("connect"))
        compose.runOnIdle { assertTrue("Mudlet's game is given the app's settings as it first loads", Settings.awaitsDefaults(ACHAEA)) }
        engine.profiles(listOf(achaea.newProfile(), ALPHA), open)
        waitFor(hasTestTag(GAME_TITLE_TAG))
    }

    @Test
    fun leavesOutAGameThePlayerHasAProfileAt() {
        listed(Profile("Mine", " ACHAEA.COM", 23, false, "", false))
        // Aardwolf is listed before Achaea and Aetolia after, so Achaea would be laid out between them
        scrollToGame(gameRow(game("Aardwolf")))
        scrollToGame(gameRow(game("Aetolia")))
        compose.onNode(gameRow(game(ACHAEA))).assertDoesNotExist()
        compose.onNodeWithContentDescription(string(R.string.default_game_info, ACHAEA)).assertDoesNotExist()
    }

    @Test
    fun tellsOfAGameAndShowsTheEnginesRefusalToAddIt() {
        listed()
        val stick = game(STICKMUD)
        val info = hasContentDescription(string(R.string.default_game_info, STICKMUD)) and hasClickAction()
        scrollToGame(info)
        activate(info)
        compose.onNode(hasText(STICKMUD) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.default_game_address_secure, stick.host, stick.port)).assertIsDisplayed()
        compose.onNodeWithText(stick.description).assertIsDisplayed()
        for (link in stick.links) compose.onNode(hasText(link.label) and hasClickAction() and hasAnyAncestor(isDialog())).assertExists()
        compose.onRoot().tryPerformAccessibilityChecks()

        activate(hasText(string(R.string.profile_connect)) and hasAnyAncestor(isDialog()))
        val save = engine.receive("saveProfile")
        assertEquals(Profile(STICKMUD, stick.host, stick.port, true, "", false), profileOf(save))
        compose.onNode(isDialog()).assertDoesNotExist()
        engine.refuse(save, "the disk is full")
        waitFor(hasText("saveProfile: the disk is full"))
        compose.onNodeWithText("saveProfile: the disk is full").assertIsDisplayed()
        engine.assertNoRequest("openProfile", 1000)
    }

    // What a copy test left the app holding for its profiles
    private fun forgetCopies() = compose.runOnIdle {
        for (name in listOf(COPIED, COPY, SECOND_COPY)) {
            passwords.forget(name)
            Settings.setAwaitsDefaults(name, false)
            Settings.setAwaitsInputClear(name, false)
            Copies.forgetEnded(name)
        }
    }

    // A .zip in the app's cache, of empty files at these paths
    private fun archive(name: String, vararg paths: String) = File(compose.activity.cacheDir, name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for (path in paths) {
                zip.putNextEntry(ZipEntry(path))
                zip.closeEntry()
            }
        }
    }

    private fun stagingEmpty() = File(compose.activity.cacheDir, "profile-import").listFiles().isNullOrEmpty()

    private fun assertStagingEmpty() = assertTrue("an import's staging folder is gone", stagingEmpty())

    // The screen and a dialog over it, each its own root
    private fun checkAccessibility() {
        val roots = compose.onAllNodes(isRoot())
        roots.fetchSemanticsNodes().indices.forEach { roots[it].tryPerformAccessibilityChecks() }
    }

    private fun game(name: String) = compose.activity.assets.open(DefaultGames.FILE).bufferedReader().use { DefaultGames.parse(it.readText()) }
        .single { it.name == name }

    private val importButton get() = hasText(string(R.string.profiles_import)) and hasClickAction()

    private fun gameRow(game: DefaultGame) = hasContentDescription(string(R.string.default_game_description, game.name, game.summary.ifEmpty { string(R.string.profile_address, game.host, game.port) })) and hasClickAction()

    // The list is lazy: a row far down is not laid out, so not found, until scrolled to
    private fun scrollToGame(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        compose.onNode(matcher).assertIsDisplayed()
    }

    /** The engine answers the UI's listProfiles, sent when it attached, with [profiles]. */
    private fun listed(vararg profiles: Profile) {
        engine.profiles(profiles.toList(), engine.receive("listProfiles"))
        compose.waitUntil(10_000) { EngineClient.profiles == profiles.toList() }
    }

    // The engine's answers come over the socket, which the test rule's idling does not wait for
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    // The list lays out only the rows in view, and scrolls itself to the top when games or an error come in
    private fun waitForInList(matcher: SemanticsMatcher) {
        var last: Throwable? = null
        try {
            compose.waitUntil(10_000) {
                last = runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher) }.exceptionOrNull()
                last == null
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("${matcher.description} is not in the list; the import is ${Imports.state}", last ?: e)
        }
    }

    private fun profileOf(request: JSONObject) = Profile(
        request.getString("name"),
        request.getString("host"),
        request.getInt("port"),
        request.getBoolean("tls"),
        request.getString("login"),
        request.getBoolean("autoReconnect"),
    )

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun field(@StringRes label: Int) = compose.onNode(hasSetTextAction() and hasText(string(label)))

    // Through the field's accessibility action, as TalkBack sets text: no keyboard comes up to cover the editor
    private fun type(@StringRes label: Int, text: String) {
        scrollIntoView(hasSetTextAction() and hasText(string(label)))
        field(label).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
    }

    /** What a double tap does under TalkBack: the node's accessibility click. */
    private fun activate(label: SemanticsMatcher) {
        val control = label and hasClickAction()
        scrollIntoView(control)
        compose.onNode(control).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    // As TalkBack does when it moves to a control below the edge of a small screen
    private fun scrollIntoView(matcher: SemanticsMatcher) {
        if (compose.onAllNodes(matcher and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(matcher).performScrollTo()
        }
    }

    private companion object {
        val ALPHA = Profile("Alpha", "a.example", 23, false, "", false)
        val BETA = Profile("Beta", "b.example", 4000, true, "", true)
        val GAMMA = Profile("Gamma", "g.example", 4242, true, "hero", true)

        // Two of Mudlet's games (app/src/main/assets/games), StickMUD's connection secure
        const val ACHAEA = "Achaea"
        const val STICKMUD = "StickMUD"

        // A profile folder the fake engine knows nothing of, made and removed by the export test
        const val EXPORTED = "Export Test"

        // A profile folder a copy test makes for the fake engine to list, and the names it copies it under
        const val COPIED = "Copy Test"
        val COPIED_PROFILE = Profile(COPIED, "c.example", 23, false, "", false)
        const val COPY = "Copy Test Twin"
        const val SECOND_COPY = "Copy Test Triplet"

        // The name an import test adds a profile under, and removes it from
        const val IMPORTED = "Import Test"

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
