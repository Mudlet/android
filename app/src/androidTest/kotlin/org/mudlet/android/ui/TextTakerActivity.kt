package org.mudlet.android.ui

import android.app.Activity

/** An app that takes selected text to work on, for the selection to offer; the tests catch its start, so it never shows. */
class TextTakerActivity : Activity() {
    companion object {
        const val LABEL = "Text taker"
    }
}
