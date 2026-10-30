# Approach X: <name>

## Verdict
Three to five sentences: does this approach work as a foundation, and what is its biggest risk?

## Status
| Check | Result |
|-------|--------|
| Engine desktop build and fake-server test | pass / fail |
| APK builds (`assembleDebug`) | yes / no |
| APK path | |
| Clean build time | |
| APK size (download estimate and uncompressed `lib/arm64-v8a` total) | |
| Largest native libraries | |
| 16 KB page alignment (`zipalign -c -P 16 -v 4`, and `readelf -lW` LOAD alignment of each .so) | |

## How it is wired
Startup sequence, which thread runs what, how calls cross in each direction, and how the foreground service is declared.

## Qt internals relied on
List every Qt Java class, metadata key or behaviour this depends on that Qt does not document as public API. Note each place it would break on a Qt upgrade.

## Lifecycle
Expected behaviour (mark verified or expected) when:
- the Activity is finished or recreated (rotation)
- the service is stopped
- the process is killed and restarted
- the screen is off for a long time

## Cost to build Mudlet on it
What changes when the stand-in engine is replaced with `mudlet_core` and the frontend interfaces (TConsoleFrontend, TAppFrontend, ...)? What hurts in daily development: build time, debugging, the Gradle/CMake split?

## Size of the prototype
Lines of Kotlin, C++ and build files.

## On-device checklist
What the person installing the APK should do and look at to confirm the parts you could not verify here.

## Problems hit
Errors and their fixes, so the next person doesn't repeat them.
