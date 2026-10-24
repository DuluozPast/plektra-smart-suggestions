# Literal-entry editors: reviewer test plan

## Problem and scope

An original spelling entered while defining a correction is deliberately not necessarily a dictionary word. Typing assistance must not turn `teh` into `the` inside the original field before that rule is saved. The same applies to personal dictionary words and snippet definitions.

Only six input fields are opted in: the personal dictionary add/edit word; autocorrection original and replacement; substitution-sheet trigger; snippet shortcut and body. Search, notes and ordinary message editors retain their previous policy. Existing trimming, validation and snippet-key normalization on save are unchanged.

## Mechanism

Shared Compose keyboard options request no automatic capitalization or autocorrection. The private IME option `rocks.pkb.plektra.literal_entry` carries the field-scoped request into InputContextState. The matching token is exact and comma-separated. Existing password, email and URL classification takes priority, and non-text fields are not reclassified. Existing restricted-field gates disable dictionary suggestions, replacements, double-space punctuation and text expansion. Explicit Shift and character variants remain available.

The user's opt-in for capitalization in restricted fields must not override this deliberate field opt-out. Returning to an ordinary field restores the user's usual capitalization policy.

No preference, route, permission, resource string, locale file or ZIP schema is introduced or changed. No settings or user data migration. No launcher or learned-multiword dependency. Other keyboards receive Android's standard hints but may choose how to honor them.

## Automated validation

77 targeted tests passed, with zero failures, errors or skips. Targeted classes cover field classification, spelling-related editor stores, expansion refusal and actual IME field lifecycle/modifier behaviour. The new service regression exercises restricted-field capitalization enabled, deliberate Shift and return to an ordinary field. It failed before the priority fix and passes afterwards. Existing password and email tests remain in scope. Android lint and debug APK assembly completed. The full test suite has not been run locally.

The lint task completing does not mean the repository is lint-clean: upstream has `abortOnError = false`. Its report contains 2,038 findings, with no additional canonical finding compared with the independently extracted PR160 branch (which has one extra warning in its statistics store). Lint configuration was not relaxed or changed by this branch.

## Device evidence (pending user availability and media approval)

Use a separate `rocks.pkb.plektra.review.literalentry` installation. Record only synthetic dictionary/replacement or snippet entries. Temporarily switch to this review keyboard only with user authorization, then restore the exact original default and enabled IME list. Verify the focused editor belongs to the review package before typing or recording. Demonstrate lowercase intentional spelling, retained text on editing/saving and a deliberate capital; verify normal-field suggestions/capitalization separately. No actual contacts/messages in the recording. Show the final cropped video to Emmanuel before uploading it.

## Durability

This change does not alter saving or backup logic; ordinary dictionary/replacement/snippet persistence remains unchanged. It only changes assistance while composing those entries. Installation-local typing statistics remain a separate PR and are not included here.
