package org.mudlet.android.protocol

/**
 * The engine's status `restore` (docs/protocol.md, "The restore status"): how libmudlet's engine, started again after
 * the one before it was killed, brings back the profile that one ran. Its value is a phase, then what the phase is
 * about.
 */
object EngineRestore {
    const val STATUS_KEY = "restore"

    /** Every phase, as docs/protocol.md lists them. */
    val PHASES = listOf(
        "restoring", "restored", "waiting", "reconnecting", "reconnected", "retrying", "notReconnected", "cancelled",
        "failed", "gaveUp",
    )

    /**
     * The marker line for the UI to add when the status goes from [previous] to [value], or null if nothing new
     * happened: the engine resends every status each time a UI attaches.
     */
    fun marker(previous: String?, value: String?): String? =
        if (value == null || value == previous) null else "--- ${describe(value)} ---"

    /** One line for the UI to show, for a value of the status. */
    fun describe(value: String): String {
        val phase = value.substringBefore(' ')
        val detail = value.substringAfter(' ', "")
        return when (phase) {
            "restoring" -> "The engine restarted: loading profile $detail again"
            "restored" -> "The engine restarted: profile $detail restored, not connected as it was not before"
            "waiting" -> "The engine restarted again: profile restored, reconnecting to ${detail.substringAfter(' ')} " +
                "in ${detail.substringBefore(' ')} s"
            "reconnecting" -> "The engine restarted: profile restored, reconnecting to $detail"
            "reconnected" -> "Reconnected to $detail after the engine restarted"
            "retrying" -> "Could not reconnect to $detail yet; the profile tries again on its own"
            "notReconnected" -> "Could not reconnect to $detail after the engine restarted"
            "cancelled" -> "Restoring the profile was cancelled"
            "failed" -> "The engine restarted, but could not load the profile again: $detail"
            "gaveUp" -> "The engine keeps stopping (restored ${detail.substringBefore(' ')} times in " +
                "${detail.substringAfter(' ')} minutes): the profile was not restored"
            else -> "Engine restore: $value"
        }
    }
}
