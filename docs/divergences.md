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
On an expanded screen the map stands beside the console, as the desktop's dock does, unless the player turns "Map beside
the text" off.

## A script's small mapper

A mapper window smaller than two fifths of the game area is drawn that large on the screen, its shape kept, and its
zoom buttons, edit switch, the button that centres it on the player, its area and level and its map info go in a
panel beside it rather than over it (`docs/architecture.md`, "The map"). The window's size scripts see, and the size
the engine draws the map at, stay what the script set. A desktop's mapper is laid out for a screen many times a
phone's: scaled to fit, a 200 pixel mapper is a few dp across, too small to read a room or touch one, and controls
drawn over it hide most of it.
The panel shows only once a small button over the map's top corner is pressed, and Back or a touch elsewhere hides it
again. Desktop's mapper shows its panel until the player hides it with the thin button between the two; beside a map
that small on a phone, a panel left showing covers the game's text, so it shows only while the player wants it.

## The app's map in the corner

With the see-through map off, a profile with a map and no script mapper shows the app's map small in a corner of the
game's text, placed and sized as a script's small mapper is, which the player can hide (`docs/architecture.md`, "The
map in the corner"). The desktop draws no map of its own in a corner: its mapper is a window the player opens beside
the console. A phone has no room for that window, the map in front hides the text, and without a corner map a player
with no mapper script would see where they are only by bringing the map to the front.

## The map info box

The map info (the built-in `Short` and `Full`, and a script's `registerMapInfo()`) is text the app lays over its own
map and beside a script's (above, "A script's small mapper"),
not part of the map's picture: in the app's font at Android's font scale, wrapped to the map's width, with a line
break at a part's end trimmed and a part of nothing but space left out, at most two fifths of the map's height, and
not shown on the map behind the text. The parts, their order, colours, bold and italic and the box's colour are the
desktop's, and scripts see no difference (`0016-headless-map-info.patch`; `docs/architecture.md`, "The map info"). The
desktop paints the box into the map in the profile's display font. Painted into a picture on a phone it cannot be
read by TalkBack, does not follow the player's font size, and moves and scales with the picture under a finger until
the engine's next frame.

## The Map settings page

A game's Map page has the desktop's Mapper tab sizes, room borders, large area exit arrows and player room marker, and
the Mapper colors tab's colours, through `getConfig()` keys (`0038-headless-map-settings.patch`). A colour is picked as
`#rrggbb` and keeps the alpha the profile had for it, so the grid stays faint; the desktop can pick an alpha too. Left
out: "Draw rooms on upper and lower levels", which Mudlet keeps for every profile in `Mudlet.ini`, not with the
profile; "Show the default area", which Mudlet does not save and no area list in the app reads; the symbol font, which
needs the engine's list of fonts; the 3D map style, as the engine has no 3D map; the map info background, which the
app's own info box does not use (see "The map info box"); and the desktop's anti-aliasing, grid width and zoom
direction, which are how its window draws and scrolls rather than what the map holds.

## Saving when the app leaves the front

When the app goes to the background, the open profiles are saved as Mudlet saves them at a close, but with no
`sysExitEvent` (`docs/protocol.md`, `setAppState`). Android may stop the engine without warning once the app is not in
front, so waiting for a close as the desktop does would lose what the session changed; raising `sysExitEvent` would
tell scripts the profile is closing when it is not.

## A script and the clipboard

A script's `getClipboardText()` reads the device's clipboard only while the app is in front, as Android lets only the
app with focus read it: in the background, or with another app's window focused beside it, the script reads an empty
clipboard. It waits for the app's answer at most half a second, and reads empty if none comes. Its `setClipboardText()`
puts the text on the clipboard only while the app is attached to the engine; in the background the text goes nowhere,
though the call still returns true. Desktop Mudlet reads and writes its clipboard at any time. The engine runs in a
service, which Android does not let read the clipboard, and a script that waited for the app to come back would stall
the game. From Android 12 the system says so when the app reads what another app put there, as for any app.

## A script asks for a file

A script's `invokeFileDialog()` asking for a file shows a dialog of the app's own with the script's title, as Android's
file picker shows none, and Choose opens that picker, which needs no storage permission. The file chosen is copied into
the folder the desktop's dialog would have opened at, the profile's folder or the script's own, beside any file of the
same name rather than over it, and the copy's path is what the script gets: a file elsewhere on the device has no path
the engine may open. A folder outside the app's own files takes no copy, and the script gets an empty string, as for a
cancel. Asking for a folder gives an empty string at once, as does asking with no app attached or while another
script's request waits: Android's picker lends a folder only as a URI, not as a path a script could open. While the
dialog and the picker show, the game, its timers and other scripts go on, as they do behind the desktop's dialog.

## A script opens a web page

A script's `openUrl()` or `openWebPage()` opens the page, in the app Android gives the link to, while the app is in
front with the game's screen showing, and the script gets true, as on the desktop. In the background, from another of
the app's screens, or for a scheme other than `http`, `https` and `mailto`, it is refused and the script gets false, as
desktop Mudlet answers a page it could not open. The engine runs in a service, from which Android does not let an app
start a browser, and a page that opened over another app while the player was elsewhere would take them away from what
they were doing. A `file:`, `content:` or `intent:` link, or a game's own scheme, would reach the app's files or other
apps' components rather than a page. Opening a page sends the player to another app, so a second one opens only once
they come back: a script that opens pages in a loop opens one ("Calls the user may overrule" below). The script waits
for the app's answer at most half a second, and gets false if none comes. A script of a game open beside the one
showing can open a page too, as the engine does not say whose script asked.

## The Variables view

The script library's Variables screen opens a table only under a string, number or boolean key; one under a table or
a function key shows its type and size but does not open, where the desktop editor's Variables view opens it. The
desktop holds the tree it read between clicks, while the engine is asked again for each table, and Mudlet names a
table or function key by a reference it makes anew at each read, so the path to such a table would name nothing the
next time (`docs/protocol.md`, "Reading the Lua variables").

## Settings the app keeps for every profile

"Read new text aloud", "Tell games I use a screen reader" and what becomes of blank lines are the app's settings: each
profile that loads is given the app's values, over its own, so a script's `getConfig()` reads them
(`app/src/main/kotlin/org/mudlet/android/data/Settings.kt`, `mudletSettings`). The desktop keeps them per profile. On a
phone they belong with the player and the device, as TalkBack itself does: kept per profile, every new profile would
start without the player's choice.

## The changelog

About's "Show changelog" (`ui/about/ChangelogScreen.kt`) lists the app's own release notes
([`release-notes.md`](release-notes.md)), where desktop Mudlet's Help > "Show changelog" shows Mudlet's, from its
updater's feed. The app is released apart from Mudlet, and what a player notices in it is mostly the app's own change;
the engine's Mudlet version and commit are in About. The desktop also shows its changelog once after an update, which
the app leaves to Google Play's "What's new", the same text.

## A newer test build

Desktop Mudlet's updater asks its feed at start and every 12 hours, and downloads and installs a new release itself
unless the player has turned automatic updates off (`Updater::checkUpdatesOnStart`, `src/updater.cpp`). The app has no
updater: Google Play updates its copies. Only a PTB installed from GitHub asks, at most once a day, as PTBs are built
once a night, and it only tells the player, its Download opening the release page in the browser
([`ptb.md`](ptb.md), "A newer build from GitHub"). Installing the APK itself would take `REQUEST_INSTALL_PACKAGES`,
which Play reviews and the app has no other use for, and a download and install the player did not start.

## The UI tour

The game screen's tour (`ui/game/GameTour.kt`; `docs/architecture.md`, "On-device checklist") shows the first time the
game screen does to every player, where the desktop leaves its tour out for a player it judges experienced from their
profiles and how long Mudlet has been there (`TUiTour::shouldShowOnFirstProfile`). The app's controls are not the
desktop's, so a desktop player meets them new too, and Skip ends the tour at the first step. Scripts see no tour:
the desktop sets `mudlet.uiTourPending` while its tour is due and raises `sysUiTourFinished` when it closes, both from
its connection dialog, which the engine here does not run. Telling scripts would take the engine a message from the UI
for the tour's end. Of the packages Mudlet ships, two wait on the flag: the Mudlet Tutorial, which the app does not
offer ("The games the profile list offers"), and the starter UI (`mudlet-base-ui`), whose introduction then comes on
the console under the tour, where it stays to be read after. The last step leaves out the desktop's pointer to the
tutorial.

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

## A new game keeps 10,000 lines

A game added in the app starts with a scrollback length (`consoleBufferSize`, desktop Mudlet's "Main display size")
of 10,000 lines (`Settings.consoleBufferSize`), where the desktop starts a profile with 100,000. The engine keeps each
line with a colour and a format for every character, so 100,000 lines of a busy game take hundreds of megabytes, for
each of up to four games open at once, on a phone that may stop the engine to get its memory back. 10,000 is what the
engine kept before it took the profile's setting, and still more than the 2,000 the screen keeps. The setting is the
profile's own, so a player who wants more sets it for a game, or for new games, and a profile copied from the desktop
keeps what it had.

## The most lines a game keeps comes from the phone

Desktop Mudlet's "Use maximum lines possible" gives the main console as many lines as four fifths of the computer's
memory would hold, and clips a bigger scrollback length, the profile's or a script's `setConsoleBufferSize()`, to the
same. On a phone with 8 GB that is millions of lines for each game, and Android stops the engine, and with it every
game's connection, long before they are kept. The app names its own most instead (`ScrollbackBudget.maxLines`): a
sixteenth of the device's memory, a thirty-second on a low-RAM one, for the four games the engine can have open at
once, at Mudlet's own estimate of 1,600 bytes a line, in whole thousands and never below the screen's 2,000. A phone
with 8 GB keeps up to 73,000 lines in each game. Past it a console sheds its oldest lines a batch at a time, with
`sysBufferShrinkEvent`, as on the desktop; `getConsoleBufferSize()` answers the clipped size, as the desktop does on a
computer with less memory than the one a profile was saved on, and the profile keeps the size it saved.

## A script's style sheet for the input line

A script's `setCmdLineStyleSheet()` with no name, or with `"main"`, styles the desktop's main command line. The app
draws a window's command line with its sheet's background and text colour (`docs/phase-f.md`, "Mini consoles and user
windows"), but not its input line with the main one's: the input line is the app's own, laid out for a phone with its
history, completion and send buttons, in the theme's colours the player's light or dark choice sets, and a sheet made
for the desktop's line, a dark one for a dark profile, can leave its text unreadable there. The engine keeps the sheet,
so `getCmdLineStyleSheet()` reads it back as on the desktop, and does not send it (`docs/protocol.md`, "Script
windows").

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

## A copy of a profile

The editor's Copy and "Copy settings only" make what the two buttons of the desktop's connection dialog make
(`dlgConnectionProfiles::slot_copyProfile()` and `slot_copyOnlySettingsOfProfile()`; `data/ProfileFolderCopy.kt`,
`ui/profiles/ProfileCopy.kt`), with these differences. The desktop copies the profile's password with either button,
and shows it, masked, in its password box. The app's editor never shows a stored password, so a copy would put one
under a second name unseen: the app stores the password for the copy only when the player turns on "Copy the stored
password too" in the dialog that names the copy, and leaves the password files a profile from the desktop may hold
out of a copy, as out of an export. The desktop follows a link in the profile's folder and the app leaves it out, as
a link can lead to files that are not the profile's. Where a file cannot be copied the desktop keeps the incomplete
copy and warns; the app makes the copy beside the profiles and adds it whole or not at all, as a phone runs out of
space sooner and a copy without its map looks like a profile that lost one. The desktop names the copy itself and
lets the player rename it; a profile's name cannot change in the app, so its dialog offers the desktop's name and asks
first. A game that is open is copied once it has closed and saved, as it is exported.

## The games the profile list offers

The profile list offers each of the games in Mudlet's connection dialog (`src/TGameDetails.h`, copied by
`scripts/sync-mudlet-games.py`) but the Mudlet Tutorial, which the desktop plays offline at `localhost`, port 0, from
its own package. The engine's profiles take a port from 1 to 65535 (`ControlBridge`, `ProfileStore`) and the app
connects when it opens one, so the tutorial would take a profile kind and an offline open of its own, for a lesson
that points at the desktop's menus and windows, which the app does not have.

## Removing a package asks first

The package manager asks before it removes a package (`docs/architecture.md`, "The package manager"); the desktop's
Remove removes the packages selected at once. On a phone a tap meant to expand a package's description lands near its
Remove button, and what a removal takes, the package's items and files, does not come back.

## A telnet:// link from another app

A `telnet://` link opened outside Mudlet connects on the desktop at once (`mudlet::handleTelnetUri`): to the profile
at its address, or to a new profile it makes, named after the host and saved; a `telnets://` link also turns on the
saved profile's TLS. The app asks first, naming the address, as the desktop asks for a link inside a game
(`connectFromGameLink`), opens an unknown address in a new game's editor for the player to check and save, and
never changes a saved profile, so a `telnets://` link to a profile that does not connect securely opens a new game.
A phone opens links from messages and web pages a tap away, often by mistake: a link that connected at once would
reach whatever server it named, or log in to one of the player's games with its saved details, unasked.

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
  would mark it visited and do nothing the player can see. A `prompt:` link in the game's text is followed then, as
  the engine follows it, but its text does not reach the masked field either.
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
- Of a label's or a text box's own sheet, a rule with a pseudo-state is not drawn (`QLabel:hover`, and also one for a
  state the widget would be in, such as `:enabled`, `:!hover` or a focused text box's `:focus`), nor one with a
  descendant or child selector (`QWidget QLabel`), an object name (`#label_<profile>_<name>`) or an attribute; the
  desktop applies those that match (`docs/phase-f.md`, "Labels"). The app's widgets are not Qt's, so no state, name,
  property or parent of one is there to match, and a touch screen has no pointer to hover.
- A label made not to fill its background (`createLabel()`'s `fillBackground` 0) is drawn with its own colour, the one
  `setBackgroundColor()` gave it, where its sheet draws no box of its own, as one made to fill is; the desktop draws
  that colour on such a label only where it is a declaration of the sheet that Qt reads (`docs/phase-f.md`, "Labels").
  The engine sends the colour as the label's `bg` (`docs/protocol.md`, "Script windows") but not `fillBackground`,
  which the headless `createLabel()` does not keep. Geyser fills its labels but a gauge's text label, whose colour is
  wholly see-through.
- A sheet's `border-image` or `background-image` of a file that does not load still counts as drawing the label's box,
  so the label's own colour is not drawn under it; the desktop draws the colour there (`docs/phase-f.md`, "Labels").
  The app asks the engine for the file only as it draws, so it does not know in time whether the file loads.
- A box's length in `em` or `ex` is not read: `padding: 1em` leaves the padding set before it, and the `border`
  shorthand with one (`border: 2em solid red`) leaves the border with no width, so none is drawn. The desktop measures
  them by its font: at Qt's default 9 pt font `2em` is 30 px and `2ex` 14 px (`docs/phase-f.md`, "Labels"). Those are
  the heights of a line and of an x in the desktop's font, which the app, drawing with Android's fonts, does not have.
- A comment between a rule's selector and its brace (`QLabel /* note */ { ... }`) is read as space; Qt drops that rule,
  and reads the rules after it. The app takes comments out of a sheet before it reads it, which keeps its reader to one
  kind of space, for a place where Geyser's sheets have no comment.
- A colour of the palette's, `palette(highlight)` and the like, is no colour in a label's, a bar's or a button's sheet,
  so it is read as one Qt reads no colour off: a property set back to none, a border in the sheet's `color`, and in a
  label's rich text black for its text and no change to its background; Qt takes the colour from the widget's palette
  (`docs/phase-f.md`, "Labels"). The app's widgets have no Qt palette, and the desktop's palette, which differs with its
  style and theme, does not reach it.
- A border whose colour the sheet does not name (`border: 2px solid`, or `border-width` and `border-style` alone) is
  drawn in the text's colour, the sheet's `color` or else the app's; Qt draws it black whatever the `color`. One it
  names but Qt reads no colour off (`border: 2px solid none`) is drawn as Qt draws it, in the sheet's `color`, or clear
  where it has none (`docs/phase-f.md`, "Labels"). A black frame on the app's dark theme would not show, and the text's
  colour is what a frame with no colour has in CSS and in the browsers that scripts' authors test their sheets in.
- A border of a gradient (`border: 2px solid qlineargradient(...)`) is drawn in one colour: a linear gradient's at
  the middle of its box, which is the colour Qt draws at the middle of each edge, and a radial or conical gradient's
  first stop (`docs/phase-f.md`, "Labels"). Qt fills each edge with the gradient across the edge's own box, so the
  colour changes along an edge and across it. The app draws a border as one stroke of one colour, which the middle
  colour stands for on every edge.
- A border whose top edge is left native (`border: 2px solid red; border-top-style: native`) is not drawn on a label,
  a text box or a bar, and leaves its content no room, as on the desktop; where the sheet also has a background, Qt
  draws the other edges, in their style (`docs/phase-f.md`, "Labels"). The app draws one border for all four edges,
  and a native top edge is what Qt draws no border of where the sheet draws nothing else.
- A text box whose style sheet sets no colours has the theme's surface and text colours; the desktop's has the
  profile's command-line colours, dark grey on black unless the player changed them (`docs/phase-f.md`, "Text
  boxes"). They make it look like the desktop's command line, which has them too. The engine does not send them, and
  the app's input line and a command line whose sheet sets no colours have the theme's colours, so a text box looks like
  the fields the player types in, as on the desktop.

## A see-through user window or switcher page

A user window whose colour a script made partly or wholly transparent shows the game area's black under it, docked or as
a page of the window switcher (`docs/phase-f.md`, "Mini consoles and user windows"). On the desktop a user window is a
dock widget, and what shows under it is the colour of Mudlet's main window, or of its own window when floating: the
desktop style's, or what the profile's style sheet gives. The app's user windows are part of the game area, with no
window or Qt style of their own, and a desktop theme's window colour drawn there would sit in the game screen beside the
console's black. A mini console in the game area shows what is under it, as on the desktop; one the stacked layout makes
a page of the switcher, from a side border, shows black too, as that layout draws no label from those borders and the
page's pane covers the console.

## A console's scroll bar

A mini console or user window a script gave a scroll bar (`enableScrollBar()`) has a thin thumb drawn over its right
edge, which takes no width from the text (`docs/phase-f.md`, "Mini consoles and user windows"). On the desktop the bar
is a widget 15 pixels wide beside the text, with a pixel between them, so the text has 16 pixels less. A script that
wraps by the window's width leaves that room itself: Geyser's `autoWrap` takes 15 pixels off before it counts the
columns to wrap at, so in such a console nothing is under the app's thumb. In one wrapped at a width the script chose
(`setWindowWrap()`), the desktop cuts the columns past the narrower text off, and the app draws them, the last under
the thumb where it is. Taking the width would make every row's text smaller, as the app sizes a console's
text to fit the columns the engine reports, and those count the whole width with or without a bar.
A finger takes the thumb in a strip 24 dp wide, wider than the thumb drawn and than the desktop's bar, which lies over
the rows' right edge: a link there under the strip goes to the thumb, not the link, until the rows or the thumb move.
The desktop's bar is beside the text and covers no link. A console too short for the thumb to move in (under about
48 dp tall) has none, where the desktop's bar is there at any height.

The thumb is also all there is of the bar: no track to tap for a page, and no arrows, as a finger scrolls the rows
themselves. It tells where the rows in view are among those the app holds, the console's newest, not among every
line the console has. The main console has no scroll bar in the app, where the desktop's has one unless a script takes
it off: it scrolls by touch, with a button back to the newest line.

## A scroll box

A scroll box draws no frame and no scroll bars (`docs/phase-f.md`, "Scroll boxes"). On the desktop it is a `QScrollArea`
with a one-pixel frame and, where its content is larger, the desktop style's scroll bars, which take their room from
what shows. On a touch screen the box scrolls under a finger and with TalkBack's scroll actions, and bars of a few
pixels would only take room the script gave its windows. A box with nothing in it to size its content, none of its
windows both visible and of some size, does not scroll: on the desktop its content keeps the size it last had, or that
of the window the box was made in, which is no part of what a script laid out. One whose windows lie so far above or
left of its top left that Qt would give its content a negative size does not scroll either, where the desktop keeps the
size it had.
A box hidden and shown again, or moved to or from a page of the switcher, starts at its top left: its scroll is kept
only while it is drawn, where a hidden `QScrollArea` keeps its scroll bars' values.

A mouse does not drag a `QScrollArea`, so on the desktop a press on a label in one is always the label's. Here a drag
that starts on a label in a box that scrolls is the box's once the box takes it, which it does along an axis it scrolls
on even at the end of its range, and so is a tap that stops its fling:
the label gets neither, not even `enter`, which comes once the touch is the label's rather than as the finger lands. A
drag the box has not taken by twice the touch slop, as along an axis it does not scroll on, is the label's.

## The error console

The error console keeps the last 1000 entries since the profile loaded or was cleared, fewer once their texts pass a
million characters, and cuts a text over 10000 characters with `…` (`docs/protocol.md`, `errorConsole`;
`docs/architecture.md`, "The error console"). Each entry shows when it came, and one raised by a trigger, alias,
script, timer, key or button opens that item's page. The desktop's console keeps what its buffer holds, as text, with
no time or way to the item. Unbounded, the list the engine gives each UI that attaches could outgrow the socket's
frame; on a phone, scrolling to the item by hand from a name in a message is slow with a finger and slower with
TalkBack.

## Log options

A game's Logging page has desktop Mudlet's log options but "Save log files in:": a game's logs always go in its
profile's `log` folder (`docs/architecture.md`, "Settings"). On the desktop a player can pick any folder for them.
On Android the folders a player can pick are the system's documents, which Mudlet's files cannot write to by a path,
and a folder of the profile's goes with it when the profile is backed up or deleted.

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
- The app draws its own button's colour where the desktop draws its native button, with no background colour in the
  sheets and its native frame left (`docs/phase-f.md`, "Button bars and commands"), under their `background-image`
  where they set one. Qt may also draw that native frame
  instead of a background a sheet sets without a border, as some desktop styles do, where the app draws the sheet's
  background. What the button looks like then depends on the desktop's style, and the sheet asked for the background.

## The emergency stop

Desktop Mudlet's emergency stop, a button under the main console that stops a profile's triggers, aliases, timers, key
bindings and events in one click, is a switch on the game sheet's card for the game in front, two taps away, and the
game title shows a stop sign while it holds (`docs/architecture.md`, "The emergency stop"). The row below a phone's
input line has room for a few controls only, and a button there that one stray tap turns on would leave the game's
scripts silently off; the stop sign keeps the state in sight, and TalkBack reads it with the title. What it stops, and
that it holds across a reconnect but not once the profile closes, are the desktop's.

## Recording a replay

Desktop Mudlet's replay recording button, under the main console, is a switch on the game sheet's card for the game in
front, as the emergency stop is, for the same reason (`docs/architecture.md`, "Recording a replay"). The desktop leaves
a recording in the profile's log folder for the player to find; the app, whose folders the player cannot browse, offers
the last one to Android's share sheet. The file, its name and what it holds are the desktop's.

## Playing a replay

Desktop Mudlet's replay button is on the game sheet's card, and its replay toolbar a bar under the input line while a
replay plays (`docs/architecture.md`, "Playing a replay"). The bar has no clock: libmudlet does not say when it moves,
and asking every second would keep the engine awake through a whole replay for a number TalkBack would read over the
game's text. Pause, speed and stop are the desktop's, from 1 to 1024 times.

## Timestamps and the lag

Desktop Mudlet's timestamp button and latency box, under the main console, are a switch and a line on the game sheet's
card for the game in front, for the reason the emergency stop is (`docs/architecture.md`, "Timestamps and the lag").
The desktop wraps a line in its buffer before it draws it, so each row of a long line has a timestamp of its own; the
app wraps what the engine sends as one line, so its rows after the first start under the line's text, not under a
time. The times, their format and colour, and the lag's figures are the desktop's.

## Selecting the game's text

Desktop Mudlet selects the main console's text by the character, from where a drag starts to where it ends; the app
selects whole lines, from the line a long press or TalkBack's "Select from here" started on to the one a tap or "Select
to here" took it to (`docs/architecture.md`, "Copying the game's text"). A character under a fingertip in monospaced
game text is a fraction of the finger's width, and dragging a handle across a list that scrolls as new lines arrive
lands short or long more often than not; a line is a target a thumb and TalkBack both hit. The lines are copied as the
desktop copies a selection of whole lines, up to 200,000 characters at once: Android hands the text to the clipboard or
the share sheet in one transaction, which more would overflow, and the desktop has no such limit. The desktop's "Copy
as HTML" and "Copy as image" are not offered yet.

## The movement pad

The game screen has a movement pad below its bottom row: a key for each of the eight compass directions and for up,
down, in and out (`docs/architecture.md`, "The movement pad"). Desktop Mudlet has no such control. Its players walk
with the keyboard's number pad, which a phone does not have, and typing a direction for every room with the on-screen
keyboard up is the slowest part of playing on one. The pad adds nothing a script or a game can see: a compass key
presses the profile's key binding on the number pad key desktop players walk that way with, where the profile has one,
and otherwise sends the direction's short word as if typed, as the keys with no number pad key always do. It is the
app's own, shows for every game, and stays away with a hardware keyboard, with which the app walks as the desktop does.

## The Sound button

The game screen's Sound button, desktop Mudlet's toolbar "Mute all media", is there only once the game has played a
sound, music or video since it loaded, or while either mute is on (`docs/architecture.md`, "The Sound button"). The
desktop's toolbar always has it. The row below a phone's input line has room for a few controls only, which a button
for a game that never plays a sound would take from the pinned commands. The two mutes are on each game's Sound
settings page at all times, and are Mudlet's own, shared by every game, as on the desktop.

## Calls the user may overrule

Differences the app chose rather than had to, which the user may decide otherwise:

- One web page a script opens each time the player comes back to the app, where the desktop opens every page a script
  asks for ("A script opens a web page").
