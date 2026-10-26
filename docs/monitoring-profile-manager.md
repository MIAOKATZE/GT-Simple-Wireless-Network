# Recoverable monitoring profile manager

Open `/gtswnprofile ui` (alias `/monitorprofile ui`). Opening does not require the original item, so a lost terminal does not hide the recovery list. New packet IDs 46/47 are gated by negotiated capability bit 32; incompatible clients receive a chat explanation instead.

- **Import held source** inspects the actual server-side held terminal. It never consumes it and does not accept a copied NEI/ghost-stack NBT payload. DIT, portable wireless-monitor and quantum imports use their native adapter's current authorization checks. This is the supported alternative to drag-and-drop import.
- Select a profile; hover its row to see its full identity and owner. Page arrows browse the actor's authorized list. Server summaries contain metadata, never pairing keys or source NBT.
- **Bind held terminal** restores a reference onto a valid replacement terminal. Quantum recovery additionally requires a genuinely rebound/authorized quantum terminal for the same fixed native source; it does not reconstruct an encryption key.
- Enter a player's full UUID to share or revoke read access. **Tab** cycles existing readers for the selected owned profile. Only owners receive the reader UUID list; server authority, not disabled client buttons, enforces every operation.
- **Offline sampling** is explicit opt-in; the interval field accepts 20–72000 ticks. It does not force-load chunks. Quantum jobs recheck current UUID-backed native SECURITY grants (or actual controller ownership for an unsecured grid), without a fake player or stored credentials. Missing native authority pauses the job.
- **Allow Web publishing** grants owner consent only, after a second confirmation warning about all Web bearer-token holders. An operator must separately configure a Web dashboard entry. Consent does not enable offline sampling.

Mutations use a bounded, main-server-thread FIFO. A connection has at most 16 queued controls; at most 128 connections are retained and 32 controls are processed per tick with rotating fairness. A monotonically increasing request ID prevents replay or out-of-order requests from repeating a mutation. The GUI permits one outstanding operation and exposes a same-ID retry after five seconds. Full response pages (including the first open) consume the common per-player/global telemetry byte budgets. One result is retained per connection until admitted; no following mutation executes before that response is sent. Private metadata is rebuilt from current ACLs on each send attempt. A bounded 16-result replay cache preserves original success/error outcomes; expired replay IDs fail without executing again. Mutations/acknowledgments are never telemetry-coalesced. Logout/server stop clear connection state.

Static protocol coverage is in `MonitorProfilePacketTest`: fixed-size requests, bounds, metadata round trips, malformed/trailing payload rejection, and request-ID replay protection. GUI/game validation requires the normal authorized build/test environment; no game instance or listener is launched by this implementation.

## Quantum loss recovery is intentionally two-stage

**需先原生配对再恢复档案。** A blank replacement quantum terminal is not automatically enrolled by the profile manager. Pair the replacement using the native AE security-terminal workflow, bind it to the original quantum controller, then select the recovered profile and use **Bind held terminal**. The recover-button tooltip states these prerequisites in English and Chinese. On networks whose native safety policy does not require pairing, the existing native policy still applies; profile import/recovery never bypasses current native management authorization.

`ItemNetworkQuantumTerminal.writeAnchor` writes anchor coordinates and generation only. Its `setEncryptionKey` callback is an encoding callback, not an actor-authorized credential-issuance API. `QuantumTerminalAccess.permitsNative` checks both the encoded network and native permissions. Recovery therefore does not synthesize a key from the profile, duplicate an old credential, or weaken pairing checks. This is not one-click recovery from a blank quantum terminal.

## Names and DIT presentation recovery

Profiles have owner-editable human names (48 characters maximum). Use **Rename** in the manager or `/gtswnprofile rename <profile UUID> <name...>`; an empty name restores the stable UUID-based fallback. Existing saves without a name remain valid. Names reject formatting codes, controls and malformed Unicode; they grant no ownership or access.

DIT import captures only whitelisted presentation values: sort column, direction, count format, and a bounded custom item display name. Authorized sort/count changes update that profile presentation, which is restored when a replacement DIT is bound. Machine aliases, colors and bold text already live in the profile's copied `DeviceTerminalDataStore` records. No whole-item NBT, native key, source binding, owner field or visual-enable preference is copied by the presentation adapter. Changes to an item's display name outside the manager can be captured with another explicit import of the already-owned profile terminal.
