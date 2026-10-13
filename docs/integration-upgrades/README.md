# Plektra Smart Suggestions — proposed Integration upgrades

This contribution branch ports the custom Pastiera Integration improvements onto Plektra nightly `v26.10-nightly.11.184948Z`. It is a review and testing branch, not an official Plektra release. The Android project retains its normal structure, including `app`, `gradle`, the wrapper, resources and tests.

Latest performance update: [background learning persistence, validation and remaining limits](performance-phase2.md), with [phase 2 test results](performance-phase2-test-results.json). Earlier features below are retained.

## Changes compared with the original Plektra baseline

### 1. Learned multiword suggestions and next-word continuity

- Add stores for learned phrases, multiword groups, word usage and correction usage.
- Offer learned groups of words alongside single-word suggestions, with contextual ranking and phrase promotion/demotion.
- Preserve context after accepting a suggestion by swipe, so acceptance feeds the normal learning and next-word pipeline.
- Reconstruct the relevant context before the current word without carrying the previous sentence into the prediction.
- Avoid an artificial dominant score for a weak one-word context; prefer more specific matching contexts where available.

Multiword suggestions are optional. These changes improve the learning path; they do not guarantee that a particular phrase will be suggested after a fixed number of repetitions.

### 2. Numbers, apostrophes and compound words

- Retain numeric tokens in learned sequences, including numbers typed with the locked Alt layer.
- Correct word-boundary handling on that input path so a number such as `11` can participate in `à → 11 → heures`.
- Avoid prompting the user to add plain numbers to the personal word dictionary.
- Preserve internal apostrophes and hyphens, including the alternate-character input path.
- Support contextual completion of `rendez` to `rendez-vous`, as well as prediction following `J’ai`.

### 3. Learning performance and persistence

- Cache the multiword data in memory instead of repeatedly reading its JSON file during typing.
- Use atomic file replacement and recovery for the learned multiword store.
- Move broad promotion/demotion maintenance away from the immediate word-boundary path.
- Wait for pending learning operations when exporting a backup.
- Preserve compact dictionary V3/V3.1 readers alongside the Plektra loading paths.

No typing-latency or battery benchmark has been performed on the Titan. A larger learned corpus can still increase memory consumption and search time.

### 4. Complete learned-suggestion backup and restore

- Export the five learning datasets as JSON entries inside the backup ZIP, in addition to the existing settings and dictionary data.
- Include the new preferences and typing counters in backup schemas.
- Integrate learning snapshots with Plektra’s restore validation and rollback, so a failed restore does not leave partial learning state.
- Use SQLite snapshot support where available and a compatible fallback for older SQLite versions.
- Add regression tests and a small synthetic learning fixture. No personal backup or real learned corpus is included in this contribution.

### 5. Swipe calibration

- Add calibration for the gestures used to accept the left, middle and right suggestions.
- Save and restore calibration settings and connect them to gesture handling.

This is separate from Plektra’s screen-contour calibration. Plektra’s new keyboard-bar geometry is retained.

### 6. Configurable multiline text selection

- Add upward/downward selection across multiple lines through the navigation configuration.
- Allow the user to choose the supported letter-key assignments, save them and include them in backups.
- Preserve the selection anchor when extending the selected range.

### 7. Typing statistics

- Add a Typing Statistics screen below Accessibility.
- Show total words entered, manually entered words and words entered through suggestions.
- Avoid counting an accepted prefix or suggestion twice and avoid relearning an accepted word after trimming its temporary space.
- Batch counter writes and flush at session/backup boundaries.

### 8. Enter-key sending options

- Add “Enter inserts a newline; Shift+Enter sends”, with the physical left/right Shift side selected by the user.
- Add “Enter inserts a newline; Alt+Enter sends” to the main messaging preset and per-application behavior choices.
- Retain the additional Alt+Enter send shortcut option.
- Resolve conflicts so an enabled send policy takes priority over Alt+Enter language switching in the relevant editor.
- Preserve the independent optional Ctrl+Space language-switch shortcut, including devices where Fn represents Ctrl.

Sending still depends on the application’s editor behavior and the selected send strategy.

### 9. Optional punctuation spacing and temporary-space cleanup

- Keep the existing before/after punctuation settings structure rather than replacing it.
- Implement immediate automatic spacing after selected punctuation, including `;`, `–`, `?`, `!`, `:` and `)`.
- Leave these settings disabled unless selected by the user.
- Track spaces inserted automatically after accepted suggestions or punctuation, then remove only the tracked space when closing punctuation follows.
- Handle `)` followed by `.` without leaving an unwanted automatic space between them.
- Preserve manually typed spaces, spacing before an opening parenthesis and the configured French nonbreaking-space behavior.

### 10. Literal dictionary and replacement entry

- Disable automatic capitalization and correction inside manual word, correction, substitution and snippet-entry fields.
- Add an explicit editor marker so the protection remains effective even when suggestions are allowed in otherwise restricted fields.
- Preserve intentional uppercase input through Shift and preserve ordinary sentence capitalization outside these fields.

### 11. Optional daily custom-theme rotation

- Add daily rotation of selected saved themes for the hardware/software theme targets.
- Add an explicit Save button; editing the selection does not silently save an unfinished choice.
- Support a single constant theme or a cycle through multiple themes, skipping deleted themes and falling back when no selection is usable.
- Keep Plektra’s system-theme default and existing override priorities.
- Check local date changes during keyboard sessions, including timezone and daylight-saving changes, without scheduling a task that wakes the phone.

### 12. Localization and settings layout

- Port Integration menu translations across the supported languages while retaining Plektra-specific resources and existing translations.
- Translate labels such as keyboard theme and dictionaries, and shorten selected French labels for small screens.
- Give Save As its own row in the theme screen; align the Export/Delete controls.
- Apply safe screen insets to the scrolling Enter-behavior settings.
- Keep Plektra’s new Normal/Slim configuration screen rather than reviving the obsolete Pastiera status-bar screen.

Machine-assisted translations need native-speaker review. Some screens introduced only by Plektra still need a separate language review.

### 13. Dictionaries and experimental app identity

- Retain nine additional dictionaries from Integration: Danish, Spanish, French, Dutch, Norwegian, Polish, Portuguese, Russian and Ukrainian. Plektra’s existing dictionaries are not replaced. See `INTEGRATION_DICTIONARY_NOTICES.md`.
- Use `rocks.pkb.plektra.custom.integrationtest` and the label **Plektra Integration Test** for the local `stableDebug` variant, allowing it to coexist with Pastiera Multimots Test and official Plektra.
- Keep the Plektra namespace and the identities of the other build variants for upstream review.
- Preserve the local legacy-preference migration helper for compatible upgrade scenarios. A separately installed app cannot read another app’s private data; transferring learned suggestions requires backup/import.

The distributed APK is a local experiment. The private signing key is excluded. The contribution branch is not a ready-made independent release channel; release identity, branding and update endpoints require separate preparation before distributing a fork publicly as a product.

## Plektra improvements preserved

The merge keeps Plektra’s cached keyboard-bar geometry, curved surfaces, Normal/Slim configuration, visibility management, notes, dictation and smooth-caret lifecycle. SymSpell candidate deduplication and the long-input safeguards remain in place. The learning extensions participate in Plektra’s restore validation and rollback rather than replacing them.

## Validation and remaining work

- 293 targeted tests: **291 passed, 2 Windows graphics tests skipped**.
- The APK compiled successfully. The separate test application ID and signing certificate were verified after rebuilding.
- Coverage includes learning, backup/restore, migration, number/compound handling, punctuation, selection, statistics, Enter behavior, theme rotation and menu resources.
- The full test suite was not run. The selected tests do not establish production readiness, phone compatibility or performance gains.
- Phone checks remain necessary for restored learning/calibration, swipe acceptance, `J’ai → rendez-vous → à → 11 → heures`, compound completion, punctuation, literal fields, Alt+Enter and theme rotation.

This branch is based on the precise nightly used for the merge. Later changes on upstream `main` have not been rebased into it. The proposal should be reviewed and split into focused contributions before upstream integration.
