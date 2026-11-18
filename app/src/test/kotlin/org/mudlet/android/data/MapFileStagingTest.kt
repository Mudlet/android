package org.mudlet.android.data

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class MapFileStagingTest {
    private val root = Files.createTempDirectory("map-files").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun theExtensionChoosesTheFormatAsDesktopsDoesAndElseHowTheFileBegins() {
        val binary = byteArrayOf(0, 0, 0, 20, 1, 2)
        assertEquals(MapFileStaging.Format.JSON, MapFileStaging.format("Achaea.JSON", binary))
        assertEquals(MapFileStaging.Format.XML, MapFileStaging.format("map.xml", binary))
        assertEquals(MapFileStaging.Format.DAT, MapFileStaging.format("map.dat", "{".toByteArray()))
        assertEquals(MapFileStaging.Format.JSON, MapFileStaging.format(null, "﻿\n  {\"areas\": []}".toByteArray()))
        assertEquals(MapFileStaging.Format.XML, MapFileStaging.format("download", "<?xml version=\"1.0\"?>".toByteArray()))
        assertEquals(MapFileStaging.Format.DAT, MapFileStaging.format("map.dat.txt", binary))
        assertEquals(MapFileStaging.Format.DAT, MapFileStaging.format("", ByteArray(0)))
    }

    @Test
    fun aPickedFileIsStagedUnderTheAppsOwnNameWhateverItsNameSays() {
        val folder = MapFileStaging.folder(root)
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val staged = MapFileStaging.stageIn(ByteArrayInputStream(bytes), "../../shared_prefs/evil.json", folder)
        assertEquals(File(folder, "map.json"), staged)
        assertArrayEquals(bytes, staged.readBytes())
        assertEquals(listOf("map.json"), folder.list()!!.toList())
        // The next one replaces it, so the folder never holds more than the map file under way
        MapFileStaging.stageIn(ByteArrayInputStream("<map/>".toByteArray()), null, folder)
        assertEquals(listOf("map.xml"), folder.list()!!.toList())
    }

    @Test
    fun aFileLargerThanTheCapIsRefusedAndLeavesNothing() {
        val out = ByteArrayOutputStream()
        assertThrows(MapFileStaging.TooLarge::class.java) { MapFileStaging.copyCapped(ByteArrayInputStream(ByteArray(11)), out, max = 10) }
        MapFileStaging.copyCapped(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), max = 10)

        val folder = MapFileStaging.folder(root)
        val endless = object : java.io.InputStream() {
            override fun read() = 1

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill(1, off, off + len)
                return len
            }
        }
        assertThrows(MapFileStaging.TooLarge::class.java) { MapFileStaging.stageIn(endless, "huge.dat", folder, max = 1000) }
        assertFalse(File(folder, "map.dat").exists())
    }
}
