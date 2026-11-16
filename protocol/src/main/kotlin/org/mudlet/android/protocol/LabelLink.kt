package org.mudlet.android.protocol

/**
 * Who follows a tapped link in a label's text. The engine does, as Mudlet's `TLabel::slot_linkActivated()` does
 * (docs/phase-f.md, "Upstream gaps", 1), and gives it the label's visited style where Mudlet's link styling restyles
 * that link (docs/protocol.md, "Labels"); a web link is the app's to open, as the engine, a service, has no screen to
 * open it from.
 */
sealed interface LabelLink {
    /** `http:` and `https:`, opened in the browser. */
    data class Web(val url: String) : LabelLink

    /** Anything else, [href] as the label's text gives it, which the engine follows with `followLabelLink`. */
    data class Engine(val href: String) : LabelLink {
        /** A `prompt:` link, whose text the engine puts on the input line, which then takes the focus, as Mudlet's command line does. */
        val prompts: Boolean get() = scheme(href) == "prompt"
    }

    companion object {
        /** Who follows [link], a label link's `href`. */
        fun of(link: String): LabelLink {
            val scheme = scheme(link)
            return if (scheme == "http" || scheme == "https") Web(trimmed(link)) else Engine(link)
        }
    }
}

// A browser also drops the whitespace around an href before reading its scheme; Qt's whitespace (QChar::isSpace),
// which is Kotlin's without U+001C to U+001F and with U+0085
private fun trimmed(link: String) = link.trim { (it.isWhitespace() && it !in '\u001c'..'\u001f') || it == '\u0085' }

/**
 * The scheme of [link], a label link's `href`, read as `TLabelModel::followLink()` reads it: before the first colon of
 * the trimmed href, in lower case, and only where something comes before that colon; null for none.
 */
private fun scheme(link: String): String? {
    val href = trimmed(link)
    val colon = href.indexOf(':')
    return if (colon > 0) href.substring(0, colon) else null
}
