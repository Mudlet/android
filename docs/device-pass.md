# The device pass: trying the app on a real phone

The app is tested by machines on every change, but only on emulators. Some things only a real phone shows: whether a
game stays connected with the screen off for an hour, whether a phone maker's battery saver stops it, how the keyboard
feels, whether sound plays, and how TalkBack reads it all. This page asks you to try those things and write down what
you see. You do not need to be a developer, or to have a computer: a phone and a game you can log in to are enough.

Allow about two hours, spread over a day and a night. You do not have to do every part: the table below says which
parts matter most on your kind of phone. A part you skip is fine; say so in your report.

## What kind of phone you have

| Your phone | Parts to do | Why |
|---|---|---|
| An older or low-cost phone (Android 9 or 10, 3 GB of memory or less) | 1 to 7, and 12 | Is it fast enough, and does Android close the app when memory is short? |
| A Samsung, Xiaomi, Redmi, Poco, OnePlus, Oppo, Vivo, Realme, Huawei or Honor phone | 1 to 8 | These makers add their own battery savers, which may stop the app (parts 5 and 6 matter most). |
| A Google Pixel, ideally on Android 16 | All parts, 1 to 14 | The plain Android every other phone starts from. |
| A tablet, a foldable or a Chromebook | 1 to 4, 10, 11, 13, 14 and 15 | Bigger screens show windows side by side, and often have a keyboard. |

Your Android version is in the phone's Settings, under "About phone" (on some phones "Software information").

## Before you start: things the app does not do yet

Please do not report these; they are known and being worked on:

- Links in the game's main text (words the game underlines) cannot be tapped yet. Links in a script's own windows can.
- Keys pressed on a keyboard do not reach a game's key bindings yet.
- There is no backup. **Uninstalling the app deletes your games, scripts and maps**, and so does "Clear storage" in
  the phone's settings. The test app is a separate app from Mudlet, so it cannot touch the real Mudlet's games.
- The app has no "Report a problem" button yet: write your report as described at the end of this page.

## How to write things down

Open a new note on your phone, or use paper. For each part, write its number, then "ok", or the step number and what
happened, in your own words. "3.2: the keyboard closed after Send" is a perfect report. Some steps ask for a time or a
number: write it down as you go. A screenshot (on most phones, the power and volume-down buttons together) helps
whenever something looks wrong.

When you are done, copy your notes into the report form (the last section).

Commands in this page are long: copying them from this page on your phone and pasting them into the input line is
easier than typing them.

## Part 1. Install and start

1. Install **Mudlet PTB** (the test version). With Google Play: open
   <https://play.google.com/apps/testing/org.mudlet.mudlet.ptb>, tap "Become a tester", then install it from Play.
   Without Play (some Huawei phones, or if you prefer): download `mudlet-ptb-arm64-v8a.apk` from
   <https://github.com/Mudlet/android/releases/tag/ptb> and open it; your phone asks to allow installing apps from
   the browser. On a Chromebook with an Intel or AMD chip, take `mudlet-ptb-x86_64.apk` instead.
2. Open it. On Android 13 and later it explains its notification and asks to allow notifications: allow them.
3. It opens on "Profiles", with "More games" below: the games Mudlet knows.
4. Tap the "i" button at the top (About and licences) and write down the **version** shown at the top. Go back.

**Write down:** your phone's make and model, its Android version, the app's version, and whether you installed from
Play or from GitHub.

## Part 2. Get a game ready

Most later parts use a game you can log in to. Use a game you already play if it is listed under "More games", or add
it with "Add a game" (its name, address and port, which the game's website gives). If you have no game, Aardwolf and
Multi-Users in Middle-earth (MUME) in the list let you make a character in a few minutes.

1. Tap the game under "More games" (it connects at once), or Connect beside a game you added. The game's text
   appears, in colour.
2. **Write down** how many seconds it took from your tap to the first text.
3. Log in with your character.
4. Some steps below ask you to type a command that starts with `lua`. Desktop Mudlet understands these by itself; the
   app needs a small helper first, made once per game:
   1. Tap the wand button at the top (Scripts), then Aliases, then "New alias".
   2. Name: `lua`. Pattern: `^lua (.*)$`
   3. Tap in the Lua field at the bottom and paste (or type), on one line:
      `local f, e = loadstring("return " .. matches[2]) if not f then f, e = loadstring(matches[2]) end if not f then echo(e .. "\n") return end local ok, r = pcall(f) if not ok then echo(tostring(r) .. "\n") elseif r ~= nil then display(r) end`
   4. Tap Apply, then go back to the game (Back, then the game's last lines at the foot of the list).
   5. Type `lua 1 + 1` and Send. The answer `2` shows in the game's text. If it does not, write down what showed.

## Part 3. Play

1. Tap the input line at the bottom. The keyboard opens, and the game's text moves up with its newest line still in
   view.
2. Type a command (`look`) and tap the keyboard's Send key. It is sent and the keyboard stays open for the next one.
   Write down what the keyboard's action key shows (it should be Send) and whether the keyboard changed what you
   typed (a capital first letter, a "correction").
3. Type half of a command you sent before: the rest of it shows greyed after what you typed. Tap the grey text: it
   goes on the line, not yet sent.
4. Tap the up arrow beside the line: your last command comes back.
5. Tap "History" below the line: the commands you sent are listed. Tap one to put it on the line.
6. Scroll the game's text up while the game is sending text: the text stays where you are, and a "Jump to the bottom"
   button counts the new lines. Tap it: you are back at the newest line.
7. Turn the phone sideways and back: nothing is lost or shown twice, and a half-typed command stays.
8. Pinch the game's text with two fingers: it grows and shrinks.
9. Play for ten minutes as you normally would. **Write down** anything slow, odd or hard to do.

## Part 4. Leave and come back

1. Pull down the notifications: one reads "Keeping <your game> connected".
2. Go to the home screen, use another app for five minutes, and come back to Mudlet PTB. The game is still connected,
   and the text that came while you were away is there.
3. Open the recent apps and swipe Mudlet PTB away. Open it again from the home screen: the game is still connected.
4. Turn on airplane mode for a few seconds, then off. The game's name at the bottom turns red and says the connection
   was lost; it reconnects by itself or offers Reconnect.
5. In the notification, tap Quit. The notification goes and the game is disconnected and closed. Open the app again:
   no game is open, and it does not connect by itself.

**Write down** anything that did not happen as described.

## Part 5. An hour with the screen off

This is the most important part. Desktop Mudlet's timers (scripts that do something every so often) must keep running
while your phone sleeps.

1. Plug the phone out of the charger. Connect to your game and log in.
2. Send this command (it counts a tick every minute, and sends you a notification after half an hour):
   `lua dp = {os.time()}; tempTimer(60, function() dp[#dp + 1] = os.time() end, true); tempTimer(1800, function() showNotification("Mudlet test", "Half an hour, " .. os.date("%H:%M")) end); echo("Started at " .. os.date("%H:%M") .. "\n")`
3. It answers "Started at" and the time. **Write down** that time.
4. Turn the screen off and leave the phone alone, unplugged, for at least one hour. Do not use it.
5. After the hour, turn the screen on and open the app. **Write down:**
   - whether the game is still connected (the game's name at the bottom says "Connection lost" if not), and any line
     in the game's text that starts with "The engine restarted" (copy it exactly);
   - whether the "Mudlet test" notification came, and the time it gives (it was due half an hour after the start);
   - the result of this command:
     `lua local g = 0; for i = 2, #dp do g = math.max(g, dp[i] - dp[i - 1]) end; echo(#dp - 1 .. " ticks, longest gap " .. g .. " seconds, last one " .. os.time() - dp[#dp] .. " seconds ago\n")`
     After an hour, about 60 ticks with a longest gap near 60 seconds means the timers kept running. Fewer ticks, or
     a gap of several minutes, means the phone paused them.
     An answer that mentions `dp` and "nil" means the app's engine was stopped and started again while you were away,
     which also stopped the count: write that down.
6. Does your game log out characters who stay idle? Write down what it does, if you know (the game's help usually
   says), so a lost connection can be told from the game's own rule.

## Part 6. A night on battery

Phone makers' battery savers sometimes stop apps overnight. This part finds out whether yours does.

1. Find the app's battery setting: in the phone's Settings, Apps, Mudlet PTB, Battery (on Xiaomi phones, "Battery
   saver"). **Write down** what it is set to (for example "Optimised", "Unrestricted", "Restricted", "No
   restrictions"). Leave it as it is.
2. If your phone has a battery saver or power saving mode, **write down** whether it is on. Leave it as you normally
   have it.
3. In the evening, connect to your game, send the command from Part 5 step 2, and leave the phone unplugged overnight,
   screen off.
4. In the morning, open the app and do Part 5 step 5 again (the same three things to write down).
5. If the game was not connected in the morning, or there were far fewer ticks than minutes passed, set the app's
   battery setting to "Unrestricted" (or "No restrictions"), and do this part once more the next night. **Write
   down** both results.

Many phone makers' battery savers are described at <https://dontkillmyapp.com>, which can help you find the setting.

## Part 7. Secure connections and downloads

1. Connect to Multi-Users in Middle-earth (MUME) from "More games": it uses a secure connection. The game's text
   appears. **Write down** how many seconds it took.
2. Connected to any game, send this command, all on one line:
   `lua registerAnonymousEventHandler("sysDownloadDone", function(_, f) echo("got " .. f .. "\n") end) registerAnonymousEventHandler("sysDownloadError", function(_, e) echo("failed: " .. e .. "\n") end) downloadFile(getMudletHomeDir() .. "/tls-check.html", "https://www.mudlet.org/")`
   After a moment, a line starting with "got" shows. **Write down** what showed if it says "failed".

## Part 8. Sound

1. Send `lua ttsSpeak("Hello from Mudlet")`. **Write down** whether the phone said it, and whether the media volume
   buttons change how loud it is.
2. If your game plays sounds or music (some do, often after you turn it on in the game's own options), play for a
   while. **Write down** whether you hear them, and whether they keep playing with the screen off.

## Part 9. TalkBack

TalkBack is Android's screen reader. Turn it on in the phone's Settings, Accessibility, TalkBack. It changes how you
use the phone: tap once to hear something, double tap to use it, swipe right or left with one finger to move from one
thing to the next. Skip this part if you are not comfortable with it.

1. On "Profiles": swiping goes through "Profiles", each game ("name, address"), its "Edit" and "Connect to" buttons,
   and "Add a game".
2. Connect to a game. New text from the game is read as it comes, without you doing anything. A lot of text at once
   is read once, as how many lines and the last of them, not line by line.
3. Swiping goes from the game's lines to the input line ("Command, edit box"), then its buttons, then the game's name
   ("<game>, Connected, game 1 of 1").
4. At the game's password prompt, the input line becomes a password field: TalkBack says "dot" or nothing for what you
   type, never the letters.
5. Turn airplane mode on for a moment: TalkBack says the connection was lost once, with the game's name first.
6. Open the game's settings (the gear button at the top): the section titles are read as headings, and each switch
   reads its name and whether it is on.

**Write down** anything that was not read, read twice, read wrongly or impossible to reach. If you use TalkBack every
day, your general impression is the most useful thing you can write.

## Part 10. A keyboard

Only with a Bluetooth or USB keyboard, or a Chromebook's own.

1. Typing goes into the input line, and Enter sends.
2. Ctrl+R opens the History; Escape or Back closes it.
3. Type the start of a word the game sent, then Tab: the word is completed.
4. With two games open (Part 12), Ctrl+Tab switches between them.

## Part 11. Look and size

1. Turn the phone's dark theme on and off (Settings, Display): every screen of the app follows it.
2. Set the phone's font size to its largest (Settings, Display, Font size): the app's text grows and nothing is cut
   off. Set it back.
3. On Android 12 or later: the app takes its colours from your wallpaper. In the app's settings (the gear on
   "Profiles"), turn "Colours from my wallpaper" off: it uses Mudlet's own colours. The game's text keeps its colours
   either way.
4. Go back from any screen with the back gesture (a swipe from the screen's edge): the screen you are going back to
   shows as your finger moves.

## Part 12. Several games at once

1. Connected to a game, tap the game's name at the bottom, then "Open another game", and connect a second game. The
   name at the bottom shows "2".
2. Swipe the game's name sideways to switch games; tap it to see both games and switch from there.
3. Open a third game. On a phone with little memory, use the phone for a few minutes, then come back: **write down**
   whether all three are still connected.
4. Close one game from that list: it asks first if the game is connected.

## Part 13. Scripts

1. Tap the wand button (Scripts). Triggers, Aliases, Scripts, Timers, Keys and Buttons are listed, with your `lua`
   alias among the aliases.
2. Tap Triggers, "New trigger". Add a pattern `You see` (or a few words your game often sends), type `echo("seen\n")`
   in the Lua field, and Apply. Go back to the game: after a line with those words, "seen" shows.
3. Tap Timers, "New timer". Set Seconds to 30 and "Send to the game" to `look`, Apply, go back and switch it on:
   `look` is sent every 30 seconds. Switch it off again.
4. **Write down** anything hard to do with a phone's keyboard, or that did not work.

## Part 14. The map

1. Connect to a game that offers its map (Achaea, Aetolia, Imperian, Lusternia or Starmourn from "More games"
   should; so do many others with a mapping script). Tap the Map button at the top. If there is no map yet, it offers to
   download it: tap that, and the map appears.
2. Walk around in the game: the map follows you. A tap on a room shows its name and exits, with "Walk here".
3. Pinch to zoom; "Find a room" with part of a room's name lists the matches.
4. Tap the Map button again: the map goes behind the game's text, faint, and follows you there.

## Part 15. Big screens

On a tablet, an unfolded foldable, or a Chromebook:

1. Turn it sideways: on "Profiles", Edit opens the game's editor beside the list, and Back closes it.
2. On a Chromebook, resize the app's window: the game's text fits the new width.
3. If your game has its own screen layout with chat windows or gauges (many do), they show where the game put them,
   beside the text.
4. Fold and unfold a foldable while a game is connected: the game stays connected and nothing is shown twice.

## Sending your report

Open <https://github.com/Mudlet/android/issues/new?template=device-pass.md> (you need a free GitHub account), fill in
the form with your notes and screenshots (drag them into the text box, or tap it and attach them), and press
"Create". If you have no GitHub account, write your report in Google Play's feedback for Mudlet PTB instead.

Please never put your game password, or anything else private, in a report or a screenshot.

## For developers

Testers who have a computer with `adb` and CI's debug APK (`mudlet-libmudlet-debug-apk`, which has the Self-test
screen) can also run the full list behind this page: "On-device checklist" in [`architecture.md`](architecture.md),
whose items 2 to 13 and 33, and a few steps elsewhere, need the Self-test. Reports go in the same form, with the
checklist's item numbers.
