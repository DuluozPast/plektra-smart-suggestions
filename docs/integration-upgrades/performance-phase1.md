# Learned multiword performance — phase 1

This change preserves learned groups, scores, promotion rules, JSON schema and backup compatibility.

- Index promoted groups by exact key, locale and normalized context prefixes. Query only groups matching the current context or predicted first words.
- Update index buckets incrementally, preserving the old store ordering and tie-breaking, including changed promotions moving to the end.
- Batch group updates for a completed word into one atomic JSON write. In-memory changes remain immediately visible and are persisted before the operation returns.
- Leave disabled/empty learning batches lazy: they do not load the multiword file.
- Rebuild indexes after loading/restoration; remove obsolete buckets after deletion.

Regression tests compare indexed lookups to legacy scans across 600 groups in two languages, exercise promotion/update/removal ordering, durable reload, empty batches, nested batches and interrupted learning. The same implementation is ported to Pastiera Integration.

Limits: JSON persistence still encodes the complete store and is synchronous. SQLite learning transactions and final candidate sorting are unchanged. No asynchronous persistence or additional data-retention limit has been introduced. Indexes use additional memory. Phone latency and battery improvements remain unmeasured.

The next step is ordered asynchronous learning/persistence with immediate visibility of pending data and explicit draining before backup. It should be developed and validated separately.

Validation of the final code: Plektra 297 targeted tests, 295 passed and 2 Windows graphics tests skipped; Pastiera Integration 122 targeted tests passed. Both debug APKs compiled and their separate application identities/signatures were verified.

An expanded Integration run exposed two failing cases (rich restore on SQLite 3.8.7 and promoted dictionary casing). Both were reproduced on the archived pre-optimization Integration source. They remain unresolved baseline issues, not fixes claimed by this change. Full-suite validation and phone benchmarks remain pending.
