package org.mudlet.android.protocol

import org.json.JSONObject

/**
 * What a tapped link in a label's text does, as Mudlet's `TLabel::slot_linkActivated()` decides it. Upstream's
 * headless session cannot follow a label's link (docs/phase-f.md, "Upstream gaps", 1), so until it can, the app does
 * it: [request] is what to send the engine for it, and a web link is the app's to open. A visited link's style is not
 * kept.
 */
sealed interface LabelLink {
    /** `send:`: the command, through the aliases as the command line sends it. */
    data class Send(val command: String) : LabelLink

    /** `prompt:`: the text, put on the input line for the player to send. */
    data class Prompt(val text: String) : LabelLink

    /** `http:` and `https:`, opened in the browser. */
    data class Web(val url: String) : LabelLink

    /** Anything else that is a Lua command, run as one. */
    data class Lua(val code: String) : LabelLink

    /** A scheme Mudlet does not handle, such as `ftp:` or `mailto:`, on what is no Lua method call: not run. */
    data class NotRun(val scheme: String) : LabelLink

    /** What to send the engine for this link, with [id] for its answer; null for one the engine has no part in. */
    fun request(id: Long): JSONObject? = when (this) {
        is Send -> Requests.runLua("expandAlias(${luaString(command)})", id)
        is Prompt -> Requests.runLua("printCmdLine(${luaString(text)})", id)
        is Lua -> Requests.runLua(code, id)
        is Web, is NotRun -> null
    }

    companion object {
        private val SCHEME_NAME = Regex("^[a-z][a-z0-9+.-]*$")

        // Lua allows whitespace and --[[ ]] comments on either side of a method's name
        private val METHOD_CALL = Regex("^(?:\\s|--\\[(=*)\\[.*?\\]\\1\\])*[A-Za-z_][A-Za-z0-9_]*(?:\\s|--\\[(=*)\\[.*?\\]\\2\\])*[(\"'{\\[]")

        /** What following [link], a label link's `href`, does. */
        fun of(link: String): LabelLink {
            // A browser also drops the whitespace around an href before reading its scheme
            val href = link.trim()
            val colon = href.indexOf(':')
            if (colon > 0) {
                val scheme = href.substring(0, colon).lowercase()
                // Untrimmed: a send: or prompt: payload can end in a space the player means to keep
                val payload = link.substring(link.indexOf(':') + 1)
                when {
                    scheme == "send" -> return Send(payload)
                    scheme == "prompt" -> return Prompt(payload)
                    scheme == "http" || scheme == "https" -> return Web(href)
                    scheme == "javascript" || (SCHEME_NAME.matches(scheme) && !METHOD_CALL.containsMatchIn(payload)) -> return NotRun(scheme)
                }
            }
            return Lua(link)
        }

        /** [text] as a Lua string literal. */
        fun luaString(text: String): String {
            val out = StringBuilder("\"")
            for (c in text) {
                when {
                    c == '\\' -> out.append("\\\\")
                    c == '"' -> out.append("\\\"")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c < ' ' || c == '\u007F' -> out.append('\\').append(c.code.toString().padStart(3, '0'))
                    else -> out.append(c)
                }
            }
            return out.append('"').toString()
        }
    }
}
