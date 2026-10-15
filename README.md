# Plektra Smart Suggestions — contribution branch

Latest update: [learned punctuation and casing, app-aware Fn formatting, language-aware keys and suggestion-space repair](docs/integration-upgrades/punctuation-case-and-formatting.md), validated by [489 selected tests](docs/integration-upgrades/punctuation-case-and-formatting-test-results.json): 487 passed, two existing graphics exclusions, no failures. Earlier theme and search upgrades are retained.

Latest theme display update: [optional active-theme title, automatic text contrast and rotation selection repair](docs/integration-upgrades/theme-title-and-rotation-selection.md), with [397-test validation](docs/integration-upgrades/theme-title-and-rotation-selection-test-results.json).

This experimental branch proposes optional learned multiword suggestions and the custom Integration upgrades. It is not an official Plektra release.

Latest update: each language/layout theme assignment can opt into its own saved daily rotation, with explicit Save, backup compatibility and reliable date refresh. See [scoped theme rotation](docs/integration-upgrades/scoped-theme-rotation.md) and [targeted test results](docs/integration-upgrades/scoped-theme-rotation-test-results.json): 218 Integration tests passed; 380 Plektra tests passed and 2 Windows graphics tests skipped. All earlier optional upgrades and [search performance changes](docs/integration-upgrades/performance-phase3.md) remain. Phone validation remains pending.

Read the [complete English change summary](docs/integration-upgrades/README.md) and [targeted test results](docs/integration-upgrades/targeted-test-results.json). Based on nightly `v26.10-nightly.11.184948Z`; later upstream main commits are not included. Phone validation remains pending.

The local stableDebug test APK uses `rocks.pkb.plektra.custom.integrationtest` and can coexist with Pastiera Multimots Test. Transfer learned data through backup/import. No private signing key or personal backup is included.

---

<p align="center"><img src="docs/branding/plektra-open-color.svg" alt="Plektra" width="160"></p>

# Plektra

**Advanced (hardware) keyboard control on Android**

Free software under [GPLv3](LICENSE), with source code you can inspect, modify and share.

[Product page](https://pkb.rocks/) · [Download Stable](https://pkb.rocks/plektra/download/stable/) · [Download Nightly](https://pkb.rocks/plektra/download/nightly/) · [Installation guide](docs/getting-started.md)

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

### Modular toolbar configuration

Configure suggestions, character variations and menu buttons independently.

![Titan 2 Elite with the keyboard bar showing suggestions and menu buttons](docs/showcase/exports/stable/landscape/02-keyboard-bar.png)

### Adjustable interface density

Choose a full bar, compact controls, a modifier LED strip or a hidden bar.

![Four bar configurations: full bar, suggestions, LED strip and hidden](docs/showcase/exports/stable/landscape/03-bar-configurations.png)

### Dual profiles: Normal and Slim

Configure Normal and Slim independently, then switch between the two profiles.

![Normal and Slim keyboard bar profiles compared](docs/showcase/exports/stable/landscape/04-slim-profile.png)

### Flexible toolbar layouts

Place suggestions and actions in a shared row or arrange them in stacked rows.

![Stacked bar and shared row compared](docs/showcase/exports/stable/landscape/05-shared-row.png)

### Live modifier state indicators

See active and locked modifiers in the keyboard bar or Android status bar.

![Keyboard bar showing an active and a locked modifier](docs/showcase/exports/stable/landscape/14-modifiers.png)

### Ortholinear and staggered key geometries

Choose ortholinear or staggered rows for the software keyboard.

![Software keyboard with ortholinear and staggered key arrangements](docs/showcase/exports/stable/landscape/20-key-arrangement.png)

## Text and tools

### Predictive text and completions

Complete words from suggestions as you type. Dictionaries and locally learned next words are supported.

![Word suggestions for a partly typed word](docs/showcase/exports/stable/landscape/06-suggestions.png)

### Contextual character variations

Select accented letters and symbols from the variations for the current character.

![Character variations for the last typed key in the bar](docs/showcase/exports/stable/landscape/07-variations.png)

### Long-press secondary characters

Long-press a software key to select its secondary characters.

![Long-press popup with alternate characters on the software keyboard](docs/showcase/exports/stable/landscape/19-key-popup.png)

### Expandable text snippets & templates

Save reusable text such as addresses, email addresses and dates. Find snippets with a short prefix.

![Saved snippets for an address, an email and a date](docs/showcase/exports/stable/landscape/08-snippet-selection.png)

### Text shortcut expansion

Expand a snippet shortcut into its saved text.

![A snippet shortcut before and after expansion](docs/showcase/exports/stable/landscape/09-snippet-result.png)

### Direct SYM layer mapping

Open SYM pages and enter symbols using the corresponding physical keys.

![SYM page with symbols mapped to the physical keys](docs/showcase/exports/stable/landscape/10-symbols.png)

### Integrated, low-profile emoji picker

Browse and insert emoji from the keyboard.

![Emoji picker below a conversation](docs/showcase/exports/stable/landscape/11-emoji.png)

### Integrated clipboard manager

Browse clipboard history and insert a saved entry.

![Clipboard history with copied entries](docs/showcase/exports/stable/landscape/12-clipboard.png)

### Keyboard text selection

Select text using physical keyboard controls.

![Text selected with keyboard controls](docs/showcase/exports/stable/landscape/13-selection.png)

### Terminal-style Ctrl navigation and selection

Use Ctrl shortcuts to move the cursor, select text, copy and paste.

![Ctrl layer with navigation and editing actions on the keys](docs/showcase/exports/stable/landscape/21-ctrl-navigation.png)

## Look and layout

### Nine built-in interface themes

Choose from nine built-in keyboard bar themes.

![Nine keyboard bar themes, from Plektra Dark to Transparent Dark](docs/showcase/exports/stable/landscape/15-hardware-themes.png)

### Standalone software keyboard mode

Use the software keyboard on phones without physical keys, with suggestions, symbols and snippets.

![Software keyboard on a Pixel 7a in two themes](docs/showcase/exports/stable/landscape/16-software-keyboard.png)

### Native QWERTY, AZERTY, and QWERTZ support

Select QWERTY, AZERTY or QWERTZ, including German umlauts.

![QWERTY, AZERTY and QWERTZ layouts](docs/showcase/exports/stable/landscape/17-layouts.png)

### High-contrast mode for improved visibility

Use a high-contrast theme with clear key outlines and distinct modifier indicators.

![High-contrast theme on a Titan 2 Elite and a Pixel 7a](docs/showcase/exports/stable/landscape/18-high-contrast.png)

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
| Google Pixel 7a + Clicks Power Keyboard | Directly tested physical keyboard combination |
| Samsung Galaxy Z Fold6 | Directly tested software keyboard and folding behaviour; also used with Clicks Power Keyboard |

Community-supported devices include the BlackBerry KEY2, Minimal Phone MP01 and BlackBerry Q25 (Zinwa mod), alongside other Clicks keyboard models. These are a selection of tested devices; further improvements for foldables are planned. See the [device support ranking](docs/devices.md) and [hardware notes](docs/device-support/).

## GitHub star history

<a href="https://pkb.rocks/#stars-heading">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/pkb-rocks/pkb-rocks.github.io/refs/heads/stats/star-history-dark.svg">
    <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/pkb-rocks/pkb-rocks.github.io/refs/heads/stats/star-history.svg">
    <img alt="Plektra GitHub stars over time" src="https://raw.githubusercontent.com/pkb-rocks/pkb-rocks.github.io/refs/heads/stats/star-history.svg" width="720">
  </picture>
</a>

[Release download statistics](https://pkb.rocks/#statistics-heading) compare APK downloads after 24 and 72 hours. The star chart and download statistics update independently of this README.

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

- [Product page](https://pkb.rocks/)
- [GitHub](https://github.com/pkb-rocks/plektra)
- [Open Collective](https://opencollective.com/plektra)
- [Security policy](SECURITY.md)
- [Imprint](https://pzauner.de/Notes/.hidden/Impressum.pdf)
