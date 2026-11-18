package org.mudlet.android.engine

import android.app.ActivityManager
import android.content.Context
import org.mudlet.android.protocol.ScrollbackBudget

object EngineScrollback {
    /** The most lines a game's console keeps on this device, which the engine and the settings screen both take. */
    fun maxLines(context: Context): Int {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return ScrollbackBudget.maxLines(memory.totalMem, manager.isLowRamDevice)
    }
}
