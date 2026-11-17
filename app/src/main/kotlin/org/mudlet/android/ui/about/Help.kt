package org.mudlet.android.ui.about

import androidx.annotation.StringRes
import org.mudlet.android.R
import org.mudlet.android.protocol.ScriptItemKind

/** A page opened in the browser, under its [label] */
data class HelpLink(@StringRes val label: Int, val url: String)

/**
 * The pages desktop Mudlet opens in the browser for help: its Help menu (mudlet.cpp, main_window.ui), and the
 * introductions its script editor links to for each kind of item (dlgTriggerEditor.cpp).
 */
object Help {
    /** Help > "API Reference" on desktop, whose tool tip and page are the manual's contents */
    val MANUAL = HelpLink(R.string.help_manual, "https://wiki.mudlet.org/w/Manual:Contents")

    /** The page desktop's script editor takes its list of Lua functions from (Mudlet's CI/update-autocompletion.lua) */
    val LUA_FUNCTIONS = HelpLink(R.string.help_lua_functions, "https://wiki.mudlet.org/w/Manual:Lua_Functions")

    /** The rest of desktop's Help menu, in its order, with its names */
    val COMMUNITY = listOf(
        HelpLink(R.string.help_videos, "https://www.mudlet.org/media/"),
        HelpLink(R.string.help_discord, "https://www.mudlet.org/discord"),
        HelpLink(R.string.help_live_chat, "https://web.libera.chat/?channel=#mudlet"),
        HelpLink(R.string.help_forum, "https://forums.mudlet.org/"),
    )

    fun introduction(kind: ScriptItemKind): HelpLink {
        val (label, anchor) = when (kind) {
            ScriptItemKind.TRIGGER -> R.string.help_introduction_triggers to "Triggers"
            ScriptItemKind.ALIAS -> R.string.help_introduction_aliases to "Aliases"
            ScriptItemKind.SCRIPT -> R.string.help_introduction_scripts to "Scripts"
            ScriptItemKind.TIMER -> R.string.help_introduction_timers to "Timers"
            ScriptItemKind.KEY -> R.string.help_introduction_keys to "Keys"
            ScriptItemKind.BUTTON -> R.string.help_introduction_buttons to "Buttons"
        }
        return HelpLink(label, "https://wiki.mudlet.org/w/Manual:Introduction#$anchor")
    }
}
