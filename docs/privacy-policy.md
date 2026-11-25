# Mudlet for Android: privacy policy

> For maintainers: this file is the privacy policy Mudlet's listing gives Google Play, at
> <https://github.com/Mudlet/android/blob/main/docs/privacy-policy.md>. It must say the same as the Data safety answers
> in [`store-listing.md`](store-listing.md#data-safety), which give the evidence in the code for each statement here;
> change both together.

**Effective date:** 2026-11-22; the file's
[history](https://github.com/Mudlet/android/commits/main/docs/privacy-policy.md) shows every earlier version.

This policy covers Mudlet for Android and Mudlet PTB, its nightly test build, made by the Mudlet team, a group of
volunteers. In short: **Mudlet collects nothing about you.** It has no account, no ads, no analytics and no crash
reporting of its own, and the Mudlet team receives no data from it.

## What Mudlet sends, and to whom

Mudlet is a client for text-based online games (MUDs). Those games are run by other people, not by the Mudlet team.

- **The games you connect to.** When you connect to a game, Mudlet sends it what you type: your commands, your
  character's name and password when you log in, and the replies Mudlet makes for you to the game's protocols (such as
  your screen size and the name of the client). The game's own privacy policy, if it has one, covers what it does with
  that. Some games accept only unencrypted connections; Mudlet uses TLS encryption where the game offers it and you
  choose it.
- **Addresses a game or your scripts ask for.** A game can ask Mudlet to download files from addresses it gives: its
  user interface package, its map, its sounds and music, or to sign in through a provider it names. Scripts and
  packages you install can download files, send web requests, chat with other players directly (MMCP) or join IRC
  chat. Mudlet makes these connections only when the game or your scripts ask, and to the addresses they give. A
  script you install can do anything a program can, so install scripts and packages only from people you trust.
- **GitHub, from Mudlet PTB installed from GitHub.** Mudlet PTB, the test build, when you installed it from GitHub
  rather than Google Play, asks GitHub at most once a day for the number of the newest test build, to tell you when
  there is a newer one. It sends nothing about you, your games or your device; GitHub sees the request come from your
  internet address, as any website would, and GitHub's privacy statement covers that. You can turn it off in Settings,
  Test builds. Mudlet itself, and Mudlet PTB installed from Google Play, never ask.
- **GitHub, from the package manager.** When you open a game's package manager, Mudlet downloads the list of packages
  in Mudlet's package repository from GitHub, where it is kept, if it has no copy it can read, its copy lacks a
  package you ask for, or you tap Refresh; then the icons of the packages its Explore and Updates views show, and a
  package when you tap Install or Update. These requests name only the file they ask for and send nothing about you,
  your games or your device; GitHub sees them come from your internet address, as any website would, and GitHub's
  privacy statement covers that.
- **No one else.** Mudlet does not contact the Mudlet team, check for updates (but for the test build's check above),
  show ads, measure how you use it or send crash reports. On Android, Mudlet's desktop features that contact other
  services, such as the updater and Discord status, are not built in.

## What Mudlet keeps on your device

- Your profiles (each game's address and your settings), your scripts and packages, maps, and the logs of your games if
  you turn logging on, in the app's private storage, which other apps cannot read.
- Passwords and sign-in tokens Mudlet keeps for you are encrypted with a key held in the Android Keystore, on your
  device.
- A small file that lets Mudlet reconnect you if Android stops it in the background. It never holds a password.

## Backups and moving to a new phone

Mudlet lets Android back up your profiles, scripts, packages, maps and settings to your Google account, but only when
Android encrypts the backup with your phone's screen lock (PIN, pattern or password); without a screen lock there is no
backup. When you move to a new phone with Android's transfer, by cable or over Wi-Fi, these go with you, and so do the
logs of your games and the command suggestions Mudlet learned from what you typed. Logs and suggestions never go to a
cloud backup. Your passwords, the sign-in tokens Mudlet keeps for games, and the small reconnect file never leave your
phone: on a new phone, or after a restore, Mudlet asks you for each game's password again. You can turn backups off in
your phone's settings, under Google or System, Backup. To stay within the 25 MB a cloud backup can hold, it keeps only
the latest save of each game's map and profile; older saves go only to a new phone. A backup bigger than 25 MB all the
same is not made at all.

Android's own system logs on your device may hold Mudlet's error messages; they never hold a password.

You can export a profile to a file yourself, from the profile's editor: it goes only where you choose in Android's file
picker, and Mudlet leaves your passwords out of it. Who can read it then depends on where you put it. Importing one
reads only the file you pick, or the one you open with Mudlet or share with it from another app, and leaves out any
password in it.

You can share a log of your games, or open it in another app, yourself, from the profile's editor or the game's Logging
page: Mudlet hands that one log, and nothing else, only to the app you choose when you tap, and asks for no permission
to your files. Who can read it then depends on that app and where you send it.

## Reporting a problem

About > Report a problem shows you a report to send the Mudlet team when something goes wrong: Mudlet's recent
messages from Android's system log, the versions of the app and of Mudlet, and your device's make, model and Android
version. Your saved passwords and your characters' logins are taken out of it, and what you typed while a game hid
your input is never in it. You see all of it before anything is sent, and Mudlet sends nothing itself: you choose to
share it through Android's share sheet, copy it, or paste it into an issue on GitHub, and only the person or service
you send it to receives it.

## Permissions

- **Internet and network state**, to connect to games.
- **Notifications**, to show that Mudlet is running in the background and to show the messages your scripts ask it to
  show, such as a tell from another player. You can turn notifications off; Mudlet keeps working.
- **Foreground service**, to keep your game connected and your scripts running while you use other apps.

Mudlet does not ask for your location, contacts, camera, microphone, files or any other personal data.

## Deleting your data

Everything Mudlet keeps is on your device. To delete it, uninstall Mudlet, or open Android's Settings, Apps, Mudlet,
Storage and choose "Clear storage". A backup Android made of it is in your Google account, which you can delete there
(Google Drive, Backups). There is no copy anywhere the Mudlet team can reach, so there is none for it to delete. To have
a game delete your character or account, ask that game.

## Google Play

If you install Mudlet from Google Play, Google handles the download and, if you allow it in your device's settings,
sends crash and performance reports to the Mudlet team through the Play Console. Those reports are about the app (for
example, where it crashed), and Google's privacy policy covers them.

## Children

Mudlet is not meant for children under 13. It connects to games the Mudlet team does not run or moderate, where you
can talk to other players.

## Changes

If Mudlet's handling of data changes, this policy changes with it, and the new version takes effect the day it is
merged, and the effective date above changes to that day. The app's source
code is public at <https://github.com/Mudlet/android> and <https://github.com/Mudlet/Mudlet>, so anyone can check what it
does.

## Contact

For questions about this policy or about Mudlet and your data, open an issue at
<https://github.com/Mudlet/android/issues>.
