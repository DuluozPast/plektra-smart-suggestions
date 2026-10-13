# Avoid obsolete CPU work and repeated usage-cache misses

This update retains phases 1 and 2 and all earlier optional Integration upgrades. It does not cap learned vocabulary, shorten phrase suggestions, change ranking rules or add a new input delay.

## Changes

- Add cooperative cancellation checkpoints inside synchronous prefix/candidate lookup, SymSpell buckets, edit-distance calculation and suggestion scoring. The active coroutine supplies its cancellation check through a thread-local scope containing only synchronous work. The scope restores the previous checkpoint on completion or failure; a cancelled request cannot poison the next request or another worker thread.
- Use the existing bounded negative caches for absent normalized and exact word-usage entries. Repeated dictionary candidates without personal history no longer query SQLite on every lookup. Learning removes the corresponding miss and publishes positive counters immediately. Restore/cache invalidation and language separation remain intact. Each miss cache retains its existing 4,096-entry bound.
- Build the detailed next-word diagnostic log only when suggestion diagnostics are enabled.

## Tangible validation

`WordUsageNegativeCacheTest` performs 4,000 consultations: 200 absent candidate words, ten repetitions, both normalized and exact counters. It verifies exactly 400 readable-database accesses. The previous uncached miss path queries on every absent lookup; this scenario avoids 3,600 reads (90%). This is a storage-access count, not a phone speed or battery measurement.

`SearchWorkCancellationTest` constructs a 10,000-candidate SymSpell bucket, injects cancellation at checkpoint 25, verifies interruption there, then verifies a subsequent request succeeds. A checkpoint count is not a candidate count or a latency result. Other tests verify cancellation before scoring returned prefixes, unchanged results for non-cancelled number/apostrophe/compound searches, cleanup of nested checkpoints and isolation across threads. Miss-cache tests also cover immediate learning and restored data becoming visible.

Exact suite totals are in [the phase 3 targeted test report](performance-phase3-test-results.json). Existing engine, SymSpell, compact-dictionary and optional-feature regression suites are selected alongside the persistence tests.

## Remaining scope

Next-word prediction at word boundaries and text reads through InputConnection remain on their existing paths. Redundant UI publication has not been suppressed because the same suggestion list can accompany a changed add-word button or modifier state. Those paths require separate profiling/snapshot work rather than assuming unchanged text means unchanged UI. Cold reads, model-lock contention during maintenance and allocations may still cause pauses.

No Titan typing-latency, battery or sustained-memory benchmark has been performed. The full test suite and phone validation remain pending. The phase 2 asynchronous persistence tradeoff remains: abrupt process termination can lose the newest pending learning, while completed backups wait for accepted writes. No claim of BlackBerry-equivalent performance is made.
