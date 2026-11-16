package org.mudlet.android.engine

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mudlet.android.protocol.Requests
import java.io.File

/**
 * EngineService on a device, with the engine the APK was built with (libmudlet in CI's emulator job): it runs in its
 * own :engine process, Qt comes up there and answers the socket handshake, it is in the foreground only while a game is
 * open, its notification says whether that game is connected, it files a game's tells under it, a stop is not undone at the next start while a killed engine brings its
 * profile and connection back (docs/architecture.md, "Restoring the session"), and the TLS backend's libraries load.
 * native/tests/android_instrumented.py runs it.
 */
@RunWith(AndroidJUnit4::class)
class EngineServiceTest {
    // The test APK instruments itself: its own package runs the service
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engineProcess = "${context.packageName}:engine"
    private val probes = ArrayList<EngineProbe>()
    private lateinit var game: FakeGame

    @Before
    fun setUp() {
        stopEngine()
        // The tells an earlier test had the engine post
        context.getSystemService(NotificationManager::class.java).cancelAll()
        // Each test starts from a fresh install's files: no profile, no session to restore, no stop marker
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        game = FakeGame()
    }

    @After
    fun tearDown() {
        probes.forEach { it.close() }
        stopEngine()
        game.close()
    }

    @Test
    fun runsInItsOwnProcess_andQtAnswersTheHandshake() {
        startEngine()
        val probe = attach()
        val hello = probe.attached()
        assertNotEquals("the engine runs in the test's process", Process.myPid(), hello.pid)
        assertEquals("the engine is the $engineProcess process", enginePid(), hello.pid)
        assertEquals("libmudlet", probe.status("engineKind") { true })
        // EngineService.onCreate called nativeServiceReady() before main() went on to create the QGuiApplication
        assertEquals("ok", probe.status("serviceReadyWait") { true })
        assertEquals("android", probe.status("platform") { true })
        probe.status("qtStartMsAfterServiceOnCreate") { it.toLong() >= 0 }

        val service = await("EngineService running") { engineService() }
        assertEquals(engineProcess, service.process)
        assertEquals(hello.pid, service.pid)
    }

    @Test
    fun itIsInTheForegroundOnlyWhileAGameIsOpen_withANotificationNamingIt() {
        startEngine()
        val probe = attach()
        probe.attached()
        await("EngineService out of the foreground with no game open, and its notification gone", probe = probe) {
            engineService()?.takeIf { !it.foreground && engineNotification() == null }
        }

        connectToGame(probe)
        awaitEngineTitle(context.getString(R.string.engine_notification_title_profile, DEFAULT_PROFILE), probe)

        probe.send(Requests.closeProfile(DEFAULT_PROFILE, 2))
        await("EngineService out of the foreground again once the game closes", probe = probe) {
            engineService()?.takeIf { !it.foreground && engineNotification() == null }
        }
    }

    @Test
    fun theNotificationSaysSoOnceTheGameIsNotConnected_andStaysWhileItIsOpen() {
        startEngine()
        val probe = attach()
        connectToGame(probe)
        val connected = context.getString(R.string.engine_notification_title_profile, DEFAULT_PROFILE)
        val notConnected = context.getString(R.string.engine_notification_title_not_connected, DEFAULT_PROFILE)
        awaitEngineTitle(connected, probe)

        // The game closes the connection
        game.dropConnections()
        probe.status("connection") { it == "disconnected" }
        awaitEngineTitle(notConnected, probe)

        probe.send(Requests.connect(FakeGame.HOST, game.port, false))
        probe.status("connection") { it == "connected" }
        awaitEngineTitle(connected, probe)

        // Disconnect in the UI
        probe.send(Requests.disconnect())
        probe.status("connection") { it == "disconnected" }
        awaitEngineTitle(notConnected, probe)

        probe.send(Requests.connect(FakeGame.HOST, game.port, false))
        awaitEngineTitle(connected, probe)

        // A script's disconnect()
        probe.send(Requests.runLua("disconnect()", 1))
        probe.status("connection") { it == "disconnected" }
        val shown = awaitEngineTitle(notConnected, probe)
        assertEquals(
            context.getString(R.string.engine_notification_text_not_connected),
            shown.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test
    fun aTellIsFiledUnderItsGame() {
        startEngine()
        val probe = attach()
        connectToGame(probe)
        probe.send(Requests.runLua("showNotification(\"$TELL_TITLE\", \"ping\")", 1))
        val tell = await("the tell's notification", probe = probe) { activeNotifications().firstOrNull { it.title() == TELL_TITLE } }
        assertEquals(DEFAULT_PROFILE, tell.notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
        assertEquals(DEFAULT_PROFILE, tell.notification.group)
        assertTrue(
            "the game's tells have a summary to be bundled under",
            activeNotifications().any { it.notification.group == DEFAULT_PROFILE && it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 },
        )
    }

    @Test
    fun aStopFromTheNotificationIsNotUndoneByTheNextStart() {
        startEngine()
        // The engine takes one UI at a time, the newest: this one goes before the next one attaches
        attach().use { connectToGame(it) }
        assertEquals(1, game.accepted)

        // The engine notification's Quit action, as the user taps it
        val notification = await("the engine's notification") { engineNotification() }
        notification.notification.actions.single { it.title.toString() == context.getString(R.string.engine_notification_quit) }.actionIntent.send()
        awaitEngineGone()
        assertTrue("onDestroy wrote the stop marker", File(EngineSession.stopMarkerPath(context)).exists())

        startEngine()
        val probe = attach()
        probe.attached()
        assertEquals("Android (not started)", probe.status("profile") { true })
        Thread.sleep(QUIET_MS)
        assertEquals("nothing restored", emptyList<String>(), probe.restores())
        assertEquals("no reconnect to the game", 1, game.accepted)
    }

    @Test
    fun aKilledEngineComesBackWithItsProfileAndConnection() {
        startEngine()
        val first = attach()
        val firstInstance = first.attached().instance
        connectToGame(first)
        // The engine takes one UI at a time, the newest: this one goes before the next one attaches
        first.close()

        // As the low-memory killer: no onDestroy, so no stop marker
        Process.killProcess(enginePid()!!)
        awaitEngineGone()

        startEngine()
        val probe = attach()
        assertNotEquals("a new engine", firstInstance, probe.attached().instance)
        probe.status("restore", RESTORE_MS) { it.startsWith("reconnected ") }
        assertEquals("connected", probe.status("connection") { true })
        // The engine can see the connection up before the game's accept() has returned
        await("the game to accept the engine's second connection") { if (game.accepted >= 2) Unit else null }
        assertEquals("one reconnect", 2, game.accepted)
    }

    @Test
    fun aTlsConnectGoesThroughOpenSsl() {
        startEngine()
        val probe = attach()
        probe.attached()
        // The game does not speak TLS, so the handshake fails; that it began shows Qt's TLS backend loaded OpenSSL. A
        // secure connection needs a name, as a certificate names its server, not an address
        probe.send(Requests.connect("localhost", game.port, true))
        val profile = probe.status("profile", EngineProbe.ENGINE_START_MS) { it == "Android loaded" || it.startsWith("start failed") }
        assertEquals("Android loaded", profile)
        val hello = await("the engine's first bytes to the game", probe = probe) {
            game.firstBytes.firstOrNull()?.takeIf { it.isNotEmpty() }
        }
        // A TLS record of type handshake (22), version 3.x: a ClientHello
        assertEquals("a TLS handshake record: ${hello.take(8)}", 0x16, hello[0].toInt())
        assertEquals(3, hello[1].toInt())
        probe.text { it.contains("Secure handshake failed") }
    }

    private fun attach() = EngineProbe(context).also { probes.add(it) }

    private fun connectToGame(probe: EngineProbe) {
        probe.attached()
        probe.send(Requests.connect(FakeGame.HOST, game.port, false))
        probe.status("profile", EngineProbe.ENGINE_START_MS) { it == "$DEFAULT_PROFILE loaded" || it.startsWith("start failed") }
            .let { assertEquals("$DEFAULT_PROFILE loaded", it) }
        probe.status("connection") { it == "connected" }
        probe.text { it == FakeGame.BANNER_TEXT }
    }

    private fun startEngine() {
        context.startForegroundService(Intent(context, EngineService::class.java))
    }

    private fun stopEngine() {
        if (enginePid() == null) return
        context.stopService(Intent(context, EngineService::class.java))
        awaitEngineGone()
    }

    private fun activeNotifications(): List<StatusBarNotification> = context.getSystemService(NotificationManager::class.java).activeNotifications.toList()

    private fun engineNotification() = activeNotifications().firstOrNull { it.id == EngineNotifications.ENGINE_NOTIFICATION_ID }

    // A game not connected stays open with its scripts, which the foreground keeps running
    private fun awaitEngineTitle(title: String, probe: EngineProbe): StatusBarNotification = try {
        await("the engine's notification titled \"$title\", in the foreground", probe = probe) {
            engineNotification()?.takeIf { it.title() == title && engineService()?.foreground == true }
        }
    } catch (e: AssertionError) {
        throw AssertionError("${e.message}; shown: \"${engineNotification()?.title()}\", foreground ${engineService()?.foreground}", e)
    }

    private fun StatusBarNotification.title() = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun awaitEngineGone() = await("the $engineProcess process to end") { if (enginePid() == null) Unit else null }

    private fun enginePid(): Int? = context.getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName == engineProcess }?.pid

    @Suppress("DEPRECATION") // still answers for the caller's own services
    private fun engineService(): ActivityManager.RunningServiceInfo? = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(50).firstOrNull { it.service.className == EngineService::class.java.name }

    /** Polls [get] until it answers; a timeout names [what], and what [probe]'s engine said if there is one. */
    private fun <T : Any> await(what: String, timeoutMs: Long = EngineProbe.TIMEOUT_MS, probe: EngineProbe? = null, get: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            get()?.let { return it }
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out after $timeoutMs ms waiting for $what" + (probe?.let { "; ${it.describe()}" } ?: ""))
            }
            Thread.sleep(100)
        }
    }

    private companion object {
        // How long a test waits to be sure something does not happen
        const val QUIET_MS = 3_000L

        // The profile libmudlet opens for a connect with none open (MUDLET_PROFILE)
        const val DEFAULT_PROFILE = "Android"
        const val TELL_TITLE = "A tell for the test"

        // A new engine, then libmudlet's profile, then the connection
        const val RESTORE_MS = 180_000L
    }
}
