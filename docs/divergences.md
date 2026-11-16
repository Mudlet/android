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
