# Daily theme rotation within language/layout assignments

Language/layout entries are now directly visible on the main Keyboard theme page, before the keyboard preview. Enabled input styles and previously saved assignments are listed, including saved scopes whose language is temporarily disabled. Opening a row edits that scope directly; no assignment is written until Save. The general keyboard policy remains accessible separately.

Each existing theme assignment now offers Fixed theme or Daily rotation. Daily mode selects saved themes in click order, with an explicit Save button. Draft selection is local to the dialog; Cancel leaves stored settings unchanged. An empty selection cannot be saved. One saved theme stays constant; multiple themes cycle by local calendar day.

The matching assignment resolves its own cycle before the global theme policy. A French/AZERTY cycle therefore does not affect other languages or layouts. The existing specificity rules remain. Old assignments lacking the new fields default to fixed mode; no automatic migration enables rotation.

The existing assignment JSON now includes mode, rotationThemeNames and rotationAnchorDay alongside the fallback theme. The existing backup preference contract exports this JSON as a string, so no new preference namespace or backup schema is required. Saved theme definitions remain in their original backed-up preference. Deleted themes are skipped; an empty available cycle falls back to the assignment's stored theme. Saving an unchanged cycle preserves its calendar anchor.

The daily scheduler detects local cycles even when the global mode is fixed. Its callback invalidates the rendered-state cache before repainting, so unchanged suggestions/modifiers cannot suppress a theme refresh. Scheduling remains session-only and does not introduce a wake-up alarm. Theme resolution has a bounded cache containing at most the last assignment per keyboard target, invalidated by day, saved themes, preferences identity or assignment changes.

Kotlin comments explain compatibility, explicit saving, cycle anchors, cache bounds and forced repaint. Three new resource strings are supplied in all eleven supported languages. Previous optional Integration upgrades and performance changes remain.

Seven scoped-rotation regressions in each project cover language/layout separation, matching specificity, missed calendar days and a single theme, legacy JSON compatibility, deletion/fallback, cache invalidation, and real preference backup export/restore of order, anchor and saved themes. Exact totals are in [the targeted test results](scoped-theme-rotation-test-results.json). The full suite and phone/UI validation remain pending.
