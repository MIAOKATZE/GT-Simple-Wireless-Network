package com.miaokatze.gtswn.common.testworld;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongPredicate;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkColorRegistry;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.miaokatzecore.testkit.Deadline;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Explicit-command-only controller. All world access stays on the server thread. */
public final class TestWorldManager {

    public static final int MIN = -32;
    public static final int MAX = 31;
    public static final int LAYOUT_SCHEMA = 1;
    public static final long RESET_SCAN_BUDGET_NANOS = 3_000_000L;
    public static final int RESET_SCAN_MAX_CHECKS = 65536;
    private static final Map<String, TestScenario> SCENARIOS = new LinkedHashMap<>();
    private static final TestWorldManager EVENTS = new TestWorldManager();
    private static final List<ForgeChunkManager.Ticket> TICKETS = new ArrayList<>();
    private static volatile TestContext current;
    private static WorldServer world;
    private static TestWorldData data;
    private static TestWorldReport report;
    private static File reportFile;
    private static List<TestScenario> selection;
    private static int scenarioIndex;
    private static int loadIndex;
    private static int resetCursor;
    private static TestWorldFootprint resetFootprint;
    private static int requestedRadius = TestWorldFootprint.DEFAULT_RADIUS;
    private static long operationTicks;
    private static long tickStartNanos;
    private static long tickStartCpuNanos;
    private static TestWorldPerformance runPerformance;
    private static TestWorldPerformance phasePerformance;
    private static TestWorldPerformance tickPerformance;
    private static TestContext tickWorkloadContext;
    private static TestWorldReport.WorkloadPhase tickWorkloadPhase;
    private static TestWorldPerformance closedResetPerformance;
    private static TestContext closedPerformanceContext;
    private static boolean processingTick;
    private static TestWorldReport deferredFinalReport;
    private static File deferredFinalFile;
    private static TestWorldReport.WriteReceipt finalWrite;
    private static final Map<File, TestWorldReport.WriteReceipt> PENDING_FINAL_WRITES = new LinkedHashMap<>();
    private static File boundReportTarget;
    private static String phase = "IDLE";
    private static String reportPath = "";
    private static String publishedReceipt = "";
    private static boolean registered;
    private static List<ChunkCoordIntPair> releasingChunks;
    private static int releaseIndex, releaseWait;
    private static String releasingStatus, releasingFailure;

    private TestWorldManager() {}

    public static void init() {
        if (!DebugTestGate.isEnabled()) return;
        if (registered) return;
        registered = true;
        TestUiKit.register();
        MinecraftForge.EVENT_BUS.register(EVENTS);
        FMLCommonHandler.instance()
            .bus()
            .register(EVENTS);
    }

    public static void register(TestScenario scenario) {
        if (scenario == null || !scenario.id()
            .matches("[a-z0-9][a-z0-9_.-]{0,79}")) throw new IllegalArgumentException("Invalid test scenario ID");
        if (SCENARIOS.containsKey(scenario.id()))
            throw new IllegalArgumentException("Duplicate scenario " + scenario.id());
        new TestWorldFootprint(scenario.footprintRadius());
        SCENARIOS.put(scenario.id(), scenario);
    }

    static List<TestScenario> scenarios() {
        return new ArrayList<>(SCENARIOS.values());
    }

    public static List<String> ids() {
        return new ArrayList<>(SCENARIOS.keySet());
    }

    public static String description(String id) {
        return SCENARIOS.get(id)
            .description();
    }

    public static void serverStarting() {
        if (!DebugTestGate.isEnabled()) return;
        SCENARIOS.clear();
        ScenarioRegistry.register();
        TestWorldReport.startWriter();
        rememberFinalWrite();
        data = null;
        finalWrite = null;
        boundReportTarget = null;
        reportPath = "";
        phase = "IDLE"; // The new save's journal, not the previous save's receipt, owns its gate.
        GTSimpleWirelessNetwork.LOG.info(
            "[GTSWN-TEST] READY dimension={} scenarios={} availability={}",
            TestDimension.dimId(),
            SCENARIOS.size(),
            TestDimension.problem() == null ? "enabled" : TestDimension.problem());
    }

    private static TestWorldData journal() {
        DebugTestGate.requireEnabled();
        if (TestDimension.problem() != null) throw new IllegalStateException(TestDimension.problem());
        if (data == null) {
            WorldServer overworld = MinecraftServer.getServer()
                .worldServerForDimension(0);
            if (overworld == null) throw new IllegalStateException("Overworld unavailable");
            TestWorldData loaded = TestWorldData.get(overworld);
            if (loaded.dimension != 0 && loaded.dimension != TestDimension.dimId()) throw new IllegalStateException(
                "Saved test dimension ID=" + loaded.dimension + "; configuration changed. Restore the ID.");
            data = loaded;
            data.dimension = TestDimension.dimId();
            if (data.layoutSchema != LAYOUT_SCHEMA) data.built = false;
            bindReportJournal(
                overworld.getSaveHandler()
                    .getWorldDirectory());
            if ("RUNNING".equals(data.operation) || "BUILDING".equals(data.operation)) {
                data.operation = "INTERRUPTED";
                data.built = false;
                if (data.activeRun.matches("[0-9a-f-]{36}")) {
                    File save = overworld.getSaveHandler()
                        .getWorldDirectory();
                    File interrupted = new File(new File(save, "gtswn-test-reports"), data.activeRun + ".json");
                    TestWorldReport.markInterrupted(interrupted, data.activeRun, data.dimension);
                    data.lastReport = interrupted.getAbsolutePath();
                    data.lastOutcome = "INTERRUPTED";
                }
                data.activeRun = "";
            }
            reportPath = data.lastReport;
            data.markDirty();
        }
        return data;
    }

    private static void rememberFinalWrite() {
        if (finalWrite == null) return;
        if (finalWrite.state() == TestWorldReport.WriteReceipt.State.WRITTEN || finalWrite.abandoned()) {
            if (PENDING_FINAL_WRITES.get(finalWrite.target) == finalWrite)
                PENDING_FINAL_WRITES.remove(finalWrite.target);
        } else PENDING_FINAL_WRITES.put(finalWrite.target, finalWrite);
    }

    /** Rebind only to the current save's derived absolute target; saved lastReport is display metadata. */
    private static void bindReportJournal(File saveDirectory) {
        rememberFinalWrite();
        finalWrite = null;
        boundReportTarget = null;
        phase = "IDLE";
        if (("REPORTING".equals(data.operation) || "REPORT_FAILED".equals(data.operation))
            && data.activeRun.matches("[0-9a-f-]{36}")) {
            boundReportTarget = TestWorldReport
                .normalizedTarget(new File(new File(saveDirectory, "gtswn-test-reports"), data.activeRun + ".json"));
            TestWorldReport.WriteReceipt cached = PENDING_FINAL_WRITES.get(boundReportTarget);
            finalWrite = cached != null && cached.matches(boundReportTarget, data.activeRun) ? cached
                : TestWorldReport.recoverFinal(boundReportTarget, data.activeRun, data.lastOutcome);
            rememberFinalWrite();
            data.lastReport = boundReportTarget.getAbsolutePath();
            phase = "REPORTING";
            acknowledgeFinalWrite();
        }
        if ("COMPLETE".equals(data.operation) && data.activeRun.isEmpty()
            && data.lastRun.matches("[0-9a-f-]{36}")
            && finalWrite == null) {
            boundReportTarget = TestWorldReport
                .normalizedTarget(new File(new File(saveDirectory, "gtswn-test-reports"), data.lastRun + ".json"));
            finalWrite = TestWorldReport.recoverFinal(boundReportTarget, data.lastRun, data.lastOutcome);
            data.lastReport = boundReportTarget.getAbsolutePath();
        }
        reportPath = data.lastReport;
    }

    private static boolean matchesCurrentJournal() {
        return data != null && finalWrite != null && finalWrite.matches(boundReportTarget, data.activeRun);
    }

    public static boolean isRunning(World candidate) {
        TestContext context = current;
        return context != null && candidate == context.world() && TestDimension.isTest(candidate);
    }

    public static void record(World candidate, String type, String detail) {
        TestContext context = current;
        if (context != null && candidate == context.world() && TestDimension.isTest(candidate))
            context.event(type, detail);
    }

    public static void build() {
        build("build");
    }

    public static void build(String operation) {
        if (!"build".equals(operation) && !"reset".equals(operation))
            throw new IllegalArgumentException("Unknown reset operation: " + operation);
        requireIdle();
        journal();
        world = TestDimension.ensureWorld();
        selection = null;
        createReport(operation);
        report.results.add(new TestWorldReport.Result("harness.reset"));
        beginReset();
    }

    public static String run(String requested) {
        requireIdle();
        List<TestScenario> chosen = selectScenarios(requested, SCENARIOS.values());
        if (chosen.isEmpty()) throw new IllegalStateException("No test scenarios registered");
        journal();
        world = TestDimension.ensureWorld();
        selection = chosen;
        finalWrite = null;
        scenarioIndex = 0;
        createReport(requested);
        report.actors.addAll(TestActors.describe());
        for (TestScenario scenario : chosen) report.results.add(new TestWorldReport.Result(scenario.id()));
        report.event(null, 0, "run", "selection=" + requested + "; scenarios=" + chosen.size());
        beginReset();
        return report.runId;
    }

    static List<TestScenario> selectScenarios(String requested, Iterable<TestScenario> available) {
        List<TestScenario> chosen = new ArrayList<>();
        for (TestScenario scenario : available) {
            if ("all".equals(requested) ? scenario.includeInAll()
                : scenario.id()
                    .equals(requested))
                chosen.add(scenario);
        }
        if (chosen.isEmpty() && !"all".equals(requested))
            throw new IllegalArgumentException("Unknown scenario: " + requested);
        return chosen;
    }

    private static void createReport(String requested) {
        finalWrite = null;
        tickStartNanos = 0;
        tickStartCpuNanos = -1;
        tickPerformance = null;
        closedResetPerformance = null;
        closedPerformanceContext = null;
        runPerformance = new TestWorldPerformance();
        report = new TestWorldReport(
            UUID.randomUUID()
                .toString(),
            TestDimension.dimId(),
            requested);
        File save = MinecraftServer.getServer()
            .worldServerForDimension(0)
            .getSaveHandler()
            .getWorldDirectory();
        reportFile = new File(new File(save, "gtswn-test-reports"), report.runId + ".json");
        boundReportTarget = TestWorldReport.normalizedTarget(reportFile);
        reportPath = reportFile.getAbsolutePath();
        data.lastRun = report.runId;
        data.lastSelection = requested;
        data.lastCleanup = "PENDING";
        data.lastActorLevel2 = data.lastActorLevel4 = null;
        data.lastActor = "not observed";
        data.lastTransport = "not observed";
        data.activeRun = report.runId;
        data.lastOutcome = "BUILDING";
        data.lastReport = reportPath;
        publishedReceipt = "";
        TestLabEvents.publish(report.runId, requested, "run.started", "selected=" + requested, null, "pending");
    }

    public static boolean busy() {
        return !"IDLE".equals(phase);
    }

    private static void requireIdle() {
        journal();
        acknowledgeFinalWrite();
        if (busy()) throw new IllegalStateException("Test operation already active: " + phase);
        if (TestDimension.problem() != null) throw new IllegalStateException(TestDimension.problem());
        if (TestUiKit.inUse())
            throw new IllegalStateException("Restore the active UI kit before rebuilding or running server tests");
    }

    private static void beginReset() {
        requestedRadius = selection == null ? TestWorldFootprint.DEFAULT_RADIUS
            : selection.get(scenarioIndex)
                .footprintRadius();
        resetFootprint = data.beginResetFootprint(requestedRadius);
        phasePerformance = new TestWorldPerformance();
        phase = "BUILDING";
        loadIndex = 0;
        resetCursor = 0;
        operationTicks = 0;
        data.built = false;
        data.operation = phase;
        data.markDirty();
        if (report != null) {
            data.lastOutcome = phase;
            report.status = phase;
            report.event(null, report.elapsedTicks, "reset", "bounded reset " + resetFootprint.describe());
            report.write(reportFile, false);
        }
    }

    private static void leaseChunk(int cx, int cz) {
        ChunkCoordIntPair chunk = new ChunkCoordIntPair(cx, cz);
        for (ForgeChunkManager.Ticket acquired : TICKETS) if (acquired.getChunkList()
            .contains(chunk)) return;
        ForgeChunkManager.Ticket ticket = TICKETS.isEmpty() ? null : TICKETS.get(TICKETS.size() - 1);
        if (ticket == null || !ticketHasRoom(
            ticket.getChunkListDepth(),
            ticket.getChunkList()
                .size())) {
            ticket = ForgeChunkManager
                .requestTicket(GTSimpleWirelessNetwork.instance, world, ForgeChunkManager.Type.NORMAL);
            if (ticket == null) throw new IllegalStateException("Forge chunk ticket refused; test stopped");
            TICKETS.add(ticket);
            ticket.getModData()
                .setBoolean("GTSWNTestRun", true);
        }
        ForgeChunkManager.forceChunk(ticket, chunk);
    }

    /** Forge evicts only for a positive maxDepth; zero and negative native depths are unbounded. */
    static boolean ticketHasRoom(int depth, int chunks) {
        return depth <= 0 || chunks < depth;
    }

    /** Verify the actual active leases and loaded chunks, rather than the count of past load requests. */
    static int verifiedForcedChunkCount(WorldServer expectedWorld, int radius) {
        if (world != expectedWorld)
            throw new IllegalStateException("Chunk lease verification requires the active test world");
        TestWorldFootprint footprint = new TestWorldFootprint(radius);
        int count = 0;
        for (int i = 0; i < footprint.chunkCount(); i++) {
            ChunkCoordIntPair chunk = new ChunkCoordIntPair(footprint.chunkX(i), footprint.chunkZ(i));
            boolean leased = false;
            for (ForgeChunkManager.Ticket ticket : TICKETS) {
                if (ticket.getChunkList()
                    .contains(chunk)
                    && ForgeChunkManager.getPersistentChunksFor(world)
                        .containsEntry(chunk, ticket)) {
                    leased = true;
                    break;
                }
            }
            if (leased && world.theChunkProviderServer.chunkExists(chunk.chunkXPos, chunk.chunkZPos)) count++;
        }
        return count;
    }

    private static void buildTick() {
        if (++operationTicks > 6000) throw new IllegalStateException("Build exceeded 6000 real server ticks");
        if (loadIndex < resetFootprint.chunkCount()) {
            int cx = resetFootprint.chunkX(loadIndex);
            int cz = resetFootprint.chunkZ(loadIndex);
            leaseChunk(cx, cz);
            world.theChunkProviderServer.loadChunk(cx, cz);
            loadIndex++;
            return; // At most one synchronous chunk load per server tick.
        }
        Deadline deadline = Deadline.afterNanos(RESET_SCAN_BUDGET_NANOS);
        int visited = 0;
        while (resetCursor < resetFootprint.blockCount() && visited < RESET_SCAN_MAX_CHECKS) {
            int y = resetCursor & 255;
            int x = resetFootprint.blockX(resetCursor);
            int z = resetFootprint.blockZ(resetCursor);
            net.minecraft.block.Block target = y == 0 ? Blocks.bedrock : y <= 63 ? Blocks.stone : Blocks.air;
            if (world.getBlock(x, y, z) != target || world.getBlockMetadata(x, y, z) != 0)
                world.setBlock(x, y, z, target, 0, 3);
            resetCursor++;
            visited++;
            if ((visited & 63) == 0 && deadline.isExpired()) return;
        }
        if (resetCursor < resetFootprint.blockCount()) return;
        // Only entities inside the authored reset volume belong to this operation. Players are preserved.
        for (Object object : new ArrayList<>(world.loadedEntityList)) {
            Entity entity = (Entity) object;
            if (!(entity instanceof EntityPlayer) && entity.posX >= -resetFootprint.radius
                && entity.posX < resetFootprint.radius
                && entity.posZ >= -resetFootprint.radius
                && entity.posZ < resetFootprint.radius) entity.setDead();
        }
        forgetResetQuantumHistory();
        TestWorldFixtures.build(world);
        data.completeResetFootprint(requestedRadius);
        data.layoutSchema = LAYOUT_SCHEMA;
        data.built = true;
        data.operation = "COMPLETE";
        data.markDirty();
        closedResetPerformance = phasePerformance;
        phasePerformance = null;
        if (selection == null) {
            TestWorldReport.Result result = report.results.get(0);
            result.status = "PASS";
            result.detail = "Bounded reset completed " + resetFootprint.describe() + "; authored fixtures built";
            result.ticks = operationTicks;
            complete("PASS", null);
            GTSimpleWirelessNetwork.LOG.info("[GTSWN-TEST] BUILD DONE dimension={}", TestDimension.dimId());
        } else startScenario();
    }

    /** Called only after the entire authored reset volume has been physically cleared. */
    private static void forgetResetQuantumHistory() {
        TestWorldFixtures.requireTestWorld(world);
        int radius = resetFootprint.radius;
        LongPredicate clearedVolume = position -> {
            int x = QuantumControllerRegistry.unpackX(position);
            int y = QuantumControllerRegistry.unpackY(position);
            int z = QuantumControllerRegistry.unpackZ(position);
            return x >= -radius && x < radius && z >= -radius && z < radius && y >= 0 && y <= 255;
        };
        QuantumControllerRegistry controllers = QuantumControllerRegistry.get(world);
        controllers.forgetRemovedControllerHistory(clearedVolume);
        QuantumNetworkColorRegistry.get(world)
            .forgetColors(
                position -> clearedVolume.test(position) && !controllers.isQuantized(position)
                    && !controllers.isSuspendedController(position));
    }

    private static void startScenario() {
        TestScenario scenario = selection.get(scenarioIndex);
        TestWorldFootprint footprint = new TestWorldFootprint(requestedRadius);
        if (resetFootprint.radius != footprint.radius) {
            releaseTickets();
            // All target chunks were loaded incrementally during reset; forcing them does not load new chunks.
            for (int index = 0; index < footprint.chunkCount(); index++)
                leaseChunk(footprint.chunkX(index), footprint.chunkZ(index));
        }
        TestWorldReport.Result result = report.results.get(scenarioIndex);
        result.status = "RUNNING";
        if (!"device.paged_client".equals(result.id) && !"quantum.safety_client".equals(result.id)) {
            result.actor = "synthetic server fixtures";
            result.transport = "direct native server callbacks; no client/Netty claim";
        }
        TestLabEvents.publish(report.runId, result.id, "task.started", "native server callbacks", null, "pending");
        result.detail = "running";
        current = new TestContext(world, report.runId, report, result);
        current.beginPerformance();
        phasePerformance = current.performance();
        phase = "RUNNING";
        report.status = phase;
        data.operation = phase;
        data.lastOutcome = phase;
        data.markDirty();
        TestWorldProbes.start((type, detail) -> {
            TestContext context = current;
            if (context != null) context.event(type, detail);
        }, TestDimension.dimId());
        current.event("start", scenario.id() + "; footprint " + footprint.describe());
        report.write(reportFile, false);
        long started = System.nanoTime();
        try {
            scenario.start(current);
        } catch (Exception | LinkageError ex) {
            current.callbackFailure("start", ex);
        }
        recordCallback(System.nanoTime() - started);
        current.operationEnd("scenario.start", started);
        if (current.outcome != null) finishScenario();
    }

    private static String exception(Throwable ex) {
        return ex.getClass()
            .getName() + ": "
            + ex.getMessage();
    }

    private static void recordCallback(long nanos) {
        report.callbackNanos += nanos;
        report.maxCallbackNanos = Math.max(report.maxCallbackNanos, nanos);
        if (current != null) {
            current.result.callbackNanos += nanos;
            current.result.maxCallbackNanos = Math.max(current.result.maxCallbackNanos, nanos);
        }
    }

    private static void scenarioTick() {
        TestScenario scenario = selection.get(scenarioIndex);
        current.elapsed++;
        if (current.elapsed > Math.max(1, scenario.timeoutTicks()))
            current.fail("timeout after " + scenario.timeoutTicks() + " real ticks");
        else {
            long started = System.nanoTime();
            try {
                scenario.tick(current);
            } catch (Exception | LinkageError ex) {
                current.callbackFailure("tick", ex);
            }
            recordCallback(System.nanoTime() - started);
            current.operationEnd("scenario.tick", started);
        }
        if (current.outcome != null) finishScenario();
    }

    private static void finishScenario() {
        TestContext context = current;
        long cleanupStarted = System.nanoTime();
        context.result.cleanup = "COMPLETED";
        try {
            selection.get(scenarioIndex)
                .cleanup(context);
        } catch (Exception | LinkageError ex) {
            context.result.cleanup = "FAILED";
            context.callbackFailure("cleanup", ex);
            context.event("cleanup_failure", exception(ex));
        } finally {
            context.operationEnd("scenario.cleanup", cleanupStarted);
            captureCounts(context);
            TestWorldProbes.stop();
        }
        context.result.status = context.outcome;
        if (data != null) {
            if ("FAILED".equals(context.result.cleanup)) data.lastCleanup = "FAILED";
            data.lastActorLevel2 = context.result.actorLevel2;
            data.lastActorLevel4 = context.result.actorLevel4;
            data.lastActor = context.result.actor;
            data.lastTransport = context.result.transport;
            data.markDirty();
        }
        TestLabEvents.publish(
            context.runId(),
            context.result.id,
            "task.finished",
            context.detail,
            context.outcome,
            "scenario cleanup=" + context.result.cleanup + "; fixtures retained");
        context.result.detail = context.detail;
        context.result.ticks = context.elapsed;
        closedPerformanceContext = context;
        phasePerformance = null;
        current = null;
        scenarioIndex++;
        if (scenarioIndex < selection.size()) beginReset();
        else {
            String outcome = "PASS";
            boolean passed = false;
            for (TestWorldReport.Result result : report.results) {
                if ("FAIL".equals(result.status)) {
                    outcome = "FAIL";
                    break;
                }
                if ("PASS".equals(result.status)) passed = true;
            }
            if ("PASS".equals(outcome) && !passed) outcome = "SKIP";
            complete(outcome, null);
        }
    }

    private static void complete(String status, String failure) {
        TestWorldProbes.stop();
        if ("BUILDING".equals(phase) && phasePerformance != null) {
            closedResetPerformance = phasePerformance;
            phasePerformance = null;
        }
        // A whole unattended factory otherwise unloads in one tick. Native AE then tears down the
        // central controller before its wireless leaves, repeatedly migrating the entire storage grid.
        if (!"RELEASING".equals(phase) && world != null
            && world.playerEntities.isEmpty()
            && !TICKETS.isEmpty()
            && report != null
            && NativeFactoryStressScenario.ID.equals(report.selection)) {
            List<ChunkCoordIntPair> chunks = new ArrayList<>();
            for (ForgeChunkManager.Ticket ticket : TICKETS) chunks.addAll(ticket.getChunkList());
            chunks.sort(Comparator.comparingInt(TestWorldManager::releasePriority));
            releasingChunks = chunks;
            releaseIndex = releaseWait = 0;
            releasingStatus = status;
            releasingFailure = failure;
            phase = "RELEASING";
            current = null;
            selection = null;
            report.event(null, report.elapsedTicks, "release_start", "chunks=" + chunks.size());
            GTSimpleWirelessNetwork.LOG
                .info("GTSWN_NATIVE_STRESS_RELEASE run={} chunks={} cadenceTicks=2", report.runId, chunks.size());
            return;
        }
        completeNow(status, failure);
    }

    private static void completeNow(String status, String failure) {
        releaseTickets();
        if (data != null && !"FAILED".equals(data.lastCleanup)) data.lastCleanup = "COMPLETED";
        if (report != null) {
            report.status = status;
            TestLabEvents.publish(
                report.runId,
                report.selection,
                "cleanup.finished",
                "chunk tickets released; authored fixtures retained",
                status,
                "released");
            TestLabEvents.publish(
                report.runId,
                report.selection,
                "STOPPED".equals(status) ? "run.cancelled" : "run.finished",
                failure,
                status,
                "released");
            report.failure = failure;
            report.endedAtMs = System.currentTimeMillis();
            report.event(null, report.elapsedTicks, "complete", status + (failure == null ? "" : ": " + failure));
            if (processingTick) {
                deferredFinalReport = report;
                deferredFinalFile = reportFile;
            } else {
                finishPerformance(report);
                finalWrite = report.writeFinal(reportFile);
                rememberFinalWrite();
            }
        }
        if (data != null) {
            data.operation = report == null ? "COMPLETE" : "REPORTING";
            if (report != null) data.lastOutcome = status;
            if (report == null) data.activeRun = "";
            data.markDirty();
        }
        phase = report == null ? "IDLE" : "REPORTING";
        current = null;
        selection = null;
        report = null;
        world = null;
    }

    private static void captureCounts(TestContext context) {
        for (Map.Entry<TestWorldProbes.Type, Long> entry : TestWorldProbes.counts()
            .entrySet())
            context.result.observedBusinessEvents.put(
                entry.getKey()
                    .name(),
                entry.getValue());
    }

    public static void stop(String reason) {
        journal();
        stop(reason, true);
    }

    private static void stop(String reason, boolean explicitAbandon) {
        acknowledgeFinalWrite();
        // Business is already complete; an administrator stop cannot replace that result.
        if ("RELEASING".equals(phase)) return;
        if ("REPORTING".equals(phase)) {
            // Only an explicit administrator stop may abandon a snapshot already lost across process restart.
            if (explicitAbandon && matchesCurrentJournal() && finalWrite.abandonRecovery()) {
                if (data != null) {
                    data.operation = "REPORT_FAILED";
                    data.activeRun = "";
                    data.markDirty();
                }
                phase = "IDLE";
                rememberFinalWrite();
                GTSimpleWirelessNetwork.LOG.warn(
                    "[GTSWN-TEST] Abandoned unavailable final evidence run={} outcome={} report={}",
                    finalWrite.runId,
                    finalWrite.outcome,
                    finalWrite.target);
            }
            return; // Never replace completed business with STOPPED or discard a live final snapshot.
        }
        if ("IDLE".equals(phase)) return;
        if (current != null) {
            current.fail(reason);
            TestContext context = current;
            long cleanupStarted = System.nanoTime();
            context.result.cleanup = "COMPLETED";
            try {
                selection.get(scenarioIndex)
                    .cleanup(context);
            } catch (Exception | LinkageError ex) {
                context.result.cleanup = "FAILED";
                if (data != null) data.lastCleanup = "FAILED";
                context.callbackFailure("cleanup", ex);
                context.event("cleanup_failure", exception(ex));
            } finally {
                context.operationEnd("scenario.cleanup", cleanupStarted);
            }
            captureCounts(context);
            context.result.status = "FAIL";
            context.result.detail = context.detail;
            context.result.ticks = context.elapsed;
            closedPerformanceContext = context;
            phasePerformance = null;
        }
        if (data != null && "BUILDING".equals(phase)) data.built = false;
        complete("STOPPED", reason);
    }

    private static void releaseTickets() {
        for (ForgeChunkManager.Ticket ticket : TICKETS) ForgeChunkManager.releaseTicket(ticket);
        TICKETS.clear();
        releasingChunks = null;
    }

    /** Keep all four central chunks (controller and supply) until the wireless leaves are gone. */
    static int releasePriority(ChunkCoordIntPair chunk) {
        int x = chunk.chunkXPos, z = chunk.chunkZPos;
        if (x >= -1 && x <= 0 && z >= -1 && z <= 0) return Integer.MAX_VALUE;
        return -Math.max(Math.abs(x), Math.abs(z));
    }

    private static void releaseTick() {
        // If a player enters during cleanup, hand control back to normal player chunk loading.
        if (!world.playerEntities.isEmpty() || releaseIndex >= releasingChunks.size()) {
            report.event(null, report.elapsedTicks, "release_end", "chunks=" + releaseIndex);
            GTSimpleWirelessNetwork.LOG
                .info("GTSWN_NATIVE_STRESS_RELEASE_END run={} chunksRequested={}", report.runId, releaseIndex);
            completeNow(releasingStatus, releasingFailure);
            return;
        }
        if (releaseWait-- > 0) return;
        ChunkCoordIntPair chunk = releasingChunks.get(releaseIndex++);
        for (Iterator<ForgeChunkManager.Ticket> iterator = TICKETS.iterator(); iterator.hasNext();) {
            ForgeChunkManager.Ticket ticket = iterator.next();
            if (ticket.getChunkList()
                .contains(chunk)) ForgeChunkManager.unforceChunk(ticket, chunk);
            if (ticket.getChunkList()
                .isEmpty()) {
                ForgeChunkManager.releaseTicket(ticket);
                iterator.remove();
            }
        }
        // Use the normal provider queue: tile lifecycle callbacks and both native saves still run.
        world.theChunkProviderServer.unloadChunksIfNotNearSpawn(chunk.chunkXPos, chunk.chunkZPos);
        releaseWait = 1;
    }

    public static boolean built() {
        return journal().built;
    }

    /** A fixture may expand only inside the area already journaled by the active operation. */
    static int fixtureRadius(WorldServer candidate) {
        return candidate == world && ("BUILDING".equals(phase) || "RUNNING".equals(phase)) ? requestedRadius
            : TestWorldFootprint.DEFAULT_RADIUS;
    }

    private static String footprintStatus(TestWorldData state) {
        TestWorldFootprint authored = new TestWorldFootprint(TestWorldFootprint.savedRadius(state.authoredRadius));
        return " footprint=" + authored.describe()
            + ("BUILDING".equals(phase) && resetFootprint != null ? " scan=" + resetFootprint.describe()
                + " chunks="
                + loadIndex
                + "/"
                + resetFootprint.chunkCount()
                + " reset="
                + resetCursor
                + "/"
                + resetFootprint.blockCount() : "");
    }

    public static String status() {
        if (TestDimension.problem() != null) return "DISABLED: " + TestDimension.problem();
        TestWorldData state = journal();
        return phase + " dimension="
            + TestDimension.dimId()
            + " built="
            + state.built
            + " saved="
            + state.operation
            + " tickets="
            + TICKETS.size()
            + footprintStatus(state)
            + (current == null ? ""
                : " scenario=" + current.result.id
                    + " tick="
                    + current.tick()
                    + (current.result.workload == null ? "" : " workloadPhase=" + current.result.workload.phase))
            + " run="
            + state.lastRun
            + " outcome="
            + state.lastOutcome
            + writeStatus();
    }

    static Map<String, Object> snapshot() {
        acknowledgeFinalWrite();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("runId", report != null ? report.runId : data == null ? null : data.lastRun);
        facts.put("state", phase);
        facts.put("built", data != null && data.built);
        facts.put("dimension", TestDimension.dimId());
        facts.put("outcome", report != null ? report.status : data == null ? null : data.lastOutcome);
        facts.put("reportPath", reportPath.isEmpty() ? null : reportPath);
        facts.put(
            "reportState",
            finalWrite == null ? "NOT_TRACKED"
                : finalWrite.state()
                    .name());
        facts.put("reportError", finalWrite == null ? null : finalWrite.error());
        facts.put(
            "reportWritten",
            data != null && finalWrite != null
                && finalWrite.matches(boundReportTarget, data.lastRun)
                && !finalWrite.abandoned()
                && finalWrite.state() == TestWorldReport.WriteReceipt.State.WRITTEN);
        facts.put(
            "cleanupPending",
            current != null || !TICKETS.isEmpty()
                || "RELEASING".equals(phase)
                || TestUiKit.inUse()
                || data != null && "FAILED".equals(data.lastCleanup));
        facts.put("cleanupStatus", data == null ? "NOT_STARTED" : data.lastCleanup);
        facts.put("taskId", current == null ? null : current.result.id);
        facts.put("actor", current != null ? current.result.actor : data == null ? "not observed" : data.lastActor);
        facts.put(
            "actorLevel2",
            current != null ? current.result.actorLevel2 : data == null ? null : data.lastActorLevel2);
        facts.put(
            "actorLevel4",
            current != null ? current.result.actorLevel4 : data == null ? null : data.lastActorLevel4);
        facts.put(
            "transport",
            current != null ? current.result.transport : data == null ? "not observed" : data.lastTransport);
        facts.put("fixtureRetention", "preserved; reset/build required to remove authored fixtures");
        facts.put("artifact", TestLabIdentity.ARTIFACT);
        facts.put(
            "artifactIdentityScope",
            "currently loaded candidate; the original report binds its execution artifact separately");
        return facts;
    }

    public static String report() {
        TestWorldData state = journal();
        acknowledgeFinalWrite();
        String retry = "";
        if (data != null && finalWrite != null
            && finalWrite.matches(boundReportTarget, data.lastRun)
            && !finalWrite.abandoned()
            && finalWrite.state() == TestWorldReport.WriteReceipt.State.FAILED)
            retry = finalWrite.retry() ? " retry=queued asynchronously" : " retry=not queued";
        return "status=" + (report == null ? state.lastOutcome : report.status)
            + " phase="
            + phase
            + " report="
            + reportPath
            + footprintStatus(state)
            + writeStatus()
            + retry;
    }

    private static String writeStatus() {
        if (finalWrite == null) {
            if (data != null && "REPORT_FAILED".equals(data.operation))
                return " reportWrite=FAILED finalSnapshot=unavailableAfterRestart evidenceAbandoned="
                    + data.activeRun.isEmpty();
            return " reportWrite=" + (report == null ? "NOT_TRACKED" : "CHECKPOINT_ONLY");
        }
        return " reportWrite=" + finalWrite.state()
            + " reportRun="
            + finalWrite.runId
            + " writeAttempts="
            + finalWrite.attempts()
            + " finalSnapshot="
            + (finalWrite.hasSnapshot() ? "retained" : "unavailableAfterRestart")
            + (finalWrite.abandoned() ? " evidenceAbandoned=true" : "")
            + (!finalWrite.hasSnapshot() && !finalWrite.abandoned()
                && finalWrite.state() == TestWorldReport.WriteReceipt.State.FAILED
                    ? " unavailableEvidenceMayBeAbandonedWith=/gtswn test stop"
                    : "")
            + (finalWrite.error()
                .isEmpty() ? "" : " writeError=" + finalWrite.error());
    }

    /** Main-thread acknowledgement; an unsuccessful receipt never clears the save's active run. */
    private static void acknowledgeFinalWrite() {
        if (!matchesCurrentJournal()) return;
        if (finalWrite.state() == TestWorldReport.WriteReceipt.State.WRITTEN) {
            if (!(finalWrite.runId + ":WRITTEN").equals(publishedReceipt)) {
                TestLabEvents.publish(
                    finalWrite.runId,
                    "runner",
                    "report.written",
                    finalWrite.target.getAbsolutePath(),
                    finalWrite.outcome,
                    "released");
                publishedReceipt = finalWrite.runId + ":WRITTEN";
            }
            data.operation = "COMPLETE";
            data.activeRun = "";
            data.markDirty();
            rememberFinalWrite();
            if ("REPORTING".equals(phase)) phase = "IDLE";
        } else if (finalWrite.state() == TestWorldReport.WriteReceipt.State.FAILED
            && !"REPORT_FAILED".equals(data.operation)) {
                data.operation = "REPORT_FAILED";
                data.markDirty();
            }
    }

    public static void shutdown() {
        if (!DebugTestGate.isEnabled()) return;
        stop("server stopping", false);
        if ("RELEASING".equals(phase)) completeNow(releasingStatus, releasingFailure);
        releaseTickets();
        TestWorldProbes.stop();
        TestWorldReport.shutdownWriter();
        acknowledgeFinalWrite();
        data = null;
        SCENARIOS.clear();
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (!event.world.isRemote && event.world == world) stop("test world unloaded", false);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void tickStart(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || runPerformance == null
            || "IDLE".equals(phase)
            || "REPORTING".equals(phase)) return;
        tickStartNanos = System.nanoTime();
        tickStartCpuNanos = runPerformance.cpuTime();
        tickPerformance = phasePerformance;
        tickWorkloadContext = current;
        tickWorkloadPhase = current == null ? null : current.workloadPhase();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if ("REPORTING".equals(phase)) {
            acknowledgeFinalWrite();
            return;
        }
        if ("IDLE".equals(phase)) return;
        TestWorldReport measuredReport = report;
        TestWorldPerformance measuredRun = runPerformance;
        TestWorldPerformance measuredPhase = phasePerformance;
        TestContext measuredContext = current;
        TestWorldReport.WorkloadPhase measuredWorkloadPhase = current == null ? null : current.workloadPhase();
        long workStarted = System.nanoTime();
        long workCpuStarted = measuredRun == null ? -1 : measuredRun.cpuTime();
        if (measuredReport != null) measuredReport.elapsedTicks++;
        processingTick = true;
        try {
            if ("BUILDING".equals(phase)) buildTick();
            else if ("RUNNING".equals(phase)) scenarioTick();
            else if ("RELEASING".equals(phase)) releaseTick();
        } catch (Exception | LinkageError ex) {
            GTSimpleWirelessNetwork.LOG.error("[GTSWN-TEST] Operation failed", ex);
            if (current != null) {
                current.callbackFailure("runner", ex);
                stop(exception(ex), false);
            } else complete("FAIL", exception(ex));
        } finally {
            long finished = System.nanoTime();
            long cpuFinished = measuredRun == null ? -1 : measuredRun.cpuTime();
            long work = finished - workStarted;
            long window = tickStartNanos == 0 ? -1 : finished - tickStartNanos;
            long cpuWindow = tickStartNanos == 0 ? -1 : TestWorldPerformance.delta(tickStartCpuNanos, cpuFinished);
            long workCpu = TestWorldPerformance.delta(workCpuStarted, cpuFinished);
            if (measuredRun != null) measuredRun.tick(window, cpuWindow, work, workCpu);
            if (measuredPhase != null) measuredPhase.tick(
                measuredPhase == tickPerformance ? window : -1,
                measuredPhase == tickPerformance ? cpuWindow : -1,
                work,
                workCpu);
            if (measuredContext != null) measuredContext.recordWorkloadFrame(
                measuredWorkloadPhase,
                measuredContext == tickWorkloadContext && measuredWorkloadPhase == tickWorkloadPhase ? window : -1,
                measuredContext == tickWorkloadContext && measuredWorkloadPhase == tickWorkloadPhase ? cpuWindow : -1,
                work,
                workCpu);
            if (measuredReport != null) {
                measuredReport.managerWorkNanos += work;
                measuredReport.managerMaxWorkNanos = Math.max(measuredReport.managerMaxWorkNanos, work);
                if (tickStartNanos != 0) {
                    measuredReport.observedTickWindows++;
                    measuredReport.totalObservedTickWindowNanos += window;
                    measuredReport.maxObservedTickWindowNanos = Math
                        .max(measuredReport.maxObservedTickWindowNanos, window);
                }
                finishClosedPerformance(measuredReport);
            }
            tickStartNanos = 0;
            tickPerformance = null;
            tickWorkloadContext = null;
            tickWorkloadPhase = null;
            processingTick = false;
            if (deferredFinalReport != null) {
                finishPerformance(deferredFinalReport);
                finalWrite = deferredFinalReport.writeFinal(deferredFinalFile);
                rememberFinalWrite();
                deferredFinalReport = null;
                deferredFinalFile = null;
            }
        }
    }

    private static void finishClosedPerformance(TestWorldReport target) {
        if (closedResetPerformance != null) {
            TestWorldPerformance.Snapshot reset = closedResetPerformance.finish();
            target.resetPerformance.add(reset);
            if (("build".equals(target.selection) || "reset".equals(target.selection)) && !target.results.isEmpty())
                target.results.get(0).performance = reset;
            closedResetPerformance = null;
        }
        if (closedPerformanceContext != null) {
            TestContext context = closedPerformanceContext;
            if (context.performance() != null) context.result.performance = context.performance()
                .finish();
            context.finishWorkloadPerformance();
            closedPerformanceContext = null;
        }
    }

    private static void finishPerformance(TestWorldReport target) {
        finishClosedPerformance(target);
        if (runPerformance != null) target.performance = runPerformance.finish();
        runPerformance = null;
        phasePerformance = null;
        tickPerformance = null;
        tickWorkloadContext = null;
        tickWorkloadPhase = null;
    }
}
