package com.miaokatze.gtswn.common.testworld;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Explicit-command-only controller. All world access stays on the server thread. */
public final class TestWorldManager {

    public static final int MIN = -32;
    public static final int MAX = 31;
    public static final int LAYOUT_SCHEMA = 1;
    public static final long RESET_SCAN_BUDGET_NANOS = 3_000_000L;
    public static final int RESET_SCAN_MAX_CHECKS = 65536;
    private static final int RESET_BLOCKS = 64 * 64 * 256;
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
    private static long operationTicks;
    private static long tickStartNanos;
    private static boolean processingTick;
    private static TestWorldReport deferredFinalReport;
    private static File deferredFinalFile;
    private static TestWorldReport.WriteReceipt finalWrite;
    private static final Map<File, TestWorldReport.WriteReceipt> PENDING_FINAL_WRITES = new LinkedHashMap<>();
    private static File boundReportTarget;
    private static String phase = "IDLE";
    private static String reportPath = "";
    private static boolean registered;

    private TestWorldManager() {}

    public static void init() {
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
        SCENARIOS.put(scenario.id(), scenario);
    }

    public static List<String> ids() {
        return new ArrayList<>(SCENARIOS.keySet());
    }

    public static String description(String id) {
        return SCENARIOS.get(id)
            .description();
    }

    public static void serverStarting() {
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
        requireIdle();
        journal();
        world = TestDimension.ensureWorld();
        selection = null;
        data.activeRun = "";
        tickStartNanos = 0;
        beginReset();
    }

    public static String run(String requested) {
        requireIdle();
        List<TestScenario> chosen = new ArrayList<>();
        if ("all".equals(requested)) chosen.addAll(SCENARIOS.values());
        else if (SCENARIOS.containsKey(requested)) chosen.add(SCENARIOS.get(requested));
        else throw new IllegalArgumentException("Unknown scenario: " + requested);
        if (chosen.isEmpty()) throw new IllegalStateException("No test scenarios registered");
        journal();
        world = TestDimension.ensureWorld();
        selection = chosen;
        finalWrite = null;
        scenarioIndex = 0;
        tickStartNanos = 0;
        report = new TestWorldReport(
            UUID.randomUUID()
                .toString(),
            TestDimension.dimId(),
            requested);
        report.actors.addAll(TestActors.describe());
        for (TestScenario scenario : chosen) report.results.add(new TestWorldReport.Result(scenario.id()));
        File save = MinecraftServer.getServer()
            .worldServerForDimension(0)
            .getSaveHandler()
            .getWorldDirectory();
        reportFile = new File(new File(save, "gtswn-test-reports"), report.runId + ".json");
        boundReportTarget = TestWorldReport.normalizedTarget(reportFile);
        reportPath = reportFile.getAbsolutePath();
        data.lastRun = report.runId;
        data.activeRun = report.runId;
        data.lastOutcome = "BUILDING";
        data.lastReport = reportPath;
        report.event(null, 0, "run", "selection=" + requested + "; scenarios=" + chosen.size());
        beginReset();
        return report.runId;
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
            report.event(null, report.elapsedTicks, "reset", "bounded reset x/z=-32..31, y=0..255");
            report.write(reportFile, false);
        }
    }

    private static void leaseChunk(int cx, int cz) {
        ChunkCoordIntPair chunk = new ChunkCoordIntPair(cx, cz);
        for (ForgeChunkManager.Ticket acquired : TICKETS) if (acquired.getChunkList()
            .contains(chunk)) return;
        ForgeChunkManager.Ticket ticket = TICKETS.isEmpty() ? null : TICKETS.get(TICKETS.size() - 1);
        if (ticket == null || ticket.getChunkList()
            .size() >= ticket.getChunkListDepth()) {
            ticket = ForgeChunkManager
                .requestTicket(GTSimpleWirelessNetwork.instance, world, ForgeChunkManager.Type.NORMAL);
            if (ticket == null) throw new IllegalStateException("Forge chunk ticket refused; test stopped");
            TICKETS.add(ticket);
            ticket.getModData()
                .setBoolean("GTSWNTestRun", true);
            if (ticket.getChunkListDepth() <= 0) throw new IllegalStateException("Forge chunk ticket depth is zero");
        }
        ForgeChunkManager.forceChunk(ticket, chunk);
    }

    private static void buildTick() {
        if (++operationTicks > 6000) throw new IllegalStateException("Build exceeded 6000 real server ticks");
        if (loadIndex < 16) {
            int cx = -2 + loadIndex / 4;
            int cz = -2 + loadIndex % 4;
            leaseChunk(cx, cz);
            world.theChunkProviderServer.loadChunk(cx, cz);
            loadIndex++;
            return; // At most one synchronous chunk load per server tick.
        }
        long deadline = System.nanoTime() + RESET_SCAN_BUDGET_NANOS;
        int visited = 0;
        while (resetCursor < RESET_BLOCKS && visited < RESET_SCAN_MAX_CHECKS) {
            int y = resetCursor & 255;
            int column = resetCursor >>> 8;
            int x = MIN + column / 64;
            int z = MIN + column % 64;
            net.minecraft.block.Block target = y == 0 ? Blocks.bedrock : y <= 63 ? Blocks.stone : Blocks.air;
            if (world.getBlock(x, y, z) != target || world.getBlockMetadata(x, y, z) != 0)
                world.setBlock(x, y, z, target, 0, 3);
            resetCursor++;
            visited++;
            if ((visited & 63) == 0 && System.nanoTime() >= deadline) return;
        }
        if (resetCursor < RESET_BLOCKS) return;
        // Only entities inside the authored reset volume belong to this operation. Players are preserved.
        for (Object object : new ArrayList<>(world.loadedEntityList)) {
            Entity entity = (Entity) object;
            if (!(entity instanceof EntityPlayer) && entity.posX >= MIN
                && entity.posX < MAX + 1
                && entity.posZ >= MIN
                && entity.posZ < MAX + 1) entity.setDead();
        }
        TestWorldFixtures.build(world);
        data.layoutSchema = LAYOUT_SCHEMA;
        data.built = true;
        data.operation = "COMPLETE";
        data.markDirty();
        if (selection == null) {
            releaseTickets();
            phase = "IDLE";
            world = null;
            GTSimpleWirelessNetwork.LOG.info("[GTSWN-TEST] BUILD DONE dimension={}", TestDimension.dimId());
        } else startScenario();
    }

    private static void startScenario() {
        TestScenario scenario = selection.get(scenarioIndex);
        TestWorldReport.Result result = report.results.get(scenarioIndex);
        result.status = "RUNNING";
        result.detail = "running";
        current = new TestContext(world, report.runId, report, result);
        phase = "RUNNING";
        report.status = phase;
        data.operation = phase;
        data.lastOutcome = phase;
        data.markDirty();
        TestWorldProbes.start((type, detail) -> {
            TestContext context = current;
            if (context != null) context.event(type, detail);
        }, TestDimension.dimId());
        current.event("start", scenario.id());
        report.write(reportFile, false);
        long started = System.nanoTime();
        try {
            scenario.start(current);
        } catch (Exception | LinkageError ex) {
            current.callbackFailure("start", ex);
        }
        recordCallback(System.nanoTime() - started);
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
        }
        if (current.outcome != null) finishScenario();
    }

    private static void finishScenario() {
        TestContext context = current;
        try {
            selection.get(scenarioIndex)
                .cleanup(context);
        } catch (Exception | LinkageError ex) {
            context.callbackFailure("cleanup", ex);
            context.event("cleanup_failure", exception(ex));
        } finally {
            captureCounts(context);
            TestWorldProbes.stop();
        }
        context.result.status = context.outcome;
        context.result.detail = context.detail;
        context.result.ticks = context.elapsed;
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
        releaseTickets();
        if (report != null) {
            report.status = status;
            report.failure = failure;
            report.endedAtMs = System.currentTimeMillis();
            report.event(null, report.elapsedTicks, "complete", status + (failure == null ? "" : ": " + failure));
            if (processingTick) {
                deferredFinalReport = report;
                deferredFinalFile = reportFile;
            } else {
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
            try {
                selection.get(scenarioIndex)
                    .cleanup(context);
            } catch (Exception | LinkageError ex) {
                context.callbackFailure("cleanup", ex);
                context.event("cleanup_failure", exception(ex));
            }
            captureCounts(context);
            context.result.status = "FAIL";
            context.result.detail = context.detail;
            context.result.ticks = context.elapsed;
        }
        if (data != null && "BUILDING".equals(phase)) data.built = false;
        complete("STOPPED", reason);
    }

    private static void releaseTickets() {
        for (ForgeChunkManager.Ticket ticket : TICKETS) ForgeChunkManager.releaseTicket(ticket);
        TICKETS.clear();
    }

    public static boolean built() {
        return journal().built;
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
            + ("BUILDING".equals(phase) ? " chunks=" + loadIndex + "/16 reset=" + resetCursor + "/" + RESET_BLOCKS : "")
            + (current == null ? "" : " scenario=" + current.result.id + " tick=" + current.tick())
            + " run="
            + state.lastRun
            + " outcome="
            + state.lastOutcome
            + writeStatus();
    }

    public static String report() {
        TestWorldData state = journal();
        acknowledgeFinalWrite();
        String retry = "";
        if (matchesCurrentJournal() && !finalWrite.abandoned()
            && finalWrite.state() == TestWorldReport.WriteReceipt.State.FAILED)
            retry = finalWrite.retry() ? " retry=queued asynchronously" : " retry=not queued";
        return "status=" + (report == null ? state.lastOutcome : report.status)
            + " phase="
            + phase
            + " report="
            + reportPath
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
        stop("server stopping", false);
        releaseTickets();
        TestWorldProbes.stop();
        TestWorldReport.shutdownWriter();
        acknowledgeFinalWrite();
        data = null;
        SCENARIOS.clear();
    }

    @SubscribeEvent
    public void spawn(LivingSpawnEvent.CheckSpawn event) {
        if (TestDimension.isTest(event.world)) event.setResult(Event.Result.DENY);
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (!event.world.isRemote && event.world == world) stop("test world unloaded", false);
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if ("REPORTING".equals(phase)) {
            if (event.phase == TickEvent.Phase.END) acknowledgeFinalWrite();
            return;
        }
        if ("IDLE".equals(phase)) return;
        if (event.phase == TickEvent.Phase.START) {
            tickStartNanos = System.nanoTime();
            return;
        }
        TestWorldReport measuredReport = report;
        long workStarted = System.nanoTime();
        if (measuredReport != null) measuredReport.elapsedTicks++;
        processingTick = true;
        try {
            if ("BUILDING".equals(phase)) buildTick();
            else if ("RUNNING".equals(phase)) scenarioTick();
        } catch (Exception | LinkageError ex) {
            GTSimpleWirelessNetwork.LOG.error("[GTSWN-TEST] Operation failed", ex);
            if (current != null) {
                current.callbackFailure("runner", ex);
                stop(exception(ex), false);
            } else complete("FAIL", exception(ex));
        } finally {
            long finished = System.nanoTime();
            if (measuredReport != null) {
                long work = finished - workStarted;
                measuredReport.managerWorkNanos += work;
                measuredReport.managerMaxWorkNanos = Math.max(measuredReport.managerMaxWorkNanos, work);
                if (tickStartNanos != 0) {
                    long window = finished - tickStartNanos;
                    measuredReport.observedTickWindows++;
                    measuredReport.totalObservedTickWindowNanos += window;
                    measuredReport.maxObservedTickWindowNanos = Math
                        .max(measuredReport.maxObservedTickWindowNanos, window);
                }
            }
            processingTick = false;
            if (deferredFinalReport != null) {
                finalWrite = deferredFinalReport.writeFinal(deferredFinalFile);
                rememberFinalWrite();
                deferredFinalReport = null;
                deferredFinalFile = null;
            }
        }
    }
}
