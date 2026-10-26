# DIT compact transport implementation notes

Status: source and regression fixtures are implemented. This module note does not certify execution; use the final candidate validation record for compilation and test outcomes. No measured network result is claimed here.

## Compatibility and authorization

- Legacy request/sync discriminators 8/9 retain their layouts. The compact request/sync use separately registered IDs 39/40, and are used only after the shared connection-bound capabilities negotiation advertises DIT.
- Server processing validates the current online player object, current connection, terminal possession, profile ACL, and native-source authorization **before** processing an ACK. Client UUIDs, versions, and ACKs never grant access.
- The terminal UUID doubles as the migrated profile reference. A server-assigned delivery epoch is bound to the connection, client cache session, and the profile adapter's world/source/authorization revisions. It changes on cache loss, source/profile changes, connection replacement, rollback, and invalid base claims.
- ACL changes explicitly revoke tracked viewers. A bounded sweep also checks native authorization and possession every 20 ticks. Compact revoke controls clear the client snapshot and dictionary. Legacy revocation sends an empty snapshot at a real store revision, then advances the real revision again so a later grant cannot collide with the empty cache. No synthetic maximum-version tombstone is used.

## Complete baseline and bounded continuation

The server retains one acknowledged complete row dictionary, one immutable in-flight target, and at most one encoded unsent page per terminal. The live store is the latest dirty state: intervening samples are not queued. Four terminal deliveries per player, the existing five-tick request throttle, at most two pages per burst, 128 rows per page, 1024 rows per target, and 600-tick idle expiry remain in place.

Only an ACK matching a target that was completely sent in the same delivery epoch can promote that target to the dictionary. Partial display prefixes and merely sent pages are not complete ACKs. An incomplete transfer replays the frozen target; it does not restart at each new sample. After complete ACK, the next target captures the latest live store once.

Both legacy and compact pages use the shared serialized-byte budget. Budget denial leaves the page cursor unchanged. Revocations use the separate bounded-size control path, not the latest-state queue. A compact body is limited to 256 KiB; no compressed payload expansion exists.

## Wire and row representation

- Compact request: schema byte, binary terminal UUID, binary cache-session UUID, epoch, complete ACK version, pending target version: **57 bytes**.
- Compact sync: schema/kind, two binary UUIDs, epoch/base/target versions, page index/count, target row count, payload length: **66-byte header** plus the bounded payload.
- Kinds: full, delta, keepalive, revoked. Full and delta pages in one frozen target may differ, but share the same base/target/epoch and target layout.
- Every target page explicitly defines its resulting order. Base-row references omitted from the completed target are removals; new rows carry full fields. Empty full payload clears the collection.
- Unchanged rows carry only a two-byte base dictionary reference. Changed rows carry a reference, field mask, and only changed fields. Added rows carry all fields. Changed/new strings use a page-local dictionary; unchanged static strings and coordinates are not sent.
- Coordinates are four binary integers; the client reconstructs `dim:x:y:z` rather than receiving a duplicate coordinate string. Long values and raw IEEE-754 double bits remain exact.
- Full and delta payloads are actually encoded and compared per page; full is chosen if no larger. This avoids a delta penalty when most metadata or identities change.

The source-level all-four-power-fields-changing fixture expects `5 + 1024 * 38 = 38917` row-payload bytes in the generic codec test; unchanged rows require two bytes each. These are fixture expectations, not executed measurements or a claim about TCP bandwidth/TPS. Actual paging adds a 66-byte packet header and a five-byte row-codec header per page.

## Recovery and decoder boundaries

The client maintains its complete dictionary separately from legacy display prefixes and only commits a compact target after all pages arrive with exact row counts and unique identities. Missing base, malformed payload, cross-page duplicates, mismatched layouts, stale epochs, old connection sessions, unknown masks, invalid strings/enums, oversized payloads, truncation, and trailing bytes cannot establish authority. Newer epoch reset pages must begin at page zero; subsequent out-of-order pages are accumulated. Keepalive can refresh only the matching complete cache, never create one.

Four new test classes contain 25 authored cases covering codecs, request/sync framing, exact numerics, full fallback, changed-row bandwidth fixtures, complete ACK, fabricated ACK, frozen continuation, denied-budget cursor stability, out-of-order client assembly, malformed/resync, cache session loss, revocation, and bounded client cache behavior. Existing legacy tests remain. Execution status is recorded separately in `monitoring-uplink-validation.md`. Real-platform functional and load validation remains distinct from unit tests and startup checks.
