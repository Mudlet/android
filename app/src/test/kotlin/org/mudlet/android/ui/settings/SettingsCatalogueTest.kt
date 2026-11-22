package org.mudlet.android.ui.settings

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.data.LogNameFormat
import org.mudlet.android.data.Settings

/** Which rows a settings page shows, in what order, by the values of the others ("New games", the app's values) */
class SettingsCatalogueTest {
    @After
    fun putBack() {
        Settings.undoWrap.reset()
        Settings.undoWrapWidth.reset()
        Settings.logNameFormat.reset()
    }

    private fun shown(category: SettingsCategory) = SettingsPage(category, null).entries.map { it.key }

    @Test
    fun undoingTheGamesWrappingIsLastOnTheDisplayPage_markedExperimental_withTheWidthStraightAfter() {
        Settings.undoWrap.set(true)
        assertEquals(listOf(Settings.undoWrap.key, Settings.undoWrapWidth.key), shown(SettingsCategory.DISPLAY).takeLast(2))
        val switch = settingsCatalogue.single { it.key == Settings.undoWrap.key }
        assertTrue("it says it is experimental", (switch as SettingEntry.Switch).experimental)
    }

    @Test
    fun theWidthShowsOnlyWhileTheGamesWrappingIsUndone_andIsKeptWhileHidden() {
        Settings.undoWrapWidth.set(100)
        assertFalse("no width while off", Settings.undoWrapWidth.key in shown(SettingsCategory.DISPLAY))
        Settings.undoWrap.set(true)
        assertTrue("the width while on", Settings.undoWrapWidth.key in shown(SettingsCategory.DISPLAY))
        Settings.undoWrap.set(false)
        Settings.undoWrap.set(true)
        assertEquals(100, AppSettings.value(Settings.undoWrapWidth))
    }

    @Test
    fun theScriptEditorsDesignIsAnExperimentalChoiceOfFour_eachWithALineThatSaysWhatItIs_theCurrentOneFirst() {
        val design = settingsCatalogue.single { it.key == Settings.scriptEditorDesign.key } as SettingEntry.Choice<*>
        assertEquals(SettingsCategory.EXPERIMENTAL, design.category)
        assertEquals(4, design.choiceLabels().distinct().size)
        assertEquals(4, design.choiceHints()?.distinct()?.size)
        assertEquals(Settings.ScriptEditorDesign.CURRENT, Settings.scriptEditorDesign.default)
        assertEquals(listOf(design.key), shown(SettingsCategory.EXPERIMENTAL))
    }

    @Test
    fun theLogsNameShowsOnlyForANamedLog() {
        assertFalse(Settings.logFileName.key in shown(SettingsCategory.LOGGING))
        Settings.logNameFormat.set(LogNameFormat.NAMED)
        assertTrue(Settings.logFileName.key in shown(SettingsCategory.LOGGING))
    }
}
