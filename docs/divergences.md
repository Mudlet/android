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
  link the player sees and cannot follow is a fault, not a choice a script makes.
