package org.mudlet.android.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/** Shows the text it was handed to work on and whether it may change it, as [TAKEN] says, for a test to read. */
class TextTakerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        setContentView(TextView(this).apply { this.text = TAKEN.format(readOnly, text) })
    }

    companion object {
        const val LABEL = "Text taker"
        const val TAKEN = "read-only %b: %s"
    }
}
