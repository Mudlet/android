# Public test builds

A public test build (PTB) is the app built every night from Mudlet's latest `development` and this repository's
`main`, as Mudlet's desktop PTBs are. Testers get it two ways:

- **Google Play's closed testing track** of **Mudlet** itself (`org.mudlet.mudlet`): join the
  [Mudlet Android Testers](https://groups.google.com/g/mudlet-ptb-testers) group and then the test with a link,
  install Mudlet from Play, and Play keeps it on the nightly. It moves to open testing, which needs no group, once Play allows
  (below);
- the rolling **`ptb` GitHub pre-release**: an APK for each ABI of the app **Mudlet PTB**
  (`org.mudlet.mudlet.ptb`), which installs beside Mudlet, and the source code they are built from.

`.github/workflows/ptb.yml` builds and publishes them. This page says how it works, what the maintainer sets up once,
how testers join, and how to roll a bad PTB back.

## How a PTB is made

Every night at 05:23 UTC (after the libmudlet nightly, whose ccache it takes up), and when run by hand (steps 2 to 5 are
`release-build.yml`, a reusable workflow the stable release, [`release.md`](release.md), calls too):

1. **plan**: resolves Mudlet's `development` to a commit, and reads the last PTB's commits from the markers in the
   `ptb` pre-release's notes. If neither Mudlet's commit nor this repository's changed, it stops there (a manual run
   with `force` builds anyway), unless Google Play did not take the last PTB (step 7); while there is no pre-release,
   before the setup below, every run builds. It works out the version (below).
2. **engine** (arm64-v8a and x86_64 at once): libmudlet and its dependencies, with libmudlet.yml's caches, and the
   engine staged by `STAGE_ONLY=1 scripts/build-apk.sh`. libmudlet is built with `MUDLET_VERSION_BUILD=-ptb-<date>`,
   as Mudlet's own PTBs are, so the engine calls itself `<version>-ptb-<date>-<commit>`.
3. **source** (beside engine): `scripts/build-source-bundle.sh` builds `mudlet-ptb-source.tar`, the source code
   the release is built from ("The source bundle" below). Every run builds it, so a dry run shows it still builds;
   only a run that publishes keeps it.
4. **package**: `scripts/build-release.sh` merges the two staged engines and builds the release AAB (both ABIs) as Mudlet
   ("Identity" below), an APK for each ABI as Mudlet PTB, and the smoke build (below), with R8 and resource shrinking. It checks each APK as CI checks every APK
   (`scripts/check-apk.sh`: Qt, the engine, OpenSSL, 16 KB alignment, the launcher icon, the licences), that the APKs carry
   the PTB's application id and version, and that the AAB and the APKs carry the signing certificate, libraries for exactly the two ABIs, the
   Baseline Profiles Compose and AndroidX ship (`docs/plan.md`, lane 16, on one of the app's own), and in the AAB R8's mapping
   and the native symbol tables, which Play uses for its crash reports. It checks the APKs' and the AAB's sizes against
   their budgets (`scripts/check-apk-size.sh`, CONTRIBUTING.md's "APK size budget"): over budget fails a pull request,
   while a nightly or manual PTB still publishes, with the error in its summary.
5. **smoke**: the x86_64 smoke build on an Android 15 emulator, with the test libmudlet.yml runs on the debug build
   (`native/tests/android_smoke.py`): the engine service and Qt come up, a profile connects and runs Mudlet's Lua, a
   trigger fires and notifies, and the UI reattaches to a restarted engine. This is what shows that R8 kept what Qt
   and the engine reach by name.
6. **play**: uploads the AAB to Mudlet's closed testing track (`alpha` in Play's API), or to the track the repository
   variable `PLAY_PTB_TRACK` names (`beta` for open testing), with
   [r0adkll/upload-google-play](https://github.com/r0adkll/upload-google-play), pinned to a commit.
7. **github-release**: replaces the `ptb` pre-release, its tag on this repository's commit, with the APKs, the
   source bundle, `SHA256SUMS.txt` and notes naming both commits. It does not wait for Play: when Play refuses the
   AAB, the play job fails, and so does the run, which reports it below, but the pre-release is still replaced, its
   notes say that Play did not take it, and their `<!-- play: failed -->` marker has the next night build again
   (with a new versionCode) even if nothing changed, until Play takes one. Play never gets the same versionCode twice.

Steps 6 and 7 run only on `main`, only after the smoke test passed and the source bundle was built, and only with
the secrets below. On any other branch, and on a pull request that changes the app's code (`app/`,
`engine-service/`, `protocol/`, `suggest/`, `native/`) or the release build (the workflow, the Gradle files, the
size budgets or the scripts it runs), the run is a dry run: it builds, signs with a throwaway key, runs the smoke
test and publishes nothing. A failed nightly opens or updates the issue "Nightly PTB fails".

### The source bundle

The app is free software under the GPL v3 ([`licences.md`](licences.md)), which asks for its source to be offered
with the binaries for as long as they are, and the LGPL v3 the same for Qt. Links to GitHub and to Qt's and the
dependencies' servers are not in this project's hands, so each pre-release carries `mudlet-ptb-source.tar` beside
the APKs, a tar of compressed archives:

- `mudlet-android-<commit>.tar.gz`: this repository at the commit built (`git archive`), with the scripts that build
  the APK;
- `mudlet-<commit>.tar.gz`: Mudlet at the commit the engine is built from;
- `lua-modules/`: the Lua C modules Mudlet's CMake fetches at configure time (`cmake/BundledLuaModules.cmake`), at
  their pinned commits;
- `deps/`: the archives `scripts/build-mudlet-deps.sh` and `scripts/build-openssl.sh` build from, which each
  downloads and checks against its pinned SHA-256 with `FETCH_ONLY=1`;
- `qt/`: the source of the five Qt modules the APK ships (qtbase, qtsvg, qtmultimedia, qt5compat, qtspeech) and of
  the FFmpeg release Qt Multimedia loads;
- `SOURCES.txt`: where each came from, and every file's SHA-256.

Qt's whole source (`qt-everywhere-src`, about 1 GB) is not in it, only linked: the five modules are all the APK has
of Qt, and the bundle stays a few hundred MB, which a runner builds in minutes. The script checks that the link the
About screen gives for Qt's whole source answers, and fetches the modules from the same `archive/` path, so a moved
link fails the run. The Android SDK, NDK and Gradle are tools, and the Android libraries are Apache 2.0 or similar,
listed in the About screen with their sources; none is in the bundle.

```sh
scripts/build-source-bundle.sh ../mudlet mudlet-ptb-source.tar   # a Mudlet checkout at the commit built
```

### Identity: Mudlet's test on Play, an app of its own on GitHub

On Google Play the PTB is a testing track of Mudlet itself, `org.mudlet.mudlet`, as Android apps' betas usually are:
a tester joins the test and Play gives them the nightly in place of the stable version. Its AAB is built with
`-PreleaseChannel=stable` (`AAB_CHANNEL`, `scripts/build-release.sh`), so it is named "Mudlet", has Mudlet's icon,
and has no check for a newer build from GitHub, as Play updates it. A personal developer account's 14-day closed
test (below) is per app, so running it on Mudlet earns the production access the stable releases
([`release.md`](release.md)) need; a Play app of the PTB's own would need a test of its own too.

The cost: a tester has one Mudlet from Play, the nightly. To go back to the stable version they leave the test and
uninstall, which deletes their profiles, as Android never installs an older versionCode over a newer one. A nightly's
crashes and ANRs count in Mudlet's Android vitals.

The GitHub APKs are the app `org.mudlet.mudlet.ptb`, built with `-PreleaseChannel=ptb` (`app/build.gradle.kts`),
which adds `.ptb` to the application id, names the app "Mudlet PTB" and gives it its own launcher icon: Mudlet's arch
at night, as on the desktop PTB's icon ([`launcher-icon.md`](launcher-icon.md)). It installs beside Mudlet, as
"Mudlet PTB.exe" and "Mudlet PTB.app" do on the desktop, with its own profiles. The channel is a Gradle property
rather than a product flavour so that the variants and their tasks and outputs (`assembleDebug`, `app-debug.apk`),
which the scripts and the other workflows use, stay as they are.

### Versions

- **versionCode** is the build's UTC time as `yyDDDHHmm`: year, day of the year, hour and minute, so 263181530 is
  2026, day 318, 15:30. It only grows, which Play requires of every upload, and it reads as a date. A run number
  would restart if the workflow were renamed and repeat on a re-run, and a commit's date would go back when an older
  Mudlet commit is rebuilt (see "Rolling back"). PTBs never run at once (the workflow's concurrency group) and take
  far longer than a minute, and the plan job refuses a code that is not above the last PTB's. It stays below Play's
  limit of 2100000000 until 2099.
- **versionName** follows Mudlet's desktop PTBs (`Mudlet-<APP_VERSION>-ptb-<date>-<commit>`, `CI/set-build-info.sh`
  and `create-github-release.yml` in Mudlet/Mudlet) and adds this repository's commit:
  `5.1.0-ptb-2026-11-14-abc1234-android-def5678`, where `abc1234` is Mudlet's and `def5678` this repository's.

### Signing

Play App Signing: Google holds the key the app is signed with on devices, and the workflow signs what it uploads
with an *upload key*, which Play checks. A lost or leaked upload key can be replaced (Play Console, Setup, App
signing, "Request upload key reset"); the app signing key never leaves Google. Play fixes an app's upload key
at its first upload, so this key is also the one Mudlet's stable AABs are signed with ([`release.md`](release.md)).
The GitHub APKs are signed with the upload key too; they are Mudlet PTB, another app, so they install beside
Play's Mudlet.

Without the upload key's secrets, and on every branch but `main`, the job makes a throwaway key, so the signing path
is exercised on every run, and publishes nothing: a key that changes every night would leave no installed PTB able to
take the next one.

## Why Google Play's open testing track

Checked in November 2026. For testers it is the simplest way: one link, then installs and updates come from Play
like any app's, with no "install unknown apps" switch and no download to repeat each night. Its requirements are met:

- **AAB**: required for new apps. The PTB is one AAB with both ABIs; Play gives each device only its own.
- **Target API level**: since 31 August 2026 new apps and updates target Android 16 (API 36). The app targets 36.
- **16 KB memory pages**: required for apps targeting Android 15 or later. Every library is 16 KB aligned, and
  `scripts/check-apk.sh` checks each APK.
- **Play App Signing**: required for new apps; above.
- **Review**: each upload to a testing track is reviewed, usually within hours, sometimes days; until then testers
  keep the last approved PTB.
- **Developer account**: a *personal* account made after 13 November 2023 must run a closed test with at least 12
  testers for 14 days before it may use open testing or production. An *organization* account, which needs a D-U-N-S
  number, has no such wait. Mudlet's account is personal, so the PTB starts on Mudlet's closed testing; after 14 days
  with 12 testers opted in, apply for production access (Dashboard), then set up open testing as step 7 describes
  for closed testing and set the repository variable `PLAY_PTB_TRACK` to `beta`.
- **Open testing is public**: anyone may join, with no limit, and an app with no production release is found by
  searching Play; testers' feedback is private and gives no public rating. The listing should say it is a nightly.

The alternatives were weaker for testers: Play's internal testing allows 100 testers added by email; closed testing
needs each tester's address or a Google Group; Firebase App Distribution needs an invitation and its own tester app;
F-Droid builds from source on its own schedule, not nightly, and would need Qt's toolchain; GitHub alone means
downloading by hand each time. GitHub stays as the way in for people without Play.

**Android developer verification**: since 30 September 2026, certified devices in Brazil, Indonesia, Singapore and
Thailand install apps from outside Play only if a verified developer registered them, with their signing key, and
Google plans the same everywhere from 2027. Play registers its own apps; for the GitHub APKs, register
`org.mudlet.mudlet.ptb` with the upload key's certificate (step 11 below), or testers there must use Android's
advanced sideloading flow.

## One-time setup, for the maintainer

1. **A Play Console developer account**, as an organization (<https://play.google.com/console/signup>), with its
   identity verification done. A personal account works too, after the 14-day closed test above.
2. **The upload key**, made on your own machine and kept in your password manager; never in this repository:

   ```sh
   keytool -genkeypair -v -keystore mudlet-ptb-upload.jks -storetype PKCS12 -keyalg RSA -keysize 4096 \
       -validity 10000 -alias upload -dname "CN=Mudlet PTB upload key, O=Mudlet"
   keytool -list -v -keystore mudlet-ptb-upload.jks -alias upload | grep SHA256   # its certificate's fingerprint
   ```

3. **The upload key's repository secrets**: Settings, Secrets and variables, Actions, "New repository secret":

   | Secret | Value |
   |---|---|
   | `PLAY_UPLOAD_KEYSTORE_BASE64` | `base64 -w0 mudlet-ptb-upload.jks` |
   | `PLAY_UPLOAD_KEYSTORE_PASSWORD` | the keystore's password |
   | `PLAY_UPLOAD_KEY_ALIAS` | `upload` |
   | `PLAY_UPLOAD_KEY_PASSWORD` | the key's password; leave it out for a PKCS12 keystore, where it is the keystore's |

   Only the package job's signing step reads them, and only on `main`.
4. **The first AAB**: Actions, PTB, "Run workflow" on `main`, with `force` ticked. With the upload key but no Play
   credentials it builds, runs the smoke test and publishes only the GitHub pre-release. Download the run's `ptb`
   artifact; the AAB is `dist/mudlet-ptb.aab`, which is Mudlet (`org.mudlet.mudlet`). Play's API cannot create an app or make its first release, so this
   one goes up by hand.
5. **The app in Play Console**: Home, "Create app": name "Mudlet", default language English (United States), App,
   Free, and accept the declarations.
6. **The app's content** (Policy and programs, App content, and Grow, Store presence), which Play requires before
   any release, open testing included: a privacy policy URL,
   `https://github.com/Mudlet/android/blob/main/docs/privacy-policy.md` ([`privacy-policy.md`](privacy-policy.md));
   app access (nothing behind a login); ads (none); content rating questionnaire; target audience; data safety (no
   data collected, no data shared: what a player sends goes only to the game server they chose); the category, Games,
   Role Playing; the **foreground service** declaration: `specialUse`, with the
   reason in `engine-service/src/main/AndroidManifest.xml` (keeping the player's game connection and scripts running
   in the background), and a short video of it if Play asks; and Mudlet's store listing: a short and a full
   description, the 512×512 icon, the 1024×500 feature graphic and at least two screenshots.
   [`store-listing.md`](store-listing.md) has the text, the screenshots to take and how, and each of these answers
   with its evidence in the code.
7. **Closed testing, with Play App Signing**: Test and release, Testing, Closed testing, the Alpha track. Under
   Countries/regions, add them all; under Testers, add the Google Group `mudlet-ptb-testers@googlegroups.com`, which
   anyone can join, and the feedback URL `https://github.com/Mudlet/android/issues`. "Create new release": when asked
   how the app is signed, choose the Google-generated app signing key; the AAB's own key then becomes the upload key.
   Upload `mudlet-ptb.aab`, keep the release notes the workflow wrote (`dist/whatsnew/whatsnew-en-US`), then "Next",
   "Save", and on the Publishing overview "Send for review". Play reviews it before testers can join.
8. **The service account Play's API uses**: in the Google Cloud console (<https://console.cloud.google.com>), pick
   or create a project, enable the "Google Play Android Developer API" (APIs and services, Library), then IAM and
   admin, Service accounts, "Create service account", named `mudlet-ptb-publisher`, with no project roles. On it,
   Keys, "Add key", "Create new key", JSON, and keep the file it downloads.
9. **Its Play permissions, the fewest that publish**: Play Console, Users and permissions, "Invite new users", the
   service account's email; no account permissions; App permissions, "Add app", Mudlet, with only "Release apps
   to testing tracks". It cannot touch any other app, or production. They can take up to a day to reach the API.
10. **The `play` environment**: Settings, Environments, "New environment" named `play`; Deployment branches and tags,
    "Selected branches and tags", add `main`; then "Add environment secret" `PLAY_SERVICE_ACCOUNT_JSON`, the JSON
    file's whole content. Only the play job uses the environment, so only a run on `main` can read the secret.
11. **Developer verification for the GitHub APKs**: in Play Console, register `org.mudlet.mudlet.ptb` for
    distribution outside Play with the upload key's certificate (its SHA-256 is in step 2's output, and in each
    pre-release's notes). Google is still extending this part of Play Console; until it is done, testers in the
    regions above install the APKs with Android's advanced flow.

From then on each night that has something new uploads to Play and replaces the GitHub pre-release. Uploads are
sent for review automatically; with Publishing overview's "Managed publishing" on, they wait for you to publish them.

## Joining as a tester

- **From Google Play**: join the [Mudlet Android Testers](https://groups.google.com/g/mudlet-ptb-testers) group, then
  open <https://play.google.com/apps/testing/org.mudlet.mudlet> signed in with the same Google account, "Become a
  tester", then follow its link to Play and install Mudlet. Play updates it to each nightly. To leave, open the same
  link and "Leave the program", then uninstall Mudlet, which deletes its profiles, and install it again from Play.
  Feedback written in Play reaches the developers privately.
- **From GitHub**: on <https://github.com/Mudlet/android/releases/tag/ptb>, download `mudlet-ptb-arm64-v8a.apk` for
  a phone or tablet (`mudlet-ptb-x86_64.apk` for a Chromebook with an Intel or AMD chip, or an emulator), check it
  against `SHA256SUMS.txt`, open it and allow your browser to install apps (`mudlet-ptb-source.tar` beside it is
  the source code, which installing does not need). The links
  (`.../releases/download/ptb/mudlet-ptb-arm64-v8a.apk`) always give the latest PTB; installing a newer one updates
  it. It cannot update an install from Play, or be updated by one: uninstall first.

From GitHub it installs beside Mudlet, as "Mudlet PTB", with its own profiles. Report problems as issues here.
[`device-pass.md`](device-pass.md) is what to try on a real phone, and its report form.

## A newer build from GitHub

Play updates its own installs; nothing updates one from GitHub. So a PTB that Google Play did not install tells its
player when a newer one is out (`app/src/main/kotlin/org/mudlet/android/update/`):

- **Which copies.** Only the PTB has the check: `BuildConfig.PTB_UPDATE_CHECK` is true only with
  `-PreleaseChannel=ptb`, and as a constant it lets R8 drop the network code from the stable app. Within the PTB, a
  copy checks unless Android names Google Play (`com.android.vending`) as its installer (`getInstallSourceInfo` on
  Android 11 and later, `getInstallerPackageName` before). An APK opened from a browser or file manager, installed by
  adb, or by another store counts as from GitHub: Android names the package installer, the shell or nothing for
  those, and every one of them leaves the copy with no other way to hear of a newer build. Play's own copies never
  ask, as Play's policy allows an app no way to update but Play.
- **When.** At most once a day, when the app comes to the front, it asks
  `https://api.github.com/repos/Mudlet/android/releases/tags/ptb` without signing in, and reads the
  `<!-- versionCode: N -->` line that the github-release job writes in the pre-release's notes. The time is kept
  before it asks (`ptbLastCheck` in the `ui` SharedPreferences), so a failed or odd answer (no network, a rate limit,
  the pre-release deleted while a run replaces it) does nothing and is not asked again until the next day.
- **What it sends.** A plain GET with the User-Agent `Mudlet-PTB`, the same for every copy, in place of Android's,
  which names the device's model; nothing about the player, their games or the device. The answer is never logged:
  only its HTTP status, the error's type, or that it held no versionCode.
- **What the player sees.** A versionCode above the installed one shows "A newer test build is available" at the top
  of the profile list, with Download, which opens the pre-release's page in the browser, and Dismiss, after which
  that build is not offered again (`ptbDismissedVersion`); a newer one is. The app never installs anything itself and
  does not ask for `REQUEST_INSTALL_PACKAGES`.
- **Turning it off.** Settings, "Test builds", "Check for new test builds" (`checkTestBuilds`, on by default): while
  it is off the app asks nothing and shows no notice. The page is there only in a copy that checks.

A rollback's rebuild (below) gets a higher versionCode, so it is offered as well.

## Rolling back

Android never installs an older versionCode over a newer one, so a bad PTB is replaced by a newer build of good code:

1. **Stop the nightlies** while you fix it: Actions, PTB, "…", "Disable workflow".
2. **Build the last good code again**: Actions, PTB, "Run workflow" on `main` with `mudlet_ref` set to the last good
   Mudlet commit (from the earlier pre-release notes, or Play Console's release names) and `force` ticked. If this
   repository's change was the bad one, revert it on `main` first. The rebuild gets a new, higher versionCode, so Play
   and the pre-release's links offer it as an update.
3. **While Play reviews it**, the bad build stays on the track. To stop new installs of it sooner, pause the track in
   Play Console (Testing, Closed testing, "Pause track"); testers who have it keep it.
4. **Enable the workflow again** once Mudlet's `development` or `main` is fixed.

To take the GitHub APKs down at once: `gh release delete ptb --cleanup-tag --yes`. The next run then builds whatever
is current, as no last PTB is left to compare with.

## Building a release locally

```sh
# Each ABI's engine, staged (with LIBMUDLET_BUILD_DIR for libmudlet; x86_64 needs Qt for android_x86_64)
STAGE_ONLY=1 DEPLOY_DIR=staged/arm64-v8a scripts/build-apk.sh
ANDROID_ABI=x86_64 STAGE_ONLY=1 DEPLOY_DIR=staged/x86_64 scripts/build-apk.sh
# The AAB, an APK for each ABI and the smoke build; unsigned without MUDLET_KEYSTORE
MUDLET_KEYSTORE=... MUDLET_KEYSTORE_PASSWORD=... MUDLET_KEY_ALIAS=upload \
    scripts/build-release.sh staged/arm64-v8a staged/x86_64 -- -PreleaseChannel=ptb
```

R8's rules for what Qt and the engine reach by name are in `engine-service/consumer-rules.pro`: all of Qt's Java
classes, `EngineNative`'s native method and `EngineNotifications.postTell`, which C++ calls. A new JNI call from C++
into Kotlin needs a rule there, and the smoke build on the emulator (`android_smoke.py --package`) is how to see one
missing.
