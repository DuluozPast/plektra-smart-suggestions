# T2E acceptance — 11 October 2026

Build: `26.10-nightly.11.164908Z`, installed using `scripts/build-nightly-debug.sh 26.10 --install --device <serial>`.

## Hardware observations

- Full bar, suggestions, LED-only, hidden, Slim and shared-row configurations were captured and visually reviewed on the Titan 2 Elite. Eight approved captures, including contextual variations, replace the corresponding raw showcase images.
- The right LED-only hook is removed. The native rails have matching open ends; the frame verification reports zero clipped LED pixels.
- Outer buttons follow the inner LED contour to the end of their slots, including the lower edge. The initial button-to-rail clearance was one physical pixel; the later recheck below replaces it with 2.2 dp.
- Contextual variations were triggered by entering the final E after “See you at the caf”. Selecting è produced “See you at the cafè”. The bottom keys retain their rounded outlines.
- The emoji picker was inspected after loading completed. Its rows and footer controls are visible.
- Settings search and back controls share the screen background. The Notes pencil, split view and preview controls share one segmented group.

## Variation height diagnosis

The capture theme preset previously replaced the device’s custom variation-height scale with the preset value. The receiver now retains the current geometry when applying a capture palette. The device’s original `variations_height_scale` of `0.7943517565727234` survives capture and restoration.

The device already supplies a 1076×1200 screenshot through its display override. The capture script keeps that size without applying the reference’s two-pixel crop a second time. The physical display setting and density override were preserved.

The IME layout also extended children from zero rather than their actual top position and retained excess nested bottom padding. Child placement now respects its measured top and the calibrated lower inset. Four pixels of lower inset remain; the verified key outlines are not cut off.

## Verification

Targeted tests cover LED endpoints, button contour and hit areas, suggestions/menu rows, IME transitions and the Notes segmented controls. Showcase render and verify pass, including native LED containment and retained foreground pixels. The website’s Stable and Nightly assets were rebuilt from these exports.

Temporary capture preferences were restored and compared with the saved hardware geometry and profile values; no differences were found.

## Remaining hardware checks

Physical SYM switching, physical Ctrl lock/navigation, clipboard selection and long-press popups still need interaction with the actual hardware keys. Virtual ADB key events did not reproduce every physical shortcut reliably. Existing screenshots for those scenes retain their earlier provenance rather than being reported as newly accepted captures.

The other devices in the showcase were not recaptured during this T2E session.

## Isolated LED-only recheck — 18:46 UTC

Build `26.10-nightly.11.184539Z` was installed through the canonical Debug script on the Titan 2 Elite. Four new captures replace Full, Suggestions, LED-only and Hidden. The capture uses an explicit `titan2elite_qwerty` profile because this device reports `Titan2` in Android's model property.

The previous right LED-only rail extended to the interior Ctrl slot (32.8% of the screen width). Without button surfaces, its fallback now ends at the outer right slot (16.0%), matching the left outer slot (16.4%). The physical capture confirms short corner rails on both sides, with no long right-hand extension. Existing button surfaces retain their own contours and the right rail retains its Ctrl/Shift split.

The contrast grid remains opaque on repeated drawing. All four captures were visually inspected before promotion. The transparent crops and the Stable/Nightly configuration cards were also inspected. Device display size, density, profile, modifier mode and saved geometry were restored without differences. This isolated check does not repeat the other hardware-key checks listed above.

The device comparison derives the T2E keyboard-bar anchor from the same measured crop as the showcase, so the grid cannot be mistaken for the bar edge.

## Native transparent export and spacing recheck — 19:23 UTC

Build `26.10-nightly.11.192311Z` was installed with the canonical Debug script. Sixteen T2E scenes were captured; fifteen visible keyboards also have native transparent PNGs. All four bar modes and the nine built-in themes were visually reviewed. The hidden state has no IME artwork.

Button-to-inner-LED clearance is now 2.2 dp (approximately five pixels at the device's density), including the lower curve. LED-only rails end at the calibrated display curve; the 16.4%/16.0% logical-slot fallback used in the earlier recheck is superseded.

Native alpha exports draw the existing keyboard children without host content or chrome background fills. Both rails and their native antialiasing survive export. No background-color subtraction is applied to these PNGs. The configuration and theme comparisons use a common light background on the Nightly website to retain contrast for inactive rails.

Geometry, theme, profile, modifier mode, display size (1076×1200) and density (360) were checked against the saved state after restoration; no differences were found.

A final Debug build (`26.10-nightly.11.192610Z`) restricts transparent background removal to marked structural containers. Unmarked clipboard cards keep their fill and border. Full bar, contextual variations and Cobalt were captured again; these three files carry the final build provenance. Twenty-three targeted tests, including native alpha retention and preserved card backgrounds, and the Release Kotlin compilation pass. Independent visual review accepts the configuration, snippet, theme, software-keyboard and layout cards.

The Debug setting was enabled through the T2E settings UI. Opening a synthetic Notes host produced a native PNG and metadata from build `26.10-nightly.11.192610Z`; the export setting returned to false after the capture.
