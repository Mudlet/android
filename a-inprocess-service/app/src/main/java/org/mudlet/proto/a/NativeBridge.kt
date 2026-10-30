package org.mudlet.proto.a

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

sealed interface EngineEvent {
    /** [runs] is flattened `start, length, fg` triples; fg is 0-15 or -1 for the default colour. */
    class Line(val text: String, val runs: IntArray, val isPrompt: Boolean) : EngineEvent
    data class Notify(val title: String, val text: String) : EngineEvent
    data class Status(val key: String, val value: String) : EngineEvent
}

/**
 * The whole JNI surface. The external functions are bound by RegisterNatives in
 * libmudletproto's JNI_OnLoad, which runs when QtServiceBase.onCreate() loads the library;
 * before that they throw UnsatisfiedLinkError. Each one only queues work onto Qt's thread.
 * The @JvmStatic callbacks are invoked by C++ on Qt's thread and must not block.
 */
object NativeBridge {
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 8192)
    val events: SharedFlow<EngineEvent> = _events
    val dropped = AtomicLong()

    @JvmStatic external fun nativeServiceReady()
    @JvmStatic external fun nativeEngineRunning(): Boolean
    @JvmStatic external fun nativeConnect(host: String, port: Int)
    @JvmStatic external fun nativeSend(line: String)
    @JvmStatic external fun nativeDisconnect()
    @JvmStatic external fun nativeRunLua(code: String)
    @JvmStatic external fun nativeSetViewWidthPx(px: Int)

    fun libraryLoaded(): Boolean = try {
        nativeEngineRunning()
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    @JvmStatic
    fun onLine(text: String, runs: IntArray, isPrompt: Boolean) = emit(EngineEvent.Line(text, runs, isPrompt))

    @JvmStatic
    fun onNotify(title: String, text: String) = emit(EngineEvent.Notify(title, text))

    @JvmStatic
    fun onStatus(key: String, value: String) = emit(EngineEvent.Status(key, value))

    private fun emit(event: EngineEvent) {
        if (!_events.tryEmit(event)) dropped.incrementAndGet()
    }
}
