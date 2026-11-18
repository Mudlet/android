package org.mudlet.android.data

import android.content.Context
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Which games the app notes, the first time it runs with the setting, as having had their input line emptied after
 * every send: those with a profile then, so that each is told to go on as it was (`ProfileSession`); and that the map
 * behind the text is off for a player who never chose, and as they chose for one who did.
 */
@RunWith(AndroidJUnit4::class)
class SettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val profiles = File(context.cacheDir, "settings-test-profiles")

    @Before
    @After
    fun forget() {
        prefs.edit(commit = true) { clear() }
        profiles.deleteRecursively()
    }

    @After
    fun keepTheAppsOwnSettings() {
        Settings.mapBehindText.load(context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE))
        Settings.mapInCorner.load(context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE))
    }

    @Test
    fun theMapBehindTheTextIsOffUnlessThePlayerChoseIt() {
        // Nothing stored, as for a player who never touched the switch, whichever app they had before
        Settings.mapBehindText.load(prefs)
        assertFalse(Settings.mapBehindText.value)

        prefs.edit(commit = true) { putBoolean(MAP_BEHIND_TEXT, true) }
        Settings.mapBehindText.load(prefs)
        assertTrue(Settings.mapBehindText.value)
        prefs.edit(commit = true) { putBoolean(MAP_BEHIND_TEXT, false) }
        Settings.mapBehindText.load(prefs)
        assertFalse(Settings.mapBehindText.value)
    }

    @Test
    fun theMapInTheCornerShowsUnlessThePlayerHidIt() {
        Settings.mapInCorner.load(prefs)
        assertTrue(Settings.mapInCorner.value)

        prefs.edit(commit = true) { putBoolean(MAP_IN_CORNER, false) }
        Settings.mapInCorner.load(prefs)
        assertFalse(Settings.mapInCorner.value)
        prefs.edit(commit = true) { putBoolean(MAP_IN_CORNER, true) }
        Settings.mapInCorner.load(prefs)
        assertTrue(Settings.mapInCorner.value)
    }

    @Test
    fun theGamesThereBeforeTheInputLineCouldKeepACommandAreNotedOnce() {
        for (game in listOf("Achaea", "My game")) assertTrue(File(profiles, game).mkdirs())
        Settings.noteGamesBeforeInputClear(prefs, profiles)
        assertEquals(setOf("Achaea", "My game"), prefs.getStringSet(KEY, null))

        // A game added since has the setting from its start
        assertTrue(File(profiles, "Later").mkdirs())
        Settings.noteGamesBeforeInputClear(prefs, profiles)
        assertEquals(setOf("Achaea", "My game"), prefs.getStringSet(KEY, null))
    }

    @Test
    fun anAppWithNoGamesYetNotesNoneAndNoneLater() {
        Settings.noteGamesBeforeInputClear(prefs, profiles)
        assertEquals(emptySet<String>(), prefs.getStringSet(KEY, null))

        assertTrue(File(profiles, "Later").mkdirs())
        Settings.noteGamesBeforeInputClear(prefs, profiles)
        assertEquals(emptySet<String>(), prefs.getStringSet(KEY, null))
    }

    private companion object {
        const val PREFS = "settings-test"

        const val APP_PREFS = "settings"

        // The stored names: players' apps have them
        const val KEY = "gamesAwaitingInputClear"
        const val MAP_BEHIND_TEXT = "mapBehindText"
        const val MAP_IN_CORNER = "mapInCorner"
    }
}
