package org.mudlet.android.ui.game

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The device's font a family a script names is drawn in ([fontFamily]): consoles, labels, text boxes and buttons. */
class FontFamilyTest {
    @Test
    fun mudletsConsoleFontsAndOtherMonospaceNamesAreMonospaceThoughTheySaySans() {
        for (name in listOf("Bitstream Vera Sans Mono", "Ubuntu Mono", "Roboto Mono", "Droid Sans Mono", "Cutive Mono", "Courier New")) {
            assertEquals(name, FontFamily.Monospace, fontFamily(name))
        }
    }

    @Test
    fun aSerifNameIsSerifRobotosAndASlabSerifToo() {
        for (name in listOf("Noto Serif", "Roboto Serif", "Roboto Slab", "Times New Roman")) assertEquals(name, FontFamily.Serif, fontFamily(name))
    }

    @Test
    fun robotoTheDevicesOwnUbuntuMudletsOwnAndASansNameAreSansSerif() {
        for (name in listOf("Roboto", "Ubuntu", "Noto Sans", "Bitstream Vera Sans")) assertEquals(name, FontFamily.SansSerif, fontFamily(name))
    }

    @Test
    fun scriptFontsAreCursive() {
        // The device's two, and one that only the word script points to
        for (name in listOf("Dancing Script", "Coming Soon", "Segoe Script")) assertEquals(name, FontFamily.Cursive, fontFamily(name))
    }

    @Test
    fun aNameWithNoneOfTheWordsIsNoneOfThem() {
        for (name in listOf("Carrois Gothic SC", "Frobnicate Pro", "")) assertNull(name, fontFamily(name))
    }
}
