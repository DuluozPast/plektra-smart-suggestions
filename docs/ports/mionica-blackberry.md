# BlackBerry improvements ported from mionica/pastiera

Original author: **Mircea Horea IONICĂ ([mionica](https://github.com/mionica))**.
Tracking issue: <https://github.com/pkb-rocks/plektra/issues/107>.

The following contributions were adapted to Plektra's current profiles and event routing:

- [09b1f51ac9](https://github.com/mionica/pastiera/commit/09b1f51ac9): the BlackBerry SYM/emoji row `0ZXCVBNM$`, zero/currency mappings, labels and settings serialization. Plektra retains its existing currency default and gives the close button its own trailing slot. The globe button's long press opens the editor.
- [38be1cfb53](https://github.com/mionica/pastiera/commit/38be1cfb53): shifted symbol mappings, including custom mappings and JSON strings. Plektra merges incomplete uppercase maps over the original mappings, preserves unshifted user text, and uses the same mappings for displayed labels, touch and hardware input. One-shot Shift is consumed after a successful commit.
- [12ce0a35c1](https://github.com/mionica/pastiera/commit/12ce0a35c1): BlackBerry Alt+0 speech input. Plektra retains Alt+Ctrl, honors the existing enable switch, ignores key repeats and Ctrl chords, and leaves disabled Alt+0 to normal routing.
- [3a43af1132](https://github.com/mionica/pastiera/commit/3a43af1132): relevant hardware settings only. Plektra also respects the manually selected device profile.

Source attribution is retained next to adapted code. The shared symbol JSON entries and zero/currency additions to settings maps and labels come from Mircea's first contribution. Authorship trailer for the eventual port commit:

```
Co-authored-by: Mircea Horea IONICĂ <6553639+mionica@users.noreply.github.com>
```

## Direct Boot / before first unlock (BFU)

The [original Direct Boot contribution](https://github.com/mionica/pastiera/commit/5da24e7634) inspired the storage audit and `DirectBootKeyboardConfig`.

Plektra retains its credential-protected preferences and private files. A separate, reconstructible device-protected snapshot contains only:

- A bundled keyboard layout identifier (custom layouts fall back to QWERTY).
- A known physical keyboard profile.
- A currency symbol from a fixed allowlist.
- Shift, Alt and Ctrl tap-latch booleans and the clear-Alt-on-Space boolean.

The snapshot is refreshed only after unlock, including subsequent changes to those settings. Unknown keys are purged when refreshing; the snapshot is excluded from backup and transfer. No snippets, clipboard history, recent emojis, learned words, launcher cache, custom text/mappings, speech credentials or other preferences are copied. Existing user preferences are not moved, replaced or deleted. Application initialization waits for user unlock before accessing them.

**The IME is not marked `directBootAware`; full BFU input is not enabled by this port.** Its current startup initializes suggestion dictionaries, clipboard history, expansion sources, external integrations and multiple credential-protected files. Changing its manifest flag alone would expose those paths to locked storage failures. A subsequent BFU implementation needs an isolated input/lifecycle path, unlock handoff and cold-boot integration tests before enabling that flag.

See [Android's Direct Boot guidance](https://developer.android.com/privacy-and-security/direct-boot): use device-protected storage only for data required before unlock, retain private information in credential-protected storage.

## Verification and limits

Automated integration tests cover the rendered Q25/Key2 grid and touch commits, symbol case selection and serialization, existing device routing, and snapshot isolation/refresh behavior. The snapshot tests verify that private values never enter the device-protected store and credential-protected preferences remain unchanged.

Q25 and Key2 hardware are unavailable. Their physical scancodes, voice launch and appearance are unverified on those devices. BFU input and a cold-boot unlock handoff are not accepted or claimed by these tests.

The speakerphone contribution and Romanian dictionary were not ported.
