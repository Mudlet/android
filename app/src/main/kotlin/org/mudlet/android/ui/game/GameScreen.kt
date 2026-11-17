package org.mudlet.android.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mudlet.android.R
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.RefusedCertificate
import org.mudlet.android.ui.ConsoleColors
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.MudletTopBar
import org.mudlet.android.ui.ProfileSession
import org.mudlet.android.ui.ScriptWindowsClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.reflect.KClass

/**
 * The console with the windows the game's scripts draw around it, the input line, and the row below it, top to bottom.
 * The row holds the game pill ([GamePill]), which shows the front game's connection and opens the game sheet
 * ([GameSheet]) with Reconnect, Disconnect, the open games and "Open another game", which goes back to the profile
 * list ([onBack]), where Connect opens one more beside those open; beside the pill, while the game is not connected,
 * one chip with what to do about it ([ConnectionChip]); once the game has played a sound in this session, or while
 * anything is muted, desktop Mudlet's Sound button ([SoundButton]); then the quick bar's History and pinned commands. After the title bar, TalkBack reads the console, the input line and that row, then the script
 * windows and their switcher, then the button bars. The keyboard pushes them up, so the console shrinks above it. The
 * input line's suggestions and the quick bar's pinned commands come from the profile the engine runs
 * ([rememberEngineSuggestions]), which learns from what is sent. The History button, or Ctrl+R on a hardware keyboard,
 * opens the command history ([HistorySheet]). While the game hides input the row has only the pill and its chip, and
 * there is no history. Why the engine could not send something shows over the console's foot, where the keyboard does
 * not hide it. The title bar's map button swaps the map and the console ([FullScreenMap]), until Back or the button
 * again, and for the game switched to: with the setting, the map is faint behind the text ([BackgroundMap]) and the
 * text faint behind the map in front ([textBehindMap]); without it there is no map behind the text and the map in front
 * covers the console. The input line and the row stay below the map in front, to type and pick commands in. The title
 * bar's scripts button opens the profile's script library ([onOpenScripts]), and a long press on a line of the console
 * offers to make a trigger from it ([onMakeTrigger], with the line's text).
 *
 * All of it is the front profile's ([EngineClient.front]), and what the screen keeps of a profile, such as the typed
 * line and where the console is scrolled to, waits for it while another is in front. When another game comes to the
 * front, TalkBack says its name and state and the focus goes to its input line. A swipe on the pill, or Ctrl+Tab and
 * Ctrl+Shift+Tab on a hardware keyboard, brings the next or the previous game, and the console, or the map in front,
 * slides across with it ([GameSwipe]). Each change of the front game's connection is said once, with the game's name
 * first, so Mudlet's retries after a drop are not each read out.
 */
@Composable
fun GameScreen(onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenScripts: () -> Unit, onMakeTrigger: (String) -> Unit) {
    val front = EngineClient.front
    val kept = rememberSaveableStateHolder()
    val open = EngineClient.sessions.map { it.name }
    val shown = remember { HashSet<String>() }
    // A profile that closed takes what was kept of it along
    LaunchedEffect(front.name, open) {
        shown.add(front.name)
        val closed = shown.filter { it !in open && it != front.name }
        closed.forEach(kept::removeState)
        shown.removeAll(closed.toSet())
    }
    // Not the profile's: a switch keeps the map in front (docs/multi-profile.md, "Interactions")
    var mapOpen by rememberSaveable { mutableStateOf(false) }
    val before = remember { Last<String>() }
    val switched = remember(front.name) {
        val was = before.value
        before.value = front.name
        was != null && was != front.name && front.name.isNotEmpty()
    }
    // Not the profile's either: a slide carries on across the switch it makes
    val scope = rememberCoroutineScope()
    val swipe = remember { GameSwipe(scope) }
    kept.SaveableStateProvider(front.name) { ProfileGameScreen(front, switched, mapOpen, { mapOpen = it }, swipe, onBack, onOpenSettings, onOpenScripts, onMakeTrigger) }
}

// What a composition last saw, which changing does not compose again
private class Last<T> {
    var value: T? = null
}

@Composable
private fun ProfileGameScreen(
    c: ProfileSession,
    switched: Boolean,
    mapOpen: Boolean,
    onMapOpen: (Boolean) -> Unit,
    swipe: GameSwipe,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScripts: () -> Unit,
    onMakeTrigger: (String) -> Unit,
) {
    val suggestions = rememberEngineSuggestions()
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var gamesOpen by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notSent = stringResource(R.string.input_not_sent)
    val context = LocalContext.current
    val passwords = remember { PasswordStore(context) }
    val resources = LocalResources.current
    val hidden = c.hiddenInput
    val boundKeysDown = remember { HashSet<Int>() }
    // Hidden input closes the history for good: it does not open again when the password field goes
    LaunchedEffect(hidden) { if (hidden) historyOpen = false }
    // The map behind the text is not there to be moved or picked in: once the map in front closes, not as a rotation
    // remakes it, it shows no room and follows the player again
    LaunchedEffect(mapOpen) {
        if (!mapOpen) {
            c.appMap.rooms.close()
            if (!c.appMap.mover.following) c.appMap.mover.followPlayer()
        }
    }
    val sendError = c.sendError
    if (sendError != null) {
        val message = stringResource(R.string.send_error, sendError)
        LaunchedEffect(sendError) {
            c.sendErrorShown()
            // The newest reason replaces one still showing; launched apart, as sendErrorShown ends this effect
            snackbars.currentSnackbarData?.dismiss()
            scope.launch { snackbars.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long) }
        }
    }

    val games = EngineClient.sessions
    val state = ConnectionState.of(c)
    val stateText = state.text()
    val name = c.name.ifEmpty { stringResource(R.string.game_none) }
    // What Reconnect opens again while no game is open
    val reconnectName = c.name.ifEmpty { EngineClient.lastProfile.orEmpty() }
    val canReconnect = EngineClient.enginePid > 0 && EngineClient.canReconnect
    val index = games.indexOf(c)
    val previous = games.getOrNull(index - 1)?.name
    val next = if (index >= 0) games.getOrNull(index + 1)?.name else null
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val switchedText = stringResource(R.string.game_state, name, stateText)
    LaunchedEffect(Unit) { if (switched) Announcer.announce(switchedText, assertive = false) }
    val heard = remember { Last<KClass<out ConnectionState>>() }
    val transitionText = stringResource(R.string.connection_announce, name, stateText)
    LaunchedEffect(state::class) {
        val was = heard.value
        heard.value = state::class
        if (was != null && was != state::class) Announcer.announce(transitionText, assertive = false)
    }

    fun reconnect() = EngineClient.reconnect(passwords::get) { error ->
        scope.launch { snackbars.showSnackbar(resources.getString(R.string.connection_reconnect_refused, error), withDismissAction = true) }
    }

    fun disconnect() {
        if (!c.disconnect()) {
            scope.launch { snackbars.showSnackbar(resources.getString(R.string.connection_disconnect_refused, resources.getString(R.string.profiles_error_not_attached)), withDismissAction = true) }
        }
    }

    fun close(game: String) = EngineClient.closeProfile(game) { error ->
        if (error != null) {
            scope.launch { snackbars.showSnackbar(resources.getString(R.string.game_close_refused, error), withDismissAction = true) }
        } else if (EngineClient.sessions.isEmpty()) {
            onBack()
        }
    }

    // A command from the quick bar or the history, sent as the input line sends
    fun sendNow(command: String) {
        if (c.sendCommand(command)) suggestions.sent(command) else scope.launch { snackbars.showSnackbar(notSent) }
    }

    Scaffold(
        topBar = {
            MudletTopBar(stringResource(R.string.game_title), onBack) {
                ScriptCommandsButton()
                IconButton(onOpenScripts) { Icon(painterResource(R.drawable.ic_auto_fix_high), stringResource(R.string.scripts_open, c.name)) }
                MapButton(mapOpen, onMapOpen)
                IconButton(onOpenSettings) {
                    Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title))
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().onPreviewKeyEvent { event ->
                // A binding comes first, as desktop's command line claims a bound profile-switching shortcut
                if (sentToKeyBindings(event, c.windows, boundKeysDown)) return@onPreviewKeyEvent true
                val ctrlR = event.key == Key.R && event.isCtrlPressed && !hidden
                val ctrlTab = event.key == Key.Tab && event.isCtrlPressed
                // Both halves of the press, so the focused field types no "r" and the focus stays where it is
                if (ctrlR && event.type == KeyEventType.KeyDown) historyOpen = true
                if (ctrlTab && event.type == KeyEventType.KeyDown) {
                    val forward = !event.isShiftPressed
                    val target = if (forward) next else previous
                    if (target != null && EngineClient.setFront(target)) swipe.arrive(left = forward != rtl)
                }
                ctrlR || ctrlTab
            },
        ) {
            Column(Modifier.weight(1f).fillMaxWidth().graphicsLayer { translationX = swipe.offset * size.width }) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val textSizeSp = Settings.textSize.value
                    val colors = ConsoleColors.of(c.status["consoleColors"])
                    val behind = Settings.mapBehindText.value
                    var backProgress by remember { mutableFloatStateOf(0f) }
                    val textBehind = if (mapOpen && behind) textBehindMap(c.appMap, colors) else null
                    // Under the map in front: faint with the setting, or drawn on for a predictive back gesture to show,
                    // but neither read by TalkBack nor reached by a hardware keyboard's focus
                    val covered = if (!mapOpen) Modifier else Modifier.clearAndSetSemantics {}.focusProperties { canFocus = false }
                    val faint = if (textBehind == null) {
                        Modifier
                    } else {
                        Modifier.background(textBehind.ground).graphicsLayer { alpha = textBehind.alpha().let { it + (1f - it) * backProgress } }
                    }
                    // One map on the screen: none behind the text while a script's mapper window is drawn, nor drawn
                    // there while the map is in front
                    ScriptWindowsArea(textSizeSp, Modifier.fillMaxSize().then(covered).then(faint)) { consoleModifier, mapperDrawn ->
                        Console(
                            c.lines,
                            c.tail,
                            c.linesAdded,
                            textSizeSp = textSizeSp,
                            colors = colors,
                            onTextSize = Settings.textSize::set,
                            onScreenSize = EngineClient::setScreenSize,
                            consoleModifier,
                            links = c.links,
                            onMakeTrigger = onMakeTrigger,
                        ) { if (!mapperDrawn) BackgroundMap(colors, behind, inFront = mapOpen) }
                    }
                    if (mapOpen) {
                        FullScreenMap(
                            onClose = { onMapOpen(false) },
                            textBehind = textBehind != null,
                            onBackProgress = { backProgress = it },
                            Modifier.fillMaxSize(),
                        )
                    }
                    SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
                }
                InputLine(
                    hiddenInput = hidden,
                    history = c.history,
                    commandLine = c.commandLine,
                    onCommandLineShown = c::commandLineShown,
                    onSend = { line -> c.sendCommand(line).also { if (it) suggestions.sent(line) } },
                    onSendHidden = { secret -> c.sendHidden(secret).also { if (it) suggestions.sentHidden() } },
                    onDismissHidden = c::dismissHidden,
                    onComplete = c::complete,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                    suggestions = suggestions.answer,
                    onInputChanged = suggestions::inputChanged,
                    picked = picked,
                    onPickedShown = { picked = null },
                    takeFocus = c.windows.focusInput,
                    onFocusTaken = c.windows::inputFocusTaken,
                    mapInFront = mapOpen,
                    focusOnStart = switched,
                    clearAfterSend = c.setting(Settings.clearInputAfterSend),
                    highlightHistory = c.setting(Settings.highlightHistory),
                )
            }
            QuickBar(
                if (hidden) emptyList() else suggestions.pinned,
                onHistory = if (hidden) null else ({ historyOpen = true }),
                onSend = ::sendNow,
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                GamePill(
                    name,
                    state,
                    index,
                    games.size,
                    othersUnread = games.sumOf { if (it === c) 0L else it.unread },
                    othersAttention = games.any { it !== c && it.attention },
                    onShowGames = { gamesOpen = true },
                    onReconnect = if (canReconnect) ::reconnect else null,
                    onDisconnect = if (c.name.isNotEmpty() && state.canDisconnect) ::disconnect else null,
                    previous = previous,
                    next = next,
                    onSwitch = EngineClient::setFront,
                    swipe = swipe,
                )
                when (val action = state.action) {
                    null -> {}
                    ConnectionAction.STOP -> if (c.name.isNotEmpty()) ConnectionChip(action, name, onClick = ::disconnect)
                    else -> if (canReconnect) ConnectionChip(action, reconnectName, onClick = ::reconnect)
                }
                val sounds = c.sounds
                if (sounds.played || sounds.scriptsMuted || sounds.gameMuted) {
                    SoundButton(sounds.scriptsMuted && sounds.gameMuted, onToggle = { c.toggleAllSoundsMuted() })
                }
            }
        }
    }
    if (historyOpen && !hidden) {
        HistorySheet(suggestions, onPick = { picked = it }, onSend = ::sendNow, onDismiss = { historyOpen = false })
    }
    if (gamesOpen) {
        GameSheet(
            c,
            name,
            games,
            canReconnect,
            onReconnect = ::reconnect,
            onDisconnect = ::disconnect,
            onSwitch = {
                // First: this game's screen is saved as it leaves the front, and an open sheet would show again on its return
                gamesOpen = false
                EngineClient.setFront(it)
            },
            onClose = ::close,
            onOpenAnother = onBack,
            onDismiss = { gamesOpen = false },
        )
    }
    LaunchedEffect(c.secureOfferRefusal) {
        val reason = c.secureOfferRefusal ?: return@LaunchedEffect
        c.secureOfferRefusalShown()
        // Launched apart, as secureOfferRefusalShown ends this effect
        scope.launch { snackbars.showSnackbar(resources.getString(R.string.secure_offer_refused, reason), withDismissAction = true) }
    }
    if (c.secureOffer && !c.secureOfferPutOff) {
        fun answer(accept: Boolean) {
            if (!c.answerSecureOffer(accept)) {
                scope.launch { snackbars.showSnackbar(resources.getString(R.string.secure_offer_refused, resources.getString(R.string.profiles_error_not_attached)), withDismissAction = true) }
            }
        }
        // Back is "not now": desktop has only Yes and No, and No turns the offer off for good
        AlertDialog(
            onDismissRequest = c::putOffSecureOffer,
            confirmButton = { TextButton({ answer(true) }) { Text(stringResource(R.string.secure_offer_accept)) } },
            dismissButton = { TextButton({ answer(false) }) { Text(stringResource(R.string.secure_offer_decline)) } },
            title = { Text(stringResource(R.string.secure_offer_title)) },
            text = { Text(stringResource(R.string.secure_offer_text)) },
        )
    }
    val refused = c.refusedCertificate
    if (refused != null && !c.refusedCertificatePutOff) {
        RefusedCertificateDialog(
            refused,
            onAccept = {
                if (c.acceptRefusedCertificate()) {
                    reconnect()
                } else {
                    scope.launch { snackbars.showSnackbar(resources.getString(R.string.certificate_not_accepted, resources.getString(R.string.profiles_error_not_attached)), withDismissAction = true) }
                }
            },
            onOpenSettings = {
                c.putOffRefusedCertificate()
                onOpenSettings()
            },
            onDismiss = c::putOffRefusedCertificate,
        )
    }
}

// Desktop shows these facts only in the profile's preferences; the app asks as the connection ends, and the choice is
// still the game's settings, so it is remembered and can be undone where desktop keeps it
@Composable
private fun RefusedCertificateDialog(certificate: RefusedCertificate, onAccept: () -> Unit, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (certificate.others) {
                TextButton(onOpenSettings) { Text(stringResource(R.string.certificate_open_settings)) }
            } else {
                TextButton(onAccept) { Text(stringResource(R.string.certificate_accept)) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.certificate_cancel)) } },
        title = { Text(stringResource(R.string.certificate_refused_title)) },
        text = {
            val gap = Modifier.padding(top = 8.dp)
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.certificate_refused_text))
                certificate.errors.forEach { Text(stringResource(R.string.certificate_error, it)) }
                Text(stringResource(R.string.certificate_issued_to, certificate.issuedTo), gap)
                Text(stringResource(R.string.certificate_issuer, certificate.issuer))
                Text(stringResource(R.string.certificate_expires, expiryText(certificate.expires)))
                Text(stringResource(R.string.certificate_serial, certificate.serial))
                if (certificate.others) {
                    Text(stringResource(R.string.certificate_only_ignore_all), gap)
                } else {
                    if (certificate.expired) Text(stringResource(R.string.certificate_turns_on, stringResource(R.string.settings_accept_expired_certificate)), gap)
                    if (certificate.selfSigned) Text(stringResource(R.string.certificate_turns_on, stringResource(R.string.settings_accept_self_signed_certificate)), gap)
                    Text(stringResource(R.string.certificate_risk), gap)
                }
            }
        },
    )
}

@Composable
private fun expiryText(expires: String): String {
    if (expires.isEmpty()) return stringResource(R.string.certificate_expiry_unknown)
    return runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.parse(expires))
    }.getOrDefault(expires)
}

/**
 * Whether [event] is for a key binding of the profile, which is then pressed: a key down whose Qt key the bindings
 * take, outside a script's text box, and the up of a key whose down was, so the focused field types neither half.
 * [down] holds the Android key codes whose down went to the bindings.
 */
private fun sentToKeyBindings(event: KeyEvent, windows: ScriptWindowsClient, down: MutableSet<Int>): Boolean {
    val native = event.nativeKeyEvent
    if (event.type == KeyEventType.KeyUp) return down.remove(native.keyCode)
    if (event.type != KeyEventType.KeyDown || windows.focusedTextBox != null) return false
    val shifted = native.getUnicodeChar(native.metaState and android.view.KeyEvent.META_SHIFT_MASK)
    val key = HardwareKeys.of(native.keyCode, native.metaState, shifted) ?: return false
    if (key !in windows.keyBindings) return false
    // Negative for a dead key, which types nothing yet
    val typed = native.unicodeChar
    // Unsent, as with no link to the engine, the key types as any other
    if (!windows.pressKey(key, if (typed > 0) String(Character.toChars(typed)) else "")) return false
    down += native.keyCode
    return true
}
