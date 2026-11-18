package org.mudlet.android.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

/** Puts [text] on the clipboard under [label], and says [copied] where Android does not say so itself. */
internal fun copyText(context: Context, label: String, text: String, copied: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    // From Android 13 the system shows what was copied
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
}

/** Offers [text], with [subject] if given, to Android's share sheet titled [title]; says [noApp] where no app takes it. */
internal fun shareText(context: Context, text: String, title: String, noApp: String, subject: String? = null) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    if (subject != null) send.putExtra(Intent.EXTRA_SUBJECT, subject)
    try {
        context.startActivity(Intent.createChooser(send, title))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, noApp, Toast.LENGTH_LONG).show()
    }
}
