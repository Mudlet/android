package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The "new game text" notices: none in front, one per game that asked for attention, following its count. */
class GameNoticesTest {
    private val notices = GameNotices().apply { appState(away = true) }

    private fun text(game: String = GAME, unread: Long, attention: Boolean = false, withText: Boolean = false, latest: String = LINE) = notices.text(game, unread, latest, attention, withText)

    @Test
    fun nothingWhileTheAppIsInFront() {
        val inFront = GameNotices()
        assertNull(inFront.text(GAME, 3, LINE, attention = true, withText = true))
        notices.appState(away = false)
        assertNull(text(unread = 3, attention = true))
    }

    @Test
    fun textAloneIsNoNoticeUntilTheGameAsksForAttention() {
        assertNull(text(unread = 1))
        assertNull(text(unread = 2))
        assertEquals(GameNotices.Show(GAME, 2, null), text(unread = 2, attention = true))
    }

    @Test
    fun oneNoticePerGameFollowsItsCount() {
        assertEquals(GameNotices.Show(GAME, 1, null), text(unread = 1, attention = true))
        assertNull(text(unread = 1))
        assertNull(text(unread = 1, attention = true))
        assertEquals(GameNotices.Show(GAME, 5, null), text(unread = 5))
        assertNull(text(OTHER, unread = 9))
        assertEquals(GameNotices.Show(OTHER, 9, null), text(OTHER, unread = 9, attention = true))
    }

    @Test
    fun theLatestLineOnlyWhenThePlayerAllowsIt() {
        assertEquals(GameNotices.Show(GAME, 1, LINE), text(unread = 1, attention = true, withText = true))
        assertEquals(GameNotices.Show(GAME, 2, null), text(unread = 2, withText = true, latest = "  "))
        assertEquals(GameNotices.Show(GAME, 3, null), text(unread = 3, withText = false))
    }

    @Test
    fun comingBackTakesThemAllAway() {
        text(unread = 1, attention = true)
        text(OTHER, unread = 1, attention = true)
        text(QUIET, unread = 4)
        assertEquals(setOf(GameNotices.Cancel(GAME), GameNotices.Cancel(OTHER)), notices.appState(away = false).toSet())
        assertEquals(emptyList<GameNotices.Cancel>(), notices.appState(away = false))
    }

    @Test
    fun eachLeavingStartsAfresh() {
        text(unread = 1, attention = true)
        assertEquals(emptyList<GameNotices.Cancel>(), notices.appState(away = true))
        assertNull(text(unread = 2))
        assertEquals(GameNotices.Show(GAME, 2, null), text(unread = 2, attention = true))
    }

    @Test
    fun aClosedGameTakesItsNoticeAway() {
        text(unread = 1, attention = true)
        text(OTHER, unread = 1, attention = true)
        assertEquals(GameNotices.Cancel(GAME), notices.closed(GAME))
        assertNull(notices.closed(GAME))
        assertNull(notices.closed(QUIET))
        assertEquals(listOf(GameNotices.Cancel(OTHER)), notices.appState(away = false))
    }

    private companion object {
        const val GAME = "Achaea"
        const val OTHER = "Aardwolf"
        const val QUIET = "Lusternia"
        const val LINE = "A dragon lands beside you."
    }
}
