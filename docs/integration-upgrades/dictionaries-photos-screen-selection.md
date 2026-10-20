# Extended dictionaries, photo themes and screen-text selection

This update adds the changes developed since `e766f73` (16 October 2026) to the existing Plektra contribution branch. It retains the earlier learning, calibration, app-aware formatting and editor compatibility work. This is an experimental contribution branch, not an official upstream release.

## Dictionary loading and typing

- Stream Extended dictionary imports and use a disk-backed dictionary reader instead of retaining every expanded dictionary as large Kotlin object collections.
- Bound resource lifetimes and caches, release retired readers safely, and avoid holding the lifecycle lock during cursor callbacks and ranking. A failed Extended load can fall back per language rather than retrying on every keypress.
- Avoid fuzzy autocorrection work for an exact known spelling when the existing safety decision would reject its replacement. Unknown words and accent corrections still follow the correction path.
- Restore French apostrophe/accent correction such as `l'etat` to `l'état` without removing the ability to learn unusual words.
- Distinguish real Space taps from hardware repeat events, use event timestamps for double Space, and preserve the provenance of provisional spaces after suggestions and punctuation. The fixes cover native-editor fallback and closing formatting/quote markers.

The user tested French, English Extended and Italian Extended on a Titan 2 after the disk-reader changes. Memory and latency results remain device- and workload-dependent; this update does not promise that every dictionary combination fits every phone. Instrumented measurements identified expensive fuzzy boundary processing; the resulting performance gain has not been established as a general benchmark.

## Caps Lock and local statistics

Caps Lock transforms both displayed and committed suggestions, including learned lowercase next words. Accepting a suggestion must not restore a pre-lock snapshot from a temporary modifier layer. Explicitly unlocking Caps remains respected.

Typing counters belong to the current installation: exports omit them and imports ignore both older and higher archived counts. The statistics screen displays Android's installation date. The one-time local legacy-preference migration remains separate from importing another user's archive. Uninstalling the app is not a backup operation and can remove local data.

## Photo and texture themes

The theme editor supports sampled colors and actual cropped photo textures. A single crop can be continuous between keys, or span all supported theme surfaces through the global photo action. References remain small theme JSON values; bounded PNG crops are stored in `theme-images/` and travel with the backup ZIP. Source photos are not copied into theme archives.

The photo preview includes intensity from 0 to 100 percent, blending the photo with the theme's backing color without changing the crop or its continuity. Existing themes default to 100 percent. An existing texture can be reopened directly to adjust intensity, fitting and automatic contrast. Automatic foreground/halo proposals account for intensity; manual title colors are respected on photo backgrounds instead of being replaced using the hidden plain backing color.

On a recognized or connected physical keyboard, the virtual-key layout tab remains available but is optional for completing a theme draft. Defaults remain intact. The obsolete LED-visibility field, which had no draft-editor control, no longer blocks saving.

New UI labels are translated in the eleven supported locales. Translations still benefit from native-speaker review.

## French quotation marks and WhatsApp formatting

Immediate French quotation marks support the outer `« … »` and nested `“…”` levels. Rich-text quotation conversion edits the quote edges rather than recommitting the body, protecting formatting in editors such as Joplin. Verified automatic spaces can move outside a closing quote or style marker; manually typed spaces retain their separate semantics.

The user confirmed that WhatsApp recognizes italic text inside French quotes with ordinary spaces. For WhatsApp and WhatsApp Business, quote edges next to `_` or `*` use ordinary spaces. Other quote contexts, internal French punctuation spacing and other applications retain their usual typography. Tests cover immediate insertion, deferred conversion and automatic punctuation around style closure. The final combined APK still requires phone confirmation of this latest formatting path.

## Optional Nav outside text editors

The optional Android accessibility service relays configured Nav actions outside editable fields. It does not activate itself. Native selection remains application-dependent. Holding a navigation key repeats movement and stops on release or context loss.

An explicit **Select screen text** action opens a read-only local panel for visible text exposed by the active app. The user's chosen binding is double Fn to lock Nav, then P; bindings remain configurable. Capture occurs only on this action, with character/node limits and exclusion of password/editable subtrees. The panel supports touch selection, configured Nav movement, Select All and explicit copying. Closing removes its local text snapshot; copying uses the Android clipboard and can participate in the existing user-enabled clipboard behavior. The app's original text is not rewritten.

The user confirmed that this panel opens in ChatGPT and Telegram, and earlier confirmed native selection expansion in DuckDuckGo. This is not a claim that native selection works universally, that all off-screen document content is captured, or that every messenger exposes its text. Reddit comment sending remains an earlier known limitation.

## Validation

The combined Plektra APK built successfully with **498 selected tests passed**, zero failures/errors/skips in those selected suites. Pastiera has 481 passing selected tests. The [selected Plektra test report](dictionaries-photos-screen-selection-test-results.json) accompanies this update. Coverage includes dictionary/resource lifecycle, import/restore, statistics, learning and casing, Space timing and repeats, quote edges, formatting, photo metadata/backup, texture continuity, title color and optional Nav routing.

The full repository suite was not run. Native pixel-rendering tests requiring the unavailable Windows Robolectric native DLL remain outside this selected run; UI/phone checks supplement the automated tests. Local test APKs retain the separate test app identity. No private signing key, phone backup, learned user database, personal screenshot or diagnostic text trace is included in this publication.
