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
  translate (`translations/mudlet.ts` in Mudlet/Mudlet, on the same Crowdin).

## Setting up Crowdin

The maintainer does this once, on Crowdin; nothing in this repository holds a token.

1. In the Crowdin project, under Integrations, connect the GitHub integration to `Mudlet/android` with the branch
   `main` and the configuration file `crowdin.yml` (Mudlet/Mudlet's integration reads `.crowdin.yml` instead).
   Crowdin's GitHub app holds the access, so the repository needs no secret.
2. Let it upload the sources, and check that the project lists the two `strings.xml` files under `app/` and
   `engine-service/`, each string with its translator's comment as context.
3. Set when it syncs: its pull request then comes as translators finish strings.

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

## Pseudo-locales

Debug builds carry Android's two pseudo-locales (`isPseudoLocalesEnabled` in `app/build.gradle.kts`), generated from
the English when the APK is built:

- **en-XA**, "English (XA)": each letter accented and the text made longer, `[Þŕöƒîļéš one]`, which shows text that
  is cut off, and any text that is not in `strings.xml`, as it stays plain;
- **ar-XB**, "Arabic (XB)": the text reversed and the layout mirrored, right to left.

On a phone or emulator with a debug build, turn on Developer options; both then appear in Settings, System,
Languages. `PseudoLocaleTest` (`app/src/androidTest`) runs the app in each, as the per-app language setting of
Android 13 and later does, so on the API 36 emulator of CI's emulator job only: on the profile list and the game
screen, no text may be cut short, overflow its box or leave the screen, ar-XB must be mirrored, and the
accessibility checks run.
