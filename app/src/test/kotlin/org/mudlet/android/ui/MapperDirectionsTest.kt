package org.mudlet.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.LuaVariable
import org.mudlet.android.protocol.VariableKey
import org.mudlet.android.protocol.VariableType

/** The movement pad's words, read from the generic mapper's `map.configs` at each tap as its speedwalk would send them. */
class MapperDirectionsTest {
    private val sent = ArrayList<JSONObject>()
    private val timers = ArrayList<() -> Unit>()
    private val words = ArrayList<String?>()
    private var attached = true
    private var lastId = 0L
    private val logged = ArrayList<String>()
    private val directions = MapperDirections(
        newId = { ++lastId },
        send = { message -> attached.also { if (it) sent.add(message) } },
        later = { _, block -> timers.add(block) },
        log = { logged.add(it) },
    )

    private fun string(name: String, value: String, cut: Boolean = false) = LuaVariable(VariableKey(name, VariableType.STRING), VariableType.STRING, value, cut)

    private fun translating(on: Boolean) = listOf(
        LuaVariable(VariableKey("lang_dirs", VariableType.STRING), VariableType.TABLE, members = 44),
        LuaVariable(VariableKey("use_translation", VariableType.STRING), VariableType.BOOLEAN, on.toString()),
    )

    private val swedish = listOf(string("e", "ö"), string("n", "n"), string("d", "ner"), string("out", "ut"), string("east", "öster"))

    /** The two requests one tap sent, taken off: the ids of `map.configs` and of its `lang_dirs`. */
    private fun asked(): Pair<Long, Long> {
        assertEquals(2, sent.size)
        val (configs, dirs) = sent.map { request ->
            assertEquals("variables", request.getString("t"))
            assertTrue("the hidden ones too", request.getBoolean("showHidden"))
            assertEquals(0, request.getInt("from"))
            val path = request.getJSONArray("path")
            (0 until path.length()).map { path.getJSONObject(it).getString("name") to path.getJSONObject(it).getString("key") } to request.getLong("id")
        }
        sent.clear()
        assertEquals(listOf("map" to "string", "configs" to "string"), configs.first)
        assertEquals(listOf("map" to "string", "configs" to "string", "lang_dirs" to "string"), dirs.first)
        return configs.second to dirs.second
    }

    private fun answer(id: Long, entries: List<LuaVariable>) = directions.onVariables(EngineMessage.Variables(emptyList(), true, 0, entries.size, 0, entries, id))

    private fun tap(way: String) = directions.word(way) { words.add(it) }

    @Test
    fun aTapSendsTheMappersWordForItsWayWhileItTranslates() {
        tap("e")
        val (configs, dirs) = asked()
        assertTrue(answer(dirs, swedish))
        assertEquals("nothing till both are answered", emptyList<String?>(), words)
        assertTrue(answer(configs, translating(true)))
        assertEquals(listOf<String?>("ö"), words)
    }

    @Test
    fun withTranslationOffTheWayItselfIsSentAsTheMappersSpeedwalkSendsIt() {
        tap("e")
        val (configs, dirs) = asked()
        answer(configs, translating(false))
        answer(dirs, swedish)
        assertEquals(listOf<String?>("e"), words)
    }

    @Test
    fun withoutTheGenericMapperTheWayItselfIsSent() {
        tap("out")
        val (configs, dirs) = asked()
        assertTrue(directions.onRefused(configs))
        assertTrue(directions.onRefused(dirs))
        assertEquals(listOf<String?>("out"), words)
    }

    @Test
    fun aLangDirsTheEngineDoesNotListUnderConfigsSendsTheWayItself() {
        // The table map.defaults holds too, listed under that name alone
        tap("d")
        val (configs, dirs) = asked()
        answer(configs, translating(true))
        directions.onRefused(dirs)
        assertEquals(listOf<String?>("d"), words)
    }

    @Test
    fun aWordThatIsNoWholeStringWithTextInItSendsTheWayItself() {
        val configs = translating(true)
        val number = LuaVariable(VariableKey("n", VariableType.STRING), VariableType.NUMBER, "1")
        assertEquals("n", MapperDirections.wordFor("n", configs, listOf(number)))
        assertEquals("n", MapperDirections.wordFor("n", configs, listOf(string("n", "  "))))
        assertEquals("n", MapperDirections.wordFor("n", configs, listOf(string("n", "nor", cut = true))))
        assertEquals("a number key is another key", "n", MapperDirections.wordFor("n", configs, listOf(LuaVariable(VariableKey("n", VariableType.NUMBER), VariableType.STRING, "x"))))
        assertEquals("a way it has no word for", "in", MapperDirections.wordFor("in", configs, swedish))
        assertEquals("ut", MapperDirections.wordFor("out", configs, swedish))
        assertEquals("no use_translation", "e", MapperDirections.wordFor("e", translating(true).take(1), swedish))
        assertEquals("a use_translation that is no boolean", "e", MapperDirections.wordFor("e", listOf(string("use_translation", "true")), swedish))
    }

    @Test
    fun tapsWhileAReadIsOutWaitForItAndGoInTheirOrder() {
        tap("e")
        val (configs, dirs) = asked()
        tap("out")
        tap("n")
        assertEquals("one read for them all", 0, sent.size)
        answer(configs, translating(true))
        answer(dirs, swedish)
        assertEquals(listOf<String?>("ö", "ut", "n"), words)
        tap("d")
        val next = asked()
        assertTrue("each tap after reads afresh", next.first > dirs)
    }

    @Test
    fun anAnswerToNoneOfItsRequestsIsTheVariablesScreens() {
        assertFalse("none out", answer(99, swedish))
        assertFalse(directions.onRefused(99L))
        tap("e")
        asked()
        assertFalse(answer(99, swedish))
        assertFalse(directions.onRefused(99L))
        assertFalse(directions.onRefused(null))
        assertEquals(emptyList<String?>(), words)
    }

    @Test
    fun aReadNotAnsweredInTimeSendsWhatCameAndALateAnswerIsNotTaken() {
        tap("e")
        val (configs, dirs) = asked()
        answer(configs, translating(true))
        assertEquals(1, timers.size)
        timers.removeAt(0)()
        assertEquals(listOf<String?>("e"), words)
        assertEquals("a read not answered is logged", 1, logged.size)
        assertFalse(answer(dirs, swedish))
        assertEquals(listOf<String?>("e"), words)
    }

    @Test
    fun theTimerOfAReadAnsweredDoesNothing() {
        tap("e")
        val (configs, dirs) = asked()
        answer(configs, translating(true))
        answer(dirs, swedish)
        tap("n")
        asked()
        timers.removeAt(0)()
        assertEquals("the second read still waits", listOf<String?>("ö"), words)
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun theLinkGoingFailsTheTapsWaitingAndOneMadeWithoutItIsNotSent() {
        tap("e")
        tap("n")
        val (configs, _) = asked()
        directions.onDetached()
        assertEquals(listOf<String?>(null, null), words)
        assertFalse(answer(configs, translating(true)))
        attached = false
        tap("s")
        assertEquals(listOf<String?>(null, null, null), words)
        attached = true
        tap("s")
        assertEquals("a new read once back", 2, sent.size)
    }
}
