package org.mudlet.android.engine

import android.content.Context
import java.io.File

object EngineSocket {
    // In the app's private data dir: only this app's uid can reach it, unlike an abstract-namespace name
    fun path(context: Context): String = File(context.noBackupFilesDir, "engine.sock").absolutePath
}
