# Translations

The app's text is translated on [Crowdin](https://crowdin.com), as Mudlet's desktop app is: developers write the
English, translators translate it on Crowdin, and Crowdin sends the translations back as a pull request. This page
says how that is set up, how to write a string so it can be translated, what CI checks, and how to see a layout in
text it was not drawn for.

## How it works

- **The English is the source.** `app/src/main/res/values/strings.xml` holds the app's screens and
  `engine-service/src/main/res/values/strings.xml` the engine's notifications. [`crowdin.yml`](../crowdin.yml) names
  both, and where each translation goes: `values-<code>/strings.xml` beside its source, with Crowdin's Android code
  for the language (`values-de-rDE`, `values-pt-rBR`).
- **Crowdin's GitHub integration** reads `crowdin.yml` on `main`, uploads the English whenever it changes, and opens a
  pull request from its own branch with the translations, titled "Improve: New Crowdin updates", as Mudlet's
  `.crowdin.yml` has it. CI runs on it as on any other, and it is squashed into `main` the same way.
- **Translations are never edited here.** Crowdin overwrites a `values-<code>/` file with its next pull request, so a
  wrong translation is fixed on Crowdin. A string not translated yet shows in English (Android lint's
  `MissingTranslation` is off in `lint.xml` for this reason).
- **The engine's own text**, such as a connection error's reason, comes from libmudlet in English; it is Mudlet's to
  translate (`translations/mudlet.ts` in Mudlet/Mudlet, on the same Crowdin). Mudlet's compiled translations
  (`qm.qrc`) are already built into libmudlet, but nothing installs a `QTranslator` for them yet, so they stay
  English until that is added.

## The app's language

- **The languages offered** are the English and each `values-<code>/` folder of `app/src/main/res` whose
  `strings.xml` holds a string: `app/build.gradle.kts` lists them, once for `BuildConfig.TRANSLATIONS`, which the
  setting reads (`AppLanguages`), and once for `res/xml/locales_config.xml` (its `GenerateLocaleConfig` task), the
  list in the manifest's `android:localeConfig`. A language Crowdin exports with nothing translated is left out of
  both, and a pull request from Crowdin that adds a language adds it to both with no other change. AGP's own
  `generateLocaleConfig` is not used, as it would count `:engine-service`'s Qt translations too.
- **Android 13 and later** offer the list in the phone's Settings, Apps, Mudlet, Language, and keep the choice
  themselves (`LocaleManager`) for both of the app's processes.
- **The app's own setting**, Settings, Appearance, Language, works from Android 9 on: "System default" first, then
  each language named in itself and marked as in it, so TalkBack reads "Deutsch" in German. On 13 and later it sets
  the same `LocaleManager` choice, so either place shows the other's. Before 13 it is kept in the file
  `files/app-language` (`AppLanguage` in `:engine-service`), which both processes read: the UI process puts it into
  its resources and recreates its screen, and the engine's notification reads it each time it is built, so a choice
  made while a game is open shows in it at once. A choice made before the phone is updated to Android 13 moves to
  `LocaleManager` on the first start after.
- **Every language is in every install**: the AAB's language splits are off (`bundle { language { enableSplit =
  false } }`), as Play would otherwise install only the phone's languages and a language chosen in the app would show
  in English. The strings are a small part of the APK.
- **Tested with a fake translation**: debug builds carry `values-kl/` (Kalaallisut, which no one will translate the
  app into soon) in `app/src/debug/res` and `engine-service/src/debug/res`, a few strings each prefixed "KL", shaped
  as a Crowdin export is. `AppLanguageTest` picks it in the setting and in Android's per-app setting and checks the
  screens switch to it and back; `EngineNotificationTest` checks the notification follows a choice made after the
  engine started; `LanguagePseudoLocaleTest` checks the row and its list in en-XA and ar-XB.

## Setting up Crowdin

The maintainer does this once, on Crowdin and GitHub; nothing in this repository holds a token. The app has a Crowdin
project of its own, "Mudlet for Android", beside desktop's "mudlet": each language enabled in a project is exported as
a folder, and in this app a folder is a language the player can pick, so the app's languages are only those that have
a translator for it, while desktop's project keeps its own. The two share a translation memory and glossary, so desktop's
words for "profile", "trigger" or "alias" are suggested here.

1. **GitHub**, as an owner of the Mudlet organisation: Settings, GitHub Apps (Installed GitHub Apps), Crowdin,
   Configure, and give it access to `Mudlet/android` beside `Mudlet/Mudlet`.
2. **The project**: on crowdin.com, Create project. Name "Mudlet for Android", public, source language English, and
   as target languages only those that have a translator ready.
3. **Shared wording**: in the new project's Settings, Translation Memories and then Glossaries, add desktop's
   "mudlet" project's.
4. **The repository**: the project's Integrations, GitHub, Set up integration, "Source and translation files mode".
   Pick `Mudlet/android` and its branch `main`, keep the configuration file `crowdin.yml`, and the service branch
   Crowdin suggests (`l10n_main`). If its export options offer "Skip untranslated strings", tick it, as
   `crowdin.yml` asks for.
5. **Check the upload**: the project's Sources lists `app/src/main/res/values/strings.xml` and
   `engine-service/src/main/res/values/strings.xml`, and each string shows its translator's comment as its context.
6. **The pull requests**: in the integration's settings, set how often it syncs. Its pull request, "Improve: New
   Crowdin updates", is squashed into `main` once CI is green, as any other.
7. **Languages**: right-to-left ones (Arabic, Hebrew, Persian, Urdu) need nothing more; the app mirrors for them, as
   the ar-XB pseudo-locale shows. Serbian in Latin script does: Crowdin's Android code for it, `sr-rCS`, is a region
   Android has no Latin script for, so it needs a `languages_mapping` in `crowdin.yml` to `b+sr+Latn` first. A
   language's plural forms come from Crowdin, which asks translators for each form that language has.

The [Crowdin CLI](https://crowdin.github.io/crowdin-cli/) reads the same file, and takes the project and a personal
access token from the environment variables `crowdin.yml` names, so neither is ever written into a file:

```sh
export CROWDIN_PROJECT_ID=<the project's numeric id> CROWDIN_PERSONAL_TOKEN=<a token from your Crowdin account>
crowdin upload sources --dryrun    # what it would upload
crowdin download                   # the translations, into values-<code>/
```

A workflow that used the CLI instead of the integration would keep the two values as repository secrets of those
names.

## Writing a string

- **Every text a user sees or hears is in a `strings.xml`**: on screen, in a notification, in a toast, and what a
  screen reader reads (`contentDescription`, a button's label). Kotlin gets it with `stringResource()` or
  `getString()`. Text only developers see, such as the self-test's, is marked `translatable="false"`; Crowdin leaves
  it out.
- **A comment for translators right before each string**, which Crowdin shows beside it: where the text appears, what
  it does, and what each placeholder stands for (`%1$s is the game's address, as host:port`). A comment before a
  group of strings covers only the first of them.
- **Whole sentences with numbered placeholders** (`%1$s`, `%2$d`), never text put together from pieces, as other
  languages order the words differently; a count is a `<plurals>`, as some languages have more than two forms.
- **A new meaning is a new name**: changing what an English string says, or its placeholders, under the same name
  leaves every translation saying the old thing, or crashing on a missing placeholder, until Crowdin's next pull
  request. Give the changed string a new name; fixing a typo can keep the old one.
- **Room to grow**: a translation can be half as long again as the English, and some languages run right to left.
  Let text wrap rather than cutting it short, and use `start` and `end` rather than `left` and `right`.

## What CI checks

`scripts/lint.sh strings` (`scripts/check-strings.py`), in CI's Checks workflow whenever `app/` or `engine-service/`
changes:

- **No text written into the Kotlin** of `app/` or `engine-service/`: `Text("...")`, `contentDescription = "..."`
  and Compose's other text arguments, a toast's, a snackbar's, a dialog's and a notification's text. Android lint's
  `HardcodedText` looks only at XML layouts, which the app has none of. A literal with no letters (`""`, `"?"`) is not
  text. A line that must keep its literal says why at its end: `// untranslated: <why>`.
- **Every translatable string, plural and string array has its translator's comment** right before it.

Android lint (`lint.xml`) treats a translation as Crowdin's to fix, so what lint would flag in one does not fail the
build: a string the English has dropped (`ExtraTranslation`), the translators' typography and spelling, a plural form
the language does not use or lacks, and Crowdin's folder names (`LocaleFolder`). A translation whose placeholders do
not match the English's still fails (`StringFormatMatches`, `StringFormatInvalid`). The debug build's `values-kl/`
has each of these, so CI's lint run shows the rules hold.

## Pseudo-locales

Debug builds carry Android's two pseudo-locales (`isPseudoLocalesEnabled` in `app/build.gradle.kts`), generated from
the English when the APK is built:

- **en-XA**, "English (XA)": each letter accented and the text made longer, `[Þŕöƒîļéš one]`, which shows text that
  is cut off, and any text that is not in `strings.xml`, as it stays plain;
- **ar-XB**, "Arabic (XB)": the text reversed and the layout mirrored, right to left.

On a phone or emulator with a debug build, turn on Developer options; both then appear in Settings, System,
Languages. `PseudoLocaleTest` (`app/src/androidTest`) runs the app in each, as the per-app language setting of
Android 13 and later does, so on the API 36 emulator of CI's emulator job only: on the profile list, the profile
editor, the settings and the game screen, no text may be cut short, overflow its box or leave the screen, ar-XB must
be mirrored, and the accessibility checks run. They are not in the app's language list or Android's, so a release
build never offers them; set as the app's language, the setting names them as it would any other.
