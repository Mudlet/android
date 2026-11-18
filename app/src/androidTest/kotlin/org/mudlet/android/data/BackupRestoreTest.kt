package org.mudlet.android.data

import android.content.Context
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Half of a round trip through Android's backup that `native/tests/android_instrumented.py` drives with `bmgr`:
 * [seed] writes one of each kind of the app's data, the runner backs the app up as a cloud backup or as a transfer to a
 * new phone, clears it and restores it, and [check] says what came back. The runner leaves this class out of the app's
 * other tests, as it runs on nothing [seed] wrote.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = context.filesDir
    private val profile = File(files, "mudlet/profiles/$PROFILE")

    @Test
    fun seed() {
        write(File(profile, "url"), "probe.example")
        write(File(profile, "log/probe.txt"), "a line of the game")
        write(File(files, "suggest/$PROFILE.txt"), "typed")
        write(File(files, "suggest/$PROFILE.pins"), "pinned")
        write(File(files, "settings/Mudlet-$PROFILE-login.conf"), "a sign-in token")
        write(File(files, "engine-stopped"), "")
        assertTrue(PasswordStore(context).set(PROFILE, "secret"))
        prefs("settings").edit(commit = true) { putString(KEY, "kept") }
        prefs("engine").edit(commit = true) { putString(KEY, "this device's") }
        prefs("ui").edit(commit = true) { putString(KEY, "this device's") }
    }

    @Test
    fun check() {
        val toNewPhone = InstrumentationRegistry.getArguments().getString("backupTo") == "phone"
        assertEquals("the profile came back", "probe.example", File(profile, "url").readText())
        assertEquals("the pinned commands came back", "pinned", File(files, "suggest/$PROFILE.pins").readText())
        assertEquals("the app's settings came back", "kept", prefs("settings").getString(KEY, null))
        assertEquals("the log came back only to a new phone", toNewPhone, File(profile, "log/probe.txt").exists())
        assertEquals("the suggestions came back only to a new phone", toNewPhone, File(files, "suggest/$PROFILE.txt").exists())

        assertFalse("the passwords' file came back", File(files, PasswordStore.FILE).exists())
        val passwords = PasswordStore(context)
        assertFalse("the profile shows a saved password", passwords.has(PROFILE))
        assertNull("Connect would send a password", passwords.get(PROFILE))
        assertFalse("a sign-in token came back", File(files, "settings").exists())
        assertFalse("the engine's stop marker came back", File(files, "engine-stopped").exists())
        assertNull("the engine's preferences came back", prefs("engine").getString(KEY, null))
        assertNull("the UI's preferences came back", prefs("ui").getString(KEY, null))
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private companion object {
        const val PROFILE = "Backup Probe"
        const val KEY = "backupProbe"
    }
}
