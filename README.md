<p align="center"><img src="docs/branding/plektra-open-color.svg" alt="Plektra" width="160"></p>

# Plektra

**Advanced (hardware) keyboard control on Android**

Configure physical key mappings, keyboard bar profiles, text tools and software keyboard layouts. Built for power users. Free software under [GPLv3](LICENSE).

[Product page](https://pkb.rocks/plektra/) · [Download Stable](https://pkb.rocks/plektra/download/stable/) · [Download Nightly](https://pkb.rocks/plektra/download/nightly/) · [Installation guide](docs/getting-started.md)

[![GitHub stars](https://img.shields.io/github/stars/pkb-rocks/plektra?style=flat&label=GitHub%20stars)](https://github.com/pkb-rocks/plektra/stargazers)

## Highlights

| Feature | Controls and tools |
| --- | --- |
| Customizable Toolbar | Suggestions, character variations and menu buttons; separate Normal and Slim profiles; shared or stacked rows |
| Hardware Key Remapping | Physical layouts, modifiers, SYM pages, navigation shortcuts, app shortcuts and command mappings |
| Smart Text Suggestions | Dictionaries, word completions, autocorrection and locally learned next words |
| Snippets & Clipboard | Text expansion from a short prefix, reusable snippets and clipboard history |
| Themes & Layouts | Nine keyboard bar themes, high contrast, QWERTY, AZERTY and QWERTZ, plus importable layouts |
| Software Keyboard Mode | Touch input with suggestions, symbols, snippets and ortholinear or staggered rows |

![Plektra on Titan 2, Titan 2 Elite and Pixel 7a](docs/showcase/exports/stable/landscape/01-devices.png)

## Keys and bar

Configure suggestions, character variations and menu buttons independently. Choose a full bar, compact controls, a modifier LED strip or a hidden bar. Normal and Slim profiles keep their own configuration. Place suggestions and actions in a shared row or use stacked rows.

Live indicators show active and locked modifiers in the keyboard bar or Android status bar. Physical layouts support Shift, Ctrl, Alt, SYM, long press, multi-tap, navigation and command mappings.

![Modular toolbar configuration](docs/showcase/exports/stable/landscape/02-keyboard-bar.png)

![Dual profiles: Normal and Slim](docs/showcase/exports/stable/landscape/04-slim-profile.png)

## Text and tools

### Predictive text and completions

Complete words from suggestions as you type. German, English and Italian dictionaries are bundled; additional dictionaries can be installed. Plektra supports autocorrection and locally learned next words.

![Predictive text and completions](docs/showcase/exports/stable/landscape/06-suggestions.png)

### Contextual character variations and SYM mapping

Select accented letters and symbols from character variations. SYM pages map additional symbols to physical keys. Long-press software keys to select secondary characters. An integrated emoji picker is also available.

### Expandable text snippets & templates

Save reusable text such as addresses, email addresses and dates. Find a snippet with a short prefix and expand its shortcut into the saved text.

![Text shortcut expansion](docs/showcase/exports/stable/landscape/09-snippet-result.png)

### Clipboard, navigation and selection

Browse clipboard history and insert saved entries. Use keyboard controls to select text, and Ctrl shortcuts to move the cursor, select, copy and paste.

![Terminal-style Ctrl navigation and selection](docs/showcase/exports/stable/landscape/21-ctrl-navigation.png)

## Look and layout

Choose from nine built-in keyboard bar themes, including a high-contrast theme. The standalone software keyboard includes suggestions, symbols and snippets, with ortholinear and staggered key geometries.

QWERTY, AZERTY and QWERTZ layouts are supported, including German umlauts. Physical layouts also include Greek, Cyrillic, Arabic and transliteration; custom JSON layouts can be imported.

![Nine built-in interface themes](docs/showcase/exports/stable/landscape/15-hardware-themes.png)

![Standalone software keyboard mode](docs/showcase/exports/stable/landscape/16-software-keyboard.png)

Explore the complete [21-image device showcase](docs/showcase/README.md), with Stable and Nightly galleries. See [Features](docs/features.md) for voice input, local notes, backup and accessibility options.

## Download and install

| Channel | Purpose | Download |
| --- | --- | --- |
| Plektra Stable | Release for everyday use | [Latest Stable APK](https://pkb.rocks/plektra/download/stable/) |
| Plektra Nightly | Development builds with the newest features; can contain bugs and incomplete features | [Latest Nightly APK](https://pkb.rocks/plektra/download/nightly/) |

The first Stable release is planned for **26.10**. Until then, Nightly is the newest build. Versions use CalVer: `26.10` means October 2026; `26.10-nightly.DD.HHmmssZ` adds the UTC build day and time. Download links resolve the newest release automatically.

Stable and Nightly are separate apps with their own settings. Install both and select the active keyboard in Android’s keyboard settings. Use Stable as your everyday keyboard and switch to Nightly to try new features; switch back to Stable if a Nightly build gets in your way.

The full APK includes the Clicks accessibility service for launcher-button remapping. The APK without that service retains every other keyboard feature, including TalkBack support. The two variants replace each other within the same channel.

- APK without the Clicks service: [Stable](https://pkb.rocks/plektra/download/stable/?variant=no-accessibility) · [Nightly](https://pkb.rocks/plektra/download/nightly/?variant=no-accessibility)
- Release notes: [Stable](https://pkb.rocks/plektra/download/stable/?to=notes) · [Nightly](https://pkb.rocks/plektra/download/nightly/?to=notes)
- Checksums: [Stable](https://pkb.rocks/plektra/download/stable/?to=checksums) · [Nightly](https://pkb.rocks/plektra/download/nightly/?to=checksums)

Additional distribution channels:

- [Our F-Droid Nightly repository](https://pkb.rocks/fdroid/nightly/repo) — Available soon
- [F-Droid main repository](https://f-droid.org/packages/rocks.pkb.plektra/) — Available soon
- [Google Play](https://play.google.com/store/apps/details?id=rocks.pkb.plektra.unsovereign) — Available soon
- [Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22rocks.pkb.plektra.nightly%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fpkb-rocks%2Fplektra%2Freleases%22%2C%22author%22%3A%22pkb-rocks%22%2C%22name%22%3A%22Plektra%20Nightly%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22filterReleaseTitlesByRegEx%5C%22%3A%5C%22nightly%5C%22%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22-full%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D) — Nightly updates from GitHub releases

The canonical Stable and Nightly APKs use separate published hardware-backed signing keys. Planned Google Play variants use separate app identities. See [Getting started](docs/getting-started.md) and [all releases](https://github.com/pkb-rocks/plektra/releases).

## Devices

| Device | Support |
| --- | --- |
| Unihertz Titan 2 | Developed and release-tested; device-specific physical keyboard layouts and display geometry |
| Unihertz Titan 2 Elite | Developed and release-tested; device-specific physical keyboard layouts and display geometry |
| Google Pixel 7a + Clicks Power Keyboard (“Clicksel”) | Directly tested physical keyboard combination |
| Samsung Galaxy Z Fold6 | Directly tested software keyboard and folding behaviour; also used with Clicks Power Keyboard |

Community-supported devices include the BlackBerry KEY2, Minimal Phone MP01 and BlackBerry Q25 (Zinwa mod), alongside other Clicks keyboard models. These are a selection of tested devices; further improvements for foldables are planned. See the [device support ranking](docs/devices.md) and [hardware notes](docs/device-support/).

## GitHub star history

<a href="https://pkb.rocks/plektra/#stars-heading">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/pkb-rocks/pkb-rocks.github.io/refs/heads/stats/star-history-dark.svg">
    <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/pkb-rocks/pkb-rocks.github.io/refs/heads/stats/star-history.svg">
    <img alt="Plektra GitHub stars over time" src="https://raw.githubusercontent.com/pkb-rocks/pkb-rocks.github.io/refs/heads/stats/star-history.svg" width="720">
  </picture>
</a>

[Release download statistics](https://pkb.rocks/plektra/#statistics-heading) compare APK downloads after 24 and 72 hours. The star chart and download statistics update independently of this README.

## Moving from Pastiera

Import a Pastiera backup ZIP in Plektra’s Advanced settings. Plektra keeps its own app identity. An assisted migration flow is planned before the first Stable release. See [Migration from Pastiera](docs/migration-from-pastiera.md).

## Build and contribute

Build a Nightly debug APK with JDK 17 and the Android SDK for compile SDK 36:

```sh
git clone https://github.com/pkb-rocks/plektra.git
cd plektra
scripts/build-nightly-debug.sh 26.10
```

To build and install on a connected device, add `--install --device <serial>`. Project structure and checks are in [Development](docs/development.md); maintainers use [Releasing](docs/releasing.md).

Distributed forks must use their own name, application ID, update endpoints and branding, and must not present themselves as official Plektra releases.

## Project links

- [Product page](https://pkb.rocks/plektra/)
- [GitHub](https://github.com/pkb-rocks/plektra)
- [Open Collective](https://opencollective.com/plektra)
- [Security policy](SECURITY.md)
- [Imprint](https://pastiera.eu/imprint)
