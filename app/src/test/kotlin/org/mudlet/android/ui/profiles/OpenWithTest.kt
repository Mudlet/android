package org.mudlet.android.ui.profiles

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mudlet.android.data.ProfileArchive
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** An Intent from another app told apart by its action and URI schemes, and the manifest offering the app for a .zip. */
class OpenWithTest {
    @Test
    fun aFileOpenedOrSharedWithTheAppIsAProfileToImport() {
        assertEquals(OpenWith.PROFILE_FILE, OpenWith.of(OpenWith.ACTION_VIEW, "content", null))
        assertEquals(OpenWith.PROFILE_FILE, OpenWith.of(OpenWith.ACTION_VIEW, "CONTENT", null))
        assertEquals(OpenWith.PROFILE_FILE, OpenWith.of(OpenWith.ACTION_SEND, null, "content"))
    }

    @Test
    fun aViewOfAnythingElseIsAGameLinkForTelnetLinkToJudge() {
        assertEquals(OpenWith.GAME_LINK, OpenWith.of(OpenWith.ACTION_VIEW, "telnet", null))
        assertEquals(OpenWith.GAME_LINK, OpenWith.of(OpenWith.ACTION_VIEW, "telnets", null))
        assertEquals(OpenWith.GAME_LINK, OpenWith.of(OpenWith.ACTION_VIEW, "https", null))
        assertEquals(OpenWith.GAME_LINK, OpenWith.of(OpenWith.ACTION_VIEW, null, null))
    }

    @Test
    fun aFilePathOrASendWithNoFileIsNothing() {
        assertEquals(OpenWith.NOTHING, OpenWith.of(OpenWith.ACTION_VIEW, "file", null))
        assertEquals(OpenWith.NOTHING, OpenWith.of(OpenWith.ACTION_SEND, null, "file"))
        assertEquals(OpenWith.NOTHING, OpenWith.of(OpenWith.ACTION_SEND, null, null))
        assertEquals(OpenWith.NOTHING, OpenWith.of(OpenWith.ACTION_SEND, "content", null))
        assertEquals(OpenWith.NOTHING, OpenWith.of("android.intent.action.MAIN", null, null))
        assertEquals(OpenWith.NOTHING, OpenWith.of(null, "content", "content"))
    }

    @Test
    fun theManifestOffersTheAppForAZipByTheTypesTheImportTakes() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val filters = manifest.getElementsByTagName("intent-filter").let { list -> (0 until list.length).map { list.item(it) as Element } }
        fun values(filter: Element, tag: String, attribute: String) = filter.getElementsByTagName(tag).let { list -> (0 until list.length).map { (list.item(it) as Element).getAttribute(attribute) }.filter { it.isNotEmpty() } }

        fun withTypes(action: String) = filters.single { action in values(it, "action", "android:name") && values(it, "data", "android:mimeType").isNotEmpty() }
        val view = withTypes(OpenWith.ACTION_VIEW)
        assertEquals(ProfileArchive.MIME_TYPES.toSet(), values(view, "data", "android:mimeType").toSet())
        assertEquals("a VIEW only of what another app grants the app to read", listOf("content"), values(view, "data", "android:scheme"))
        val send = withTypes(OpenWith.ACTION_SEND)
        assertEquals(ProfileArchive.MIME_TYPES.toSet(), values(send, "data", "android:mimeType").toSet())
        // A SEND carries its file in an extra, and a filter naming a scheme matches only an Intent with a data URI
        assertEquals(emptyList<String>(), values(send, "data", "android:scheme"))
    }
}
