# T2E acceptance — 11 October 2026

Build: `26.10-nightly.11.164908Z`, installed using `scripts/build-nightly-debug.sh 26.10 --install --device <serial>`.

## Hardware observations

- Full bar, suggestions, LED-only, hidden, Slim and shared-row configurations were captured and visually reviewed on the Titan 2 Elite. Eight approved captures, including contextual variations, replace the corresponding raw showcase images.
- The right LED-only hook is removed. The native rails have matching open ends; the frame verification reports zero clipped LED pixels.
- Outer buttons follow the inner LED contour to the end of their slots, including the lower edge. The button-to-rail clearance is one physical pixel.
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
