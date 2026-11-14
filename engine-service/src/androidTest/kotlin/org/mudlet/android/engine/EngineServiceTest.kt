package org.mudlet.android.engine

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Process
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
 * own :engine process in the foreground, Qt comes up there and answers the socket handshake, a stop is not undone at
 * the next start while a killed engine brings its profile and connection back (docs/architecture.md, "Restoring the
 * session"), and the TLS backend's libraries load. native/tests/android_instrumented.py runs it.
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
    fun runsInItsOwnProcessInTheForeground_andQtAnswersTheHandshake() {
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

        val service = await("EngineService in the foreground") { engineService()?.takeIf { it.foreground } }
        assertEquals(engineProcess, service.process)
        assertEquals(hello.pid, service.pid)
    }

    @Test
    fun aStopFromTheNotificationIsNotUndoneByTheNextStart() {
        startEngine()
        connectToGame(attach())
        assertEquals(1, game.accepted)

        // The engine notification's "Stop engine" action, as the user taps it
        val notification = await("the engine's notification") {
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .firstOrNull { it.id == EngineNotifications.ENGINE_NOTIFICATION_ID }
        }
        notification.notification.actions.single { it.title.toString() == "Stop engine" }.actionIntent.send()
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
        first.close()

        // As the low-memory killer: no onDestroy, so no stop marker
        Process.killProcess(enginePid()!!)
        awaitEngineGone()

        startEngine()
        val probe = attach()
        assertNotEquals("a new engine", firstInstance, probe.attached().instance)
        probe.status("restore", RESTORE_MS) { it.startsWith("reconnected ") }
        assertEquals("connected", probe.status("connection") { true })
        assertEquals("the engine connected to the game again", 2, game.accepted)
    }

    @Test
    fun aTlsConnectGoesThroughOpenSsl() {
        startEngine()
        val probe = attach()
        probe.attached()
        // The game does not speak TLS, so the handshake fails; that it began shows Qt's TLS backend loaded OpenSSL
        probe.send(Requests.connect(FakeGame.HOST, game.port, true))
        probe.text(EngineProbe.ENGINE_START_MS) { it.contains("Secure handshake failed") }
        val hello = await("the engine's first bytes to the game") { game.firstBytes.firstOrNull()?.takeIf { it.isNotEmpty() } }
        // A TLS record of type handshake (22), version 3.x: a ClientHello
        assertEquals("a TLS handshake record: ${hello.take(8)}", 0x16, hello[0].toInt())
        assertEquals(3, hello[1].toInt())
    }

    private fun attach() = EngineProbe(context).also { probes.add(it) }

    private fun connectToGame(probe: EngineProbe) {
        probe.attached()
        probe.send(Requests.connect(FakeGame.HOST, game.port, false))
        probe.status("profile", EngineProbe.ENGINE_START_MS) { it == "Android loaded" || it.startsWith("start failed") }
            .let { assertEquals("Android loaded", it) }
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

    private fun awaitEngineGone() = await("the $engineProcess process to end") { if (enginePid() == null) Unit else null }

    private fun enginePid(): Int? = context.getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName == engineProcess }?.pid

    @Suppress("DEPRECATION") // still answers for the caller's own services
    private fun engineService(): ActivityManager.RunningServiceInfo? = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(50).firstOrNull { it.service.className == EngineService::class.java.name }

    private fun <T : Any> await(what: String, timeoutMs: Long = EngineProbe.TIMEOUT_MS, get: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            get()?.let { return it }
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out after $timeoutMs ms waiting for $what")
            Thread.sleep(100)
        }
    }

    private companion object {
        // How long a test waits to be sure something does not happen
        const val QUIET_MS = 3_000L

        // A new engine, then libmudlet's profile, then the connection
        const val RESTORE_MS = 180_000L
    }
}
