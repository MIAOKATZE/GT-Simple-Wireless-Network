# Negotiated EU / portable / AE history transport

Implementation record for the follow-on integration batch. This is source/test coverage, not an in-world performance claim.

## Compatibility

- Existing protocol-60 request/response bytes and signed chart-reuse semantics remain unchanged.
- `MonitorCapabilities` confirms new features through the existing legacy-safe probe before new discriminators are sent.
- Portable history uses 37 (request) / 38 (response); physical panel history uses 44 (ACK) / 45 (response), with capability `PANELS=16` independent of `PORTABLE=1`.
- Unsupported peers keep the original portable snapshots, physical S35 EU stream and AE packet. Modern physical recipients do **not** also receive routine legacy broadcasts.
- Initial chunk descriptions are always complete. Routine legacy EU pushes retain the single-sample delta and eight-delta full recovery when admitted immediately; deferred legacy pushes coalesce complete full snapshots so skipped frames never break a delta chain. Initial descriptions cannot accidentally consume an incremental-only body.

## Data and baselines

- Config revision changes only when scalar configuration changes, independently of raw-window history revisions.
- A separate explicitly ACKed metadata revision covers owner/window labels, palette/tab/display metadata, chart binding and ordered row keys/names/icons. Exact bounded bytes, not a hash, determine identity; reorder/pin/source changes invalidate the dictionary. Unchanged fields are omitted and reconstructed only from the same binding/revision. Numeric amounts/rates/averages and original sample clocks remain live.
- Server dictionaries are capped at 32 panels / 2 MiB per player and 16 MiB globally; eviction forces a new full metadata revision. Client terminal caches retain at most 32 bounded snapshots.
- Each raw EU/AE window is bounded to 61 original samples. Native ticks and wall timestamps remain intact; EU remains exact `BigInteger`, AE exact `long`, and rates remain doubles.
- Raw-capable packets do not send duplicate normalized curve arrays. Clients reconstruct an acknowledged bounded window and normalize the entire new window after every append, so extrema changes rescale old points correctly.
- Windows support prefix eviction plus suffix append, explicit reset, empty replacement, source-generation changes and full recovery. Unknown/mismatched ACK/config/source/window-field bases cannot be presented as fresh data.
- Portable requests acknowledge each completely received selected or pinned graph window, including its requested fields. The server retains at most nine window revisions per live tile.
- Physical clients ACK only after complete atomic application. Server bases advance only for actually sent versions named by an ACK; four sent candidates are retained per recipient. Delayed/coalesced transport does not advance a baseline.
- Original row sample times are transported separately on the new portable envelope. Receiving a fresh envelope does not invent a fresh AE observation timestamp.
- Exact EU decimal strings are bounded to 4,096 characters on the new wire. Oversized values are rejected rather than rounded/truncated into a fabricated number. Existing legacy preview limits remain unchanged.

## Physical screens and source lifecycle

- Routine EU publication is per current chunk watcher; AE additionally preserves its 64-block receiver range. Static block configuration/bindings travel only through initial/change S35 descriptions, not every modern EU sample.
- Compact sample application never overwrites newer static S35 configuration with a queued older configuration.
- EU native observation uses a separate ephemeral short lease (hard cap 200 ticks); panel renewal covers its unchanged existing polling cadence, and explicit job renewal does not activate the legacy five-minute request window. The public monitor API’s existing `updateRequestTick` contract remains intact. Unobserved loaded panels no longer renew forever; watched-tick accounting and fast machine/redstone work are unchanged.
- AE observers include real world screens, not just GUIs. Nearby observation and authorized portable access renew a 100-tick grace lease. Explicit monitoring jobs have a separate bounded renewal.
- Same actual AE grid/world/tick reads are shared and projected to each panel's bound keys. Existing reconciliation probes may still perform their intentional comparison reads; no claim of one physical read during reconciliation is made.
- AE monitoring identities are persisted through a registered native `IGridCache` / `IGridStorage` adapter, not the session-random `IGrid.getId()`. Publication waits for settled native topology. Normal storage reconstruction is intended to preserve the proven UUID; published splits, conflicting known-grid merges and uncertain identities retire the previous boundary. This is an implemented identity policy, not evidence that all native save/load orders have passed real-world validation. Coordinate-keyed legacy histories and prior generations are retained, not silently merged or deleted when a screen breaks. Same-grid sampling is shared; each panel still projects and stores its own selected history.
- Portable profile authorization is checked before source reads or lease renewal. Revoked profile responses clear portable caches; the legacy-style wireless HUD also checks authoritative profile access and receives a negotiated denial signal that clears numeric caches without creating zero samples.

## Limits and validation

New codecs reject malformed counts, unknown flags, negative revisions, non-finite raw samples, trailing data and truncation. Physical ACK admission is bounded; baseline advances cancel and rebuild queued old-base patches, and reset ACKs trigger a full replacement. Telemetry uses the central per-player/global byte-budget transport, including routine legacy S35 EU payloads serialized through an internal native-packet envelope (no new discriminator is sent to old clients). Initial/static vanilla chunk descriptions remain outside the telemetry cap. Quantum recipients rejected by queue bounds retain a retry obligation until a latest snapshot is retained or sent; already queued snapshots are not polled. Server stop clears delivery and baseline state; client world/session changes clear physical caches.

Added unit coverage includes raw-window normalization, prefix eviction, rollback/reset, source changes, independent config revisions, exact large integers and timestamps, per-pin cursor round trips, malformed/truncated packets, AE source sharing and observation leases. Central compilation/tests are pending until the final validation report records their actual outcome. No game/world runtime validation was performed by this transport workstream.

Payload-bearing portable action responses also use the telemetry budget. Each connection retains at most one pending action result and executes no later request/action before the send callback completes; different actions are not coalesced. Budget denial retains the same result for retry, with current possession and authorization rechecked. Lightweight empty-panel ACK/revocation controls retain priority. Final regression status is recorded separately.

### Bounded current/bulk queue policy

- Encoded telemetry payloads at most 4 KiB use the CURRENT lane; larger payloads use BULK. This is explicitly size-based QoS, not a claim to infer every UI consumer's semantics. Small full snapshots remain cheap/current, while large deltas use bulk.
- Both lanes debit the same per-player/global total byte buckets. While bulk is retained, CURRENT additionally passes half-rate, bounded-burst admission guards; these are not extra bandwidth. Refill not consumed by current accumulates for the oldest bulk turn.
- CURRENT can bypass queued bulk, including small complete DIT pages. Current and bulk alternate first access to fresh tick credit: even a maximum-size legal bulk frame can eventually fit, without blocking current for that entire accumulation period. Bulk turns and scarce current credit rotate across players.
- The existing total pending count/memory bounds remain unchanged. With a limit of at least two, bulk cannot occupy the last slot reserved for current. Coalescing remains per stream; no accepted packet is silently evicted. Authorization is revalidated at dispatch, including reentrant cancellation.
- This is a bounded byte-budget scheduler, not a wall-clock latency promise: server tick rate, configured rates, competing recipients, and payload size govern delay. Ordered controls remain outside telemetry debt.
