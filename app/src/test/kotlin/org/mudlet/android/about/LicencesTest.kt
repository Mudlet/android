package org.mudlet.android.about

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The licence data the About screen reads: the parser, and the files in src/main/assets/licences as they are */
class LicencesTest {
    // Gradle runs a module's unit tests in the module's directory
    private val assets = File("src/main/assets/licences")
    private val noGradleModules = """{"components": []}"""

    private fun repositoryFiles() = mapOf(
        "notices.json" to assets.resolve("notices.json").readText(),
        "qt-third-party.json" to assets.resolve("qt-third-party.json").readText(),
        "android.json" to noGradleModules,
    )

    @Test
    fun licenceIdsAreTheIdentifiersOfAnSpdxExpression() {
        assertEquals(listOf("MIT"), Licences.licenceIds("MIT"))
        assertEquals(listOf("Apache-2.0", "LLVM-exception"), Licences.licenceIds("Apache-2.0 WITH LLVM-exception"))
        assertEquals(listOf("MPL-1.1", "GPL-2.0-or-later", "LGPL-2.1-or-later"), Licences.licenceIds("(MPL-1.1 OR GPL-2.0-or-later) OR LGPL-2.1-or-later"))
    }

    @Test
    fun paragraphsJoinHardLineBreaksAndSplitOnBlankLines() {
        val text = "First line\r\nof one paragraph.\n\n  \nSecond   \n  paragraph.\n\n\n"
        assertEquals(listOf("First line of one paragraph.", "Second paragraph."), Licences.paragraphs(text))
    }

    @Test
    fun parseOrdersTheComponentsByGroupThenName() {
        val notices = """{"components": [
            {"id": "b", "group": "native", "name": "beta", "licence": "MIT"},
            {"id": "m", "group": "app", "name": "Mudlet", "licence": "GPL-2.0-or-later", "texts": ["GPL-3.0-only"]},
            {"id": "a", "group": "native", "name": "Alpha", "licence": "Apache-2.0 WITH LLVM-exception"}
        ]}"""
        val gradle = """{"components": [{"id": "maven:x:y", "name": "x:y", "version": "1", "licence": "Apache-2.0"}]}"""
        val files = mapOf("notices.json" to notices, "android.json" to gradle)

        val components = Licences.parse(files)
        assertEquals(listOf("m", "a", "b", "maven:x:y"), components.map { it.id })
        assertEquals(listOf("GPL-2.0-or-later", "GPL-3.0-only"), components[0].texts)
        assertEquals(listOf("Apache-2.0", "LLVM-exception"), components[1].texts)
        assertEquals(LicenceGroup.ANDROID, components[3].group)
    }

    @Test
    fun parseRefusesAComponentWithoutALicence() {
        val files = mapOf("notices.json" to """{"components": [{"id": "x", "group": "app", "name": "X"}]}""")
        assertThrows(JSONException::class.java) { Licences.parse(files) }
    }

    @Test
    fun theRepositoryListsParseAndHaveEveryTextTheyName() {
        val texts = assets.resolve("texts").list()!!.map { it.removeSuffix(".txt") }.toSet()
        val components = Licences.parse(repositoryFiles())
        assertEquals("ids are unique", components.size, components.map { it.id }.toSet().size)
        for (c in components) {
            assertTrue("${c.id} names ${c.texts - texts}, which have no text", texts.containsAll(c.texts))
            assertTrue("${c.id} has no name", c.name.isNotBlank())
        }
        val ids = components.map { it.id }.toSet()
        assertTrue("Qt is listed", "qt" in ids)
        assertEquals("this app's own code is listed as LICENSE has it", "GPL-3.0-or-later", components.single { it.id == "mudlet-android" }.licence)
        assertTrue("Mudlet and its Lua are listed", "mudlet" in ids && "lua51" in ids)
        assertFalse("Qt's third-party code is listed", components.none { it.group == LicenceGroup.QT })
    }
}
