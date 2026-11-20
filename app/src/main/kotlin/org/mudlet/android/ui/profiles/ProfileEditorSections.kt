package org.mudlet.android.ui.profiles

/** What the profile editor's folded "Log in automatically" says it holds: whether a password is set, never which. */
internal sealed interface LoginSummary {
    data object NotSet : LoginSummary

    data object PasswordOnly : LoginSummary

    data class As(val login: String, val password: Boolean) : LoginSummary

    companion object {
        /** Of the character name typed as [login], and whether a password is stored or typed ([password]). */
        fun of(login: String, password: Boolean): LoginSummary {
            val name = login.trim()
            return when {
                name.isNotEmpty() -> As(name, password)
                password -> PasswordOnly
                else -> NotSet
            }
        }
    }
}

/** The proxy as the profile editor's folded "Advanced" tells of it. */
internal enum class ProxySummary {
    OFF,
    ON,

    /** What is typed is a proxy the engine would refuse; Save waits for it, so the section opens by itself. */
    NEEDS_FIX,

    /** Not known, as Mudlet reads a proxy only while its game is open, but a proxy password is stored. */
    PASSWORD_STORED,

    /** Not known, as Mudlet reads a proxy only while its game is open. */
    UNKNOWN,
    ;

    companion object {
        /**
         * Of the proxy fields while they are [shown], with the proxy switched on ([use]) and what is typed one the
         * engine would take ([valid]); [passwordStored] only counts while they are not.
         */
        fun of(shown: Boolean, use: Boolean, valid: Boolean, passwordStored: Boolean): ProxySummary = when {
            !shown -> if (passwordStored) PASSWORD_STORED else UNKNOWN
            !valid -> NEEDS_FIX
            use -> ON
            else -> OFF
        }
    }
}
