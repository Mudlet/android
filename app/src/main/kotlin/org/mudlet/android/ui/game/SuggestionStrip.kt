package org.mudlet.android.ui.game

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.suggest.Suggestion

/** The suggestion strip's test tag. */
const val SUGGESTION_STRIP_TAG = "suggestionStrip"

/**
 * The row of suggestions above the input line: with [accepted] (the line the ghost text gives) first a button that
 * accepts it, then a button for each of [items], which [onPick] puts on the line. Neither sends anything.
 */
@Composable
fun SuggestionStrip(items: List<Suggestion>, accepted: String?, onAccept: () -> Unit, onPick: (Suggestion) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.testTag(SUGGESTION_STRIP_TAG).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (accepted != null) {
            IconButton(onAccept) {
                Icon(painterResource(R.drawable.ic_arrow_forward), stringResource(R.string.input_accept_suggestion, accepted))
            }
        }
        for (item in items) {
            val label = stringResource(R.string.input_suggestion, item.label)
            OutlinedButton(
                onClick = { onPick(item) },
                modifier = Modifier.semantics { contentDescription = label },
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(item.label, Modifier.widthIn(max = 200.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Shows [ghost] in [color] after the text, fish style, without changing the text: the cursor stays within what was
 * typed. The field's semantics keep the typed text, so TalkBack does not read the ghost as part of it.
 */
data class GhostTransformation(val ghost: String, val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val typed = text.length
        val shown = buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(color = color)) { append(ghost) }
        }
        return TransformedText(
            shown,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = offset

                override fun transformedToOriginal(offset: Int) = offset.coerceAtMost(typed)
            },
        )
    }
}
