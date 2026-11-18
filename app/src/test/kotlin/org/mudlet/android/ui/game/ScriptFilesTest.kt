package org.mudlet.android.ui.game

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Copying the file a script asked for into the game's folder. */
class ScriptFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aNameIsOneFilesNameNeverAPath() {
        assertEquals("map.dat", ScriptFiles.safeName("map.dat"))
        assertEquals("map.dat", ScriptFiles.safeName("../../map.dat"))
        assertEquals("evil.lua", ScriptFiles.safeName("C:\\x\\evil.lua"))
        assertEquals("ab", ScriptFiles.safeName(" a\u0000b "))
        for (none in listOf(null, "", "  ", ".", "..", "dir/", "/..")) assertEquals(ScriptFiles.UNNAMED, ScriptFiles.safeName(none))
    }

    @Test
    fun aFileAlreadyThereIsNeverReplaced() {
        val dir = folder.root
        File(dir, "map.dat").writeText("old")
        File(dir, "map (2).dat").writeText("old")
        val copied = ScriptFiles.copyInto(dir, "map.dat", "new".byteInputStream())
        assertEquals(File(dir, "map (3).dat"), copied)
        assertEquals("new", copied.readText())
        assertEquals("old", File(dir, "map.dat").readText())
        File(dir, "notes").writeText("old")
        assertEquals(File(dir, "notes (2)"), ScriptFiles.freeFile(dir, "notes"))
        assertEquals(File(dir, ".profile"), ScriptFiles.freeFile(dir, ".profile"))
    }

    @Test
    fun aCopyIsWholeOrNotThere() {
        val dir = folder.root
        val bytes = ByteArray(300_000) { it.toByte() }
        assertArrayEquals(bytes, ScriptFiles.copyInto(dir, "big.bin", bytes.inputStream()).readBytes())
        val broken = object : InputStream() {
            var left = 10_000

            override fun read(): Int = if (left-- > 0) 1 else throw IOException("gone")
        }
        try {
            ScriptFiles.copyInto(dir, "half.bin", broken)
            throw AssertionError("a broken stream was copied")
        } catch (_: IOException) {
        }
        assertEquals(listOf("big.bin"), dir.list()!!.toList())
    }

    @Test
    fun onlyAFolderInTheAppsOwnFilesTakesOne() {
        val root = folder.newFolder("files")
        val game = File(root, "mudlet/profiles/Game").apply { mkdirs() }
        assertTrue(ScriptFiles.inside(game, root))
        assertTrue(ScriptFiles.inside(root, root))
        assertFalse(ScriptFiles.inside(File(game, "../../../../elsewhere").apply { mkdirs() }, root))
        assertFalse(ScriptFiles.inside(folder.newFolder("files2"), root))
        assertFalse(ScriptFiles.inside(File(game, "missing"), root))
        assertFalse(ScriptFiles.inside(File(game, "a.txt").apply { writeText("") }, root))
    }
}
