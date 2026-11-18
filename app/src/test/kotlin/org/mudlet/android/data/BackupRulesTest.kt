package org.mudlet.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.data.BackupScope.Destination.CLOUD
import org.mudlet.android.data.BackupScope.Destination.NEW_PHONE
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupRulesTest {
    private val xml = File("src/main/res/xml")

    @Test
    fun theLogsAndSuggestionsGoOnlyToANewPhone() {
        for (path in listOf("mudlet/profiles/My Game/log/2026-11-18#10-00-00.html", "mudlet/profiles/a.b/log/errors.txt", "suggest/My Game.txt")) {
            assertFalse("$path goes to a cloud backup", BackupScope.takes(path, CLOUD))
            assertTrue("$path goes to a new phone", BackupScope.takes(path, NEW_PHONE))
        }
    }

    @Test
    fun theProfilesMudletsSettingsAndPinsGoToBoth() {
        val paths = listOf(
            "mudlet/profiles/My Game/current/2026-11-18#10-00-00.xml",
            "mudlet/profiles/My Game/map/2026-11-18#10-00-00map.dat",
            "mudlet/profiles/My Game/url",
            "mudlet/profiles/My Game/media/bell.wav",
            // A package's own file of a secret's name is the package's
            "mudlet/profiles/My Game/mypackage/password",
            "mudlet/profiles/My Game/mypackage/log/notes.txt",
            "mudlet/profiles/log/current/1.xml",
            "mudlet/Mudlet.ini",
            "mudlet/profile-order.json",
            "suggest/My Game.pins",
        )
        for (path in paths) for (to in BackupScope.Destination.entries) assertTrue("$path goes to $to", BackupScope.takes(path, to))
        assertTrue(BackupScope.takesPreferences("settings.xml"))
    }

    @Test
    fun passwordsTokensAndThisDevicesEngineStateGoNowhere() {
        val paths = listOf(
            PasswordStore.FILE,
            "settings/Mudlet-My Game-login-0a1b.conf",
            "engine-session.json",
            "engine-stopped",
            "mudlet/profiles/My Game/password",
            "mudlet/profiles/My Game/irc_password",
            "mudlet/profiles/../../passwords/profiles.json",
            "mudlet/profiles/./password",
            "mudlet/../passwords/profiles.json",
            "suggest/../settings/x.pins",
            "something-new",
        )
        for (path in paths) {
            for (to in BackupScope.Destination.entries) assertFalse("$path goes to $to", BackupScope.takes(path, to))
            assertFalse("a restore keeps $path", BackupScope.restores(path))
        }
        for (prefs in listOf("engine.xml", "ui.xml", "settings.xml.bak", "settings")) assertFalse(prefs, BackupScope.takesPreferences(prefs))
    }

    @Test
    fun aCloudBackupIsTakenOnlyWhenItIsEncryptedWithTheScreenLock() {
        assertNull(BackupScope.destination(0))
        assertEquals(CLOUD, BackupScope.destination(BackupScope.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED))
        assertEquals(NEW_PHONE, BackupScope.destination(BackupScope.FLAG_DEVICE_TO_DEVICE_TRANSFER))
        assertEquals(NEW_PHONE, BackupScope.destination(BackupScope.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED or BackupScope.FLAG_DEVICE_TO_DEVICE_TRANSFER))
    }

    @Test
    fun everyRuleListLeavesOutThePasswordsTokensAndEngineState() {
        val lists = mapOf(
            "cloud-backup" to section("data_extraction_rules.xml", "cloud-backup"),
            "device-transfer" to section("data_extraction_rules.xml", "device-transfer"),
            "full-backup-content" to parse("backup_rules.xml").documentElement,
        )
        val leftOut = listOf(
            "file" to "passwords/",
            "file" to "settings/",
            "file" to "engine-session.json",
            "file" to "engine-stopped",
            "sharedpref" to "engine.xml",
            "sharedpref" to "ui.xml",
            "root" to ".",
            "database" to ".",
            "external" to ".",
        )
        for ((name, list) in lists) {
            val excluded = rules(list, "exclude")
            for (rule in leftOut) assertTrue("$name excludes $rule: $excluded", rule in excluded)
            // An include would leave out all that it does not name, the profiles too, or bring back an excluded file
            assertEquals("$name includes nothing", emptyList<Pair<String, String>>(), rules(list, "include"))
        }
        assertEquals("true", section("data_extraction_rules.xml", "cloud-backup").getAttribute("disableIfNoEncryptionCapabilities"))
        assertTrue(PasswordStore.FILE.startsWith("passwords/"))
    }

    @Test
    fun whatTheRulesLeaveOutTheAgentDoesNotTake() {
        for ((domain, path) in rules(parse("backup_rules.xml").documentElement, "exclude")) {
            if (domain == "file") {
                val inside = if (path.endsWith("/")) path + "x" else path
                for (to in BackupScope.Destination.entries) assertFalse("$inside goes to $to", BackupScope.takes(inside, to))
            } else if (domain == "sharedpref") {
                assertFalse(path, BackupScope.takesPreferences(path))
            }
        }
    }

    @Test
    fun theAppBacksUpThroughItsAgent() {
        val app = parse("../../AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("true", app.getAttribute("android:allowBackup"))
        assertEquals("true", app.getAttribute("android:fullBackupOnly"))
        assertEquals(".data.MudletBackupAgent", app.getAttribute("android:backupAgent"))
        assertEquals("@xml/backup_rules", app.getAttribute("android:fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.getAttribute("android:dataExtractionRules"))
    }

    private fun parse(name: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(xml, name))

    private fun section(file: String, name: String) = parse(file).getElementsByTagName(name).also { assertEquals(name, 1, it.length) }.item(0) as Element

    private fun rules(list: Element, tag: String): List<Pair<String, String>> {
        val nodes = list.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }.map { it.getAttribute("domain") to it.getAttribute("path") }
    }
}
