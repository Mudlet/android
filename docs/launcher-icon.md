# Launcher icon

The app's launcher icon is Mudlet's logo, the stone arch, as an adaptive icon: a background layer, the arch in front
of it as the foreground layer, and a silhouette of the arch as the monochrome layer that Android 13 and later tint for
themed icons. The minimum SDK (28) has adaptive icons, so there are no bitmap icons.

| | Stable (`org.mudlet.mudlet`) | PTB (`org.mudlet.mudlet.ptb`) |
|---|---|---|
| Foreground | the arch, looking out on a day sky and green hills | the same arch at night: dark sky, a moon, moonlit hills |
| Background | pale sky, `#E3EEF7` | night blue, `#1C2238` |
| Monochrome | the arch | the arch and the moon |

The PTB's night follows Mudlet's own desktop PTB icon (`src/icons/mudlet_ptb_256px.png` in Mudlet/Mudlet), so a
tester tells the two apps apart in the launcher and in a themed launcher alike.

## Where the files are

- `app/src/main/res/mipmap-anydpi/ic_launcher.xml` and `ic_launcher_round.xml`: the adaptive icon, as the manifest's
  `android:icon` and `android:roundIcon`; both name the same three layers.
- `app/src/stable/res` and `app/src/ptb/res`: each channel's layers, `drawable/ic_launcher_foreground.xml`,
  `drawable/ic_launcher_monochrome.xml` and the background colour in `values/ic_launcher.xml`. `app/build.gradle.kts`
  adds the directory of the channel it builds (`-PreleaseChannel`, [`ptb.md`](ptb.md)) to the main source set.
- `scripts/check-apk.sh`, which CI runs on every APK it builds, the PTB's included, fails an APK whose `icon` or
  `roundIcon` is not an adaptive icon with background, foreground and monochrome layers.

## Making the layers

The vector drawables are made from Mudlet's SVG logo by [`scripts/launcher-icons.py`](../scripts/launcher-icons.py),
which needs only Python 3:

```sh
curl -fsSLO https://raw.githubusercontent.com/Mudlet/Mudlet/bfc932d9e76004d9624b6925b5e0278c17fdae2a/mudlet.svg
scripts/launcher-icons.py mudlet.svg
```

It converts the SVG's paths, gradients and clip paths, and leaves out what a vector drawable cannot draw: the soft
blurred shading and the sun's glow (it lists them). It scales the arch to fit the adaptive icon's safe zone, the
66dp circle in the middle of the 108dp layer that no launcher's mask cuts into, and makes the PTB's night colours,
its moon and the two silhouettes. The background colours are in the `values/ic_launcher.xml` files, by hand.

## Provenance and licence

- **Source**: `mudlet.svg` at the root of [Mudlet/Mudlet](https://github.com/Mudlet/Mudlet), commit `bfc932d`
  (14 November 2026). The same artwork, with its working files, is in
  [Mudlet/artwork](https://github.com/Mudlet/artwork).
- **Author**: Thorsten Wilms, whom Mudlet's About dialog credits with designing "our beautiful logo, our splash
  screen, the about dialog, our website, several icons and badges".
- **Licence**: Mudlet/Mudlet is licensed GPL-2.0-or-later (its README), and the logo is distributed in it with no
  licence of its own; Mudlet/artwork has no licence file. The icon here is a derivative of that GPL-distributed SVG,
  for the Mudlet project's own app. Whether the logo is meant to be under the GPL or held apart, as a project mark,
  is for Mudlet's maintainers to confirm.
