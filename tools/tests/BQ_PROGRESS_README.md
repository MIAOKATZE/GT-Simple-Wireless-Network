# BetterQuesting progress recovery regression

This standalone native regression exercises the **complete production injector**, not extracted methods.
It requires caller-supplied local MCP-mapped Minecraft/Forge/BQ jars; it neither downloads dependencies nor
runs Gradle. Keep it isolated from the normal test suite's unrelated fixture packages.

```sh
python tools/tests/run_bq_progress_regressions.py \
  --classpath-file /path/to/native-mcp-classpath.txt \
  --java-home /path/to/jdk \
  --work-dir /path/to/new-test-output
```

The classpath file contains one OS-separated classpath line with Minecraft and Forge mapped to MCP names,
BetterQuesting (with its bundled Standard tasks) mapped to the same names, Gson, Guava, Netty, Authlib,
Log4j API/core, Commons Lang/IO and Trove. Production BQ jars require SRG-to-MCP member mapping, including
inherited members whose bytecode owner is a BQ subclass. Do not replace BQ logic with mocks to satisfy
mapping errors. `--source-root` selects a baseline checkout for before/after comparisons. The regression
source is always the sibling of the runner. Output includes generated fixtures, native classpath hashes,
source hashes, compile/test logs, and synthetic worlds.

## Coverage and native boundary

- Native BQ initial loading of aggregate and per-player saves; native defaults-style database
  snapshot/reload/restore with the owned definition genuinely absent or retained.
- Recreated owned completion, **serialized claimed bit** and native checkbox task progress.
- Partial-only users with no quest completion: one of two checkbox tasks complete, 1-of-2 retrieval,
  and 1-of-2 crafting. Each runs through native save and a subsequent native reload.
- Aggregate first / per-player second, in both claim-value directions.
- Retained live claims and resets, both same-version and definition-refresh injection.
- Existing foreign and unlisted-owned progress is not replayed while a listed owned quest is recovered.
- Repeat injection, unknown saved quest records, malformed sibling files, fresh stamp behavior.
- Exact input file-byte preservation during injection (including malformed files).
- Native dirty-player set union and real native asynchronous `saveProgress` writes for an offline UUID,
  followed by another native load of the resulting per-player file. Both the ordinary saved-world case
  and the case where the definition was already absent at initial loading are covered.

The database, QuestInstance, TaskCheckbox, registries, NBT serialization, JsonHelper native initial
load/save, dirty-player tracking and disk I/O are actual BQ. Generated seams are limited to GTSWN
logger/version, Loader presence, BQ logger/world-directory holder, network sends, and an empty
OreDictionary (untransformed Minecraft has no Forge Item.delegate). No SaveLoadHandler seam is used.
The defaults window invokes the same native database operations as QuestCommandDefaults, not the full
command, event bus or dedicated-server boot. The one-quest regression resource is generated, not the
complete 22-quest pack. This is not an online player/authentication or reward-grant test.

Verified against native BQ 3.8.87 and the deployed 3.8.89 production jar. The relevant official source
files are unchanged between these tags. Full server acceptance remains a distinct check.

BQ's native migration may log a `FileNotFoundException` after moving `QuestProgress.json` to
`QuestProgress.backup.json`, because its own save method then attempts to delete the already-moved source.
The regression checks the actual completed futures and reloads the new player file; it does not mistake
that native warning for either a successful or failed persistence result.

Dirty-player recovery uses BQ's `QuestInstance.getUsersWithCompletionData` API plus the documented
top-level user envelopes of the **three exact native task classes bundled by GTSWN**: TaskCheckbox,
TaskRetrieval and TaskCrafting. Only native-output `completeUsers` strings and `userProgress[].uuid` are
read; per-user payloads are not inspected. Subclasses/custom task classes are excluded, with no
reflection, private-map access, recursive NBT traversal or separate global save pass. Native dirty-player
state is unioned, never cleared; ordinary native saving writes all quest states for each queued user. **Custom task-only progress migration is not guaranteed**: there is no
universal BQ task-user enumeration API, and this repair does not invent one or claim third-party parser
rollback.

## Optional dedicated-server saved-world fixture

```sh
python tools/tests/make_bq_saved_world_fixture.py \
  --source-root . --output-dir /path/to/new-local-fixture
```

This writes **only a new local directory**. It does not contact a server. For an independently authorized
isolated test world, the two files under `World/betterquesting/` provide a synthetic *saved-world*
fixture: the real first bundled GTSWN quest plus the two-extender crafting quest in native `QuestDatabase.json`.
The aggregate holds one synthetic user's completion/claim/checkbox and a **different** user's partial-only
1-of-2 crafting progress. These UUIDs require no login.

Using the saved definition is important: it proves native legacy loading finds the completion user
before the pack's defaults reload removes the custom definition. After injection and a normal native
save, check both per-player files identified in `fixture-manifest.json`: the first user's **owned UUID
record**, serialized claimed bit and checkbox; the second user's crafting UUID record, no completion
entry, and native `data:11 == [1]` toward the required count of 2. Do not rely only on unchanged aggregate bytes or a successful
server-start log. Then repeat the stopped-world restart and check persistence again. Never upload this
fixture over a real player world or call the synthetic completion evidence player gameplay.
