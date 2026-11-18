# Stable releases

The stable app is **Mudlet** (`org.mudlet.mudlet`), with a Google Play listing of its own beside **Mudlet PTB**
([`ptb.md`](ptb.md), "Identity"): it installs beside the PTB, keeps its own profiles, and its crashes count against its
own Android vitals. `.github/workflows/release.yml` builds and publishes it. This page says how a release is made, how
to cut one, promote it and roll it back, and what the maintainer sets up once.

## How a release is made

Pushing a tag `android-v<version>` on a commit of `main` starts it:

1. **plan** (`scripts/release-plan.sh`): checks the tag is on `main`, takes the version from it, the Play track and the
   release notes for it from [`release-notes.md`](release-notes.md), and resolves the Mudlet commit to build (below).
   A tag that cannot be released (not on `main`, no notes for its version, a Mudlet branch rather than a fixed commit)
   fails here, before anything is built; it never turns into a dry run.
2. **build**: `release-build.yml`, the PTB's own build (`ptb.md`, "How a PTB is made", steps 2 to 5): libmudlet and
   the engine for arm64-v8a and x86_64, the source bundle, the AAB and an APK for each ABI with R8, their checks and
   size budgets, and the smoke test of the minified build on an emulator. It signs with the stable upload key.
3. **notes**: the GitHub release's notes: the release's section, the two commits built and the signing certificate.
4. **play**: uploads the AAB to the Play track, once the smoke test passed and the source bundle was built.
5. **github-release**: the release `android-v<version>` on the tag, with `mudlet-arm64-v8a.apk`, `mudlet-x86_64.apk`,
   `mudlet-source.tar` and `SHA256SUMS.txt`; a pre-release while the version is on a testing track. Unlike the PTB's, it
   waits for Play to take the AAB, or runs alone when no Play credentials are set: a tag is released once.
6. **result**: says in the run's summary what was published, or why nothing was.

**Dry runs.** Any other run publishes nothing: by hand (Actions, Release, "Run workflow") on a branch, and on a pull
request that changes the workflow, the build it shares with the PTB, the plan script, the Mudlet commit or the release
notes. It builds, signs with a throwaway key, runs the smoke test, keeps the AAB and the APKs as the run's `stable`
artifact, and its summary says that nothing was published and why. A tag without the stable upload key's secrets
builds the same way and publishes nothing either. Nothing else can publish: the upload key is read only on a tag, and
the Play credentials only by the job that uploads, which runs only on a tag, in the `play-stable` environment.

### Versions

- **versionName** is the tag's version: `android-v0.1.0` is `0.1.0`, as `x.y.z` or `x.y.z-<label>` (`0.2.0-rc.1`).
  Which numbers to use is the maintainers' choice: `0.x` says early access while the app is on open testing; Mudlet's
  own numbers (`5.2.0`) say which Mudlet it is, and the About screen shows the engine's version either way.
- **versionCode** is the PTB's scheme, the build's UTC time as `yyDDDHHmm` ([`ptb.md`](ptb.md), "Versions"), so it
  only grows and a rebuild of the same tag is a new code Play accepts. The two apps' codes never meet, as they are
  different apps.

### The Mudlet it builds

`scripts/release-mudlet-ref.txt` names the Mudlet/Mudlet commit a release builds its engine from: a full commit SHA, or
a release tag `Mudlet-x.y.z`. Not a branch: a release must build the same engine when it is run again. A manual run
may give another in `mudlet_ref`.

It is a commit of Mudlet's `development` for now: Mudlet's newest release, 5.1.1, has no headless engine
(`src/headless/`, which the app runs), and the patches in `scripts/mudlet-patches/` are made against `development`. An
engine built from a commit is versioned `<APP_VERSION>-android-<commit>`, so it never claims to be a Mudlet release;
built from a `Mudlet-x.y.z` tag it is `x.y.z`. Move the file to a commit the nightly PTB has built and tested (the
`<!-- mudlet: ... -->` line of the `ptb` pre-release's notes, or Play Console's release names) before tagging, and to
a Mudlet release tag once a release has the headless engine and the patches apply to it.

### Release notes

[`release-notes.md`](release-notes.md) has a section per version, `## <version>`, which becomes Play's "What's new" and
the top of the GitHub release's notes: plain text, at most 500 characters. A dry run without one uses a placeholder.

### The Play track

| Track | API name | Who gets it |
|---|---|---|
| Internal testing | `internal` | Up to 100 testers added by email |
| Closed testing | `alpha` | Testers added by email or Google Group |
| Open testing | `beta` | Anyone who joins with the link (the default) |
| Production | `production` | Everyone, as a staged rollout |

The repository variable `PLAY_STABLE_TRACK` (Settings, Secrets and variables, Actions, Variables) sets it for tags;
unset, it is `beta`, open testing. A manual run on a tag can give `track` instead. `production` rolls out to the share
of users that `PLAY_STABLE_ROLLOUT` (or the run's `rollout`) gives, `0.1` unset; `1` sends it to everyone at once.

## Cutting a release

1. On `main`, add the version's section to `docs/release-notes.md`, and move `scripts/release-mudlet-ref.txt` to the
   Mudlet commit to release if it should change; merge that as a pull request, whose dry run shows the release builds.
2. Tag the merged commit and push the tag:

   ```sh
   git fetch origin main && git tag -a android-v0.1.0 -m "Mudlet for Android 0.1.0" origin/main
   git push origin android-v0.1.0
   ```

3. Watch Actions, Release. The run's summary says what it published. Play reviews each upload, usually within hours;
   with Publishing overview's "Managed publishing" on, it waits for you to publish it.

To run a tag's release again (say once the secrets are set), use Actions, Release, "Run workflow" and pick the tag as
the branch. A tag whose GitHub release already exists fails at the last step; delete that release first if it is to be
replaced. Never move or reuse a tag: tag the fix as the next version.

## Promoting and rolling out

From open testing to production, in Play Console: Test and release, Testing, Open testing, the release, "Promote
release", Production. Choose a staged rollout (say 10%), then raise it under Production, "Update rollout", while
Android vitals stay quiet; or set `PLAY_STABLE_TRACK` to `production` and tag the next version, which then goes to
`PLAY_STABLE_ROLLOUT` of users. The GitHub release of a version promoted by hand stays a pre-release until you edit it.

## Rolling back

Android never installs an older versionCode over a newer one, so a bad release is replaced by a newer one:

1. **Stop it spreading**: in Play Console, Production, "Halt rollout" (users who have it keep it), or pause a
   testing track.
2. **Release the last good code** as the next version: revert the change on `main` (or set
   `scripts/release-mudlet-ref.txt` back to the last good Mudlet commit), add its notes, and tag it. Its versionCode is
   higher, so Play offers it as an update.
3. To take the GitHub APKs down at once: `gh release delete android-v<version> --yes` (the tag stays, as the record of
   what was released).

## One-time setup, for the maintainer

The stable app's steps are the PTB's ([`ptb.md`](ptb.md), "One-time setup"), with the stable names. If the PTB is set
up, the account and the service account already exist.

1. **The Play Console developer account**: the same as the PTB's. A personal account made after 13 November 2023 must
   run a closed test with at least 12 testers for 14 days before open testing or production: set `PLAY_STABLE_TRACK`
   to `alpha` until then.
2. **The stable upload key**, a key of its own, so a leaked PTB key cannot sign the stable app; made and kept on your
   own machine, never in this repository:

   ```sh
   keytool -genkeypair -v -keystore mudlet-upload.jks -storetype PKCS12 -keyalg RSA -keysize 4096 \
       -validity 10000 -alias upload -dname "CN=Mudlet upload key, O=Mudlet"
   keytool -list -v -keystore mudlet-upload.jks -alias upload | grep SHA256   # its certificate's fingerprint
   ```

3. **Its repository secrets** (Settings, Secrets and variables, Actions, "New repository secret"):
   `STABLE_UPLOAD_KEYSTORE_BASE64` (`base64 -w0 mudlet-upload.jks`), `STABLE_UPLOAD_KEYSTORE_PASSWORD`,
   `STABLE_UPLOAD_KEY_ALIAS` (`upload`), and `STABLE_UPLOAD_KEY_PASSWORD` only if the key's password differs from the
   keystore's. Only the package job's signing step reads them, and only on a release tag.
4. **The first AAB**: add the first version's notes, then tag it as above. With the upload key but no Play credentials
   the run builds, signs, runs the smoke test and makes only the GitHub release (delete it if it should wait for
   Play). Download the run's `stable` artifact; the AAB is `dist/mudlet.aab`. Play's API cannot create an app or make
   its first release, so this one goes up by hand.
5. **The app in Play Console**: Home, "Create app": name "Mudlet", default language English (United States), App, Free.
6. **The app's content**, as the PTB's step 6, with the stable listing's text from [`store-listing.md`](store-listing.md)
   (not the PTB's "nightly test build" wording), the same privacy policy URL, and the category and contact email
   decided for it.
7. **The first release**, as the PTB's step 7, on the track decided for it (open testing unless decided otherwise),
   choosing the Google-generated app signing key, with `dist/whatsnew/whatsnew-en-US` as its notes.
8. **The service account's access**: Play Console, Users and permissions, the PTB's `mudlet-ptb-publisher` service
   account (or a new one, made as in the PTB's step 8), App permissions, "Add app", Mudlet, with "Release apps to
   testing tracks"; add "Release to production, exclude devices, and use Play App Signing" only if tags are to go to
   production by themselves.
9. **The `play-stable` environment**: Settings, Environments, "New environment" `play-stable`; Deployment branches and
   tags, "Selected branches and tags", add the tag rule `android-v*`; then the environment secret
   `PLAY_SERVICE_ACCOUNT_JSON`, the service account's JSON key. A tag ruleset (Settings, Rules, Rulesets, target tags
   `android-v*`, restrict creations to maintainers) keeps anyone else from starting a release.
10. **Developer verification for the GitHub APKs**: register `org.mudlet.mudlet` with the stable upload key's
    certificate, as the PTB's step 11.
11. **The variables** if the defaults do not suit: `PLAY_STABLE_TRACK` and `PLAY_STABLE_ROLLOUT` (above).
