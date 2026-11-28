package org.mudlet.android.data

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.data.ProfileFolderCopy.SettingsCopy
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

class ProfileFolderCopyTest {
    private val root = Files.createTempDirectory("profile-copy").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aWholeCopyHoldsAllTheProfileDoesButItsPasswordsItsSignInAndWhatDesktopLeavesOut() {
        val source = profile("Game")
        val map = ByteArray(300_000) { (it % 251).toByte() }
        write(source, "current/2026-11-17#10-00-00.xml", SAVE)
        write(source, "map/2026-11-17#10-00-00map.dat", map)
        write(source, "url", "game.example")
        write(source, "autoreconnect", "2")
        write(source, "password", "hunter2")
        write(source, "irc_password", "irc secret")
        write(source, "reconnect", "an account and its provider")
        write(source, "encryption_key", "a key")
        write(source, "passwords/0a1b2c.dat", "a stored password")
        // Only the profile's own data items: a package's file of the same name is the package's
        write(source, "mypackage/password", "a package's")
        write(source, "mypackage/reconnect", "a package's")
        write(source, "mypackage/passwords/notes.txt", "a package's")
        write(source, ".hidden", "hidden")
        write(source, ".git/config", "hidden")
        File(source, "log").mkdirs()
        val target = File(root, "staging/Game1")

        ProfileFolderCopy.whole(source, target)

        assertEquals(
            listOf(
                "autoreconnect",
                "current/",
                "current/2026-11-17#10-00-00.xml",
                "log/",
                "map/",
                "map/2026-11-17#10-00-00map.dat",
                "mypackage/",
                "mypackage/password",
                "mypackage/passwords/",
                "mypackage/passwords/notes.txt",
                "mypackage/reconnect",
                "url",
            ),
            tree(target),
        )
        assertArrayEquals(map, File(target, "map/2026-11-17#10-00-00map.dat").readBytes())
        assertEquals(SAVE, File(target, "current/2026-11-17#10-00-00.xml").readText())
    }

    @Test
    fun aWholeCopyKeepsEachFilesDateByWhichMudletPicksTheNewestSave() {
        val source = profile("Dated")
        write(source, "current/old.xml", SAVE)
        write(source, "current/new.xml", SAVE)
        File(source, "current/old.xml").setLastModified(1_600_000_000_000)
        File(source, "current/new.xml").setLastModified(1_700_000_000_000)
        val target = File(root, "staging/Dated1")

        ProfileFolderCopy.whole(source, target)

        assertEquals(1_600_000_000_000, File(target, "current/old.xml").lastModified())
        assertEquals(1_700_000_000_000, File(target, "current/new.xml").lastModified())
    }

    @Test
    fun aWholeCopyLeavesOutALinkRatherThanFollowingItOutOfTheFolder() {
        val source = profile("Linked")
        val outside = File(root, "outside").apply { mkdirs() }
        write(outside, "private.txt", "not the profile's")
        write(source, "current/a.xml", SAVE)
        Files.createSymbolicLink(File(source, "escape").toPath(), outside.toPath())
        Files.createSymbolicLink(File(source, "current/b.xml").toPath(), File(outside, "private.txt").toPath())
        val target = File(root, "staging/Linked1")

        ProfileFolderCopy.whole(source, target)

        assertEquals(listOf("current/", "current/a.xml"), tree(target))
    }

    @Test
    fun aCopyIsOnlyOfAProfilesFolderIntoANewOne() {
        val source = profile("Game")
        write(source, "url", "game.example")
        val existing = File(root, "staging/Taken").apply { mkdirs() }
        write(existing, "kept", "as it was")

        assertThrows(FileNotFoundException::class.java) { ProfileFolderCopy.whole(File(root, "profiles/None"), File(root, "staging/a")) }
        assertThrows(FileNotFoundException::class.java) { ProfileFolderCopy.settingsOnly(File(root, "profiles/None"), File(root, "staging/b")) }
        assertThrows(IOException::class.java) { ProfileFolderCopy.whole(source, existing) }
        assertThrows(IOException::class.java) { ProfileFolderCopy.settingsOnly(source, existing) }
        assertEquals(listOf("kept"), tree(existing))
    }

    @Test
    fun aSettingsCopyTakesTheConnectionDetailsAndTheSettingsOfTheNewestSave() {
        val source = profile("Game")
        write(source, "current/2026-11-16#10-00-00.xml", SAVE.replace("game.example", "old.example"))
        write(source, "current/2026-11-17#10-00-00.xml", SAVE)
        // A save cut short leaves its temporary file, the newest there, which is no save
        write(source, "current/2026-11-18#10-00-00.xml.AbCdEf", "<MudletPa")
        File(source, "current/2026-11-16#10-00-00.xml").setLastModified(1_600_000_000_000)
        File(source, "current/2026-11-17#10-00-00.xml").setLastModified(1_700_000_000_000)
        for (item in listOf("url", "port", "ssl_tsl", "login", "description", "password", "irc_password", "autoreconnect", "website")) {
            write(source, item, "the $item")
        }
        write(source, "map/2026-11-17#10-00-00map.dat", "a map")
        write(source, "log/2026-11-17.txt", "a log")
        val target = File(root, "staging/Game1")

        assertEquals(SettingsCopy.Copied, ProfileFolderCopy.settingsOnly(source, target))

        assertEquals(listOf("current/", "current/Copied profile (settings only).xml", "description", "login", "port", "ssl_tsl", "url"), tree(target))
        assertEquals("the login", File(target, "login").readText())
        assertEquals(SETTINGS, File(target, "current/Copied profile (settings only).xml").readText())
    }

    @Test
    fun aSettingsCopyReadsNoLinkOutOfTheFolder() {
        val source = profile("Linked")
        val outside = File(root, "outside").apply { mkdirs() }
        write(outside, "private.txt", "not the profile's")
        write(outside, "private.xml", SAVE.replace("game.example", "private.example"))
        write(source, "url", "game.example")
        write(source, "current/a.xml", SAVE)
        File(source, "current/a.xml").setLastModified(1_600_000_000_000)
        Files.createSymbolicLink(File(source, "login").toPath(), File(outside, "private.txt").toPath())
        Files.createSymbolicLink(File(source, "current/b.xml").toPath(), File(outside, "private.xml").toPath())
        val target = File(root, "staging/Linked1")

        assertEquals(SettingsCopy.Copied, ProfileFolderCopy.settingsOnly(source, target))

        assertEquals(listOf("current/", "current/Copied profile (settings only).xml", "url"), tree(target))
        assertEquals(SETTINGS, File(target, "current/Copied profile (settings only).xml").readText())
    }

    @Test
    fun aSettingsCopyOfAProfileNeverOpenedHasItsConnectionDetailsAlone() {
        val source = profile("New")
        write(source, "url", "game.example")
        write(source, "port", "4000")
        val target = File(root, "staging/New1")

        assertEquals(SettingsCopy.NoSave, ProfileFolderCopy.settingsOnly(source, target))

        assertEquals(listOf("port", "url"), tree(target))
    }

    @Test
    fun aSaveThatCannotBeReadIsSaidAndNoPartOfItIsLeftToLoad() {
        for ((what, save) in listOf(
            "cut short" to SAVE.substringBefore("</Host>").toByteArray(),
            "another program's" to "<?xml version=\"1.0\"?>\n<html><HostPackage><Host /></HostPackage></html>".toByteArray(),
            "not text in its encoding" to SAVE.toByteArray().also { it[it.indexOf('G'.code.toByte())] = 0xFF.toByte() },
            "empty" to ByteArray(0),
        )) {
            val source = profile("Broken $what")
            write(source, "url", "game.example")
            write(source, "current/save.xml", save)
            val target = File(root, "staging/Broken $what")

            val copied = ProfileFolderCopy.settingsOnly(source, target)

            assertTrue("$what: $copied", copied is SettingsCopy.Unreadable && copied.why.startsWith("save.xml: "))
            assertEquals(what, listOf("url"), tree(target))
        }
    }

    @Test
    fun aSaveWhoseDocumentTypeDeclaresAnythingIsNotRead() {
        val secret = File(root, "secret.txt").apply { writeText("not the profile's") }
        val host = "<MudletPackage version=\"1.001\"><HostPackage><Host><name>&a;</name></Host></HostPackage></MudletPackage>"
        for ((what, prolog) in listOf(
            "an entity that expands" to "<!DOCTYPE MudletPackage [<!ENTITY a \"aaaaaaaaaa\">]>",
            "an entity read from a file" to "<!DOCTYPE MudletPackage [<!ENTITY a SYSTEM \"${secret.toURI()}\">]>",
            "a document type read from a file" to "<!DOCTYPE MudletPackage SYSTEM \"${secret.toURI()}\">",
            "a comment before the root" to "<!-- <MudletPackage> -->",
        )) {
            val out = ByteArrayOutputStream()

            assertNotNull(what, ProfileFolderCopy.extractSettings(ByteArrayInputStream("<?xml version=\"1.0\"?>$prolog$host".toByteArray()), out))

            assertEquals(what, "", out.toString())
        }
    }

    @Test
    fun aSaveIsReadWithOrWithoutWhatDesktopWritesBeforeItsRoot() {
        val host = "<MudletPackage><HostPackage><Host><name>Game</name></Host></HostPackage></MudletPackage>"
        for (prolog in listOf("", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n", "\uFEFF<?xml version=\"1.0\"?><!DOCTYPE MudletPackage>\n", "<!DOCTYPE  MudletPackage >")) {
            val out = ByteArrayOutputStream()

            assertNull(prolog, ProfileFolderCopy.extractSettings(ByteArrayInputStream((prolog + host).toByteArray()), out))

            assertEquals(
                prolog,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE MudletPackage>\n<MudletPackage>\n\t<HostPackage>\n\t\t<Host>\n\t\t\t<name>Game</name>\n\t\t</Host>\n\t</HostPackage>\n</MudletPackage>\n",
                out.toString(),
            )
        }
    }

    @Test
    fun theSettingsReadBackAsTheyWereWhateverCharactersTheyHold() {
        // A carriage return, a tab and a line feed, markup's own characters, Mudlet's stand-in for a control character
        // (U+FFFC and a control picture), and a character outside the basic plane
        val value = "a\rb\tc\nd <&>\"' ￼␛ 😀 "
        val escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            .replace("\r", "&#13;").replace("\t", "&#9;").replace("\n", "&#10;")
        val save = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE MudletPackage>\n<MudletPackage version=\"1.001\">\n" +
            "\t<HostPackage>\n\t\t<Host mCommandSeparator=\"$escaped\">\n\t\t\t<name>$escaped</name>\n\t\t\t<cdata><![CDATA[<raw> & ]]></cdata>\n" +
            "\t\t\t<blank> \n\t</blank>\n\t\t</Host>\n\t</HostPackage>\n</MudletPackage>\n"
        val out = ByteArrayOutputStream()

        assertNull(ProfileFolderCopy.extractSettings(ByteArrayInputStream(save.toByteArray()), out))

        val host = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(out.toByteArray()))
            .getElementsByTagName("Host").item(0) as Element
        assertEquals(value, host.getAttribute("mCommandSeparator"))
        assertEquals(value, host.getElementsByTagName("name").item(0).textContent)
        assertEquals("<raw> & ", host.getElementsByTagName("cdata").item(0).textContent)
        // As pugixml reads a save for desktop's copy: text that is only white space is dropped
        assertEquals("", host.getElementsByTagName("blank").item(0).textContent)
    }

    @Test
    fun aSaveNestedDeeperThanMudletWritesIsNotReadToBeWrittenManyTimesItsSize() {
        val nested = 5_000
        val save = "<MudletPackage><HostPackage><Host>" + "<a>".repeat(nested) + "</a>".repeat(nested) + "</Host></HostPackage></MudletPackage>"
        var written = 0L
        val out = object : OutputStream() {
            override fun write(b: Int) {
                written++
            }
        }

        assertNotNull(ProfileFolderCopy.extractSettings(ByteArrayInputStream(save.toByteArray()), out))

        assertTrue("$written bytes written of a ${save.length} byte save", written < save.length)
    }

    @Test
    fun onlyTheFirstHostPackageIsKept() {
        val save = "<MudletPackage><HostPackage><Host><name>first</name></Host></HostPackage>" +
            "<HostPackage><Host><name>second</name></Host></HostPackage></MudletPackage>"
        val out = ByteArrayOutputStream()

        assertNull(ProfileFolderCopy.extractSettings(ByteArrayInputStream(save.toByteArray()), out))

        assertTrue(out.toString(), "first" in out.toString() && "second" !in out.toString())
    }

    private fun profile(name: String) = File(root, "profiles/$name").apply { mkdirs() }

    private fun write(folder: File, path: String, text: String) = write(folder, path, text.toByteArray())

    private fun write(folder: File, path: String, bytes: ByteArray) {
        File(folder, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }

    // Every file and folder under [folder], by path, a folder's ending in a slash
    private fun tree(folder: File): List<String> = folder.walkTopDown().filter { it != folder }
        .map { it.relativeTo(folder).invariantSeparatorsPath + if (it.isDirectory) "/" else "" }.sorted().toList()

    private companion object {
        // A save as Mudlet writes one, cut down: the profile's settings in its HostPackage, its items in the others
        val SAVE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE MudletPackage>
            <MudletPackage version="1.001">
            	<TriggerPackage>
            		<Trigger isActive="yes" isFolder="no">
            			<name>a trigger</name>
            			<script>send("hello")</script>
            		</Trigger>
            	</TriggerPackage>
            	<HostPackage>
            		<Host autoClearCommandLineAfterSend="yes" mRawStreamDump="no" mEditorTheme="Mudlet">
            			<name>Game</name>
            			<mInstalledPackages>
            				<string>generic_mapper</string>
            			</mInstalledPackages>
            			<mInstalledModules>
            				<key>a module</key>
            				<filepath>/somewhere/module.xml</filepath>
            			</mInstalledModules>
            			<url>game.example</url>
            			<mLineDelimiter></mLineDelimiter>
            			<wrapAt>100</wrapAt>
            			<stopwatches />
            			<mapInfoContributor>Short</mapInfoContributor>
            		</Host>
            	</HostPackage>
            	<ScriptPackage />
            	<VariablePackage>
            		<HiddenVariables />
            	</VariablePackage>
            </MudletPackage>
        """.trimIndent() + "\n"

        val SETTINGS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE MudletPackage>
            <MudletPackage version="1.001">
            	<HostPackage>
            		<Host autoClearCommandLineAfterSend="yes" mRawStreamDump="no" mEditorTheme="Mudlet">
            			<name>Game</name>
            			<url>game.example</url>
            			<mLineDelimiter />
            			<wrapAt>100</wrapAt>
            			<stopwatches />
            			<mapInfoContributor>Short</mapInfoContributor>
            		</Host>
            	</HostPackage>
            </MudletPackage>
        """.trimIndent() + "\n"
    }
}
