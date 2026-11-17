# Where the app differs from Mudlet desktop

Mudlet desktop is the reference for anything a script or a game can see. A gap is closed upstream, carried in
`scripts/mudlet-patches/` until Mudlet/Mudlet has it, or in the app's renderer, and never with an Android-only Lua
function or event. What is left here differs on purpose. Each entry says what differs, why matching the desktop would
be worse, and where the behaviour is described. A pull request that adds a difference adds its entry; one that takes a
difference away removes it.

## The size the game is told

The game (telnet NAWS) is told the columns and rows of the app's console in the font and text size the app draws in,
while scripts see the main window at the size the app gives in pixels (`setGameScreenSize()`, carried in
`0003-headless-game-screen-size.patch`; `docs/protocol.md`, `setScreenSize`). The desktop tells the game the grid of
the console Mudlet draws. Telling the game that grid on a phone would make it wrap lines for a console the player does
not see, while the layout scripts made for their window size would break if that size followed the phone's console.

## The app's own map

The game screen shows the profile's map with no script mapper, behind the text or in front of it. Once shown, scripts
see the map as open (`Host::mapOpen()`), as after the player opens the desktop's mapper, so a mapper script's
`centerview()` moves the player on it; they hear no `sysMapAreaChanged` or `sysMapWindowMousePressEvent` from it, as
they did not make it (`0001-headless-app-map.patch`; `docs/architecture.md`, "The map behind the text"). The desktop's
main window always offers its mapper; a phone has no room for that window beside the console, and a player should not
need a script to see the map.

## The map info box

The map info (the built-in `Short` and `Full`, and a script's `registerMapInfo()`) is text the app lays over the map,
not part of the map's picture: in the app's font at Android's font scale, wrapped to the map's width, with a line
break at a part's end trimmed and a part of nothing but space left out, at most two fifths of the map's height, and
not shown on the map behind the text. The parts, their order, colours, bold and italic and the box's colour are the
desktop's, and scripts see no difference (`0016-headless-map-info.patch`; `docs/architecture.md`, "The map info"). The
desktop paints the box into the map in the profile's display font. Painted into a picture on a phone it cannot be
read by TalkBack, does not follow the player's font size, and moves and scales with the picture under a finger until
the engine's next frame.

## Saving when the app leaves the front

When the app goes to the background, the open profiles are saved as Mudlet saves them at a close, but with no
`sysExitEvent` (`docs/protocol.md`, `setAppState`). Android may stop the engine without warning once the app is not in
front, so waiting for a close as the desktop does would lose what the session changed; raising `sysExitEvent` would
tell scripts the profile is closing when it is not.

## Settings the app keeps for every profile

"Read new text aloud", "Tell games I use a screen reader" and what becomes of blank lines are the app's settings: each
profile that loads is given the app's values, over its own, so a script's `getConfig()` reads them
(`app/src/main/kotlin/org/mudlet/android/data/Settings.kt`, `mudletSettings`). The desktop keeps them per profile. On a
phone they belong with the player and the device, as TalkBack itself does: kept per profile, every new profile would
start without the player's choice.

## A new game's input line is emptied after a send

A game added in the app starts with "Clear the input line after sending" on, and so does every game that was there
before the app had the setting (`Settings.clearInputAfterSend`, `ProfileSession.onLoaded`; `docs/architecture.md`,
"Settings"). The desktop starts a profile with "Auto clear the input line after you sent text" off: the command stays,
selected, for Enter to send again or the next key to replace. With a keyboard that is one key to repeat a command. On
a phone the on-screen keyboard's suggestions and the app's greyed completion work on what is typed after the cursor,
a tap on the line to fix a word drops the selection and leaves the old command to delete, and the up button and the
history sheet already repeat a command. The setting is the profile's own (`autoClearInputLine`), so a player who
wants the desktop's way turns it off for a game, or for new games, and a profile copied from the desktop keeps what
it had.

## A game's details changed while it is open

What the editor saves reaches a game that is open, and the game stays open. Its next connection logs in with the
character name saved, and the password stored or forgotten there (`THeadlessSession::setLogin()` and `setPassword()`,
carried in `0018-headless-login-change.patch`; `docs/architecture.md`, "Profiles in the app"). The connections after
the one that is open, Mudlet's automatic reconnect and Lua's `reconnect()` among them, go to the server saved, over
TLS or not as saved, and whether Mudlet reconnects by itself follows the switch (`setAddress()` and
`setAutoReconnect()`, carried in `0019-headless-connection-change.patch`). On the desktop the connection dialog's
boxes write the profile's files and never reach an open profile, which logs in with what it loaded, and reconnects to
where it last connected, until it is closed and opened again (`dlgConnectionProfiles::loadProfile()`); only its
preferences change the TLS choice of a profile that runs. A phone's player reconnects far more often than a
desktop's, and closing the game to make it read its own details loses what its scripts hold.

## Back on the game's offer of a secure port

The question asked when a game offers its secure port has the desktop's Yes and No ("Connect securely", "No, don't
ask again"), but Back answers neither: the dialog goes, the connection stays as it is, and the game's next offer asks
again. On the desktop Escape is No, which connects again and turns "Offer a secure connection" off for good
(`mudlet.cpp`, `signal_promptTlsAvailable`). Back on a phone is pressed far more readily than Escape on a question, and
a stray press should not cost a reconnect and the game's future offers.

## Asked about a refused certificate

When a game's certificate is refused, the desktop prints the reasons in the console and shows the certificate on the
Security tab of the profile's preferences, where the player may tick "Accept self-signed certificates" or "Accept
expired certificates" and connect again. The app asks as the connection ends, in a dialog with the same facts, and
its "Accept and connect" turns on those same settings for the game. A certificate with any other error offers the
game's settings, where "Ignore all certificate errors" is, rather than a button that ignores them all: the desktop
puts that box beside the other two, but a phone's dialog is tapped through too readily for it.

## The games the profile list offers

The profile list offers each of the games in Mudlet's connection dialog (`src/TGameDetails.h`, copied by
`scripts/sync-mudlet-games.py`) but the Mudlet Tutorial, which the desktop plays offline at `localhost`, port 0, from
its own package. The engine's profiles take a port from 1 to 65535 (`ControlBridge`, `ProfileStore`) and the app
connects when it opens one, so the tutorial would take a profile kind and an offline open of its own, for a lesson
that points at the desktop's menus and windows, which the app does not have.

## Labels and TalkBack

- A label's cursor shape is not drawn: a touch screen has no pointer to shape (`docs/phase-f.md`, `label`).
- TalkBack also reads a live label when a script makes it live or changes its region, when its name changes, and (its
  name alone) when a named one's text goes blank, and the app does not wait for the profile's "Make script buttons
  reachable with the keyboard" setting (`accessibleLabels`), which the desktop waits for (`docs/phase-f.md`, "Touch
  and TalkBack"). On a phone TalkBack is the only way a blind player reaches a script's buttons, and a change
  TalkBack does not say is a change that player never learns of.
- A clickable label and a text box take touches 48 dp around, however small a script draws them, and are drawn at
  their own size (`docs/phase-f.md`, "Touch and TalkBack"). Script geometry is made for a mouse; a finger needs
  Android's minimum touch target.
- A web link in a label's text is opened by the app, with an intent, and never takes the label's visited style
  (`docs/protocol.md`, "Labels"). The engine, a service, has no activity to open a browser from, so it is not sent the
  link: the carried patch marks a link visited only as it follows it, and marking a web link alone would take a call
  of its own in the patch and a way to ask for it in the protocol, for a colour.
- A `prompt:` link in a label is refused while the game hides input (`docs/protocol.md`, "Labels"); the desktop puts
  its text in the password box. The app's input line takes no text from the engine in that mode, so following the link
  would mark it visited and do nothing the player can see.
- A link Qt draws in a label's text is followed when tapped, also in a label where Mudlet's own check
  (`containsAnchorTag()`) finds no link, as when every link in it has a raw `<` in its quoted `href` or a space after
  the tag's `<`: the desktop draws those as links but does not make them clickable (`docs/protocol.md`, "Labels"). A
  link the player sees and cannot follow is a fault, not a choice a script makes, unless the script made the label
  click-through: its links are then not followed, as on the desktop (`docs/phase-f.md`, "Labels").
- No length in the style sheet of a label, a text box, a bar or a button is longer than 4096 of its pixels, where the
  desktop draws one of any size (`docs/phase-f.md`, "Labels"). 4096 pixels are past the side of any screen the app draws
  on, while a length of dozens of digits is infinite once it is multiplied into a screen's pixels, and Compose and
  Android throw on a corner or a size that is infinite. A `qlineargradient`'s coordinates are bounded the same way, and
  one that is not a number is 0, as Android has no gradient between such points.

## Button bars

A bar and its buttons draw the touch subset of their style sheets (`docs/phase-f.md`, "Button bars and commands").

- `:hover` and `:focus` rules never apply, nor a rule for a state the app does not give a button (`:selected`,
  `:indeterminate`), even negated. A touch screen has no pointer to hover, and a focused look would stay on a button
  after each tap.
- Rules with descendant or child selectors (`QWidget QPushButton`), an object name (`#flipButton`) or an attribute
  (`[flat="true"]`) are not drawn; the desktop applies those that match. The app's widgets are not Qt's, so no name,
  property or parent of one is there to match.
- A button is as big as its content and at least 48 dp, whatever `width`, `height` and their `min-` and `max-`
  forms ask (`docs/phase-f.md`, "Touch and TalkBack"). Sizes in a sheet are made for a mouse; a finger needs
  Android's minimum touch target.
- A down push-down button keeps its tick, and a folder its arrow, whatever the sheet; `::menu-indicator` and other
  sub-controls, `text-align`, `icon-size` and `qproperty-*` are not drawn (`docs/phase-f.md`, "Button bars and
  commands"). A sheet's colours alone would leave a player who cannot tell them apart unable to see which buttons are
  down, and the button lays out its icon, name and arrow itself, as the other buttons of the app do.
- Where the sheets set a button's background, or a bar's behind a border of their own, but no `color`, its text is black
  or white, whichever reads on it; the desktop uses its palette's button text colour. Where the button is on the app's
  colour, the sheets' `color` is drawn only where it reads on it at 4.5:1, and the app's text colour where it does not;
  where a colour of the sheets' that is more than half see-through tints the app's, the app's text colour too is drawn
  only where it reads, and black or white where it does not. The app's theme may be dark or light, which a sheet written
  for the desktop's colours never saw.
- A folder's menu keeps the app's look; the desktop gives a folder's `QMenu` the bar's sheet. The app's menu is a
  Material menu with a Back entry for a folder in it, which a `QMenu` sheet's rules (`QMenu::item`) do not describe.
- A bar draws its sheet's background, images and border, not its own padding, margin or sizes; its rules for its buttons
  still set theirs. A bar is a row or strip the app lays out to fit the screen, not a window of the size Mudlet gives
  it.
- A button is no bigger than the game area: its margin, border and padding shrink by one share until they leave its
  content 48 dp of the area, and a name too long for it ends in an ellipsis, which TalkBack still reads whole; the
  desktop makes a button as big as its sheet and its name ask. A sheet's lengths are made for a desktop's window, and a
  button whose content they push off the screen could be neither read nor touched. Compose also measures nothing wider
  than 2^18 pixels, so a vertical bar of so many columns that its places add up to more has them narrower than its
  buttons ask.
- A button's `font-size` is in sp, one to each of the sheet's pixels, so its name grows with the system's font size; a
  label's is in the label's own pixels (`docs/phase-f.md`, "Labels"). A label is a drawing a script laid out to the
  pixel, which larger letters would break; a button is a control the app lays out, to be read at the size the player
  chose for text.
- The app draws its own button's colour where the desktop draws its native button, with no background colour and no
  border property in the sheets, under their `background-image` where they set one. Qt may also draw that native frame
  instead of a background a sheet sets without a border, as some desktop styles do, where the app draws the sheet's
  background. What the button looks like then depends on the desktop's style, and the sheet asked for the background.
