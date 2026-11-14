package org.mudlet.android.engine

/** Implemented in libengine (enginehost/AndroidServiceSetup.cpp), loaded by QtServiceBase.onCreate(). */
object EngineNative {
    @JvmStatic external fun nativeServiceReady()

    /** Saves the open profile, from Qt's thread; nothing before main() has set up the engine. */
    @JvmStatic external fun nativeSave()
}
