package org.mudlet.android.engine

/** Implemented in libengine (enginehost/AndroidServiceSetup.cpp), loaded by QtServiceBase.onCreate(). */
object EngineNative {
    @JvmStatic external fun nativeServiceReady()
}
