package org.mudlet.android.engine

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The engine's ongoing notification as EngineNotifications builds it for the open games and their connections, in the
 * test's own process: EngineServiceTest opens only one game.
 */
@RunWith(AndroidJUnit4::class)
class EngineNotificationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() = show()

    @Test
    fun oneGame_saysWhetherItIsConnected() {
        show("Achaea" to "connected")
        assertEquals(context.getString(R.string.engine_notification_title_profile, "Achaea"), title())
        assertEquals(context.getString(R.string.engine_notification_text), text())

        show("Achaea" to "connecting achaea.com:23")
        assertEquals(context.getString(R.string.engine_notification_title_connecting, "Achaea"), title())
        assertEquals(context.getString(R.string.engine_notification_text_not_connected), text())

        for (connection in listOf("disconnected", "error: Connection refused")) {
            show("Achaea" to connection)
            assertEquals(connection, context.getString(R.string.engine_notification_title_not_connected, "Achaea"), title())
            assertEquals(connection, context.getString(R.string.engine_notification_text_not_connected), text())
        }
    }

    @Test
    fun severalGames_countTheConnectedOnes_andListEach() {
        show("Achaea" to "connected", "Aardwolf" to "disconnected", "Lusternia" to "connected")
        assertEquals(context.resources.getQuantityString(R.plurals.engine_notification_title_games, 2, 2), title())
        assertEquals(context.getString(R.string.engine_notification_text), text())
        assertEquals(
            listOf(
                line("Achaea", R.string.engine_notification_connected),
                line("Aardwolf", R.string.engine_notification_not_connected),
                line("Lusternia", R.string.engine_notification_connected),
            ),
            lines(),
        )

        show("Achaea" to "disconnected", "Aardwolf" to "connected")
        assertEquals(context.getString(R.string.engine_notification_title_profile, "Aardwolf"), title())

        show("Achaea" to "disconnected", "Aardwolf" to "connecting")
        assertEquals(context.resources.getQuantityString(R.plurals.engine_notification_title_none_connected, 2, 2), title())
        assertEquals(context.getString(R.string.engine_notification_text_not_connected), text())
        assertEquals(
            listOf(line("Achaea", R.string.engine_notification_not_connected), line("Aardwolf", R.string.engine_notification_connecting)),
            lines(),
        )
    }

    @Test
    fun theAppsLanguage_showsInTheNotification_evenOneChosenAfterTheEngineStarted() {
        show("Achaea" to "connected")
        // Made before the choice, as the running EngineService was: before Android 13 nothing tells it of the change
        val started = context.createConfigurationContext(Configuration(context.resources.configuration))
        try {
            assertTrue(AppLanguage.choose(context, "kl"))
            if (Build.VERSION.SDK_INT >= 33) awaitLanguage { it == "kl" }
            val built = EngineNotifications.engineNotification(if (Build.VERSION.SDK_INT >= 33) context else started).extras
            assertEquals("KL Keeping Achaea connected", built.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        } finally {
            AppLanguage.choose(context, null)
            awaitLanguage { it != "kl" }
        }
        assertEquals(context.getString(R.string.engine_notification_title_profile, "Achaea"), title())
        assertTrue(title(), title()?.startsWith("KL") == false)
    }

    // The system hands the process a change of the app's language asynchronously on Android 13 and later
    private fun awaitLanguage(done: (String) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!done(context.resources.configuration.locales[0].language)) {
            check(SystemClock.uptimeMillis() < deadline) { "the language did not change: ${context.resources.configuration.locales}" }
            Thread.sleep(50)
        }
    }

    // Not EngineService, so only the notification's games change
    private fun show(vararg games: Pair<String, String>) {
        val (names, connections) = games.toList().unzip()
        EngineNotifications.showProfiles(context, names.toTypedArray(), connections.toTypedArray())
    }

    private fun extras() = EngineNotifications.engineNotification(context).extras

    private fun title() = extras().getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun text() = extras().getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun lines() = extras().getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }

    private fun line(name: String, connection: Int) = context.getString(R.string.engine_notification_game, name, context.getString(connection))
}
