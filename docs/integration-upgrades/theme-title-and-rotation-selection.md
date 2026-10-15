# Optional theme title and rotation selection repair

The cursor-swipe area can display the active keyboard theme title instead of the swipe instruction. A switch on the main Keyboard Theme screen controls this behavior. It is enabled by default in these test builds; disabling it restores the original hint. Swipe gestures remain available either way, and the accessibility description retains the gesture instruction. The preference is included in the backup schema.

The title follows the actual hardware/software theme, including language/layout assignments and global or scoped daily cycles. Two cycle entries with identical colours retain their distinct names. Renames and preference changes refresh the title even if the colours do not change. Unsaved themes use a translated custom-theme label.

The title uses the theme's cursor-swipe colour when it reaches a 4.5:1 contrast ratio against the background; otherwise it uses whichever of black or white has higher contrast. This affects the displayed label only and does not rewrite stored theme colours. Long titles use one line with an ellipsis; temporary dictation prompts keep priority.

The resolver caches one result per keyboard target. Unchanged renders do not reparse saved-theme JSON. No extra timer or midnight alarm is added.

## Rotation selection repair

Daily cycles reference saved themes by name. Renaming themes manually in an imported backup could leave old names in the cycle, consuming invisible ranks in the editor. For example, a visible selected theme could appear as number 7 while the preceding six names were unavailable.

Both global and language/layout editors now prepare a draft containing only available saved names, normalize case and remove duplicate slots while retaining the order of surviving themes. Unknown renamed entries are not guessed or reassigned. Cleanup is persisted only after an explicit save. Cancelling leaves stored preferences untouched.

A translated Deselect all button lets users rebuild the selection in the desired order and then save it. The themes themselves remain unchanged. New labels are provided in all eleven supported languages. Kotlin comments explain the cache, display fallback and draft-only cleanup.

## Validation

The Plektra stable-debug unit-test selection completed with 397 tests: 395 passed, two existing Windows graphics exclusions, zero failures or errors. The new title suite has 12 passing cases; rotation-selection regression coverage adds three passing cases, including six stale references before a valid theme. Existing theme rotation, assignment, backup, learned-suggestion, punctuation, gesture and statistics suites were included. See [machine-readable results](theme-title-and-rotation-selection-test-results.json).

The stable-debug APK built successfully. Its separate test application ID and existing signing certificate were verified locally. Phone visual validation remains to be performed; this is not a measured typing-latency improvement. This contribution contains sources, resources, synthetic tests and documentation, with no personal backup, learned text, photos, APK or signing key.
