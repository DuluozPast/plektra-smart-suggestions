# Learned punctuation, observed casing and app-aware Fn formatting

This update extends the previously published theme and search improvements. It retains bounded candidate searches, asynchronous ordered persistence and the separate local test app identity. No later upstream rebase is included.

## Learned expressions and exact observed casing

Learned phrase keys retain commas, semicolons and colons, and now also printed sentence endings (`.`, `!`, `?`, `…`). French punctuation spacing is preserved, including the narrow nonbreaking space before `!`, `?`, `;` and `:` when enabled. A repeated sequence such as `Emmanuel Castel !` can therefore offer `Castel !` after `Emmanuel`. Plain, comma and sentence-ending variants remain distinct.

The next-word lexical model continues to learn words without the attached punctuation. Sentence endings reset context, both when typed and when a punctuated suggestion is accepted: the final word must not become a learned predecessor of the next sentence. Punctuation typed after an already completed word can record the decorated phrase without counting the word twice. Tap and swipe acceptance avoid duplicating matching punctuation already adjacent to the replaced word.

Repeated capitalization in a learned transition takes priority over an unrelated lowercase dictionary alternative. The implementation does not infer first names or surnames: it preserves the observed spelling of the association. Regression cases include long-press replacement of `c` with `C`, `Emmanuel → Castel`, and lowercase `emmanuel → Castel`. Contextual completion of learned personal words is bounded rather than a full dictionary scan.

Punctuated greetings cover both `monsieur, je me permets` and `madame, je me permets` as useful complete alternatives. Existing numbers, apostrophes, compound words and single-word suggestions remain supported. Existing learning and backups are retained; newly supported punctuation must be learned from future input. Synthetic repetition counts in tests are not a guaranteed threshold for every history.

## Formatting intentions translated for the active app

The optional Fn preset follows the current input language, not the interface language or AZERTY/QWERTY layout. French input uses Fn+G for bold, Fn+I for italic and Fn+S for underline. Other input languages currently use the standard Fn+B/I/U preset. Word and other native editors receive Ctrl+B/I/U; in particular Fn+S in French is translated to Ctrl+U, not Ctrl+S.

In WhatsApp and WhatsApp Business, bold inserts `*` and italic inserts `_`. With no selection, successive presses open and close the same marker. A selection is wrapped in both markers, or unwrapped when the selected text already includes them. Underline is not fabricated for these marker profiles.

Reddit uses native editor shortcuts by default. An explicit Reddit Markdown option selects `**` for bold and `*` for italic. Enable it only in an editor accepting Markdown: Reddit documents a transition of its mobile app to a rich-text composer. The keyboard does not inspect browser URLs or promise identical behavior across all editor versions. Native formatting requires support from the target app; this update does not add rich-text support to Google Messages. No decorative Unicode substitution is used.

Both switches appear in Settings → Nav Mode and are included in backup. Bold, Italic and Underline are also configurable key actions. The automatic preset leaves the active physical navigation grid intact; users can assign formatting actions explicitly or disable held-Ctrl navigation to use the direct app preset. Password fields and the internal emoji search do not receive messaging markers. Key repeat does not flood the editor with markers. New labels are present in all eleven supported languages.

## Suggestion spaces and formatting boundaries

Accepting a suggestion inserts a temporary trailing space. A closing formatting marker now moves only that verified automatic space outside the marker: `*bonjour ` becomes `*bonjour* ` and `_bonjour ` becomes `_bonjour_ `. Reddit handles both marker widths. An opening marker retains the separator after the preceding word. Manual spaces and another editor's pending space are not removed. Escaped and already balanced markers are distinguished within a bounded paragraph lookback, performed only on the formatting shortcut.

Formatting markers do not discard the current word being learned. A regression test types `_Emmanuel Castel_` through the formatting router and confirms the personal transition remains learned. Replacing selected text invalidates the old context appropriately.

## Validation

The final Plektra stable-debug selection completed with **489 tests: 487 passed, two existing Windows graphics exclusions, zero failures or errors**. This includes 25 formatting cases, 49 next-word controller cases and eight punctuation/model persistence cases, plus existing search, backup, theme, gesture, statistics and selection coverage. See [machine-readable final results](punctuation-case-and-formatting-test-results.json).

The stable-debug APK compiled, and its separate application ID and existing signing certificate were verified locally. Source/resource files in this update match the tested source archive, with only line-ending/BOM normalization for Git. The full upstream test suite was not run. Application routing tests use simulated editors; they are not end-to-end Word, WhatsApp or Reddit phone tests.

The user reports that the earlier bounded-search change removed perceived typing slowdowns during their use. This is useful device feedback, not a controlled latency benchmark. Native shortcut behavior and formatting rendering still require verification in the user's application versions. Sources, translations, synthetic tests and documentation are included; no personal learning backup, photos, APK or signing key is published.

References: [Word Android keyboard shortcuts](https://support.microsoft.com/en-us/accessibility/word/keyboard-shortcuts-in-word), [WhatsApp formatting](https://faq.whatsapp.com/539178204879377/), [Reddit editor modes](https://support.reddithelp.com/hc/en-us/articles/205191185-How-do-I-format-my-comment-or-post), [Reddit Markdown](https://support.reddithelp.com/hc/en-us/articles/360043033952-Formatting-Guide).
