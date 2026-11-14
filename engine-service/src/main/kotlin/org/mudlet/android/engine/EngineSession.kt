package org.mudlet.android.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The files through which an engine started after the last one was killed brings back the profile that one ran
 * (docs/architecture.md, "Restoring the session"). The engine (native/enginehost/SessionStore.cpp) keeps the state
 * file; the app writes the stop marker whenever the user stops the engine, so the next start restores nothing, even if
 * the stop ends the process before the engine has cleared the state itself.
 */
object EngineSession {
    fun statePath(context: Context): String = File(context.filesDir, "engine-session.json").absolutePath

    fun stopMarkerPath(context: Context): String = File(context.filesDir, "engine-stopped").absolutePath

    /** Call when the user stops the engine; safe from either process, and when the engine is not running. */
    fun markStopped(context: Context) {
        try {
            File(stopMarkerPath(context)).writeText("")
        } catch (e: IOException) {
            Log.w(EngineService.TAG, "could not write the stop marker: the next engine may restore the profile", e)
        }
    }
}
