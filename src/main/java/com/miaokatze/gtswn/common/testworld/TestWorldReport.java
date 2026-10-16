package com.miaokatze.gtswn.common.testworld;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtswn.Tags;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

/** Bounded event journal and append-only run identities. The writer receives detached snapshots only. */
public final class TestWorldReport {

    public static final int EVENT_LIMIT = 4096;
    public static final int RESULT_EVENT_LIMIT = 256;
    private static ThreadPoolExecutor writer;

    public final int schemaVersion = 1;
    public final String runId;
    public final String modVersion = Tags.VERSION;
    public final int dimension;
    public final String selection;
    public final String coverage = "server automation; client rendering/input, manual UI and full server restart require separate verification";
    public long startedAtMs = System.currentTimeMillis();
    public long endedAtMs;
    public long elapsedTicks;
    public String status = "BUILDING";
    public String failure;
    public long droppedEvents;
    public long callbackNanos;
    public long maxCallbackNanos;
    public final String timingScope = "Observed tick window: HIGHEST START to LOWEST END handler work completion; excludes listeners outside those boundaries and sampling/finalization overhead, and is not complete server MSPT. Manager work: this END handler work including reset, scenario, cleanup and ticket release; neither is mod-exclusive cost.";
    public final long resetScanBudgetNanos = TestWorldManager.RESET_SCAN_BUDGET_NANOS;
    public final int resetScanMaxChecksPerTick = TestWorldManager.RESET_SCAN_MAX_CHECKS;
    public long maxObservedTickWindowNanos;
    public long totalObservedTickWindowNanos;
    public long observedTickWindows;
    public long managerWorkNanos;
    public long managerMaxWorkNanos;
    public TestWorldPerformance.Snapshot performance;
    public final List<TestWorldPerformance.Snapshot> resetPerformance = new ArrayList<>();
    public final List<Result> results = new ArrayList<>();
    public final List<String> actors = new ArrayList<>();
    public final Deque<Trace> events = new ArrayDeque<>();

    TestWorldReport(String id, int dimension, String selection) {
        this.runId = id;
        this.dimension = dimension;
        this.selection = selection;
    }

    public static final class Result {

        public final String id;
        public String status = "NOT_RUN";
        public String detail = "not started";
        public long ticks;
        public long callbackNanos;
        public long maxCallbackNanos;
        public long droppedEvents;
        public TestWorldPerformance.Snapshot performance;
        public Workload workload;
        public final Map<String, Long> observedBusinessEvents = new LinkedHashMap<>();
        public final List<Trace> events = new ArrayList<>();

        Result(String id) {
            this.id = id;
        }
    }

    /** Structured native fixture facts survive the bounded event journal. No live world objects enter reports. */
    public static final class Workload {

        public String kind = "native-ae-storage";
        public String phase = "not_started";
        public String retention = "World blocks and bound terminal remain after completion or stop. The runner releases its chunk tickets; subsequent ticking needs a player or independent chunk loading. build/reset removes the fixture.";
        public final Map<String, Long> expected = new LinkedHashMap<>();
        public final Map<String, Long> observed = new LinkedHashMap<>();
        public final List<String> itemTypes = new ArrayList<>();
        public final List<Cell> cells = new ArrayList<>();
        public final List<WorkloadPhase> phases = new ArrayList<>();

        Workload copy() {
            Workload target = new Workload();
            target.kind = kind;
            target.phase = phase;
            target.retention = retention;
            target.expected.putAll(expected);
            target.observed.putAll(observed);
            target.itemTypes.addAll(itemTypes);
            for (Cell cell : cells) target.cells.add(new Cell(cell));
            for (WorkloadPhase phase : phases) target.phases.add(new WorkloadPhase(phase));
            return target;
        }
    }

    public static final class Cell {

        public final int x, y, z, slot, itemType;
        public final long bytes, capacity;
        public long initializedCount, steadyCount;
        public long initializedTypes;
        public boolean initiallyFull, rejectedExtra, steadyObserved;

        Cell(int x, int y, int z, int slot, int itemType, long bytes, long capacity) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.slot = slot;
            this.itemType = itemType;
            this.bytes = bytes;
            this.capacity = capacity;
        }

        private Cell(Cell source) {
            this(source.x, source.y, source.z, source.slot, source.itemType, source.bytes, source.capacity);
            initializedCount = source.initializedCount;
            steadyCount = source.steadyCount;
            steadyObserved = source.steadyObserved;
            initializedTypes = source.initializedTypes;
            initiallyFull = source.initiallyFull;
            rejectedExtra = source.rejectedExtra;
        }
    }

    public static final class WorkloadPhase {

        public final String name;
        public final long startedTick;
        public long endedTick;
        public TestWorldPerformance.Snapshot performance;
        transient TestWorldPerformance sampler;

        WorkloadPhase(String name, long startedTick) {
            this.name = name;
            this.startedTick = startedTick;
        }

        private WorkloadPhase(WorkloadPhase source) {
            this(source.name, source.startedTick);
            endedTick = source.endedTick;
            performance = source.performance;
        }
    }

    public static final class Trace {

        public final long tick;
        public final String scenario;
        public final String type;
        public final String detail;

        Trace(long tick, String scenario, String type, String detail) {
            this.tick = tick;
            this.scenario = scenario;
            this.type = clip(type, 128);
            this.detail = clip(detail, 2048);
        }
    }

    synchronized void event(Result result, long tick, String type, String detail) {
        Trace entry = new Trace(tick, result == null ? "runner" : result.id, type, detail);
        if (events.size() == EVENT_LIMIT) {
            events.removeFirst();
            droppedEvents++;
        }
        events.addLast(entry);
        if (result != null) {
            if (result.events.size() < RESULT_EVENT_LIMIT) result.events.add(entry);
            else result.droppedEvents++;
        }
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + " [truncated]";
    }

    private synchronized TestWorldReport snapshot() {
        TestWorldReport copy = new TestWorldReport(runId, dimension, selection);
        copy.startedAtMs = startedAtMs;
        copy.endedAtMs = endedAtMs;
        copy.elapsedTicks = elapsedTicks;
        copy.status = status;
        copy.failure = failure;
        copy.droppedEvents = droppedEvents;
        copy.callbackNanos = callbackNanos;
        copy.maxCallbackNanos = maxCallbackNanos;
        copy.maxObservedTickWindowNanos = maxObservedTickWindowNanos;
        copy.totalObservedTickWindowNanos = totalObservedTickWindowNanos;
        copy.observedTickWindows = observedTickWindows;
        copy.managerWorkNanos = managerWorkNanos;
        copy.managerMaxWorkNanos = managerMaxWorkNanos;
        copy.performance = performance;
        copy.resetPerformance.addAll(resetPerformance);
        for (Result result : results) {
            Result target = new Result(result.id);
            target.status = result.status;
            target.detail = result.detail;
            target.ticks = result.ticks;
            target.callbackNanos = result.callbackNanos;
            target.maxCallbackNanos = result.maxCallbackNanos;
            target.droppedEvents = result.droppedEvents;
            target.performance = result.performance;
            target.workload = result.workload == null ? null : result.workload.copy();
            target.events.addAll(result.events);
            target.observedBusinessEvents.putAll(result.observedBusinessEvents);
            copy.results.add(target);
        }
        copy.events.addAll(events);
        copy.actors.addAll(actors);
        return copy;
    }

    static synchronized void startWriter() {
        if (!DebugTestGate.isEnabled()) return;
        // A timed-out shutdown may still be writing a checkpoint. Never create a competing writer.
        if (writer != null && !writer.isTerminated()) return;
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread thread = new Thread(task, "GTSWN-test-report");
            thread.setDaemon(true);
            return thread;
        });
    }

    boolean write(File target, boolean done) {
        return enqueue(snapshot(), target, done, null);
    }

    /** A detached final snapshot remains reachable after queue rejection or an I/O failure. */
    WriteReceipt writeFinal(File target) {
        WriteReceipt receipt = new WriteReceipt(snapshot(), target, runId, status);
        receipt.retry();
        return receipt;
    }

    /** On a fresh process, verify existing final evidence without inventing the lost in-memory snapshot. */
    static WriteReceipt recoverFinal(File target, String runId, String outcome) {
        WriteReceipt receipt = new WriteReceipt(null, target, runId, outcome);
        receipt.retry();
        return receipt;
    }

    /** Lexical absolute identity without filesystem I/O on the server thread. */
    static File normalizedTarget(File target) {
        return target.toPath()
            .toAbsolutePath()
            .normalize()
            .toFile();
    }

    static final class WriteReceipt {

        enum State {
            QUEUED,
            WRITING,
            WRITTEN,
            FAILED
        }

        private final TestWorldReport snapshot;
        final File target;
        final String runId;
        final String outcome;
        private volatile State state = State.FAILED;
        private volatile String error = "";
        private volatile int attempts;
        private volatile boolean abandoned;

        private WriteReceipt(TestWorldReport snapshot, File target, String runId, String outcome) {
            this.snapshot = snapshot;
            this.target = normalizedTarget(target);
            this.runId = runId;
            this.outcome = outcome;
        }

        State state() {
            return state;
        }

        String error() {
            return error;
        }

        int attempts() {
            return attempts;
        }

        boolean abandoned() {
            return abandoned;
        }

        boolean hasSnapshot() {
            return snapshot != null;
        }

        boolean matches(File expectedTarget, String expectedRun) {
            return expectedTarget != null && runId.equals(expectedRun)
                && target.equals(normalizedTarget(expectedTarget));
        }

        synchronized boolean abandonRecovery() {
            if (snapshot != null || state != State.FAILED) return false;
            abandoned = true;
            return true;
        }

        synchronized boolean retry() {
            if (!DebugTestGate.isEnabled()) {
                error = DebugTestGate.DISABLED_MESSAGE;
                state = State.FAILED;
                return false;
            }
            if (abandoned || state != State.FAILED) return false;
            state = State.QUEUED;
            attempts++;
            startWriter();
            if (snapshot != null) return enqueue(snapshot, target, true, this);
            try {
                writer.execute(() -> {
                    state = State.WRITING;
                    try {
                        JsonObject json = new JsonParser()
                            .parse(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                        if (!terminal(outcome) || !runId.equals(
                            json.get("runId")
                                .getAsString())
                            || !outcome.equals(
                                json.get("status")
                                    .getAsString())
                            || json.get("schemaVersion")
                                .getAsInt() != 1
                            || json.get("startedAtMs")
                                .getAsLong() <= 0
                            || json.get("endedAtMs")
                                .getAsLong() <= 0
                            || json.getAsJsonArray("results")
                                .size() == 0)
                            throw new IOException(
                                "No matching terminal report; the final in-memory snapshot was lost on restart");
                        validateResults(json, outcome);
                        succeeded();
                    } catch (IOException | RuntimeException ex) {
                        failed(ex);
                    }
                });
                return true;
            } catch (RuntimeException ex) {
                failed(ex);
                return false;
            }
        }

        private void succeeded() {
            error = "";
            state = State.WRITTEN;
        }

        private static boolean terminal(String outcome) {
            return "PASS".equals(outcome) || "FAIL".equals(outcome)
                || "SKIP".equals(outcome)
                || "STOPPED".equals(outcome);
        }

        private static void validateResults(JsonObject json, String outcome) throws IOException {
            int passed = 0, failed = 0, skipped = 0, notRun = 0;
            boolean pendingSuffix = false;
            for (JsonElement entry : json.getAsJsonArray("results")) {
                JsonObject result = entry.getAsJsonObject();
                String status = result.get("status")
                    .getAsString();
                if (result.get("id")
                    .getAsString()
                    .isEmpty()) throw new IOException("Missing scenario ID in recovered report");
                if ("NOT_RUN".equals(status)) {
                    pendingSuffix = true;
                    notRun++;
                } else {
                    if (pendingSuffix) throw new IOException("Executed scenario after NOT_RUN in recovered report");
                    if ("PASS".equals(status)) passed++;
                    else if ("FAIL".equals(status)) failed++;
                    else if ("SKIP".equals(status)) skipped++;
                    else throw new IOException("Invalid terminal scenario result in recovered report: " + status);
                }
            }
            boolean runnerFailure = json.has("failure") && !json.get("failure")
                .isJsonNull()
                && !json.get("failure")
                    .getAsString()
                    .trim()
                    .isEmpty();
            boolean valid;
            switch (outcome) {
                case "PASS":
                    valid = !runnerFailure && failed == 0 && notRun == 0 && passed > 0;
                    break;
                case "SKIP":
                    valid = !runnerFailure && passed == 0 && failed == 0 && notRun == 0 && skipped > 0;
                    break;
                case "FAIL":
                    valid = (failed > 0 || runnerFailure) && (notRun == 0 || runnerFailure);
                    break;
                case "STOPPED":
                    valid = runnerFailure && (failed > 0 || notRun > 0);
                    break;
                default:
                    valid = false;
            }
            if (!valid)
                throw new IOException("Overall outcome contradicts scenario results in recovered report: " + outcome);
        }

        private void failed(Throwable failure) {
            error = failure.getClass()
                .getName() + ": "
                + failure.getMessage();
            state = State.FAILED;
            GTSimpleWirelessNetwork.LOG.error("[GTSWN-TEST] REPORT FAILED run=" + runId + " path=" + target, failure);
        }
    }

    private static boolean enqueue(TestWorldReport copy, File target, boolean done, WriteReceipt receipt) {
        if (!DebugTestGate.isEnabled()) return false;
        try {
            writer.execute(() -> {
                if (receipt != null) receipt.state = WriteReceipt.State.WRITING;
                try {
                    Files.createDirectories(
                        target.toPath()
                            .getParent());
                    File temp = new File(target.getParentFile(), target.getName() + ".tmp");
                    Files.write(
                        temp.toPath(),
                        new GsonBuilder().serializeNulls()
                            .setPrettyPrinting()
                            .create()
                            .toJson(copy)
                            .getBytes(StandardCharsets.UTF_8));
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    if (receipt != null) receipt.succeeded();
                    if (done) GTSimpleWirelessNetwork.LOG.info(
                        "[GTSWN-TEST] DONE run={} status={} report={}",
                        copy.runId,
                        copy.status,
                        target.getAbsolutePath());
                } catch (IOException | RuntimeException ex) {
                    if (receipt != null) receipt.failed(ex);
                    else GTSimpleWirelessNetwork.LOG
                        .error("[GTSWN-TEST] REPORT FAILED run=" + copy.runId + " path=" + target, ex);
                }
            });
            return true;
        } catch (RuntimeException ex) {
            if (receipt != null) receipt.failed(ex);
            else GTSimpleWirelessNetwork.LOG.error("[GTSWN-TEST] Report queue unavailable for " + copy.runId, ex);
            return false;
        }
    }

    static void markInterrupted(File target, String runId, int dimension) {
        if (!DebugTestGate.isEnabled()) return;
        try {
            writer.execute(() -> {
                try {
                    JsonObject json;
                    if (target.isFile()) {
                        json = new JsonParser()
                            .parse(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                        String state = json.has("status") ? json.get("status")
                            .getAsString() : "RUNNING";
                        if (!("BUILDING".equals(state) || "RUNNING".equals(state))) return;
                    } else {
                        json = new JsonParser().parse(
                            new GsonBuilder().create()
                                .toJson(new TestWorldReport(runId, dimension, "recovered after restart")))
                            .getAsJsonObject();
                    }
                    json.addProperty("status", "INTERRUPTED");
                    json.addProperty("failure", "Server stopped before completion; no automatic test resumption");
                    json.addProperty("endedAtMs", System.currentTimeMillis());
                    if (json.has("results")) {
                        JsonArray results = json.getAsJsonArray("results");
                        for (JsonElement entry : results) {
                            JsonObject result = entry.getAsJsonObject();
                            if ("RUNNING".equals(
                                result.get("status")
                                    .getAsString())) {
                                result.addProperty("status", "FAIL");
                                result.addProperty("detail", "Interrupted by server shutdown");
                            }
                        }
                    }
                    Files.createDirectories(
                        target.toPath()
                            .getParent());
                    File temp = new File(target.getParentFile(), target.getName() + ".tmp");
                    Files.write(
                        temp.toPath(),
                        new GsonBuilder().setPrettyPrinting()
                            .create()
                            .toJson(json)
                            .getBytes(StandardCharsets.UTF_8));
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    GTSimpleWirelessNetwork.LOG.warn("[GTSWN-TEST] INTERRUPTED run={} report={}", runId, target);
                } catch (IOException | RuntimeException ex) {
                    GTSimpleWirelessNetwork.LOG.error("[GTSWN-TEST] Could not finalize interrupted run " + runId, ex);
                }
            });
        } catch (RuntimeException ex) {
            GTSimpleWirelessNetwork.LOG.error("[GTSWN-TEST] Could not queue interrupted report " + runId, ex);
        }
    }

    static synchronized void shutdownWriter() {
        if (writer == null) return;
        writer.shutdown();
        // Only called from FMLServerStopping/Stopped, never from the normal tick/stop command.
        try {
            if (!writer.awaitTermination(3, TimeUnit.SECONDS))
                GTSimpleWirelessNetwork.LOG.error("[GTSWN-TEST] Report flush exceeded 3s during server shutdown");
        } catch (InterruptedException ex) {
            Thread.currentThread()
                .interrupt();
        }
    }
}
