package org.mudlet.android.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.MapFileStaging
import org.mudlet.android.data.ProfileArchive
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.profiles.Imports
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A profile's .zip that another app opens with Mudlet or shares with it, sent as a file manager sends one (a VIEW or
 * SEND Android resolves through the manifest's filters, with a content URI): the player sees what it holds and the
 * name it will have, nothing is written until they confirm, and a bad archive gets the import's own refusal.
 */
@RunWith(AndroidJUnit4::class)
class OpenZipTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(GameLinkTest.TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var engine: FakeEngine.Session
    private val context get() = compose.activity
    private val imported get() = ProfileArchive.folder(context.filesDir, IMPORTED)

    @Before
    fun attach() {
        compose.enableAccessibilityChecksOfWhatShows()
        engine = fake.engine.nextSession()
        engine.profiles(listOf(ALPHA), engine.receive("listProfiles"))
        compose.waitUntil(10_000) { EngineClient.profiles == listOf(ALPHA) }
    }

    @After
    fun cleanUp() {
        compose.runOnUiThread {
            Imports.dismiss()
            Imports.forgetEnded()
        }
        imported.deleteRecursively()
        ProfileArchive.folder(context.filesDir, "Alpha1").deleteRecursively()
        MapFileStaging.folder(context.cacheDir).listFiles()?.filter { it.name.startsWith("open-zip") }?.forEach { it.delete() }
    }

    @Test
    fun aSharedZipShowsWhatItHoldsAndIsImportedOnlyOnceConfirmed() {
        val uri = archive("open-zip-shared.zip", "Alpha/current/save.xml" to SAVE, "Alpha/password" to "hunter2")
        send(Intent(Intent.ACTION_SEND).setType(ProfileArchive.MIME_TYPE).putExtra(Intent.EXTRA_STREAM, uri))

        waitFor(inDialog(string(R.string.profile_import_title, "Alpha")))
        val size = Formatter.formatShortFileSize(context, SAVE.length.toLong())
        compose.onNode(inDialog(context.resources.getQuantityString(R.plurals.profile_file_contents, 1, 1, size))).assertIsDisplayed()
        compose.onNode(inDialog(string(R.string.profile_import_text))).assertIsDisplayed()
        // Alpha is listed, so desktop's name for a copy of it is offered
        compose.onNode(hasSetTextAction() and hasText("Alpha1")).assertIsDisplayed()
        assertNothingWritten()

        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("alpha")) }
        compose.onNode(inDialog(string(R.string.profile_import_taken, "Alpha"))).assertIsDisplayed()
        compose.onNode(inDialog(string(R.string.profile_import)) and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(IMPORTED)) }
        checkAccessibility()
        engine.assertNoRequest("listProfiles", GameLinkTest.QUIET_MS)
        assertNothingWritten()

        activate(inDialog(string(R.string.profile_import)))
        engine.profiles(listOf(ALPHA, Profile(IMPORTED, "", 23, false, "", false)), engine.receive("listProfiles"))
        waitFor(inDialog(string(R.string.profile_imported, IMPORTED)))
        checkAccessibility()
        assertEquals(SAVE, File(imported, "current/save.xml").readText())
        assertFalse("its password is not imported", File(imported, "password").exists())
        assertFalse("only under the name confirmed", ProfileArchive.folder(context.filesDir, "Alpha1").exists())
        assertTrue("an import's staging folder is gone", stagingEmpty())

        activate(inDialog(string(R.string.profile_file_ok)))
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun cancelWritesNothing() {
        val uri = archive("open-zip-cancel.zip", "$IMPORTED/current/save.xml" to SAVE)
        send(Intent(Intent.ACTION_VIEW).setDataAndType(uri, ProfileArchive.MIME_TYPE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        waitFor(inDialog(string(R.string.profile_import_title, IMPORTED)))
        activate(inDialog(string(R.string.profile_import_cancel)))
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        assertNothingWritten()
        engine.assertNoRequest("listProfiles", GameLinkTest.QUIET_MS)
    }

    @Test
    fun theOfferOutlivesARotation() {
        val uri = archive("open-zip-rotated.zip", "Alpha/current/save.xml" to SAVE)
        send(Intent(Intent.ACTION_SEND).setType(ProfileArchive.MIME_TYPE).putExtra(Intent.EXTRA_STREAM, uri))
        waitFor(inDialog(string(R.string.profile_import_title, "Alpha")))
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(IMPORTED)) }
        compose.activityRule.scenario.recreate()
        // The new Activity attaches to the engine again
        engine = fake.engine.nextSession()
        engine.profiles(listOf(ALPHA), engine.receive("listProfiles"))
        waitFor(inDialog(string(R.string.profile_import_title, "Alpha")))
        compose.onNode(hasSetTextAction() and hasText(IMPORTED)).assertIsDisplayed()
        assertNothingWritten()
        activate(inDialog(string(R.string.profile_import_cancel)))
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aBadArchiveGetsTheImportsRefusalAndWritesNothing() {
        // From the staging folder, cache/profile-import/<id>/, to the cache itself
        val escaped = File(context.cacheDir, "escaped")
        val uri = archive("open-zip-hostile.zip", "Evil/current/save.xml" to SAVE, "Evil/../../../escaped" to "out")
        try {
            send(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            waitFor(inDialog(string(R.string.profile_import_unsafe_path)))
            checkAccessibility()
            assertFalse(escaped.exists())
            assertFalse(ProfileArchive.folder(context.filesDir, "Evil").exists())
            assertTrue("an import's staging folder is gone", stagingEmpty())
            activate(inDialog(string(R.string.profile_file_ok)))
            compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        } finally {
            escaped.delete()
        }
    }

    // As a file manager sends it: implicit, so that the manifest's filters must take it
    private fun send(intent: Intent) {
        context.applicationContext.startActivity(
            intent.setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    // A content URI from the app's own FileProvider, which serves the map-files folder in the cache
    private fun archive(name: String, vararg entries: Pair<String, String>): Uri {
        val file = File(MapFileStaging.folder(context.cacheDir).apply { mkdirs() }, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, text) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.mapfiles", file)
    }

    private fun assertNothingWritten() {
        assertTrue("nothing is unpacked before the player confirms", stagingEmpty())
        assertFalse(imported.exists())
        assertFalse(ProfileArchive.folder(context.filesDir, "Alpha1").exists())
    }

    private fun stagingEmpty() = File(context.cacheDir, "profile-import").listFiles().isNullOrEmpty()

    // The screen and a dialog over it, each its own root
    private fun checkAccessibility() {
        val roots = compose.onAllNodes(isRoot())
        roots.fetchSemanticsNodes().indices.forEach { roots[it].tryPerformAccessibilityChecks() }
    }

    private fun string(@StringRes id: Int, vararg args: Any) = context.getString(id, *args)

    private fun inDialog(text: String) = GameLinkTest.inDialog(text)

    private fun waitFor(matcher: SemanticsMatcher) = GameLinkTest.waitFor(compose, matcher)

    private fun activate(label: SemanticsMatcher) = GameLinkTest.activate(compose, label)

    private companion object {
        val ALPHA = Profile("Alpha", "a.example", 23, false, "", false)
        const val IMPORTED = "Shared Import"
        const val SAVE = "<MudletPackage/>"
    }
}
