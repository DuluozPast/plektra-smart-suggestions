# Learning persistence off the typing thread

This update follows the [indexed multiword lookup changes](performance-phase1.md). It retains the previous optional Integration upgrades and the existing JSON/SQLite schemas. It does not change promotion thresholds, remove learned phrases, or impose a new vocabulary limit.

## What changes

- Word and correction usage counters update their in-memory model immediately. Their SQL mutations run in acceptance order on lazy background workers; an older completed write cannot overwrite newer counters in memory.
- Phrase learning exposes pending usage and contextual next-word predictions immediately, while the underlying phrase and context-index transactions run in the background. Numeric tokens, compound words, language separation and longest-context ranking are preserved.
- Multiword changes update the existing indexed model immediately. The worker copies a revision snapshot under the model lock, then serializes and atomically writes JSON outside that lock. Changes accepted during an in-flight write cause a newer snapshot to follow; concurrent updates can be coalesced without losing their latest state.
- Each application data directory has one shared learning model. A keyboard-service restart reuses that model rather than loading an older file while a previous session is still writing.
- Backup and restore barriers drain all registered writers, including writers belonging to a closing session. Failed operations remain queued, are retried at a barrier, and cause the barrier to fail if persistence is still unavailable. Later writes do not overtake a failed operation.
- Normal next-word predictor shutdown lets accepted operations finish rather than cancelling its queue. Restoring learning data invalidates the shared models even when the keyboard is inactive.
- Workers start only when work arrives and expire after 30 idle seconds. No periodic alarm or phone wake-up is introduced. SQLite write-ahead logging is enabled for asynchronous usage stores.

## Validation

The new `AsyncLearningPersistenceTest` exercises a blocked writer, a persistent write failure and recovery, immediate counters versus reloaded SQL data, phrase predictions versus persisted ranking, rapid JSON updates and reload, shared-session models, and invalidation after stored data changes. Existing feature and learning regression tests are also selected. Exact results are in [the phase 2 test report](performance-phase2-test-results.json).

On Windows, the JSON test uses a narrowly scoped `AtomicFile.finishWrite` shadow: real file streams are retained, and Windows NIO atomic replacement reproduces Android/Linux rename-over-existing semantics. Without that adaptation, the host's `File.renameTo` can leave the old JSON in place. This is a test-environment adaptation, not an Android production replacement.

## Remaining limits and tradeoffs

- Learning becomes visible before it reaches durable storage. An abrupt process kill or power loss during that short pending interval can lose the most recent unpersisted changes. A completed backup waits for accepted writes; it does not silently omit them. This is not a guarantee of crash-proof per-keystroke persistence.
- Initial cache misses and cold database/model loading can still read storage synchronously. JSON snapshot copying still briefly holds the model lock and grows with the learned corpus. Serialization and file IO no longer hold that lock.
- Maintenance drains pending operations and prunes under model locks; it can still briefly contend with typing. Personal-dictionary consolidation and other existing synchronous paths remain. This patch does not claim to eliminate every source of latency.
- Context and usage caches consume extra memory. Phrase prediction still sorts the relevant context bucket. Broad promotion scans remain in background maintenance.
- Separate workers preserve order within each store. They do not create one cross-dataset transaction for an entire completed word. Existing backup/restore exclusion and rollback remain necessary.
- No Titan latency, sustained-typing, battery, or long-running memory benchmark has been performed. Phone validation and the full test suite remain pending. No claim is made about BlackBerry's private implementation or equivalent performance.

The two test APKs retain distinct application IDs and existing test signing identity, so Plektra Integration Test and Pastiera Multimots Test can coexist and update their respective previous test builds.
