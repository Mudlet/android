# Mudlet for Android: what it still needs

Written 2026-11-17 against Mudlet/android main 5a08eff (protocol 27, Mudlet patches 0001-0020) and Mudlet/Mudlet
`development` 6d49337. Every "on main" claim below was checked in the code, not only in the docs. Update the table
at the end when a lane's pull request opens or merges.

## Summary

**What a player can do today.** Add a game, or pick one of Mudlet's listed games, and connect, with TLS and a stored
password (Android KeyStore). Play in colour with scrollback, a jump-to-bottom button, history, Tab completion, greyed
suggestions, a searchable history and pinned commands. Keep several games open at once, switch with the game pill, a
swipe or Ctrl+Tab, with unread badges and per-game notifications. Leave the app and keep playing in the background
(foreground service; the engine restores itself after a kill). Use TalkBack throughout: new text read aloud, Lua
`announce()`, a "tell games I use a screen reader" switch. See what scripts draw: mini consoles, user windows, labels,
gauges, text boxes, MXP frame tabs, button bars and the Extensions menu. See the map behind the text or in front, pan,
zoom, find a room, walk to it, and edit rooms and exits by touch; download the map a game offers. Edit triggers,
aliases, scripts, timers, keys and buttons, with a Lua field that checks the code on Apply. Change Mudlet's own per-game
settings (Display, Accessibility, Input, Connection, Security, Advanced) and the app's own (text size, colours from the
wallpaper). Read the licences in About. Testers get a nightly PTB.

**What is missing for a first public release** (lanes marked "release blocker"):
1. A stable app on Google Play: its own listing, a release workflow, signing, the Play forms (lane 1). Several answers
   only the user can give.
2. Links in the main console: the game's MXP and OSC 8 links show but cannot be tapped (lane 2). Script windows'
   links already work.
3. A way not to lose everything: the app turns off Android backup, and has no export or import, so a new phone or a
   reinstall loses every profile, script and map. Moving a profile to and from desktop Mudlet is the same feature
   (lane 3).
4. Real phones: the on-device checklist has never been run end to end; screen-off timers, battery savers and TLS on a
   device are unproven (lane 4).
5. A way to report a problem with logs attached, since the app sends nothing by itself (lane 5).
6. What is in flight and is needed too: TLS certificate and secure-port questions, the error console and
   logging, the package manager screen, mute for game sounds.

**What comes after** (lanes marked "expected by desktop players", then "nice later"): hardware keyboards and key
bindings; copying and searching the game's text, timestamps and lag; the remaining display and input settings (server
encoding above all); sound, music and text to speech checked and finished; map files, map settings and area tools;
the editor's variables, search and import/export; Lua functions that need the app (open a web page, clipboard, file
picker); emergency stop and replays; telnet links and profile list extras; tablets and Chromebooks; performance on slow
phones; translations live; help; changes to lines already shown.

## How to read the lanes

- **Mark:** "release blocker", "expected by desktop players", "nice later", or "not for a phone" (with the reason).
- **Engine:** what Mudlet (`THeadlessSession`, `src/headless/THeadlessSession.h` on `development`) already offers, and
  what needs a protocol frame (`docs/protocol.md`, `SocketBridge`, `Protocol.kt`, `protocol_client.py` in one commit) or
  a Mudlet patch (`scripts/mudlet-patches/00NN-*.patch`, then an upstream PR).
- **Numbers:** protocol versions and patch numbers follow landing order. At the rebase before hand-back a lane takes
  main's protocol + 1 and main's highest patch + 1 (main now: 27 and 0020).
- **Shared files** that make lanes wait for each other: `protocol/.../Protocol.kt`, `docs/protocol.md`,
  `native/enginehost/SocketBridge.cpp`, `MudletProfile.cpp`, `ui/game/GameScreen.kt`, `ui/game/Console.kt`,
  `ui/AppNavigation.kt`, `ui/settings/SettingsCatalogue.kt`, `res/values/strings.xml`. Protocol changes are small and
  local, so two lanes can run beside each other if each keeps its protocol commit small and rebases; two lanes on
  `Console.kt` or the map files cannot.

## In flight (November 2026)

| Lane | What | Touches |
|---|---|---|
| IF1 Script editor slices 4 and 5 | Slice 4, timers, keys and buttons, merged (#142, protocol 27, patch 0020); slice 5, a trigger made from a console line, merged (#148) | `ui/scripts/`, `ScriptItemsClient.kt`, patch 0020 |
| IF2 Map frame cost, part 5 | The map frame's cost with the text behind it, merged (#144) | `AppMapClient.kt`, `protocol/MapBehindText.kt`, `TextBehindMap.kt` |
| IF3 Scripted UI | What remains of Phase F | `ui/game/Script*.kt`, `ScriptWindowsClient.kt` |
| IF4 Package manager | Installed packages, install from file and URL, uninstall: the engine side is #145, the screen follows; then modules and the package repository browser as later PRs of the same lane | new `ui/packages/`, protocol, `MudletProfile.cpp` |
| IF5 Connection security | The game's secure-port offer (`secureConnectionOffered`), a bad certificate's facts and the per-game choices; a proxy if small | `ui/game/`, settings Security page, protocol |
| IF6 Error console and logging | Lua errors as a screen; log to file (HTML or text) with desktop's Logging options, shared through the share sheet; the debug console if small | new `ui/errors/`, settings, protocol |
| IF7 Mute game sounds | A mute button that appears once a game plays a sound; desktop's three mute choices in settings; first finds out whether sound plays at all: the engine side is #143, the button follows | `ui/game/`, settings, protocol (`soundsMutedChanged`, `setSoundsMuted`) |
| IF8 Script editor design | Pick of designs A, B or C | - |
| IF9 Upstream pull requests | Mudlet/Mudlet#12962 to Mudlet/Mudlet#12965, Mudlet/Mudlet#12967 and the login fix: the user's sign-off, and a review finding on Mudlet/Mudlet#12965 | Mudlet/Mudlet |

## Lanes, in priority order

### Lane 1. A stable release on Google Play — release blocker

**Aim.** Ship "Mudlet" (`org.mudlet.mudlet`) on Play beside the existing PTB, from a tagged commit, with the Play forms
filled in from what the app really does.

**Desktop it follows.** Mudlet's release flow (`.github/workflows/create-github-release.yml`, `CI/set-build-info.sh` in
Mudlet/Mudlet): a tagged release beside the nightly PTB, the same version numbers.

**What exists.** `ptb.yml` builds, signs and uploads the PTB AAB, with R8, size budgets, the smoke test, the source
bundle and the native symbols Play uses for crash reports (`docs/ptb.md`). `release.yml` (#153) does the same for the
stable app from a tag `android-v<version>`, through the build both share (`release-build.yml`), and is a dry run
everywhere else; `docs/release.md` has its steps and the one-time Play setup only the maintainers can do. It builds a
pinned Mudlet `development` commit (`scripts/release-mudlet-ref.txt`), not a release tag: Mudlet 5.1.1 has no
headless engine. `docs/store-listing.md` has both listings' text and Play forms, and `docs/privacy-policy.md` covers
both apps. The first PTB upload failed because the Play app does not exist yet (run 44164830301). No engine or
protocol work.

**Pull requests.**
1. Done (#153, with the open testing track by default and a pinned Mudlet commit, as "What exists" says).
   `release.yml`: on a tag `android-v*` (or by hand), build from Mudlet's release tag, not `development`, sign with the
   stable upload key, upload the AAB to Play's internal track, and attach APKs and the source bundle to a GitHub
   release. Reuses `build-release.sh` and the PTB's jobs; dry run on every other branch, as the PTB does. Version: name
   from the tag, code from the same date scheme as the PTB so the two never clash.
2. Done (#153). A short `docs/release.md`: how to cut a release, promote internal to production with a staged rollout,
   roll back, and the one-time Play setup for the stable app (a copy of the PTB's steps with the stable names).
3. Store listing and privacy policy final text once the user answers the questions below; Data safety answers checked
   against lanes 3 and 5 (backup, problem reports) before they land.
4. For GitHub APK installs of the PTB: a "newer PTB available" notice, only
   if the user wants it.

**Files.** `.github/workflows/release.yml` (new), `scripts/build-release.sh`, `app/build.gradle.kts`, `docs/release.md`
(new), `docs/store-listing.md`, `docs/privacy-policy.md`, `docs/ptb.md`. Runs beside every other lane.

**Size.** M. **Depends on.** Nothing to build; publishing waits on the user's Play Console setup.

**Questions only the user can answer.**
- Play developer account: organisation (needs a D-U-N-S number, no 14-day test) or personal (12 testers for 14 days
  first)? *Recommend: organisation, as `docs/ptb.md` says.*
- The stable app's first version: "0.x, early access" on the open testing track first, or production straight away?
  *Recommend: open testing of the stable app for a few weeks, production when lanes 2 to 5 are in.*
- The contact email Play shows, the privacy policy's public URL, the category (Games, Role Playing), and the right to
  show games' text in screenshots (`docs/store-listing.md`). *Recommend: the Mudlet
  team's address; the GitHub URL of `privacy-policy.md`; Games, Role Playing; screenshots of a game whose admins
  agree, or of the bundled tutorial text.*
- The launcher icon's licence (the Mudlet logo under GPL-2.0+ through Mudlet/Mudlet). *Recommend: confirm it, as the
  PTB already ships it.*

### Lane 2. Links in the main console — release blocker

**Aim.** Tap a link in the game's text to follow it; long press, or TalkBack's action, for its menu; as script
windows' links already work.

**Desktop it follows.** `TTextEdit::mousePressEvent` and its link menu; MXP `<SEND>`, OSC 8, `echoLink()`,
`insertLink()`.

**Engine.** Everything is there: `link()`, `followLink()`, `linksChanged` upstream; `getLink`, `followLink`,
`linksChanged` in the protocol since version 5. The lines already carry their link runs. App only: the main console
never builds `LineLinks`: `Console.kt` calls `ConsoleLine` without them (`docs/architecture.md`, "Script windows' text:
links and the screen reader", says so).

**Pull requests.**
1. Main console links: reuse `LineLinks` (`ConsoleLine.kt`) as `ScriptWindowsArea.kt` builds it, the link cache from
   `ScriptWindowsClient` moved to where both use it, `linksChanged` refreshing what is cached; a `prompt:` link puts its
   text in the input line, refused while input is hidden (the labels' rule, `docs/divergences.md`). UI tests against
   `FakeEngine`, with the accessibility checks; a bite branch.
2. Only if needed after 1: a visited style refresh for links whose state changes (`linksChanged` already says which).

**Files.** `ui/game/Console.kt`, `ui/game/ConsoleLine.kt`, `ui/ProfileSession.kt`, `ui/ScriptWindowsClient.kt`, tests.
Conflicts with lane 6 (same files): lane 2 first.

**Size.** S. **Depends on.** Nothing. **Questions.** None.

### Lane 3. Profiles: backup, move to and from desktop, copy — release blocker

**Aim.** A player never loses their profiles to a new phone or a reinstall, and can take a profile from desktop Mudlet
to the phone and back.

**Desktop it follows.** The profile folder, `<config>/profiles/<name>/` (`current/*.xml`, `map/*.dat`, `log/`, media,
packages), which desktop players already copy by hand between computers; the connection dialog's Copy and "Copy
settings only" (`dlgConnectionProfiles.cpp`).

**Engine.** The profiles are in the engine's config dir, inside the app's own files, so the app process can read them.
A profile must be saved and closed, or at least saved, before it is zipped: `closeProfile` and `setAppState`'s save
exist; a control-socket request that saves one profile and answers when done is new (protocol frame), and so is "a
profile folder was added, list again" (`listProfiles` already re-reads the folder). No Mudlet patch expected.

**Pull requests.**
1. Export a profile as a `.zip` through Android's file picker (Storage Access Framework, no storage permission): saved
   first, passwords never in it, laid out as desktop's folder so desktop can open it.
2. Import a `.zip` (from the app or from desktop's profile folder zipped): checked before anything is written (paths
   inside the folder only, size limits, a `current/` save present), a name already taken offered a new name, as
   desktop's copy does. Also "Open with Mudlet" for a shared `.zip`.
3. Copy a profile in the app, whole or settings only (desktop's two buttons).
4. Android backup and device transfer of profiles, passwords and logs left out, if the user agrees (question below):
   `data_extraction_rules.xml`, `allowBackup`, the privacy policy and Data safety text.

**Files.** `ui/profiles/` (list and editor), new `data/ProfileArchive.kt`, `native/enginehost/ControlBridge.cpp`,
`ProfileStore.cpp`, `Protocol.kt`, `docs/protocol.md`, `AndroidManifest.xml`, `res/xml/data_extraction_rules.xml`,
`docs/privacy-policy.md`. Runs beside lanes 1, 2, 4, 5.

**Size.** L (four PRs of S to M). **Depends on.** Nothing.

**Question only the user can answer.**
- Let Android back profiles up to the player's Google account and move them to a new phone (passwords and logs left
  out)? *Recommend: yes for both. Google's backup is the player's own and end-to-end encrypted for device transfer;
  the developer receives nothing, so Data safety stays "no data collected". Without it, a lost phone loses every
  script unless the player exported it.*

### Lane 4. Real phones and background limits — release blocker

**Aim.** Prove on real phones what CI cannot: TLS, sound, TalkBack, the keyboard, and a game that stays connected
with the screen off for an hour; then fix what fails.

**Desktop it follows.** None: phone only.

**What exists.** The on-device checklist (`docs/architecture.md`, "On-device checklist"). The engine runs in a
`specialUse` foreground service, holds no wake lock, and was tested for Doze only on an emulator
(`docs/decisions/hosting-results/c-device.md`). Play needs the foreground-service declaration (and maybe a video).

**Pull requests.**
1. A short "device pass" page: which checklist items each phone class needs (a low-end Android 9 phone, a mid-range
   Samsung or Xiaomi with its own battery saver, a Pixel on Android 16, a tablet or Chromebook), and a log template.
   The runs are a person's (user task).
2. Fixes from the runs, each its own PR.
3. Background: measure Mudlet timers and the game's idle timeout with the screen off on a real phone. Only if timers
   stall: a per-game "Keep scripts' timers running with the screen off" switch (a partial wake lock while connected),
   off by default. When Android or a vendor's battery saver kills the engine: a one-time card after the restore that
   explains and opens the battery settings page (no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which Play restricts).
4. The Play foreground-service declaration's text and video steps in `docs/store-listing.md`.

**Files.** `docs/` (device pass), `engine-service/EngineService.kt`, `EngineSession.kt`, `ui/game/` (the card),
`data/Settings.kt`. Runs beside lanes 1 to 3.

**Size.** M, most of it human time. **Depends on.** A person with phones.

**Question only the user can answer.**
- Who runs the device pass, on which phones? *Recommend: the user on their own phone for the first half of the
  checklist; PTB testers for the rest, through a pinned GitHub issue with the template.*

### Lane 5. Report a problem — release blocker

**Aim.** A player who hits a problem can send the developers what is needed to fix it, by their own choice, with
nothing sent automatically.

**Desktop it follows.** Desktop's crash reporter and its policy (Special Options, "crash report policy",
`comboBox_crashReportPolicy`); Help > "Report an issue" (`dactionReportIssue`).

**What exists.** No crash or log reporting; Play's Android vitals give crash and ANR reports from Play installs, with
R8's mapping and native symbols already in the AAB (`docs/ptb.md`). GitHub installs give nothing.

**Pull requests.**
1. About > "Report a problem": collects the app's and the engine's recent log (logcat of both processes, passwords and
   hidden input already never logged), the versions and the device, shows it, and shares it with the share sheet or
   opens a prefilled GitHub issue. Nothing leaves the phone unless the player sends it.
2. After an engine crash the app noticed (the restore path knows), a one-time offer to report it, with the same screen.

**Files.** `ui/about/`, new `ui/report/`, `engine-service/` (its log buffer), strings. Runs beside everything.

**Size.** S-M. **Depends on.** Nothing.

**Question only the user can answer.**
- Automatic crash reports (as desktop's Sentry) or only what the player sends? *Recommend: only what the player sends,
  plus Play vitals, for the first release; it keeps the privacy policy's "Mudlet collects nothing". Revisit with an
  opt-in once there are users.*

### Lane 6. Reading the game's text: copy, search, timestamps, lag — expected by desktop players

**Aim.** Select and copy the game's text, search the scrollback, see timestamps and the game's lag, and keep more
scrollback, as desktop players do.

**Desktop it follows.** `TTextEdit` selection, copy and "Copy as HTML"; the console's search bar (`TConsole`, F3 with
`f3SearchEnabled`); `dactionToggleTimeStamp`; the lag box in the status bar; Main display's "console buffer size".

**Engine.** `setTimeStampsShown()`, `timeStampsShownChanged`, `latencyChanged` upstream; `lineShown` carries
`arrivalMSecs`, but the protocol's `line` has no time (protocol frame: `time` on `line`, a `latency` status, a
`setTimeStamps` request). Scrollback length is the app's own ring (2000 lines today).

**Pull requests.**
1. Select and copy: a line's Copy and Share in its long press menu, and TalkBack's action (#171); then a selection
   across lines, with handles to extend it.
2. Search the scrollback: a search bar from the top bar or Ctrl+F (F3 with the setting), next and previous, matches
   highlighted, the count read once.
3. Timestamps: protocol `time` on `line` and `setTimeStamps`; a switch on the Display page and in the top bar's menu.
4. Lag: the network and processing lag in the game sheet, as desktop's box.
5. Scrollback length: a Display setting (desktop's buffer size, bounded for the phone's memory).

**Files.** `ui/game/Console.kt`, `ConsoleLine.kt`, `GameScreen.kt`, `GameSheet.kt`, `ProfileSession.kt`, settings,
protocol for 3 and 4. After lane 2.

**Size.** M. **Depends on.** Lane 2 (same files). **Questions.** None.

### Lane 7. Hardware keyboards and key bindings — expected by desktop players

**Aim.** On a Chromebook, a tablet with a keyboard or a phone with one, the profile's key bindings work (numpad
walking, F-keys), and the desktop's shortcuts and the accessibility key work.

**Desktop it follows.** `KeyUnit` and `TKey`; the Shortcuts tab (main window shortcuts); Accessibility's
`caretShortcut` (a key that moves between the input line and the console); Input's "run all key bindings"
(`checkBox_runAllKeyBindings`).

**Engine.** `THeadlessSession::pressKey(key, modifiers, text)` returns whether a binding took it. Not in the protocol.
The app must decide at once whether a key goes to the input line, so it needs the list of bound key combinations up
front: a Mudlet patch adding `keyBindings()` and a `keyBindingsChanged` signal (desktop reads `KeyUnit` directly), then
protocol frames `pressKey` and `keys`. The script library already edits key items (#142).

**Pull requests.**
1. Mudlet patch and protocol: `keys` (the bound combinations) and `pressKey`.
2. App: keys the profile binds go to the engine, the rest to the input line; numpad and F-keys; a test with a
   `tempKey()` bound to numpad 8 sending "north".
3. Desktop shortcuts mapped to the app's screens (Alt+E editor, Alt+P settings and the rest from `mudlet.cpp`) and the
   caret key, with the Accessibility setting.

**Files.** New patch, `MudletProfile.cpp`, `SocketBridge.cpp`, `Protocol.kt`, `docs/protocol.md`,
`ui/game/InputLine.kt`, `GameScreen.kt`, settings. Beside lanes 1 to 5.

**Size.** M. **Depends on.** Nothing. **Questions.** None.

### Lane 8. Display and input settings, round 2 — expected by desktop players

**Aim.** The settings desktop players reach for that the app lacks, above all the server's text encoding, without
which many non-English games show garbage.

**Desktop it follows.** `dlgProfilePreferences.cpp` / `profile_preferences.ui`: General (`comboBox_encoding`,
`mAlertOnNewData`, `checkBox_enableOSC8Hyperlinks`), Input line (`command_separator_lineedit`,
`checkBox_highlightHistory`, `disable_password_masking_checkbox`), Main display (font family, wrap indent and hanging
indent), Color view (the 16 ANSI colours, foreground, background, command colours,
`checkBox_allowServerToRedefineColors`, reset), Accessibility (`checkBox_enableBlinkText`).

**Engine.** `getSettings` and `setSettings` (patch 0011, `THeadlessSession::settings()`) carry the keys `getConfig()`
knows; encoding is `setServerEncoding()` and the colour palette is `Host` state, so patch 0011 grows (or upstream's
`getConfig` gains the keys). Fonts: the engine reports `fontFamily`; a font family choice needs bundled fonts (none
today: `docs/architecture.md`, "Gaps").

**Pull requests.** One per page, each small:
1. Connection: server encoding, from Mudlet's list.
2. Input: command separator, highlight history, show passwords as typed.
3. Colours: the console palette, from Mudlet's colours, with "let the game change colours" and reset.
4. Display: OSC 8 links, blinking text, notify on new text while in the background.
5. Fonts: bundle Mudlet's own console fonts and offer the choice (APK size budget checked).

**Files.** `ui/settings/SettingsCatalogue.kt`, `data/Settings.kt`, `ui/settings/SettingsPageScreen.kt`, patch 0011,
`ui/AnsiText.kt` for the palette, strings. One settings PR at a time (same catalogue file); beside non-settings lanes.

**Size.** M. **Depends on.** Nothing; IF5, IF6 and IF7 also add settings, so rebase between them.

**Questions.** None (desktop's names and defaults).

### Lane 9. Sound, music, text to speech and video — expected by desktop players

**Aim.** A game's and a script's sounds and music play as on desktop, through Android's media volume, and the Lua text
to speech functions speak; video waits.

**Desktop it follows.** `TMedia` (MSP, MCMP/`Client.Media`, `playSoundFile`, `playMusicFile`), `TAudioMixer`,
`TMediaVoice`; `TTextToSpeech` (`ttsSpeak` and friends); `purgeMediaCache` (Special Options).

**Engine.** All in Mudlet, running in the engine process with Qt Multimedia and Qt Speech, which the APK ships; muting
is IF7. Unknown until IF7 reports: whether sound actually plays from the engine's service process, and through which
Android stream. TTS from a service is expected to work through Android's TTS. Video (`playVideoFile`) needs a widget:
not in the headless engine.

**Pull requests.** Shaped by IF7's finding:
1. Whatever makes sound play, if IF7 found it silent and too large for its lane: audio attributes (media stream),
   audio focus (duck under a call or navigation, pause on headphones unplugged), playing on in the background.
2. TTS checked on a device and in a host test; when TalkBack is on, Lua speech queued so it does not cut TalkBack off
   (decided in that pull request).
3. "Purge the media cache" on the Advanced page.

**Files.** `native/enginehost/` (audio set-up), `engine-service/`, settings. Beside most lanes.

**Size.** M. **Depends on.** IF7 (its finding). **Questions.** None yet.

### Lane 10. Map files, map settings and area tools — expected by desktop players

**Aim.** Load and share map files, keep the map's backups, set how the map draws, and manage areas, as the desktop's
Mapper tab and its map editor allow.

**Desktop it follows.** Preferences, Mapper tab (`pushButton_loadMap`, `pushButton_saveMap`, `comboBox_mapHistory`,
`pushButton_loadHistoricMap`, `pushButton_deleteMap`, `pushButton_copyMap` to other profiles,
`comboBox_mapFileSaveFormatVersion`; view options: room size, exit size, borders, upper and lower levels, area exit
arrows, default area, symbol font, map style; Mapper colours and the player room style); `T2DMap`'s area menu (create,
rename, delete, move rooms to an area); `mappingScriptMissing` (desktop offers a mapping script).

**Engine.** `saveMap()`, `loadMap()`, `saveJsonMap()`, `loadJsonMap()` in Lua; `mapProgressStarted/Changed/Finished`
and `cancelMapOperation()` upstream (the download already uses them); `mappingScriptMissing`; the view options are
`getConfig()` keys or `Host` fields (patch 0011 grows). Area create, delete and move need frames like the room edits
(patch 0013's pattern).

**Pull requests.**
1. A Map settings page: the view options and colours.
2. Map files: import a `.dat`, `.json` or `.xml` map through the file picker, export and share, with progress and
   Cancel (the download's bar).
3. Backups: the dated map saves desktop keeps, load one, delete one; copy the map to another profile.
4. Areas: create, delete, move rooms to an area (frames and a patch).
5. The mapping-script offer: when the map opens without a mapper, offer Mudlet's generic mapper through the package
   manager (after IF4).

**Files.** `ui/game/AppMap.kt`, `MapEdit.kt`, `ui/AppMapClient.kt`, `ui/MapRooms.kt`, settings, patches 0011 and 0013
(or a new one), protocol. After IF2 (map files).

**Size.** L. **Depends on.** IF2; 5 on IF4. **Questions.** None.

### Lane 11. The editor beyond items — expected by desktop players

**Aim.** The editor's other tools: the variables view, search in patterns and Lua, moving items between folders,
import and export of items, a colour pattern picker.

**Desktop it follows.** `dlgTriggerEditor`: the Variables view (`VarUnit`, `TVar`), the search box over names, patterns
and code, drag to another folder, Export and Import (XML, `XMLexport`, `XMLimport`), the colour trigger dialog, the
statistics button.

**Engine.** `moveScriptItem` exists (no UI). Search needs patterns and code in `scriptItems` or a search request
(patch 0015 grows). Variables need a patch (desktop's variable tree reads `VarUnit` from the GUI). Export and import go
through `XMLexport` and `XMLimport`, which libmudlet has.

**Pull requests.** Move to a folder (app only); search (patch and UI); export and import (file picker, patch); the
variables view, read first, then editing (patch); the colour picker.

**Files.** `ui/scripts/`, `ScriptItemsClient.kt`, patch 0015 or a new one, protocol. After IF1 and IF8.

**Size.** L. **Depends on.** IF1, and the design the user picks (IF8). **Questions.** The design pick (IF8).

### Lane 12. Lua functions that need the app — expected by desktop players

**Aim.** Scripts that open a web page, use the clipboard or ask for a file work on the phone.

**Desktop it follows.** `openUrl()`/`openWebPage()` (`TNullAppFrontend::openWebPage` hands it to
`QDesktopServices`), `getClipboardText()`/`setClipboardText()`, `invokeFileDialog()`
(`TNullAppFrontend::getOpenFileNameAsync`, which answers "nothing" today).

**Engine.** The engine is a service with no window: Android lets only the app in front read the clipboard, and a
service should not start activities. So the engine host registers a URL handler (`QDesktopServices::setUrlHandler`) and
passes URLs, clipboard writes and reads, and file requests to the app over new protocol frames; the file dialog needs a
Mudlet patch (a signal in place of the null answer), then the app's file picker copies the file into the profile.

**Pull requests.** 1. Open a web page (http, https, mailto only; never while the app is in the background).
2. Clipboard set and get. 3. File picker for `invokeFileDialog()` (patch).

**Files.** `native/enginehost/` (`MudletProfile.cpp`, `main.cpp`), protocol, `ui/ProfileSession.kt`, new
`ui/game/ScriptRequests.kt`. Beside most lanes.

**Size.** M. **Depends on.** Nothing.

**Question only the user can answer.**
- May a script open a web page with no tap from the player, as desktop allows? *Recommend: yes while the game is in
  front, as desktop; refused in the background.*

### Lane 13. Emergency stop and replays — expected by desktop players (stop), nice later (replays)

**Aim.** Stop every script at once when one misbehaves; record a session and play it back.

**Desktop it follows.** `dactionToggleEmergencyStop`; `dactionToggleReplay`, `dactionReplay` and the replay toolbar
(pause, faster, slower).

**Engine.** `setScriptsStopped()`, `scriptsStopped()`; `setReplayRecording()`, `pauseReplay()`, `resumeReplay()`,
`stopReplay()`, `changeReplaySpeed()`, `replayStarted/SpeedChanged/Finished` upstream. Protocol frames only.

**Pull requests.** 1. Emergency stop in the game sheet, state shown on the pill (S). 2. Record a replay and share the
file (S). 3. Play a replay from a file, with pause and speed (M).

**Files.** Protocol, `MudletProfile.cpp`, `ui/game/GameSheet.kt`, `GamePill.kt`. Beside lanes not on the game sheet.

**Size.** M. **Depends on.** Nothing. **Questions.** None.

### Lane 14. Telnet links and profile list extras — expected by desktop players

**Aim.** A `telnet://` link on a game's website opens the app; profiles can be opened without connecting, renamed, and
given an icon, as on desktop.

**Desktop it follows.** `mudlet::handleTelnetUri`, `connectFromGameLink`; `dlgConnectionProfiles`: "Offline" (load
without connecting), rename, custom icon and colour.

**Engine.** `openProfile` with `connect` false exists (used for passwords). Rename needs `ProfileStore` to move the
folder while the profile is closed (the passwords' associated data changes too). No patch.

**Pull requests.** 1. `telnet://host:port` intent filter: a known game opens, an unknown one opens the editor filled in.
2. Open offline (to edit scripts without connecting). 3. Rename a closed profile. 4. Profile icon.

**Files.** `AndroidManifest.xml`, `ui/MainActivity.kt`, `ui/profiles/`, `data/PasswordStore.kt`, `ProfileStore.cpp`.
After lane 3 (same profile screens).

**Size.** M. **Depends on.** Lane 3. **Questions.** None.

### Lane 15. Tablets, Chromebooks and foldables — expected by desktop players

**Aim.** Use a large screen as one: map and text side by side, a mouse that right-clicks and scrolls, windows that
resize freely.

**Desktop it follows.** The main window's docks (map beside the console).

**What exists.** A resizable phone layout, list and detail panes on expanded screens, the x86_64 APK for Chromebooks.

**Pull requests.** 1. On an expanded screen, the map docked beside the console (a setting, as "map behind the text").
2. Mouse: right click opens a link's or a room's menu, the wheel zooms the map and scrolls the console, hover shows a
link's tooltip. 3. A Chromebook and an Android desktop-windowing pass on the device checklist.

**Files.** `ui/game/GameScreen.kt`, `AppMap.kt`, `Console.kt`. After lanes 2, 6 and 10's first PR.

**Size.** M. **Depends on.** Lanes 2 and 6. **Questions.** None.

### Lane 16. Performance on a slow phone — expected by desktop players

**Aim.** The app starts fast and stays smooth on a low-end phone with two games open.

**What exists.** Map frames measured and cut (IF2); flood handling measured on the host; memory per profile estimated
at 15-25 MB (`docs/architecture.md`, "Memory per profile"). The release already carries the Baseline Profiles Compose
and AndroidX ship, which AGP merges into the APKs and the AAB (`ptb.yml` checks that they are there); there is none
of the app's own code.

**Pull requests.** 1. Measure on a low-end phone: cold start, the game screen under a flood, memory with two and three
games; a cap on open games if memory needs one. 2. A Baseline Profile of the app's start-up and game screen: a
`:baselineprofile` test module (the `androidx.baselineprofile` plugin, which adds build types beside `smoke`), an
x86_64 release build with the engine staged, and an emulator job of about 40 minutes whose generator reaches the game
screen through the profile editor against `fake_telnet.py`. The generator changes with each game screen pull request,
so this waits until the game screen settles and start-up is measured on a phone. 3. Fixes the measurements call for.

**Files.** New `:baselineprofile` module, `.github/workflows/ci.yml`, `app/build.gradle.kts`. Beside most lanes.

**Size.** M. **Depends on.** A low-end phone (user or tester). **Questions.** None.

### Lane 17. Translations live — expected by desktop players

**Aim.** Players read the app in their language, picked per app as Android 13 and later allow.

**Desktop it follows.** Crowdin for Mudlet's translations; General's "language" (`comboBox_guiLanguage`).

**What exists.** Every string in `strings.xml` with translator comments, `crowdin.yml`, the pseudo-locale tests. No
Crowdin project for the app yet. The engine's own messages are English.

**Pull requests.** 1. `localeConfig` and the per-app language list, generated from the translated locales. 2. The
first translations' import once Crowdin has them.

**Files.** `app/build.gradle.kts`, `res/xml/locales_config.xml` (new), `AndroidManifest.xml`. Beside everything.

**Size.** S. **Depends on.** The Crowdin project.

**Question only the user can answer.**
- Set up the app in Mudlet's Crowdin (a maintainer's account)? *Recommend: yes, as a second project beside desktop's,
  after the first stable release's strings settle.*

### Lane 18. Help and first steps — nice later

**Aim.** A new player finds the manual, and a scripter sees what a Lua function does while typing it.

**Desktop it follows.** Help menu: Manual (the wiki), the UI tour (`dactionUiTour`), Changelog; the editor's function
tooltips.

**Pull requests.** 1. Help in About and the editor: the wiki's manual and API pages opened in the browser. 2. Each
function's description in the Lua field's suggestions (Mudlet's docs, synced like `functions.json`). 3. A short first
run tour of the game screen, skippable.

**Files.** `ui/about/`, `ui/scripts/LuaField.kt`, `scripts/sync-mudlet-games.py`, assets. After IF1.

**Size.** M. **Depends on.** IF1, IF8. **Questions.** None.

### Lane 19. Lines changed after they were shown — expected by desktop players

**Aim.** A trigger that later changes or deletes an earlier line (`replace()`, `deleteLine()` on a line already shown,
a link restyled) changes it on the phone too.

**Desktop it follows.** `TBuffer` edits redrawn by `TTextEdit`.

**Engine.** Needs upstream API: a stable id per line in `lineShown` and a `linesUpdated` signal (`docs/roadmap.md`,
"Upstream gaps"). Mudlet patch first, then protocol
(`lineUpdated`), then the console.

**Pull requests.** 1. Mudlet patch and upstream PR. 2. Protocol and engine host. 3. The console applies updates.

**Files.** New patch, `MudletProfile.cpp`, `SocketBridge.cpp`, protocol, `ui/game/Console.kt`, `ProfileSession.kt`.
After lanes 2 and 6.

**Size.** L. **Depends on.** Lanes 2 and 6. **Questions.** None.

### Lane 20. A movement pad for touch — nice later

**Aim.** One-thumb walking (the eight compass points, up, down, in, out) for players without a keyboard.

**Desktop it follows.** None directly: desktop players use the numpad (lane 7). The pinned commands are the
closest the app has.

**Pull requests.** One: an optional pad over the quick bar, sending the game's direction commands (from the map's exit
names), TalkBack-named, off by default.

**Files.** `ui/game/QuickBar.kt`, `GameScreen.kt`, settings. After lane 7.

**Size.** S. **Depends on.** Lane 7.

**Question only the user can answer.**
- An app-only control desktop does not have: wanted? *Recommend: yes, off by default; it is the first thing phone MUD
  players ask for.*

## Not doing, and why

- **Discord Rich Presence** (`dactionDiscord`, the Chat tab's Discord options): desktop talks to the Discord app's
  local IPC, which Android does not have; Discord's mobile SDK needs an account link and a developer application, a
  product and privacy decision for later. The Lua functions stay as they are (they answer false).
- **IRC, "Mudlet chat"** (`dactionLiveHelpChat`, `dlgIRC`): a second chat client in a game client; on a phone the
  player has Discord or a browser. About links to Mudlet's Discord and forum instead.
- **MMCP peer chat** (the Chat tab): it listens for incoming TCP connections, which a phone behind a mobile network
  cannot take. Calling out could work later if asked.
- **The 3D map** (`dactionShowMap`'s 3D view): OpenGL widgets, heavy on a phone, and rarely used on desktop.
- **The updater** (`dactionUpdate`, `checkbox_noAutomaticUpdates`): Play updates the app; GitHub PTB installs get a
  notice at most (lane 1, item 4).
- **Spell check** (`groupBox_spellCheck`): Android's keyboard checks spelling already. Lua's `spellCheckWord()` keeps
  working only if dictionaries are added, which is not planned.
- **Speech to text** (`stt.*`): a native recogniser and microphone permission; Android's keyboard has voice typing.
- **Video** (`playVideoFile`): the headless engine has no video widget; would need frames streamed. Later, on request.
- **The MCP server** (Special Options, connecting desktop AI apps): a desktop integration.
- **MultiView, full screen, always on top, the tray icon, detached windows, the app style sheet**
  (`dactionMultiView`, `dactionToggleFullScreen`, `dactionToggleAlwaysOnTop`, `setAppStyleSheet`): desktop window
  management with no phone meaning; the game pill covers several games.
- **The Package Exporter** (`dactionPackageExporter`): authors build packages on desktop; revisit if asked.
- **The Mudlet Tutorial profile**: it teaches the desktop's menus and windows (`docs/divergences.md`).
- **Android below 9 (API 28)**: Qt 6's own minimum; the app is built at that floor already.
- **Editor themes and fonts, text analyzer, ID numbers** (Editor tab): wait for the editor design (IF8).

## Status table

Status is "not started", "in flight", "waiting for the user" or "done". Pull requests as `#nnn` (this repository) or
`Mudlet/Mudlet#nnnnn`, comma separated.

| Lane | Mark | Status | PRs |
|---|---|---|---|
| IF1 Script editor slices 4-5 | expected | done | #142, #148 |
| IF2 Map frame cost part 5 | expected | done | #144 |
| IF3 Scripted UI | expected | in flight: a label's style sheet, a console's colour with its alpha and its scroll bar, scroll boxes, the border and command line colours (engine train 5), a script's small mapper in the corner, and patch 0004, a label's live region, rebased on Mudlet/Mudlet#12937 (engine train 8) merged; label rich text next | #177, #186, #195, #196, #200, #206, #205, #228 |
| IF4 Package manager | release blocker | in flight: the engine side and the screen merged, and a new game's default packages checked against desktop's; modules and the package repository browser next | #145, #163, #218 |
| IF5 Connection security (TLS, secure port, proxy) | release blocker | done: the secure-port and refused-certificate questions (#147, carried by engine train 1), the refused certificate on the Security page, and a profile's SOCKS5 proxy (engine train 7, #223: patch 0042, protocol 47) merged | #157, #178, #223 |
| IF6 Error console and logging | release blocker | in flight: the engine lists Lua errors and takes desktop's log options (engine train 2); the error console screen, its count on the script library's Errors button, the Logging page, and a profile's log files listed in its editor and Logging page, shared or opened, merged; the debug console, if small, next | #150, #182, #170, #203, #211, #222 |
| IF7 Mute game sounds | expected | done | #143, #152 |
| IF8 Script editor design pick | - | waiting for the user | |
| IF9 Upstream PRs to Mudlet/Mudlet | - | waiting for the user | Mudlet/Mudlet#12962, Mudlet/Mudlet#12963, Mudlet/Mudlet#12964, Mudlet/Mudlet#12965, Mudlet/Mudlet#12967 |
| 1 Stable release on Play | release blocker | waiting for the user: the release workflow, the store listing, the PTB's GitHub pre-release published even when Play refuses the upload, and the newer test build notice for a PTB from GitHub merged; the Play forms, signing secrets and the first tag are the user's | #153, #166, #167, #193, #210 |
| 2 Main console links | release blocker | done | #149, #159 |
| 3 Profiles: backup, move, copy | release blocker | done | #151, #158, #215, #212, #224 |
| 4 Real phones and background | release blocker | in flight: the device pass page and the explanation of a stop in the background merged; the pass itself is run on real phones | #155, #160, #194 |
| 5 Report a problem | release blocker | in flight: About > Report a problem and the offer after a crash merged; automatic crash reports, on by default as the user chose, wait for the user's setup | #156, #164, #184 |
| 6 Copy, search, timestamps, lag | expected | in flight: copy or share a line, search, timestamps and lag (engine train 5), the scrollback length (engine train 4) sized to the phone (engine train 6), and a selection across lines merged; Copy as HTML next | #171, #179, #197, #200, #213, #217, #225 |
| 7 Hardware keyboards and keys | expected | done | #157, #181, #200, #216 |
| 8 Display and input settings 2 | expected | in flight: the server encoding and the input line's settings merged, with their Connection and Input pages (engine train 1), and the Colours page, links from the game and blinking text (engine train 5); notify on new text in the background and fonts next | #157, #200 |
| 9 Sound, music, TTS | expected | in flight: desktop's "Clear stored media", a smoke test that a script's sound plays on the media stream with the screen off, and the first speech while text to speech starts (engine train 4) merged; text to speech behind TalkBack and audio focus next | #176, #188, #197 |
| 10 Map files, settings, areas | expected | in flight: the engine side (engine trains 1 and 3), map files with progress and Cancel, the Map settings page (engine train 5), the app's map in a corner of the text, and a corner map's controls behind a button in the map merged; backups, deleting and copying the map in the app, then areas, next | #157, #191, #180, #200, #209, #227 |
| 11 Editor beyond items | expected | in flight: move to a folder, search (engine train 2), the colour pattern picker and the Variables view, read only (engine train 4), merged; export and import, then editing variables, next | #165, #182, #172, #197 |
| 12 Lua functions needing the app | expected | done | #191, #197 |
| 13 Emergency stop and replays | expected / nice later | done | #200 |
| 14 Telnet links, profile extras | expected | in flight: telnet:// links and Open offline merged; rename a closed profile and the profile icon next | #189, #190 |
| 15 Tablets and Chromebooks | expected | not started | |
| 16 Slow phones | expected | in flight: a manual benchmark of a game's spam through the engine and faster line frames (engine train 6) merged; the measurements on a low-end phone next | #213 |
| 17 Translations live | expected | not started | |
| 18 Help and first steps | nice later | in flight: Help in About and the script library, Show changelog, and the game screen's first-run tour merged; function descriptions wait on a source for them | #168, #183, #169 |
| 19 Lines changed after shown | expected | in flight: the patch, protocol and console merged (engine train 5); the patch's upstream pull request next | #200 |
| 20 Movement pad | nice later | done | #214 |
