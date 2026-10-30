# GTSWN stage delivery: build and dedicated-server validation

**Status: completed stage results consolidated for review; final dedicated-server acceptance remains incomplete.** This stage submission is intentionally published before the remaining runtime checks, at the owner's request on 2026-10-30. It is not a claim that all validation is complete. The final candidate has passed its clean build, but the results from an earlier server-tested candidate must not be presented as final-candidate acceptance. This is a validation report, not a release, client-GUI sign-off, or approval to merge into `master`.

## What changed and why

- **Build and test setup:** the README now states the required Core version, and MM/FastUtil are available to test compilation without changing their optional production dependency roles. Tests now initialize real Forge item delegates, respect Forge's final world-storage field, and provide the vanilla item needed by native MM code. These changes fix inaccurate test setup rather than change gameplay formulas.
- **Accurate energy checks:** the server test's expected loss now follows the configured decimal value, instead of accidentally widening a float into a slightly different double. A transfer that would credit zero EU must retain its buffer. Native charging checks now exercise registered GT batteries, including near-`Long.MAX_VALUE` charge, exact import/export, fractional remainders, compressed item NBT, and explicit server refill callbacks.
- **Screen test placement:** the screen fixture now places blocks through the native item placement callback. The previous fixture triggered removal callbacks but omitted placement callbacks, which made its 2×2 screen check fail. The ownership, facing, loaded-chunk and cross-core guards remain in place; production screen geometry was not changed to make the test pass.
- **BetterQuesting version stamp:** a fresh world may not yet have the stamp's parent directory. The writer now creates and checks the directory, writes a complete temporary file, and atomically publishes it. This protects this one metadata file; it is not a transaction over all quest files.
- **BetterQuesting saved progress:** recovery is limited to GTSWN quest definitions actually recreated during this injection. It reads the native aggregate progress first, then native per-player progress, so it can recover legacy saves without replaying stale disk state over retained claims, resets, or unrelated quests. Input files are read without the parser's backup/rename side effects. Recovered players are marked for ordinary native saving, including partial-only progress in the exact native checkbox, retrieval and crafting task classes. This is a bounded recovery repair, not a generic migration guarantee for arbitrary custom tasks.

The consolidated review also retains the earlier lifecycle, protocol, monitor, material-receipt and exact-charging repairs. In particular, holding an already-bound device continues to permit the existing shared charging behavior; this work does not add a new holder restriction. Previously lost floating-point precision cannot be reconstructed from an old save.

## Verified results, by candidate

Counts below are deliberately separate: native focused checks, full-project JUnit tests and dedicated-server scenarios are different suites and must not be added together.

| Candidate | Clean full build | Dedicated-server evidence |
|---|---|---|
| `2.0.35-review.20261030.1` | 1,760 reported tests, 0 failures, 0 errors, 3 opt-in stress skips | Initial matrix: 17 server PASS, 1 screen-fixture FAIL, 1 manual-client SKIP. Failure retained in the validation history. |
| `2.0.35-review.20261030.2` | 1,766 reported tests, 0 failures, 0 errors, 3 opt-in stress skips | Focused screen scenario PASS; full selection: 18 server PASS and 1 manual-client SKIP. Normal save/stop and exit 0; no OOM or forced termination. |
| `2.0.35-review.20261030.3` | **PASS:** clean build including checks; 1,766 reported tests, 0 failures, 0 errors, 3 opt-in stress skips | **INCOMPLETE — final server validation started, but required completion reports, full selection, seeded saved-progress save and subsequent restart are not yet verified.** |

The `.2` server selection covered wireless EU transfer/loss/refund, monitor/redstone state, screen structure and native AE sampling, terminal naming and Baubles slots, binding and portable-monitor state, native exact charging, tap lifecycle, and quantum bridge/migration/incorporation/security/anchor behavior. Completion required a saved `COMPLETE` state, zero retained chunk tickets, and a written matching JSON report—not just a console PASS line. Final `.3` validation was interrupted by an execution-environment network-policy denial before those acceptance conditions were established. Its deployed production hash was verified, and the seeded startup sequence was observed: two saved custom definitions loaded, pack defaults replaced them, and 22 GTSWN definitions were recreated. The screen scenario emitted a console PASS, but its JSON report was not retrieved; this is not accepted scenario completion. The full matrix and seeded normal save/restart remain unverified. After the interruption, the owner relayed a server-side operator report of a normal stop through stdin: exit code 0, exit reason exit, no OOM indication, and all-dimension chunk-save log entries. The instance was retained. This is operator-reported evidence, not an independently retrieved terminal receipt in this validation run; it does not verify the semantic contents of the two seeded progress records or their subsequent restart persistence.

On a same-version `.2` restart, the version stamp and both final test reports were byte-identical, and the stamp-write warning was absent. The pack's normal quest-book reload recreated GTSWN definitions. That observation alone does **not** establish player progress loss in the earlier acceptance world; the later progress defects were reproduced with controlled saved data.

### Focused BetterQuesting evidence

- Using the actual native BetterQuesting 3.8.89 artifact, the focused suite reproduced **31 failures out of 87 checks** before the recovery repair; the repaired suite passed **87/87** on JDK 17 and JDK 8. Native 3.8.87 also passed **87/87**. The integrated, formatted final candidate was rerun against native 3.8.89 and passed **87/87**.
- Coverage includes aggregate-only recovery, per-player precedence, retained claims and resets, unrelated quest preservation, malformed-input byte preservation, dirty-player tracking, and native save/reload of incomplete checkbox, retrieval and crafting tasks.
- A separate exact saved-world fixture passed **5/5** offline native checks using the real bundled quest definitions and native progress save/reload. It contains one completed/claimed user and a separate partial-only crafting user. **Its final dedicated-server startup/save/restart acceptance remains pending.**
- These tests execute native quest/progress/task/database/NBT/I/O behavior with documented host seams. They are not evidence of authenticated player gameplay, arbitrary custom-task migration, or process-crash atomicity. Reflective lifecycle invocation is confined to tests; production recovery does not use reflection.

Three separate opt-in offline synthetic quantum benchmarks also passed on `.2` and were rerun successfully on the final `.3` candidate. They are not native AE connection-load tests or full-server MSPT measurements.

## Final-candidate provenance

| Item | Value |
|---|---|
| Build version | `2.0.35-review.20261030.3` |
| Production artifact | `gtswn-2.0.35-review.20261030.3.jar` |
| Artifact size | `2776278` bytes |
| Artifact SHA-256 | `306acf4122c2299f3156827f2a39242f4253cb8ae9d8c1663157145db5d9a96e` |
| Build-input source-manifest SHA-256 | `5f48ee916d97823ba4a3d5287814f5191b8687195cc541b0d235c1b4ff6dc5b9` |
| Final deployed artifact hash verified | **PASS:** remote SHA-256 matched the final artifact before launch |
| Final server matrix/report | **PENDING** |
| Seeded completed/claimed progress after normal save | **PENDING** |
| Seeded partial-only crafting progress after normal save | **PENDING** |
| Both seeded states preserved after subsequent restart | **PENDING** |
| Frozen source tree | `5175e7b54dead0d0ab3f4cb9f47b9489f15dc908` (1,101 source paths; report-only documentation is separate) |

Build tooling: Gradle 9.3.1 with pinned GTNH convention 2.0.20; Gradle launcher JDK 25; compiler JDK 17 with Jabel 1.0.2-GTNH targeting Java 8. Local-only launch adjustments preload the already-resolved Byte Buddy agent when dynamic attachment is unavailable and use a writable formatter cache. These do not change production gameplay or upgrade the pinned build plugin. `VERSION` supplies the artifact version; Maven publication was not run. Clients must use a compatible matching build for later client/server acceptance.

## What this does not prove

- No graphical client was available: GUI/HUD/NEI rendering, real mouse/keyboard interaction and client/server handshake remain unverified. The client-only scenario is explicitly skipped, not passed.
- Synthetic player callbacks and tile NBT replay cover selected native paths, not every natural player lifecycle or every machine's full process-restart persistence.
- Heavy AE/quantum stress worlds are outside the default server matrix. No general TPS/MSPT or large-network performance claim is made.
- MM under-delivery is covered by controlled material-receipt tests, not an observed malfunction of a live ME network.
- No general cause of earlier intermittent recipe disappearance or new shared Core defect was established.

The owner requested publication of the completed stage and a pause in further testing. PR #97 consolidates both original review histories; PR #98 may be closed as superseded only after the combined published tree is verified. The surviving PR remains draft and unmerged. Remaining runtime markers are not cleared by consolidation, and `master` is not changed.

## Publication boundaries

The final source tree retains both original PR histories without squashing or rewriting them. The final report is a documentation-only addition after the clean build and does not alter its 1,101-file build-input snapshot. No production JAR, private connection details, credentials, raw server logs or world data are included. Intermediate consolidation commits were not separately built. Stage delivery does not authorize a release or merge.
