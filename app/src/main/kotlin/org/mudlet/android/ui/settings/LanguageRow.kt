package org.mudlet.android.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.mudlet.android.BuildConfig
import org.mudlet.android.R
import org.mudlet.android.engine.AppLanguage

/** The languages the settings offer: the English's and each translation's, in the order of their names. */
internal val appLanguageChoices: List<AppLanguageChoice> by lazy {
    AppLanguages.choices(AppLanguages.tags(BuildConfig.DEFAULT_LANGUAGE, BuildConfig.TRANSLATIONS))
}

/**
 * [tag]'s entry in the list, or one of its own for a language the list lacks, as a pseudo-locale is: named beside the
 * list's, so en-XA is not a second "English".
 */
private fun choiceOf(tag: String) = appLanguageChoices.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
    ?: AppLanguages.choices(appLanguageChoices.map { it.tag } + tag).single { it.tag == tag }

/** A language's name, marked as in that language, so TalkBack reads it in that language's voice. */
private fun AppLanguageChoice.inItself(): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(localeList = LocaleList(tag))) { append(name) }
}

/**
 * The app's language under the row's label, and the list to pick another from: "System default" first, then each
 * language named in itself. A pick shows the app in that language at once, as the Activity is made again.
 */
@Composable
internal fun LanguageRow(entry: SettingEntry.Language) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    // A change makes the Activity, and with it this, again
    val chosen = remember { AppLanguage.chosen(context)?.let(::choiceOf) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val systemDefault = AnnotatedString(stringResource(R.string.settings_language_system))
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.settings_choose), role = Role.Button) { picking = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(stringResource(entry.label), style = MaterialTheme.typography.bodyLarge)
        Text(chosen?.inItself() ?: systemDefault, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        entry.hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } // BITE G
    }
    if (!picking) return
    // null is "System default"; one the list lacks is shown too, so the current choice is always there to see
    val choices = listOf(null) + listOfNotNull(chosen?.takeIf { it !in appLanguageChoices }) + appLanguageChoices
    AlertDialog(
        onDismissRequest = { picking = false },
        confirmButton = {},
        dismissButton = { TextButton({ picking = false }) { Text(stringResource(R.string.settings_choice_cancel)) } },
        title = { Text(stringResource(entry.label)) },
        text = {
            LazyColumn(Modifier.selectableGroup(), rememberLazyListState(choices.indexOf(chosen))) {
                items(choices, key = { it?.tag.orEmpty() }) { choice ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(choice == chosen, role = Role.RadioButton) {
                                picking = false
                                if (choice == chosen) return@selectable
                                if (!AppLanguage.choose(context, choice?.tag)) {
                                    Toast.makeText(context, R.string.settings_language_not_saved, Toast.LENGTH_SHORT).show()
                                } else if (Build.VERSION.SDK_INT < 33) {
                                    // Android 13 and later make it again themselves
                                    activity?.recreate()
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(choice == chosen, onClick = null)
                        Text(choice?.inItself() ?: systemDefault, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
    )
}
