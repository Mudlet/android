# Foundation checklist

What is settled before UI work starts, and what is left for the maintainer: GitHub settings only an admin can change,
and two decisions. November 2026.

## Settled

| Area | State | Where |
|---|---|---|
| Hosting | Prototype C: Compose UI process without Qt, libmudlet in a `QtServiceBase` foreground service in `:engine` | [`decisions/hosting.md`](decisions/hosting.md) |
| Protocol | Version 1: 4-byte length + JSON frames, a 2000-line ring replayed on attach, flow control; `ProtocolSpecTest` keeps the spec, C++, Kotlin and Python in step | [`protocol.md`](protocol.md) |
| CI | APKs (stand-in engine, desktop and JVM tests, APK checks, Android lint), libmudlet (arm64-v8a APK, the real engine on the host, the x86_64 APK on an emulator, nightly against Mudlet's `development`), Checks (ktlint, clang-format, ShellCheck, actionlint) | [`README.md`](../README.md#ci), `.github/workflows/` |
| TLS | OpenSSL 3.5.9 (LTS) as `libcrypto_3.so`/`libssl_3.so` in every APK, for Qt's OpenSSL backend; checked in each APK | [`architecture.md`](architecture.md#tls) |
| Restore | A killed engine restores its profile and wanted connection from `engine-session.json`; an orderly stop restores nothing; after three restores in 10 minutes it gives up | [`architecture.md`](architecture.md#restoring-the-session) |
| Lua 5.1 FORTIFY | `-U_FORTIFY_SOURCE` for Lua 5.1 on Android, carried in `scripts/build-libmudlet.sh` until Mudlet/Mudlet#12914 is merged; the emulator job catches the abort | [`README.md`](../README.md#ci) |
| Tooling | Gradle wrapper 8.14.3 (checksummed), Dependabot weekly and grouped, the version pins and how to bump them | [`CONTRIBUTING.md`](../CONTRIBUTING.md) |

## For the maintainer: GitHub settings

**Required status checks for `main`** (Settings, Rules, a ruleset on the default branch, "Require status checks to
pass"). GitHub matches them by job name:

| Check | Workflow | Require? |
|---|---|---|
| `ktlint`, `clang-format`, `shellcheck`, `actionlint` | Checks | Yes: they run on every pull request, and are quick |
| `APK`, `APK (extracted libs)` | APKs | Yes, once `apks.yml`'s `pull_request` trigger loses its `paths` filter (below) |
| `libmudlet`, `desktop-engine`, `emulator` | libmudlet for Android | No: they build Mudlet's moving `development`, so they can go red for a reason outside the pull request. Read them on pull requests that touch `native/`, `engine-service/` or the scripts; the nightly opens an issue when they fail |

A workflow skipped by its `paths` filter never reports, and a required check that never reports leaves the pull
request waiting for ever. So a workflow whose checks are required must run on every pull request: drop `paths:` from
`apks.yml`'s `pull_request` trigger (keep it on `push`) when making `APK` required. It costs a run of about 4
minutes, with caches, on a pull request that touches only docs.

**The rest of the ruleset for `main`:**
- require a pull request before merging; with one maintainer, no required approvals, or the maintainer cannot merge
  their own;
- block force pushes and deletion;
- no "require branches to be up to date": with Mudlet's nightly moving, it would rerun the long jobs for little;
- let admins bypass, for an emergency.

**Elsewhere in Settings:**
- Code security: Dependabot alerts and security updates on (`.github/dependabot.yml` only does version updates), and
  secret scanning with push protection.
- General: automatically delete head branches.
- Actions, General: workflow permissions read-only by default (each workflow already asks for `contents: read`, and
  only the nightly's issue job for `issues: write`).

## For the maintainer: decisions

**ABIs to ship: arm64-v8a and x86_64.** Qt 6.12.0 has Android builds for arm64-v8a, armeabi-v7a, x86_64 and x86
(`aqt list-qt all_os android --arch 6.12.0`).
- arm64-v8a: nearly every phone and tablet that can run Android 9 (minSdk 28, Qt 6.12's minimum). Google Play needs a
  64-bit library for every 32-bit one an app ships, and, for apps targeting Android 15 or later, 16 KB page support,
  which CI already checks.
- x86_64: Chromebooks with Intel or AMD chips, Google Play Games on PC, and the emulator, which CI already builds for.
  Android lint's `ChromeOsAbiSupport` (off in `lint.xml` until this is decided) asks for it.
- Not armeabi-v7a: only 32-bit-only devices need it, low-end ones with little memory for two processes, and it would
  add a third Qt, dependency and libmudlet build to CI. Not x86: 32-bit x86 Android devices are gone.

Ship them as an Android App Bundle, so each device downloads only its own ABI. Today each APK is built for one ABI
(`-PandroidAbi`, `scripts/build-apk.sh` stages one ABI's Qt), so a release build needs `build-apk.sh` to stage both
ABIs' engines and Qt libraries into `engine-service/qtdeploy` and the app to drop its single-ABI `abiFilters`.

**Qt pin policy.** The engine uses private Qt API (`AndroidServiceSetup`), and Qt's Android templates set the AGP and
Gradle versions, so Qt is pinned to one exact version and moved deliberately:
- Stay on 6.12 and take its patch releases (6.12.x) when they come, in one pull request with whatever NDK and SDK
  levels that release names, after checking the private API still exists. Mudlet's own CI builds with Qt 6.11.3; the
  nightly shows when Mudlet's code stops building with 6.12.
- Move to a new minor release only for a reason: a Play requirement, a fix the app needs, or Mudlet's own CI moving
  past 6.12. That move takes AGP 9 and Gradle 9 with it (Qt 6.12's templates already want AGP 9.2.1).
- Never bump Qt, the NDK, SDK levels, AGP, Kotlin or Gradle one at a time; Dependabot is set up not to. The places
  to change and the checks to run are in [`CONTRIBUTING.md`](../CONTRIBUTING.md#pinned-versions).
