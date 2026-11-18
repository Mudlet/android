package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollThumbTest {
    // 60 rows of 20 px in a console 300 px tall: 1200 px of rows, a quarter of them in view
    private fun thumb(firstRow: Int, hidden: Int = 0, atEnd: Boolean = false, rows: Int = 60, minSize: Float = 48f) = ScrollThumb.of(rows, firstRow, hidden, 20f, 300, atEnd, minSize)

    @Test
    fun theThumbIsAsMuchOfTheTrackAsTheRowsInViewAreOfAllRows() {
        assertEquals(75f, thumb(0)!!.size, DELTA)
        assertEquals(225f, thumb(0, rows = 20)!!.size, DELTA)
    }

    @Test
    fun theThumbIsWhereTheRowsInViewAreAmongAllRows() {
        assertEquals(0f, thumb(0)!!.offset, DELTA)
        // Half way: 450 of the 900 px there are to scroll, so half of the 225 px the thumb can travel
        assertEquals(112.5f, thumb(22, hidden = 10)!!.offset, DELTA)
        assertEquals(225f, thumb(45)!!.offset, DELTA)
    }

    @Test
    fun atTheEndTheThumbIsAtTheBottomWhateverTheRowsHeights() {
        // Taller rows than the average came into view, so the first row's number alone would leave it short
        assertEquals(225f, thumb(40, atEnd = true)!!.offset, DELTA)
        // And rows past the end the list knows of put it no lower
        assertEquals(225f, thumb(50)!!.offset, DELTA)
    }

    @Test
    fun aDragMovesTheRowsAsFarAsTheThumbMovesAlongItsTrack() {
        // 900 px of rows for the thumb's 225 px
        assertEquals(4f, thumb(0)!!.rowsPerPixel, DELTA)
    }

    @Test
    fun theThumbIsNeverTooShortToTake() {
        val long = thumb(0, rows = 6000)!!
        assertEquals(48f, long.size, DELTA)
        // The thumb still reaches both ends: 119700 px of rows for its 252 px
        assertEquals(475f, long.rowsPerPixel, DELTA)
        assertEquals(252f, thumb(5985, rows = 6000)!!.offset, DELTA)
    }

    @Test
    fun thereIsNoThumbInAConsoleTooShortForItToMove() {
        // A thumb as long as the track or longer would tell nothing, and no drag could move it
        assertNull(thumb(0, minSize = 400f))
        assertNull(thumb(0, minSize = 300f))
        assertNull(thumb(0, minSize = 299.5f))
        // A pixel to move along is enough: all 900 px of rows for it
        val short = thumb(45, minSize = 299f)!!
        assertEquals(299f, short.size, DELTA)
        assertEquals(1f, short.offset, DELTA)
        assertEquals(900f, short.rowsPerPixel, DELTA)
    }

    @Test
    fun thereIsNoThumbWhenEveryRowIsInView() {
        assertNull(thumb(0, rows = 15))
        assertNull(thumb(0, rows = 10))
        assertNull(thumb(0, rows = 0))
        assertNull(ScrollThumb.of(60, 0, 0, 20f, 0, false, 48f))
    }

    @Test
    fun theThumbIsWhiteOnADarkConsoleAndBlackOnALightOne() {
        // By the console's relative luminance: white and black contrast equally with one of 0.1791
        assertTrue(ScrollThumb.whiteOn(0f))
        assertTrue(ScrollThumb.whiteOn(0.179f))
        assertFalse(ScrollThumb.whiteOn(0.18f))
        assertFalse(ScrollThumb.whiteOn(1f))
    }

    private companion object {
        const val DELTA = 0.01f
    }
}
