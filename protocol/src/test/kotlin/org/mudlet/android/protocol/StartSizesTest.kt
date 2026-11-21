package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sizes the app gives the engine before it opens a profile, and what it keeps of them between runs. */
class StartSizesTest {
    private val portrait = "411x891 14 AUTO"
    private val landscape = "891x411 14 AUTO"

    private fun requests(sizes: StartSizes?, window: String) = sizes?.requestsFor(window).orEmpty().map { it.toString() }

    @Test
    fun theSizesGivenInAWindowGoOutBeforeAProfileOpensFromIt() {
        val sizes = StartSizes.withMainWindow(StartSizes.withScreen(null, portrait, 31, 52), portrait, MudletSize(1693, 795))
        assertEquals(
            listOf(Requests.setScreenSize(31, 52).toString(), Requests.setMainWindowSize(1693, 795).toString()),
            requests(sizes, portrait),
        )
    }

    @Test
    fun noneGoOutForAnotherWindowOrOtherSettings() {
        val sizes = StartSizes.withMainWindow(StartSizes.withScreen(null, portrait, 31, 52), portrait, MudletSize(1693, 795))
        assertTrue(requests(sizes, landscape).isEmpty())
        assertTrue(requests(sizes, "411x891 18 AUTO").isEmpty())
        assertTrue(requests(null, portrait).isEmpty())
    }

    @Test
    fun aSizeGivenInAnotherWindowReplacesThoseOfTheLast() {
        val portraitSizes = StartSizes.withMainWindow(StartSizes.withScreen(null, portrait, 31, 52), portrait, MudletSize(1693, 795))
        val turned = StartSizes.withScreen(portraitSizes, landscape, 88, 20)
        assertEquals(StartSizes(landscape, screen = 88 to 20), turned)
        assertEquals(listOf(Requests.setScreenSize(88, 20).toString()), requests(turned, landscape))
    }

    @Test
    fun whatIsKeptReadsBackAsItWasWritten() {
        val sizes = StartSizes.withMainWindow(StartSizes.withScreen(null, portrait, 31, 52), portrait, MudletSize(1693, 795))
        assertEquals(sizes, StartSizes.decode(sizes.encode()))
        assertEquals(StartSizes(portrait), StartSizes.decode(StartSizes(portrait).encode()))
    }

    @Test
    fun nothingKeptOrWhatNoVersionWroteIsNone() {
        assertNull(StartSizes.decode(null))
        assertNull(StartSizes.decode("not json"))
        assertNull(StartSizes.decode("{}"))
        assertEquals(StartSizes(portrait), StartSizes.decode("""{"window":"$portrait","mainW":0,"mainH":5,"columns":70000,"rows":20}"""))
    }
}
