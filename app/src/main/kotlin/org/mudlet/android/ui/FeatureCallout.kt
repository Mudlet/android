package org.mudlet.android.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.content.edit
import kotlinx.coroutines.delay
import org.mudlet.android.R
import org.mudlet.android.ui.MainActivity.Companion.PREFS_UI
import org.mudlet.android.ui.game.Announcer

/** A part of the UI a callout points out. [id] names what is stored of it, so it is never renamed. */
enum class Feature(val id: String) {
    /** Swiping the game's name, or Ctrl+Tab, to switch games, once two are open. */
    GAME_SWITCHING("gameSwitching"),
}

/**
 * The app's feature callouts ([FeatureCallout]) under desktop Mudlet's rules ([CalloutRules]), kept in the `ui`
 * preferences as `callout:<id>:dismissed` and `callout:<id>:shown`, which are the player's data. The session is the UI
 * process's. Main thread only.
 */
object FeatureCallouts {
    private var rules: CalloutRules? = null

    // Mirrors CalloutRules.showing, which composition reads to keep a second callout back while one shows
    private var showing by mutableStateOf<String?>(null)

    private fun rules(context: Context): CalloutRules = rules ?: CalloutRules(PrefsCalloutStore(prefs(context))).also { rules = it }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)

    /** The player reached [feature] another way than its callout: it never shows again, and closes if it shows. */
    fun dismiss(context: Context, feature: Feature) = rules(context).dismiss(feature.id)

    fun dismissed(context: Context, feature: Feature) = rules(context).dismissed(feature.id)

    internal fun free(feature: Feature) = showing == null || showing == feature.id

    internal fun appear(context: Context, feature: Feature): Boolean = rules(context).appear(feature.id).also { showing = rules?.showing }

    internal fun gone(context: Context, feature: Feature) {
        rules(context).gone(feature.id)
        showing = rules?.showing
    }

    /** Every callout [dismissed] or never shown, in a session of its own: the tests' way to start where they need. */
    @VisibleForTesting
    fun reset(context: Context, dismissed: Boolean) {
        prefs(context).edit(commit = true) {
            for (feature in Feature.entries) {
                putBoolean(PrefsCalloutStore.dismissedKey(feature.id), dismissed)
                remove(PrefsCalloutStore.shownKey(feature.id))
            }
        }
        rules = null
        showing = null
    }
}

private class PrefsCalloutStore(private val prefs: SharedPreferences) : CalloutStore {
    // Snapshot state, so a dismissal anywhere closes the callout showing
    private val dismissedNow = mutableStateMapOf<String, Boolean>()

    override fun dismissed(id: String) = dismissedNow[id] ?: prefs.getBoolean(dismissedKey(id), false)

    override fun dismiss(id: String) {
        dismissedNow[id] = true
        prefs.edit { putBoolean(dismissedKey(id), true) }
    }

    override fun appearances(id: String) = prefs.getInt(shownKey(id), 0)

    override fun setAppearances(id: String, count: Int) = prefs.edit { putInt(shownKey(id), count) }

    companion object {
        fun dismissedKey(id: String) = "callout:$id:dismissed"

        fun shownKey(id: String) = "callout:$id:shown"
    }
}

/** Whether a callout must wait: while the game screen's tour, or something more urgent over the screen, shows. */
val LocalCalloutsHeld = compositionLocalOf { false }

/**
 * A callout ([title], [body] and Got it) under [anchor], the control it points out, as desktop Mudlet's TFeatureCallout
 * is: Material's rich tooltip, which stays until it is dealt with. It shows while [wanted], once [anchor] has settled,
 * unless [LocalCalloutsHeld] holds it back, and as [FeatureCallouts]'s rules allow; TalkBack says it as it appears.
 * Got it dismisses it for good, as [FeatureCallouts.dismiss] does wherever the player reaches the feature; a tap
 * elsewhere only hides it, until a later session. Not focusable, so the input line keeps the keyboard and Back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureCallout(feature: Feature, wanted: Boolean, title: String, body: String, modifier: Modifier = Modifier, anchor: @Composable () -> Unit) {
    val context = LocalContext.current
    val state = rememberTooltipState(isPersistent = true)
    val due = wanted && !LocalCalloutsHeld.current && FeatureCallouts.free(feature) && !FeatureCallouts.dismissed(context, feature)
    val announcement = stringResource(R.string.callout_announcement, title, body)
    LaunchedEffect(due) {
        if (!due) return@LaunchedEffect
        // As desktop's, for the anchor to settle where it stays
        delay(SETTLE_MS)
        if (!FeatureCallouts.appear(context, feature)) return@LaunchedEffect
        try {
            Announcer.announce(announcement, assertive = false)
            // Returns once a tap elsewhere hides it; throws as this effect ends, Got it's dismissal among the reasons
            state.show()
        } finally {
            state.dismiss()
            FeatureCallouts.gone(context, feature)
        }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = {
            RichTooltip(
                title = { Text(title, Modifier.semantics { heading() }) },
                action = { TextButton({ FeatureCallouts.dismiss(context, feature) }) { Text(stringResource(R.string.callout_got_it)) } },
            ) { Text(body) }
        },
        state = state,
        modifier = modifier,
        focusable = false,
        enableUserInput = false,
        content = anchor,
    )
}

private const val SETTLE_MS = 800L
