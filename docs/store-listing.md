# Google Play store listing

What the Play Console asks for before a release, and the answers for Mudlet, each checked against the code on `main`
in November 2026: the listing's text and graphics, the screenshots and how to take them, the accessibility statement,
the Data safety form and the other App content declarations, for both the Mudlet and the Mudlet PTB listings.
[`release.md`](release.md) and [`ptb.md`](ptb.md) say where in Play Console each goes (step 6 of each one-time setup);
[`privacy-policy.md`](privacy-policy.md) is the privacy policy that Play links to, at
<https://github.com/Mudlet/android/blob/main/docs/privacy-policy.md>.

The listing describes the first usable client ([`roadmap.md`](roadmap.md), Phase E). Until a claim below is true on
`main`, the Mudlet PTB listing leaves it out; "Claims to check" says which ones wait for what. Evidence for Mudlet's own
code is given at Mudlet/Mudlet `development`, commit
[`bfc932d`](https://github.com/Mudlet/Mudlet/tree/bfc932d9e76004d9624b6925b5e0278c17fdae2a), and file:line references
without a repository are to this one. Facts about Mudlet move with Mudlet: check them again when Mudlet's CMake options
or networking change.

## Name and descriptions

- **App name** (30 characters at most): `Mudlet`; the PTB's is `Mudlet PTB` (`app/build.gradle.kts:39-44`).
- **Short description** (80 characters at most; this one is 72):

  ```text
  Play text-based online games (MUDs) with Mudlet's triggers, Lua and more
  ```

- **Full description** (4000 characters at most; this one is 1975):

  ```text
  Mudlet is a client for MUDs: text-based online games, many of them running for decades, where you explore, fight, talk and role-play by reading and typing. This is Mudlet for Android, built on the same engine as Mudlet on Windows, macOS and Linux.

  Play your games
  • Pick a game from Mudlet's list, or connect to any MUD by its address and port, with TLS where the game offers it.
  • Read the game in its colours, from the 16 ANSI colours to 24-bit colour.
  • Type commands with history and word completion; the keyboard stays open after you send.
  • Log in safely: when a game asks for your password, the input line turns into a masked password field.
  • Keep playing in the background: Mudlet keeps your connection and your scripts running while you use other apps, and tells you when the game sends a tell your scripts pick out.
  • If Android stops the engine to free memory, Mudlet starts it again and reconnects.

  Mudlet's engine, in your pocket
  • Triggers, aliases and timers, written in Lua, as on the desktop, and edited on your phone; make a trigger from any line of the game's text.
  • Your game's map from Mudlet's mapper, faintly behind the text or opened on its own.
  • Your game's own packages and Mudlet's built-in Lua functions.
  • GMCP, MSDP, MSSP, MXP and the other protocols games use to send data alongside the text.

  Accessible
  Every control has a label for TalkBack, the connection state is announced as it changes, and every action has a button: nothing needs a gesture. Text follows Android's font size setting.

  Private
  Mudlet has no ads, no analytics and no account. It connects only to the games you choose, and to addresses those games or your scripts ask for. What you type goes to the game.

  Free and open source
  Mudlet is free software under the GNU GPL, made by volunteers. The Android app's source is at github.com/Mudlet/android and Mudlet's at github.com/Mudlet/Mudlet. Report problems and ideas there, or join us at mudlet.org.
  ```

  The PTB's listing starts with this paragraph instead of the first one, as [`ptb.md`](ptb.md) asks:

  ```text
  Mudlet PTB is the nightly public test build of Mudlet for Android: built every night from the latest code, so it has the newest features and the newest bugs. It installs beside Mudlet with its own profiles. Please report what breaks at github.com/Mudlet/android/issues.
  ```

Each line in these blocks is a paragraph, so they paste into Play Console as they are. Count them again after an
edit (characters, line breaks included; `awk` counts characters, not bytes, in a UTF-8 locale):

```sh
LC_ALL=C.UTF-8 awk '/^  ```text$/ { n++; on = 1; c = -1; next } on && /^  ```$/ { print "block " n ": " c; on = 0 }
    on { c += length(substr($0, 3)) + 1 }' docs/store-listing.md
```

### Claims to check

Each claim, and what on `main` makes it true. "Not yet" claims are left out of a listing until the roadmap item lands.

| Claim | On `main` | Evidence, or what it waits for |
|---|---|---|
| Pick a game from Mudlet's list, or connect by address and port, with TLS | Yes | `ProfileListScreen.kt` ("More games", from `DefaultGames.kt`) and `ProfileEditor.kt` (host, port, TLS); `ProfilesTest.addsAProfileAndStoresItsPassword` |
| Colours, ANSI to 24-bit | Yes | `app/src/main/kotlin/org/mudlet/android/ui/AnsiText.kt`, `runColour`; [`protocol.md`](protocol.md), "Runs" |
| History and completion, keyboard stays open | Yes | `InputLine.kt`, `HistoryPosition` and the completion button; `GameScreenTest.aSendReachesTheEngineAndLeavesFocusOnTheInputLine` |
| Masked password field | Yes | `InputLine.kt`, the field with `KeyboardType.Password` |
| Background running, tells as notifications | Yes | `engine-service/src/main/AndroidManifest.xml:13-24`; `EngineNotifications.kt`, `postTell` |
| Engine restarted and reconnected after a kill | Yes, with libmudlet | [`architecture.md`](architecture.md), "Restoring the session" |
| Triggers, aliases, timers, Lua, packages, GMCP and the rest | Yes, in the engine | libmudlet's `THeadlessSession` runs Mudlet's own core ([`architecture.md`](architecture.md), "libmudlet as the engine") |
| Edited on the phone; a trigger from a line | Yes | `ui/scripts/ScriptLibraryScreen.kt`, `ScriptItemScreen.kt`, `TriggerFromLineScreen.kt`; `ScriptLibraryTest`, `LineTriggerTest` |
| The game's map, behind the text or on its own | Yes | `ui/game/AppMap.kt` with `AppMapClient.kt`; `AppMapTest` |
| TalkBack labels, announced connection state, no gesture-only actions | Yes | "Accessibility" below |
| Text follows the font size setting | Yes | "Accessibility" below |
| No ads, analytics or account; only the games you choose | Yes | "Data safety" below |

## Category, tags and contact

- **App or game**: Game. **Category**: Role Playing is the suggestion for both listings, as most MUDs are
  role-playing games; the maintainers choose it when they make each listing.
- **Tags**: up to five, chosen from Play's own list when the listing is made; look for role playing, adventure,
  fantasy, multiplayer and text-based ones.
- **Contact details**: the email address Play requires (the one the maintainers choose, typed into Play Console, not
  kept here),
  the website `https://www.mudlet.org`, and the privacy policy URL,
  `https://github.com/Mudlet/android/blob/main/docs/privacy-policy.md`. The privacy policy's own contact is the issues
  page, <https://github.com/Mudlet/android/issues>.
- **Content rating** (the IARC questionnaire): Mudlet itself has no violence, language or gambling, but players
  meet whatever the game they connect to has, and talk to other players there. Answer yes to "users can interact or
  exchange content", and no to the content questions about the app's own material. The listing then shows the interactive
  element "Users Interact".
- **Target audience**: 13 and over. Mudlet has no content for children and lets them reach game servers it does not
  control, so it is not for under-13s and does not opt in to Teacher Approved or the Families programme.
- **Ads**: none.
- **App access**: nothing in the app is behind a login; a game's own login is the game's.

## Graphics

- **App icon**: 512 × 512 PNG, 32-bit with alpha, up to 1 MB. Mudlet's icon is `icon_1024x1204.png` in Mudlet/Mudlet,
  and the PTB's `icon_ptb_1024x1204.png`; scale them down. They match the launcher icons
  ([`launcher-icon.md`](launcher-icon.md)).
- **Feature graphic**: 1024 × 500, JPEG or 24-bit PNG without alpha. Play may crop it and lay its own controls over
  it, so keep the logo and words in the middle and away from the edges, and put nothing there that only reads at full
  size. A suggestion: Mudlet's logo and name on the left half, a phone showing the game screen with coloured text on
  the right; the PTB's carries "PTB" or "Nightly" as Mudlet's PTB icon does. The phone shows the demo server's text,
  as the screenshots do (below).
- **Video**: optional; skip it. Play may ask for one separately for the foreground service declaration.

### Screenshots

Play takes 2 to 8 screenshots per device type, PNG or JPEG, each side 320 to 3840 px and the long side at most twice
the short one. At least four screenshots of 1080 px or more make the app eligible for Play's larger, featured
layouts, and a 7-inch and a 10-inch tablet set let Play show it as a tablet app. Take each in portrait on a phone, and
in landscape on the tablets.

Every screenshot shows only the test game: the text of `native/tests/fake_telnet.py`, the demo server the host tests
use, and the app's own screens. No real game's text, name or world appears in the listing, so no game's permission is
needed. The demo server's lines come from a test script (its docstring lists the commands: `flood`, `tell`,
`password` and the rest): coloured lines, a tell and a password prompt, which is what the screenshots show.

| # | Screen | Phone | 7" tablet | 10" tablet | Notes |
|---|---|---|---|---|---|
| 1 | Game screen, connected to the demo server: `flood 40`'s coloured lines, the prompt at the bottom, the input line and the game title | light, dark | dark | light, dark | The first screenshot is what most people see: make the text readable at thumbnail size |
| 2 | Game screen scrolled up, with "Jump to the bottom: 12 new lines" | dark | | dark | Shows the scrollback holds still |
| 3 | The masked password field as a game asks for the password | light | | light | `password` on the demo server below |
| 4 | A tell as an Android notification, over the game screen, and the engine's notification in the shade | dark | dark | | Pull the shade down; `tell` on the demo server |
| 5 | TalkBack on, its focus on the input line's Send button, with TalkBack's "Display speech output" captions | light | | light | Shows the labels; TalkBack, Settings, Advanced settings, Developer settings |
| 6 | The game screen at the largest font size, the game title and the title bar's buttons in one row above the console | light | | | Shows the layout holds at 200% |
| 7 | The profile list, with the demo server's profile only | light, dark | light | light | Crop it above "More games", which shows real games' names and icons |

Light and dark follow the system setting (`app/src/main/kotlin/org/mudlet/android/ui/theme/Theme.kt`); the console
itself stays black (`AnsiText.kt:19-21`), as Mudlet's does, so the difference shows in the bars around it.

**How to take them.** The smoke build has the release's look (no self-test button) and the debug hook that drives the
UI, so it can connect without a profile editor. Build it as [`ptb.md`](ptb.md) "Building a release locally" says, for
`x86_64`, which gives `app/build/outputs/apk/smoke/app-x86_64-smoke.apk`. Then, with an emulator running from a
Google APIs system image (it has TalkBack), make one AVD per device type in Android Studio's Device Manager: a Pixel 8
(phone), a Nexus 7 (2013; a 7-inch tablet) and a Pixel Tablet (10-inch):

```sh
adb install -r app/build/outputs/apk/smoke/app-x86_64-smoke.apk
# Notifications allowed up front, so the app skips its "Allow notifications?" screen and can post the tell
adb shell pm grant org.mudlet.mudlet android.permission.POST_NOTIFICATIONS
# A clean status bar: 12:00, full battery and signal, no notifications
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
# The game: the demo server on this host, which the emulator sees as 10.0.2.2
python3 native/tests/fake_telnet.py 4000 &
adb shell am start -n org.mudlet.mudlet/org.mudlet.android.ui.MainActivity     # then tap "Open the game screen"
adb shell am broadcast -n org.mudlet.mudlet/org.mudlet.android.debug.SmokeTestReceiver \
    --es cmd connect --es host 10.0.2.2 --ei port 4000
adb shell am broadcast -n org.mudlet.mudlet/org.mudlet.android.debug.SmokeTestReceiver --es cmd send --es text 'flood 40'
# Dark or light, the font size, and the screenshot itself
adb shell cmd uimode night yes                 # or no
adb shell settings put system font_scale 2.0   # back to 1.0 afterwards
adb exec-out screencap -p > game-dark-phone.png
# TalkBack on and off (the Google APIs image's Android Accessibility Suite)
adb shell settings put secure enabled_accessibility_services \
    com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService
adb shell settings delete secure enabled_accessibility_services
adb shell am broadcast -a com.android.systemui.demo -e command exit
```


## Accessibility

What the app on `main` does, for the listing's "Accessible" paragraph and for anyone who asks. It claims nothing the
code does not do; the rest of the roadmap's TalkBack work ([`roadmap.md`](roadmap.md), "TalkBack, a first-class
requirement") is listed as not yet done.

**Works with TalkBack now:**

- Every button has a label TalkBack reads: Back, Send, Previous command, Next command, Complete the word, Close the
  password field, History, the game title, the connection banner's buttons, Reconnect, Disconnect, and the jump-to-the-bottom button with
  its count of new lines (`app/src/main/res/values/strings.xml`; `MudletTopBar.kt:18-24`, `InputLine.kt:172-224`,
  `GameTitle.kt`, `ConnectionBanner.kt`, `GameSheet.kt`, `Console.kt:101-111`).
- The game title reads the game's name and connection state ("Connecting to host:port…", "Connected", "Disconnected",
  "Connection lost", "Connection error: …"), and TalkBack is told each change once (`GameScreen.kt`). "Not sent" and
  "No completion" under the input line are polite live regions (`InputLine.kt:146-151`).
- The password field has password semantics: TalkBack says "password", not what is typed, and it never goes out as a
  command (`InputLine.kt:167-169`; `InputLineTest.kt:76-90`).
- The order TalkBack moves in is the game title, then the console, then the input line and its buttons (`GameScreen.kt`;
  `GameScreenTest.theGameTitleThenTheConsoleThenTheInputLineThenItsButtonsTopToBottom`), and after a send the focus stays on the input
  line (`InputLine.kt:220-223`).
- The console's lines are text TalkBack can read one by one by swiping through the list.
- The UI tests run Google's Accessibility Test Framework checks on every screen they open, on the API 36 emulator in
  CI, so a missing label, a touch target under 48 dp or low contrast fails the build (`enableAccessibilityChecks()` in
  `app/src/androidTest/kotlin/org/mudlet/android/ui/GameScreenTest.kt:63-64`, `InputLineTest.kt`, `NavigationTest.kt`).

**No gesture-only actions.** Everything is a button or a key: history has Previous and Next buttons as well as a
hardware keyboard's arrow keys, completion has a button as well as Tab (`InputLine.kt:188-219`), and the way back to
the newest text after scrolling is a button. The app has no swipe, long press or pinch of its own.

**Text size.** All text is in sp, so it follows Android's font size setting (`Theme.kt:10`); the console is 14 sp
monospace (`Console.kt:96`). The row of pinned commands below the input line scrolls sideways when its buttons do not
fit (`QuickBar.kt`). The keyboard pushes the screen up and the console keeps its bottom line in view.

**Not yet** (on the roadmap): new game text read out as it arrives, and Lua's `announce()` spoken; the console as a
list with links as TalkBack actions; a text size setting of the app's own and pinch-to-zoom with it as the
equivalent; telling games a screen reader is in use (`advertiseScreenReader`). Until then a TalkBack user reads new
text by moving to the console.

## Data safety

Play's Data safety form asks what the app collects (sends off the device) and shares (passes to a third party), and
how. The answers below come from what the code does. Mudlet runs no server the app talks to, and the Mudlet team
receives nothing from it. Everything the app sends goes to the game server the player chose, or to addresses that game
or the player's scripts name. The answer is therefore **no data collected, no data shared**.

### The answers

| Question | Answer | Why |
|---|---|---|
| Does the app collect or share any of the required user data types? | **No** | Below: everything the app sends goes to the game server the player chose, or to addresses that game or the player's scripts ask for |
| Is all of the user data encrypted in transit? | Not asked once the answer above is No | Games choose plain telnet or TLS; Mudlet offers TLS where the game does |
| Do you provide a way for users to request that their data is deleted? | Not asked once the answer above is No | The app keeps data only on the device; uninstalling or clearing its storage deletes it |

Should a Play review ever read player-to-game traffic as collection, the answer it would want is "Messages: Other
in-app messages" and "App activity: Other user-generated content", collected, not shared, required for app
functionality.

### The evidence

**Network: only the player's game, and what it or their scripts ask for.**

- The app's UI process opens no network connection. It talks to the engine over a filesystem `LocalSocket` in the
  app's own files (`app/src/main/kotlin/org/mudlet/android/ui/EngineClient.kt:361-364`), and the engine drops any
  peer that is not the app's own user id (`native/enginehost/SocketBridge.cpp:42-50`). `INTERNET` and
  `ACCESS_NETWORK_STATE` are declared only for the engine service (`engine-service/src/main/AndroidManifest.xml:4-5`).
- The app's libraries are AndroidX, Compose, `org.json` and Qt (`gradle/libs.versions.toml:29-47`): no analytics,
  advertising, crash reporting or Firebase SDK.
- The engine connects to the host and port of the profile the player opens (`MudletEngine::start`,
  `native/enginehost/MudletEngine.cpp:387-397`).
- Mudlet's desktop-only parts that reach other servers are not built for Android:
  - the updater, which asks GitHub for new releases, is built only on Linux, Windows and macOS
    ([`CMakeLists.txt:180-185`](https://github.com/Mudlet/Mudlet/blob/bfc932d9e76004d9624b6925b5e0278c17fdae2a/CMakeLists.txt#L180-L185));
  - Discord Rich Presence and the MCP server are off on Android
    ([`CMakeLists.txt:188-205`](https://github.com/Mudlet/Mudlet/blob/bfc932d9e76004d9624b6925b5e0278c17fdae2a/CMakeLists.txt#L188-L205),
    [`:262-273`](https://github.com/Mudlet/Mudlet/blob/bfc932d9e76004d9624b6925b5e0278c17fdae2a/CMakeLists.txt#L262-L273));
  - Sentry crash reporting needs the desktop GUI
    ([`src/CMakeLists.txt:1204-1211`](https://github.com/Mudlet/Mudlet/blob/bfc932d9e76004d9624b6925b5e0278c17fdae2a/src/CMakeLists.txt#L1204-L1211)),
    which this build turns off (`-DUSE_GUI=OFF`, `scripts/build-libmudlet.sh:62-72`);
  - the package manager, which lists packages from Mudlet's package repository on GitHub, the connection dialog
    with its list of games, and the About dialog's links are GUI code (`dlgPackageManager.cpp`,
    `dlgConnectionProfiles.cpp`, `mudlet.cpp` are in `mudlet_app_SRCS`,
    [`src/CMakeLists.txt:224-278`](https://github.com/Mudlet/Mudlet/blob/bfc932d9e76004d9624b6925b5e0278c17fdae2a/src/CMakeLists.txt#L224-L278)).

  `scripts/build-libmudlet.sh:104-108` fails the build if any of their symbols (Qt Widgets, `QProcess`, `QHttpServer`,
  `sentry_`) leak into libmudlet.
- What Mudlet's core, which the app does run, may fetch, and when. None of it happens unless the game or the
  player's scripts ask:
  - a game's GUI package, from the URL the game sends in GMCP `Client.GUI`
    ([`ctelnet.cpp:5058-5104`](https://github.com/Mudlet/Mudlet/blob/bfc932d9e76004d9624b6925b5e0278c17fdae2a/src/ctelnet.cpp#L5058-L5104),
    downloaded at [`:4925-4938`](https://github.com/Mudlet/Mudlet/blob/bfc932d9e76004d9624b6925b5e0278c17fdae2a/src/ctelnet.cpp#L4925-L4938));
  - a game's map, from the address the game sends in `Client.Map`, else `https://www.<game host>/maps/map.xml`
    ([`TMap.cpp:2929-2938`](https://github.com/Mudlet/Mudlet/blob/bfc932d9e76004d9624b6925b5e0278c17fdae2a/src/TMap.cpp#L2929-L2938));
  - sounds and music a game plays over MSP or MCMP, from the game's media address, else `https://www.<game host>/media/`
    ([`TMedia.cpp:1214-1221`](https://github.com/Mudlet/Mudlet/blob/bfc932d9e76004d9624b6925b5e0278c17fdae2a/src/TMedia.cpp#L1214-L1221));
  - a game's sign-in through GMCP authentication, with the provider the game names (`GMCPAuthenticator.cpp`,
    `OAuthClientFlow.cpp`, in `mudlet_SRCS`);
  - anything a script asks for: Lua's `downloadFile`, `getHTTP`, `postHTTP` and the like
    ([`TLuaInterpreterNetworking.cpp:112`](https://github.com/Mudlet/Mudlet/blob/bfc932d9e76004d9624b6925b5e0278c17fdae2a/src/TLuaInterpreterNetworking.cpp#L112),
    `:726`, `:759`), IRC through `sendIrc` (to `irc.libera.chat` unless the script sets another server,
    [`TIrcClient.h:62`](https://github.com/Mudlet/Mudlet/blob/bfc932d9e76004d9624b6925b5e0278c17fdae2a/src/TIrcClient.h#L62)),
    and MMCP chat with other players (`MMCPClient.cpp`).

  These are the game's and the player's choices, made in the game or in scripts they installed, so the data goes to
  parties the player chose. The privacy policy says so in plain words.

**Credentials.** The app stores no password: the UI sends one with each `openProfile`, for that run only, and it is
never part of a profile the engine lists (`protocol/src/main/kotlin/org/mudlet/android/protocol/Protocol.kt:29`,
`:88-91`) or of the file the engine restores from ([`architecture.md`](architecture.md), "What is kept"). The input
line's password field is not kept across a rotation (`InputLine.kt:74-75`). Passwords and sign-in tokens that Mudlet's
core itself stores (a game's GMCP sign-in token, or a character password if it is ever given one to keep) go to
Mudlet's credential store, which on
Android is QtKeychain's backend: encrypted with a key held in the Android Keystore, on the device
([`architecture.md`](architecture.md), "libmudlet as the engine"; `scripts/build-libmudlet.sh:155-158` checks that
backend is linked). The roadmap's profile editor keeps the player's password in the Android Keystore too, on the
device ([`roadmap.md`](roadmap.md), U3).

**Crash reports and logs.** The app sends no crash report and no log. It writes to logcat, on the device: the engine's
state changes and errors (`native/enginehost/MudletEngine.cpp`, `SocketBridge.cpp`), Mudlet's error console lines
(`MudletProfile.cpp`, "mudlet error console"), which can quote what a failing script was handling. Never a password,
and none of the app's or the engine's own lines carries what the player typed, the game's text or a tell's.
About > Report a problem (`app/src/main/kotlin/org/mudlet/android/ui/report/ReportProblemScreen.kt`) shows the
player the app's recent logcat, the versions and the device, with every saved password and every profile's login taken
out (`app/src/main/kotlin/org/mudlet/android/report/`), and sends nothing itself: the player passes the text on through
Android's share sheet, the clipboard or a GitHub issue in the browser, to whomever they choose. Data a user moves out
of the app by their own action, to a destination they pick, is neither collected nor shared in Play's terms.
Google Play's Android vitals gathers crashes and ANRs through Android itself, from users who allow it; that is
Google's, not the app's, and is not declared here. The release AAB carries the symbol tables those reports need
(`app/build.gradle.kts:68-69`).

**What is kept on the device.** Profiles, Mudlet's settings, scripts, packages, maps and logs a player turns on, in
the app's private files (`native/enginehost/MudletEngine.cpp:95-106`); the engine's restore file
(`engine-service/src/main/kotlin/org/mudlet/android/engine/EngineSession.kt:15-17`); whether the app has asked for
notifications (`app/src/main/kotlin/org/mudlet/android/ui/MainActivity.kt:100-105`). Other apps cannot read them.

**Backups.** Android's own backup and device transfer, through `MudletBackupAgent`
(`app/src/main/kotlin/org/mudlet/android/data/MudletBackupAgent.kt`; `android:allowBackup`, `android:backupAgent` and
`android:fullBackupOnly` in `app/src/main/AndroidManifest.xml`). What it takes is `BackupScope`
(`app/src/main/kotlin/org/mudlet/android/data/BackupScope.kt`): the profiles, Mudlet's settings, pinned commands and the
app's settings to both a cloud backup and a new phone; the logs and command suggestions, which hold the game's text and
what the player typed, to a new phone only; the passwords (`files/passwords/`), the sign-in tokens QtKeychain keeps
(`files/settings/`), the engine's restore file and stop marker, and the `engine` and `ui` preferences to neither. A
cloud backup is made only when the transport encrypts it with the screen lock (`FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED`;
`disableIfNoEncryptionCapabilities` in `app/src/main/res/xml/data_extraction_rules.xml`). The XML rules
(`data_extraction_rules.xml` from Android 12, `backup_rules.xml` before it) exclude the same secrets and engine state,
and a restore keeps only what `BackupScope` takes, so a backup made elsewhere cannot bring a password back.
The backup is Google's service, in the user's own Google account and encrypted with their screen lock; it never reaches
the developer, so it is not data collected or shared. `BackupRulesTest` checks the rules.
A player can export one profile to a `.zip` (`app/src/main/kotlin/org/mudlet/android/ui/profiles/ProfileExport.kt`),
written only where they pick through Android's file picker, with no storage permission and the profile's password
files left out (`ProfileArchive.SECRETS`); nothing is sent to the developer, so it is not data collected. Import
(`ProfileImport.kt`) reads only the file the player picks, through the same picker, and leaves those files out too.

**Deleting data.** Uninstalling the app, or Settings, Apps, Mudlet, Storage, "Clear storage", deletes everything it
kept on the device; a backup Android made is in the user's Google account, where they delete it. No copy reaches the
Mudlet team and there is no account, so there is nothing for the Mudlet team to delete.

**Permissions** (`engine-service/src/main/AndroidManifest.xml:4-8`; the app's own manifest declares none):

| Permission | Why |
|---|---|
| `INTERNET` | The engine's connection to the game |
| `ACCESS_NETWORK_STATE` | Qt's networking checks the connection |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | The engine's foreground service keeps the connection and scripts running in the background (`:13-24`, with the reason Play reads at `:18-20`) |
| `POST_NOTIFICATIONS` | The engine's notification, and tells; on Android 13 and later asked after a screen that says why, and not again after "Not now" (`MainActivity.kt:57-66`, `:97-105`) |

Libraries can add permissions in the merged manifest. Before each first submission, check the release APK's list
matches this one (AndroidX adds only its own signature permission, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`):

```sh
"$ANDROID_SDK_ROOT"/build-tools/*/aapt2 dump permissions app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

## Foreground service declaration

Play asks apps that declare a foreground service type to say why (App content, Foreground service permissions):
`specialUse`, with the reason in `engine-service/src/main/AndroidManifest.xml:18-20`. The player sees it as the
engine's notification, which names the open game (or counts the open games) and has a Quit action
(`EngineNotifications.kt`, `engineNotification`); the service leaves the foreground when no game is open
(`EngineService.kt`, `leaveForeground`). The same answers serve both listings:

- **What the app does in the foreground service**:

  ```text
  Mudlet keeps the player's connection to a text game (a MUD) open, and runs the player's own scripts (triggers, timers) on the game's text, while the player uses other apps or the screen is off. The game would disconnect the player, and their scripts would stop, if the connection closed.
  ```

- **Why it must start and continue in the background**:

  ```text
  The connection is a long-lived TCP or TLS session the player opened; no other API (WorkManager, JobScheduler) keeps a socket and its scripts running. A notification ("Keeping <game> connected") is always shown while it runs, with a Quit action, and the service leaves the foreground when no game is open.
  ```

- **The video** (30 to 60 seconds, a screen recording: `adb shell screenrecord /sdcard/fgs.mp4`), uploaded to YouTube
  as unlisted, its link pasted into the declaration:
  1. Open Mudlet and tap a game under "More games" (or the demo server's profile): the game's text appears.
  2. Pull down the notification "Keeping <game> connected".
  3. Go to the home screen, open another app and wait.
  4. Come back to Mudlet: still connected, with the text that arrived meanwhile.
  5. Tap Quit in the notification: the notification goes.
