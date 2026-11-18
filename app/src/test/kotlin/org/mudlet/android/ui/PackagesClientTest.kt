package org.mudlet.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.InstalledPackage
import org.mudlet.android.protocol.PackageInstallResult

/** What the package manager keeps of the engine's packages, and what it sends, against requests caught here. */
class PackagesClientTest {
    private val sent = ArrayList<JSONObject>()
    private val finished = ArrayList<String>()
    private var attached = true
    private var lastId = 0L
    private val packages = PackagesClient({ ++lastId }, { message -> attached.also { if (it) sent.add(message) } }) { finished.add(it) }

    private val mapper = InstalledPackage("generic_mapper", "Generic Mapper", "Mudlet", "1.2", "Maps as you walk", "")

    /** The request sent last, taken off, which must be a [type]. */
    private fun take(type: String): JSONObject {
        val request = sent.removeAt(sent.lastIndex)
        assertEquals(type, request.getString("t"))
        return request
    }

    private fun listed(list: List<InstalledPackage>) {
        packages.ask()
        assertTrue(packages.onPackages(EngineMessage.Packages(list, take("packages").getLong("id"))))
    }

    @Test
    fun aListIsKeptOnlyWhenItAnswersWhatWasAsked() {
        assertTrue(packages.ask())
        val asked = take("packages")
        assertFalse("another client's answer", packages.onPackages(EngineMessage.Packages(listOf(mapper), 99L)))
        assertNull(packages.packages)
        assertTrue(packages.onPackages(EngineMessage.Packages(listOf(mapper), asked.getLong("id"))))
        assertEquals(listOf(mapper), packages.packages)
        assertFalse("answered once", packages.onPackages(EngineMessage.Packages(emptyList(), asked.getLong("id"))))
        assertEquals(listOf(mapper), packages.packages)
    }

    @Test
    fun aChangeAsksAgainOnlyOnceTheListHasBeenShown() {
        packages.onChanged()
        assertTrue("nothing shown, nothing to ask", sent.isEmpty())
        listed(emptyList())
        packages.onChanged()
        take("packages")
    }

    @Test
    fun anInstallIsShownUntilItsAnswerAndItsFileIsLetGoThen() {
        assertTrue(packages.install("/cache/packages/generic_mapper.mpackage"))
        assertEquals("/cache/packages/generic_mapper.mpackage", take("installPackage").getString("path"))
        assertEquals(listOf("/cache/packages/generic_mapper.mpackage"), packages.installing)
        assertTrue(finished.isEmpty())

        packages.onInstalled(EngineMessage.PackageInstalled("/elsewhere/other.mpackage", PackageInstallResult.INSTALLED, ""))
        assertEquals("another client's install", 1, packages.installing.size)
        assertTrue(packages.notices.isEmpty())

        packages.onInstalled(EngineMessage.PackageInstalled("/cache/packages/generic_mapper.mpackage", PackageInstallResult.FAILED, "not a package"))
        assertTrue(packages.installing.isEmpty())
        assertEquals(listOf("/cache/packages/generic_mapper.mpackage"), finished)
        assertEquals(listOf(PackageNotice.Installed("generic_mapper.mpackage", PackageInstallResult.FAILED, "not a package")), packages.notices)
        packages.shown(packages.notices.first())
        assertTrue(packages.notices.isEmpty())
    }

    @Test
    fun aRefusedInstallLetsGoOfTheOldestWaiting() {
        packages.install("/a.mpackage")
        packages.install("/b.mpackage")
        packages.onRefused("installPackage", "no profile running", null)
        assertEquals(listOf("/b.mpackage"), packages.installing)
        assertEquals(listOf("/a.mpackage"), finished)
        assertEquals(listOf(PackageNotice.Refused("installPackage", "no profile running")), packages.notices)
    }

    @Test
    fun anInstallWithNoEngineIsLetGoAtOnce() {
        attached = false
        assertFalse(packages.install("/a.mpackage"))
        assertTrue(packages.installing.isEmpty())
        assertEquals(listOf("/a.mpackage"), finished)
        assertFalse(packages.ask())
        assertEquals("told once", listOf<PackageNotice>(PackageNotice.NotSent), packages.notices)
    }

    @Test
    fun aRemovalIsAnsweredByTheListWithoutIt() {
        listed(listOf(mapper))
        assertTrue(packages.uninstall("generic_mapper"))
        val removal = take("uninstallPackage")
        assertEquals("generic_mapper", removal.getString("name"))
        assertTrue(packages.onPackages(EngineMessage.Packages(emptyList(), removal.getLong("id"))))
        assertEquals(emptyList<InstalledPackage>(), packages.packages)
    }

    @Test
    fun aRefusedRemovalIsToldAndItsAnswerNoLongerAwaited() {
        packages.uninstall("generic_mapper")
        val id = take("uninstallPackage").getLong("id")
        packages.onRefused("uninstallPackage", "generic_mapper is not installed", id)
        assertEquals(listOf(PackageNotice.Refused("uninstallPackage", "generic_mapper is not installed")), packages.notices)
        assertFalse(packages.onPackages(EngineMessage.Packages(emptyList(), id)))
    }

    @Test
    fun aUrlIsInstalledThroughMudletsOwnInstallPackage() {
        assertTrue(packages.installFrom("https://example.org/a]]b.mpackage"))
        assertEquals("installPackage([=[https://example.org/a]]b.mpackage]=])", take("runLua").getString("code"))
        assertTrue("the console tells how it went", packages.notices.isEmpty())
    }

    @Test
    fun aLongStringOutlastsEveryClosingBracketInIt() {
        assertEquals("[[x]]", PackagesClient.luaString("x"))
        assertEquals("[==[a]]b]=]c]==]", PackagesClient.luaString("a]]b]=]c"))
    }

    @Test
    fun reattachingDropsWhatTheLastLinkLeftUnanswered() {
        listed(listOf(mapper))
        packages.install("/a.mpackage")
        packages.uninstall("generic_mapper")
        val id = take("uninstallPackage").getLong("id")

        packages.onAttached(sameInstance = true)
        assertTrue(packages.installing.isEmpty())
        assertEquals(listOf("/a.mpackage"), finished)
        assertFalse(packages.onPackages(EngineMessage.Packages(emptyList(), id)))
        assertEquals("the same engine still has them", listOf(mapper), packages.packages)
        assertEquals(1, packages.attaches)

        packages.onAttached(sameInstance = false)
        assertNull("a new engine lists them again", packages.packages)
        assertEquals(2, packages.attaches)
    }
}
