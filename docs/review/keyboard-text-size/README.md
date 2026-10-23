# Keyboard-bar text size

This contribution is extracted from the integration prototype onto upstream `5ff5615e42c1b3292a6737f5603e3cc9c7f60e7f` (23 October 2026). It contains no launcher or photo-theme dependencies.

## Behaviour

Open Settings → Keyboard bar → Contents. The text-size card changes the preferred size of suggestions, variations and punctuation from 85% to 125%, in 5% steps. The default is 100%; Default size resets it. The reference preview remains at 100% while the second row follows the slider. Both rows use the production suggestions renderer and dragging the slider does not save intermediate preview values.

The percentage applies once to Android's original pixel ceiling, including the system font setting, rather than multiplying nonlinear SP values. Autosizing retains the existing minimum and row geometry. Long candidates still shrink to fit; the chosen percentage is a preferred maximum, not a guarantee that every word grows. Existing upstream overflow behaviour is not redesigned here. The 1-pixel ladder includes the requested maximum and can choose a slightly different intermediate size at 100% than the previous coarse SP ladder.

The setting is recognized by the typed preferences schema, so ordinary ZIP export/import carries it without a new archive format. The ZIP test exports, clears the preference and restores it.

## Reproduce automated checks

```sh
./gradlew :app:testStableDebugUnitTest --tests '*KeyboardBarTypographyTest' --tests '*KeyboardTextScaleVisualTest' --tests '*KeyboardTextScaleBackupTest' :app:assembleStableDebug
```

Robolectric checks preference bounds, refreshed ceilings when the candidates do not change, whole candidate layouts, preview isolation and ZIP restoration. Its legacy graphics are not evidence of actual glyph-size ratios; use the device recording and native measurements for that.

## Device capture

A debug-only, shell-permission-protected activity displays synthetic content with the actual settings preview widget. It cycles 85%, 100%, 125% without saving preferences. It is excluded from release APKs.

```sh
adb shell am start -W -n <debug-application-id>/rocks.pkb.plektra.TypographyPreviewActivity
adb shell screenrecord --time-limit 20 /data/local/tmp/keyboard-text-size.mp4
adb pull /data/local/tmp/keyboard-text-size.mp4
```

For this review APK, a local Gradle initialization script adds `.review.textsize` to the debug application ID, allowing it to coexist with everyday keyboards. That script also selects the local debug keystore and offline test dependencies; none of those machine paths or signing keys belongs to the PR. No keyboard activation or accessibility permission is needed to view the synthetic comparison.

Validation results and media are added below after the exact branch APK has been checked.
