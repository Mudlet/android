package org.mudlet.proto.b

import android.content.Context

/** Which Context the QtQuickView that starts Qt is created with. Fixed for a process lifetime. */
enum class HostMode {
    /** The documented path: a 1x1 QtQuickView inside the Compose Activity. */
    ACTIVITY,

    /** QtQuickView created with the foreground Service as its Context and never attached. */
    SERVICE;

    companion object {
        private const val PREFS = "proto"
        private const val KEY = "hostMode"

        fun load(context: Context): HostMode {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == name } ?: ACTIVITY
        }

        fun save(context: Context, mode: HostMode) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).commit()
        }
    }
}
