package org.mudlet.android.data

import android.content.res.XmlResourceParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mudlet.android.R
import java.io.File

/** The profiles' passwords in the KeyStore-encrypted file: kept across restarts, gone after Forget, never in the clear. */
@RunWith(AndroidJUnit4::class)
class PasswordStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun forgetTheTestsPasswords() {
        val store = PasswordStore(context)
        listOf(GAME, OTHER).forEach {
            store.forget(it)
            store.forget(it, PasswordStore.Kind.PROXY)
        }
    }

    @Test
    fun aStoredPasswordSurvivesARestartAndIsGoneAfterForget() {
        assertTrue(PasswordStore(context).set(GAME, PASSWORD))

        // A new store has only the file and the KeyStore to go on, as after the app's process was restarted
        val restarted = PasswordStore(context)
        assertTrue(restarted.has(GAME))
        assertEquals(PASSWORD, restarted.get(GAME))
        assertEquals("names match ignoring case, as the engine's do", PASSWORD, restarted.get(GAME.uppercase()))

        assertTrue(restarted.forget(GAME))
        val afterForget = PasswordStore(context)
        assertFalse(afterForget.has(GAME))
        assertNull(afterForget.get(GAME))
    }

    @Test
    fun aProfilesProxyPasswordIsKeptApartFromItsGamePassword() {
        val store = PasswordStore(context)
        assertTrue(store.set(GAME, PASSWORD, PasswordStore.Kind.PROXY))
        assertFalse("a proxy's password is not the game's", store.has(GAME))
        assertNull(store.get(GAME))
        assertTrue(store.set(GAME, "game password"))
        assertEquals(PASSWORD, PasswordStore(context).get(GAME.uppercase(), PasswordStore.Kind.PROXY))
        assertEquals("game password", PasswordStore(context).get(GAME))
        val all = PasswordStore(context).all().orEmpty()
        assertTrue("a problem report takes both out", all.containsAll(listOf(PASSWORD, "game password")))

        // Sealed for its own key, so the game's ciphertext moved to the proxy's does not open, nor the other way round
        val file = File(context.filesDir, PasswordStore.FILE)
        val sealed = JSONObject(file.readText())
        sealed.put("proxy:" + GAME.lowercase(), sealed.getString(GAME.lowercase()))
        file.writeText(sealed.toString())
        assertNull(PasswordStore(context).get(GAME, PasswordStore.Kind.PROXY))
        assertEquals("game password", PasswordStore(context).get(GAME))

        assertTrue(store.forget(GAME))
        assertFalse(PasswordStore(context).has(GAME))
    }

    @Test
    fun theFileHoldsNoPasswordInTheClear() {
        assertTrue(PasswordStore(context).set(GAME, PASSWORD))
        val text = File(context.filesDir, PasswordStore.FILE).readText()
        assertFalse(text, text.contains(PASSWORD))
    }

    @Test
    fun oneProfilesCiphertextDoesNotOpenAsAnothers() {
        val store = PasswordStore(context)
        assertTrue(store.set(GAME, PASSWORD))
        assertTrue(store.set(OTHER, "other password"))
        val file = File(context.filesDir, PasswordStore.FILE)
        val all = JSONObject(file.readText())
        all.put(OTHER.lowercase(), all.getString(GAME.lowercase()))
        file.writeText(all.toString())

        assertNull(PasswordStore(context).get(OTHER))
        assertFalse("one that cannot be decrypted is forgotten", PasswordStore(context).has(OTHER))
        assertEquals(PASSWORD, PasswordStore(context).get(GAME))
    }

    @Test
    fun backupsAndDeviceTransfersLeaveThePasswordsOut() {
        val excluded = mutableMapOf<String, MutableList<String>>()
        var section = ""
        val xml = context.resources.getXml(R.xml.data_extraction_rules)
        while (xml.next() != XmlResourceParser.END_DOCUMENT) {
            if (xml.eventType != XmlResourceParser.START_TAG) continue
            when (xml.name) {
                "cloud-backup", "device-transfer" -> section = xml.name

                "exclude" -> if (xml.getAttributeValue(null, "domain") == "file") {
                    excluded.getOrPut(section) { mutableListOf() }.add(xml.getAttributeValue(null, "path"))
                }
            }
        }
        for (s in listOf("cloud-backup", "device-transfer")) {
            assertTrue("$s excludes ${PasswordStore.FILE}: ${excluded[s]}", excluded[s].orEmpty().any { PasswordStore.FILE.startsWith(it) && it != "." })
        }
    }

    private companion object {
        const val GAME = "Password Test Game"
        const val OTHER = "Password Test Other"
        const val PASSWORD = "pässwörd ✓ 123"
    }
}
