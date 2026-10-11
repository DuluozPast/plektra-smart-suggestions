# Make every key yours

Plektra gives Android phones with physical keyboards configurable layouts, input tools and keyboard controls.

Explore 21 presentations in the [Stable gallery](#stable-gallery) or [Nightly gallery](#nightly-gallery).

The galleries use real Titan 2, Titan 2 Elite and Pixel 7a captures from 8 October 2026. Both palettes show Nightly debug UI; native screenshot colors and contents are preserved. The Notes editor contains synthetic example text. Processed photographs frame the actual app captures.

## Reproduce the images

Install Node.js 20.9 or later, ImageMagick (`magick`) and librsvg (`rsvg-convert`). Then run from the repository root:

```sh
npm --prefix docs/showcase ci
npm --prefix docs/showcase run render
npm --prefix docs/showcase run verify
```

`render` creates all 21 images for both channels in landscape and portrait formats, plus SVGs and contact sheets. Landscape PNGs are the canonical gallery files. Portrait images, SVGs, crops, composed frames and contact sheets are ignored build outputs. Rendering does not create PDFs.

The renderer uses the recorded inputs under `raw/`, processed frames and display geometry under `frames/`, the presentation catalog under `sources/`, and branding from `../branding/`. It does not connect to a device. Private capture-session backups and original full photographs stay local.

The verifier checks all 84 image dimensions and channel backgrounds, preserves foreground colors during background removal, and checks that the Titan 2 Elite frame retains the captured bottom LEDs. These checks validate the presentation, not device behavior.

Capture versions and source hashes are in [provenance.json](provenance.json). Input and published-image hashes are in [checksums.json](checksums.json). The processed frames retain photographic hardware and key lettering; no generated hardware or reconstructed key legends are used.

## Stable gallery

### Make every key yours.

Physical keyboards and touch input. One set of tools, on your terms.

![Make every key yours.](exports/stable/landscape/01-devices.png)

### Your keyboard bar. Your choice.

Suggestions, variations and menu buttons. Keep the controls you actually use.

![Your keyboard bar. Your choice.](exports/stable/landscape/02-keyboard-bar.png)

### As much as you need.

A full bar, suggestions with controls, or LEDs. Keep only what you need.

![As much as you need.](exports/stable/landscape/03-bar-configurations.png)

### A lighter setup. One switch away.

Configure Normal and Slim separately. Your minimal setup is yours to define.

![A lighter setup. One switch away.](exports/stable/landscape/04-slim-profile.png)

### Keep your controls close.

Choose a shared row or a stacked bar. Arrange suggestions and actions.

![Keep your controls close.](exports/stable/landscape/05-shared-row.png)

### Finish the word. Keep typing.

English word suggestions as you type. Choose a completion without retyping.

![Finish the word. Keep typing.](exports/stable/landscape/06-suggestions.png)

### More characters. Within reach.

Discover variations for the key you type. Accents and symbols stay close.

![More characters. Within reach.](exports/stable/landscape/07-variations.png)

### A few keys. Useful details.

Your address, email or a date. Saved text, inserted from a short prefix.

![A few keys. Useful details.](exports/stable/landscape/08-snippet-selection.png)

### Write it once. Use it again.

Expand a shortcut into reusable text. Email addresses without retyping.

![Write it once. Use it again.](exports/stable/landscape/09-snippet-result.png)

### Symbols without the detour.

Open SYM pages for extra characters. Physical key labels show the mapping.

![Symbols without the detour.](exports/stable/landscape/10-symbols.png)

### Say it with an emoji.

Browse emoji from your keyboard. Keep the conversation in view.

![Say it with an emoji.](exports/stable/landscape/11-emoji.png)

### Your clipboard. At hand.

Keep useful copied text within reach. Pick an entry and carry on writing.

![Your clipboard. At hand.](exports/stable/landscape/12-clipboard.png)

### Keep your hands on the keys.

Select and edit text with keyboard controls. Make room for shortcuts in your workflow.

![Keep your hands on the keys.](exports/stable/landscape/13-selection.png)

### Know which keys are active.

See active and locked modifiers. Choose where the indicators appear.

![Know which keys are active.](exports/stable/landscape/14-modifiers.png)

### Make it feel like yours.

Different themes, the same useful tools. Choose a look that fits your day.

![Make it feel like yours.](exports/stable/landscape/15-hardware-themes.png)

### The same tools. At your fingertips.

Use Plektra without a physical keyboard. Suggestions, symbols and snippets included.

![The same tools. At your fingertips.](exports/stable/landscape/16-software-keyboard.png)

### A layout that fits you.

QWERTY, AZERTY or QWERTZ with umlauts. Choose the keys you need.

![A layout that fits you.](exports/stable/landscape/17-layouts.png)

### Make the keys stand out.

Choose a high-contrast keyboard theme. Clear outlines and distinct indicators.

![Make the keys stand out.](exports/stable/landscape/18-high-contrast.png)

### A long press. More possibilities.

Discover alternate characters on a key. Choose the character you need.

![A long press. More possibilities.](exports/stable/landscape/19-key-popup.png)

### Straight columns. Or a familiar stagger.

Choose your software key arrangement. The same controls, your preferred geometry.

![Straight columns. Or a familiar stagger.](exports/stable/landscape/20-key-arrangement.png)

### More control. Right on the keys.

Ctrl reveals navigation and editing actions. Move, select, copy and paste from the keyboard.

![More control. Right on the keys.](exports/stable/landscape/21-ctrl-navigation.png)

## Nightly gallery

### Make every key yours.

Physical keyboards and touch input. One set of tools, on your terms.

![Make every key yours.](exports/nightly/landscape/01-devices.png)

### Your keyboard bar. Your choice.

Suggestions, variations and menu buttons. Keep the controls you actually use.

![Your keyboard bar. Your choice.](exports/nightly/landscape/02-keyboard-bar.png)

### As much as you need.

A full bar, suggestions with controls, or LEDs. Keep only what you need.

![As much as you need.](exports/nightly/landscape/03-bar-configurations.png)

### A lighter setup. One switch away.

Configure Normal and Slim separately. Your minimal setup is yours to define.

![A lighter setup. One switch away.](exports/nightly/landscape/04-slim-profile.png)

### Keep your controls close.

Choose a shared row or a stacked bar. Arrange suggestions and actions.

![Keep your controls close.](exports/nightly/landscape/05-shared-row.png)

### Finish the word. Keep typing.

English word suggestions as you type. Choose a completion without retyping.

![Finish the word. Keep typing.](exports/nightly/landscape/06-suggestions.png)

### More characters. Within reach.

Discover variations for the key you type. Accents and symbols stay close.

![More characters. Within reach.](exports/nightly/landscape/07-variations.png)

### A few keys. Useful details.

Your address, email or a date. Saved text, inserted from a short prefix.

![A few keys. Useful details.](exports/nightly/landscape/08-snippet-selection.png)

### Write it once. Use it again.

Expand a shortcut into reusable text. Email addresses without retyping.

![Write it once. Use it again.](exports/nightly/landscape/09-snippet-result.png)

### Symbols without the detour.

Open SYM pages for extra characters. Physical key labels show the mapping.

![Symbols without the detour.](exports/nightly/landscape/10-symbols.png)

### Say it with an emoji.

Browse emoji from your keyboard. Keep the conversation in view.

![Say it with an emoji.](exports/nightly/landscape/11-emoji.png)

### Your clipboard. At hand.

Keep useful copied text within reach. Pick an entry and carry on writing.

![Your clipboard. At hand.](exports/nightly/landscape/12-clipboard.png)

### Keep your hands on the keys.

Select and edit text with keyboard controls. Make room for shortcuts in your workflow.

![Keep your hands on the keys.](exports/nightly/landscape/13-selection.png)

### Know which keys are active.

See active and locked modifiers. Choose where the indicators appear.

![Know which keys are active.](exports/nightly/landscape/14-modifiers.png)

### Make it feel like yours.

Different themes, the same useful tools. Choose a look that fits your day.

![Make it feel like yours.](exports/nightly/landscape/15-hardware-themes.png)

### The same tools. At your fingertips.

Use Plektra without a physical keyboard. Suggestions, symbols and snippets included.

![The same tools. At your fingertips.](exports/nightly/landscape/16-software-keyboard.png)

### A layout that fits you.

QWERTY, AZERTY or QWERTZ with umlauts. Choose the keys you need.

![A layout that fits you.](exports/nightly/landscape/17-layouts.png)

### Make the keys stand out.

Choose a high-contrast keyboard theme. Clear outlines and distinct indicators.

![Make the keys stand out.](exports/nightly/landscape/18-high-contrast.png)

### A long press. More possibilities.

Discover alternate characters on a key. Choose the character you need.

![A long press. More possibilities.](exports/nightly/landscape/19-key-popup.png)

### Straight columns. Or a familiar stagger.

Choose your software key arrangement. The same controls, your preferred geometry.

![Straight columns. Or a familiar stagger.](exports/nightly/landscape/20-key-arrangement.png)

### More control. Right on the keys.

Ctrl reveals navigation and editing actions. Move, select, copy and paste from the keyboard.

![More control. Right on the keys.](exports/nightly/landscape/21-ctrl-navigation.png)

## Capture new screenshots

`scripts/capture-showcase.sh` recreates the raw screenshots in `raw/` on an emulator or a physical device. It uses the Nightly debug build, which must be installed on the device first.

1. Install the debug build on the device. Pass the device serial with `--device`. The script does not read `ANDROID_SERIAL`.

   ```sh
   scripts/build-nightly-debug.sh 26.10 --install --device <serial>
   ```

2. Run the capture for one device profile. Use `titan-2-elite`, `titan-2` or `pixel-7a`.

   ```sh
   scripts/capture-showcase.sh --serial <serial> --device titan-2-elite
   ```

   To capture only some states, add `--only r6-full,r5-slim`. To force one host for every scene, add `--host notes` or `--host showcase`. The default is the host each scene asks for.

**Emulator.** Start the Android Virtual Device and wait until it has finished booting. The serial is usually `emulator-5554`. The display must have no size override, so run `adb shell wm size reset` first if the script reports a mismatch. The Titan 2 Elite profile expects a 1080 by 1200 display.

**Physical device.** Enable USB debugging, connect the device and check that `adb devices` lists its serial. Keep the screen unlocked and awake during the run.

The script changes some device settings while it runs. It turns on the system demo mode (clock 09:10, full battery, no notifications), changes the Plektra keyboard and notes settings for each scene, and sets the input method to Plektra Nightly if needed. It puts all of these back when it exits, including after an error or Ctrl-C. Check the device afterwards if a run is interrupted.

Each run writes to `build/showcase-capture/<device>/<timestamp>/`. The folder holds one PNG per state and a `manifest.json` with the device, serial, app version, time and SHA-256 hash of every file. Captures are never written to `raw/` automatically. Review the PNGs against the existing references, then copy the approved files into `raw/<device>/` by hand.

The scene table is [capture/scenes.json](capture/scenes.json). Each entry lists the device, the keyboard settings, the theme, the typed text and key events, and the crop. States with `"manual": true` are skipped by the script; the `instruction` field says how to capture them by hand. Expect small differences from the references: the Markdown notes editor is a different screen from the older notes host, and the emulator is not detected as a Titan 2 Elite, so its keyboard layout differs.

## Device comparison scale

The opening device comparison uses one physical width scale: Titan 2 88.7 mm, Titan 2 Elite 75 mm and Pixel 7a 72.9 mm. Manufacturer dimensions and source links are recorded in [device-dimensions.json](sources/device-dimensions.json). Transparent photograph margins are excluded when measuring the frame widths; each photograph keeps its aspect ratio.

The upper edge of each keyboard bar is measured in its capture and mapped into its device frame. These edges share one horizontal reference. Device labels share one baseline, with a 16 px gap below the lowest device silhouette.
