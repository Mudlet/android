# Public test builds

A public test build (PTB) is the app built every night from Mudlet's latest `development` and this repository's
`main`, as Mudlet's desktop PTBs are. Testers get it two ways:

- **Google Play's open testing track** of the app **Mudlet PTB** (`org.mudlet.mudlet.ptb`): join with a link,
  install from Play, and Play keeps it up to date;
- the rolling **`ptb` GitHub pre-release**: an APK for each ABI, for people without Play, and the source code
  they are built from.

`.github/workflows/ptb.yml` builds and publishes them. This page says how it works, what the maintainer sets up once,
how testers join, and how to roll a bad PTB back.

## How a PTB is made

Every night at 05:23 UTC (after the libmudlet nightly, whose ccache it takes up), and when run by hand:

1. **plan**: resolves Mudlet's `development` to a commit, and reads the last PTB's commits from the markers in the
   `ptb` pre-release's notes. If neither Mudlet's commit nor this repository's changed, it stops there (a manual run
   with `force` builds anyway); while there is no pre-release, before the setup below, every run builds. It works
   out the version (below).
2. **engine** (arm64-v8a and x86_64 at once): libmudlet and its dependencies, with libmudlet.yml's caches, and the
   engine staged by `STAGE_ONLY=1 scripts/build-apk.sh`. libmudlet is built with `MUDLET_VERSION_BUILD=-ptb-<date>`,
   as Mudlet's own PTBs are, so the engine calls itself `<version>-ptb-<date>-<commit>`.
3. **source** (beside engine): `scripts/build-source-bundle.sh` builds `mudlet-ptb-source.tar`, the source code
   the release is built from ("The source bundle" below). Every run builds it, so a dry run shows it still builds;
   only a run that publishes keeps it.
4. **package**: `scripts/build-release.sh` merges the two staged engines and builds the release AAB (both ABIs), an APK
   for each ABI, and the smoke build (below), with R8 and resource shrinking. It checks each APK as CI checks every APK
   (`scripts/check-apk.sh`: Qt, the engine, OpenSSL, 16 KB alignment, the launcher icon, the licences), and that the AAB
   and the APKs carry the PTB's application id, version and signing certificate, libraries for exactly the two ABIs, the
   Baseline Profiles Compose and AndroidX ship (`docs/roadmap.md` on one of the app's own), and in the AAB R8's mapping
   and the native symbol tables, which Play uses for its crash reports. It checks the APKs' and the AAB's sizes against
   their budgets (`scripts/check-apk-size.sh`, CONTRIBUTING.md's "APK size budget"): over budget fails a pull request,
   while a nightly or manual PTB still publishes, with the error in its summary.
5. **smoke**: the x86_64 smoke build on an Android 15 emulator, with the test libmudlet.yml runs on the debug build
   (`native/tests/android_smoke.py`): the engine service and Qt come up, a profile connects and runs Mudlet's Lua, a
   trigger fires and notifies, and the UI reattaches to a restarted engine. This is what shows that R8 kept what Qt
   and the engine reach by name.
6. **play**: uploads the AAB to the open testing track (`beta` in Play's API) with
   [r0adkll/upload-google-play](https://github.com/r0adkll/upload-google-play), pinned to a commit.
7. **github-release**: replaces the `ptb` pre-release, its tag on this repository's commit, with the APKs, the
   source bundle, `SHA256SUMS.txt` and notes naming both commits.

Steps 6 and 7 run only on `main`, only after the smoke test passed and the source bundle was built, and only with
the secrets below; the GitHub
pre-release only after Play took the AAB, or when no Play credentials are set. On any other branch, and on a pull
request that changes the release build (the workflow, `scripts/build-release.sh`, `scripts/merge-qtdeploy.py`, the
R8 rules or the Gradle files), the run is a dry run: it builds, signs with a throwaway key, runs the smoke test and
publishes nothing. A failed nightly opens or updates the issue "Nightly PTB fails".

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

### Identity: an app of its own

PTBs are the app `org.mudlet.mudlet.ptb`, named "Mudlet PTB", with a Play listing of their own, not the testing
track of the stable `org.mudlet.mudlet`:

- it installs beside Mudlet, as "Mudlet PTB.exe" and "Mudlet PTB.app" do on the desktop, so a tester keeps a working
  Mudlet while trying the nightly, and leaving the test does not mean reinstalling;
- a stable user can never get a nightly by joining a test by mistake, and a nightly's crashes and ANRs count against
  the PTB's Android vitals, not the stable app's, whose vitals affect how Play shows it;
- its versions need not fit around the stable app's.

The cost: a second listing to fill in (below), and its own profiles: Android keeps each app's data apart. A stable
listing does not exist yet; when it does, its open testing track can still carry release candidates.

The build takes `-PreleaseChannel=ptb` (`app/build.gradle.kts`), which adds `.ptb` to the application id, names
the app "Mudlet PTB" and gives it its own launcher icon: Mudlet's arch at night, as on the desktop PTB's icon
([`launcher-icon.md`](launcher-icon.md)). It is a Gradle property rather than a product flavour so that the variants and their tasks and
outputs (`assembleDebug`, `app-debug.apk`), which the scripts and the other workflows use, stay as they are.

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
signing, "Request upload key reset"); the app signing key never leaves Google. The GitHub APKs are signed with the
upload key, so an install from Play and an install from GitHub cannot update each other; a tester switching uninstalls
first. Keeping the app signing key out of GitHub is worth that.

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
  number, has no such wait, and suits Mudlet.
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
   artifact; the AAB is `dist/mudlet-ptb.aab`. Play's API cannot create an app or make its first release, so this
   one goes up by hand.
5. **The app in Play Console**: Home, "Create app": name "Mudlet PTB", default language English (United States), App,
   Free, and accept the declarations.
6. **The app's content** (Policy and programs, App content, and Grow, Store presence), which Play requires before
   any release, open testing included: a privacy policy URL,
   `https://github.com/Mudlet/android/blob/main/docs/privacy-policy.md` ([`privacy-policy.md`](privacy-policy.md));
   app access (nothing behind a login); ads (none); content rating questionnaire; target audience; data safety (no
   data collected, no data shared: what a player sends goes only to the game server they chose); the category, Games,
   Role Playing; the **foreground service** declaration: `specialUse`, with the
   reason in `engine-service/src/main/AndroidManifest.xml` (keeping the player's game connection and scripts running
   in the background), and a short video of it if Play asks; and the store listing: a short and a full description
   saying this is a nightly test build, the 512×512 icon, the 1024×500 feature graphic and at least two screenshots.
   [`store-listing.md`](store-listing.md) has the text, the screenshots to take and how, and each of these answers
   with its evidence in the code.
7. **Open testing, with Play App Signing**: Test and release, Testing, Open testing. Under Countries/regions, add
   them all; under Testers, leave the tester limit unlimited. "Create new release": when asked how the app is signed,
   choose the Google-generated app signing key; the AAB's own key then becomes the upload key. Upload
   `mudlet-ptb.aab`, keep the release notes the workflow wrote (`dist/whatsnew/whatsnew-en-US`), then "Next", "Save",
   and on the Publishing overview "Send for review". Play reviews it before testers can join.
8. **The service account Play's API uses**: in the Google Cloud console (<https://console.cloud.google.com>), pick
   or create a project, enable the "Google Play Android Developer API" (APIs and services, Library), then IAM and
   admin, Service accounts, "Create service account", named `mudlet-ptb-publisher`, with no project roles. On it,
   Keys, "Add key", "Create new key", JSON, and keep the file it downloads.
9. **Its Play permissions, the fewest that publish**: Play Console, Users and permissions, "Invite new users", the
   service account's email; no account permissions; App permissions, "Add app", Mudlet PTB, with only "Release apps
   to testing tracks". It cannot touch any other app, or production. They can take up to a day to reach the API.
10. **The `play` environment**: Settings, Environments, "New environment" named `play`; Deployment branches and tags,
    "Selected branches and tags", add `main`; then "Add environment secret" `PLAY_SERVICE_ACCOUNT_JSON`, the JSON
    file's whole content. Only the play job uses the environment, so only a run on `main` can read the secret.
11. **Developer verification for the GitHub APKs**: in Play Console, register `org.mudlet.mudlet.ptb` for
    distribution outside Play with the upload key's certificate (its SHA-256 is in step 2's output, and in each
    pre-release's notes). Google is still extending this part of Play Console; until it is done, testers in the
    regions above install the APKs with Android's advanced flow.

From then on each night that has something new uploads to Play, and once Play took it, replaces the GitHub
pre-release. Uploads are sent for review automatically; with Publishing overview's "Managed publishing" on, they wait
for you to publish them.

## Joining as a tester

- **From Google Play**: open <https://play.google.com/apps/testing/org.mudlet.mudlet.ptb> signed in with your Google
  account, "Become a tester", then follow its link to Play and install Mudlet PTB. Play updates it like any app. To
  leave, open the same link and "Leave the program", then uninstall it. Feedback written in Play reaches the
  developers privately.
- **From GitHub**: on <https://github.com/Mudlet/android/releases/tag/ptb>, download `mudlet-ptb-arm64-v8a.apk` for
  a phone or tablet (`mudlet-ptb-x86_64.apk` for a Chromebook with an Intel or AMD chip, or an emulator), check it
  against `SHA256SUMS.txt`, open it and allow your browser to install apps (`mudlet-ptb-source.tar` beside it is
  the source code, which installing does not need). The links
  (`.../releases/download/ptb/mudlet-ptb-arm64-v8a.apk`) always give the latest PTB; installing a newer one updates
  it. It cannot update an install from Play, or be updated by one: uninstall first.

Either way it installs beside Mudlet, as "Mudlet PTB", with its own profiles. Report problems as issues here.

## Rolling back

Android never installs an older versionCode over a newer one, so a bad PTB is replaced by a newer build of good code:

1. **Stop the nightlies** while you fix it: Actions, PTB, "…", "Disable workflow".
2. **Build the last good code again**: Actions, PTB, "Run workflow" on `main` with `mudlet_ref` set to the last good
   Mudlet commit (from the earlier pre-release notes, or Play Console's release names) and `force` ticked. If this
   repository's change was the bad one, revert it on `main` first. The rebuild gets a new, higher versionCode, so Play
   and the pre-release's links offer it as an update.
3. **While Play reviews it**, the bad build stays on the track. To stop new installs of it sooner, pause the track in
   Play Console (Testing, Open testing, "Pause track"); testers who have it keep it.
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
