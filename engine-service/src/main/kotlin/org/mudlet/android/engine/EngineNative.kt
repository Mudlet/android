package org.mudlet.android.engine

/** Implemented in libengine (enginehost/AndroidServiceSetup.cpp), loaded by QtServiceBase.onCreate(). */
object EngineNative {
    @JvmStatic external fun nativeServiceReady()

    /** Has Qt's thread save the open profile; does nothing until main() has set up the engine. Callable from any thread. */
    @JvmStatic external fun nativeSave()
}
