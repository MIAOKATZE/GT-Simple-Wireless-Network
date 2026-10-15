package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TestWorldReportingLifecycleTest {

    @Rule
    public final TemporaryFolder directory = new TemporaryFolder();
    private final Map<Field, Object> original = new HashMap<>();
    private Map<String, TestScenario> originalScenarios;
    private Map<File, TestWorldReport.WriteReceipt> originalPending;
    private TestWorldData journal;

    private Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void replace(Class<?> owner, String name, Object value) throws Exception {
        Field field = field(owner, name);
        if (!original.containsKey(field)) original.put(field, field.get(null));
        field.set(null, value);
    }

    @Before
    @SuppressWarnings("unchecked")
    public void saveStaticState() throws Exception {
        originalScenarios = new LinkedHashMap<>(
            (Map<String, TestScenario>) field(TestWorldManager.class, "SCENARIOS").get(null));
        Map<File, TestWorldReport.WriteReceipt> pending = (Map<File, TestWorldReport.WriteReceipt>) field(
            TestWorldManager.class,
            "PENDING_FINAL_WRITES").get(null);
        originalPending = new LinkedHashMap<>(pending);
        pending.clear();
        replace(TestDimension.class, "problem", null);
        replace(TestWorldManager.class, "report", null);
        replace(TestWorldManager.class, "finalWrite", null);
        replace(TestWorldManager.class, "boundReportTarget", null);
        replace(TestWorldManager.class, "phase", "IDLE");
        replace(TestWorldManager.class, "data", null);
        replace(TestWorldManager.class, "reportPath", "");
    }

    @After
    @SuppressWarnings("unchecked")
    public void restoreStaticState() throws Exception {
        TestWorldReport.shutdownWriter();
        for (Map.Entry<Field, Object> entry : original.entrySet()) entry.getKey()
            .set(null, entry.getValue());
        Map<String, TestScenario> scenarios = (Map<String, TestScenario>) field(TestWorldManager.class, "SCENARIOS")
            .get(null);
        scenarios.clear();
        scenarios.putAll(originalScenarios);
        Map<File, TestWorldReport.WriteReceipt> pending = (Map<File, TestWorldReport.WriteReceipt>) field(
            TestWorldManager.class,
            "PENDING_FINAL_WRITES").get(null);
        pending.clear();
        pending.putAll(originalPending);
    }

    private TestWorldReport completed() {
        return completed("completed-run");
    }

    private TestWorldReport completed(String runId) {
        TestWorldReport report = new TestWorldReport(runId, 19871, "one");
        report.status = "PASS";
        report.endedAtMs = System.currentTimeMillis();
        TestWorldReport.Result result = new TestWorldReport.Result("one");
        result.status = "PASS";
        report.results.add(result);
        return report;
    }

    private void reporting(TestWorldReport.WriteReceipt receipt) throws Exception {
        journal = new TestWorldData("report-journal");
        journal.operation = "REPORTING";
        journal.activeRun = receipt.runId;
        journal.lastRun = receipt.runId;
        journal.lastOutcome = receipt.outcome;
        journal.lastReport = receipt.target.getAbsolutePath();
        replace(TestWorldManager.class, "data", journal);
        replace(TestWorldManager.class, "phase", "REPORTING");
        replace(TestWorldManager.class, "finalWrite", receipt);
        replace(TestWorldManager.class, "boundReportTarget", receipt.target);
        replace(TestWorldManager.class, "reportPath", journal.lastReport);
    }

    private TestWorldReport.WriteReceipt failedLiveReceipt(File target) {
        TestWorldReport.startWriter();
        TestWorldReport.WriteReceipt receipt = completed().writeFinal(target);
        TestWorldReport.shutdownWriter();
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, receipt.state());
        return receipt;
    }

    private TestWorldReport.WriteReceipt failedRecovery() {
        TestWorldReport.startWriter();
        TestWorldReport.WriteReceipt receipt = TestWorldReport
            .recoverFinal(new File(directory.getRoot(), "missing.json"), "completed-run", "PASS");
        TestWorldReport.shutdownWriter();
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, receipt.state());
        return receipt;
    }

    private void bind(TestWorldData state, File saveDirectory) throws Exception {
        journal = state;
        replace(TestWorldManager.class, "data", state);
        Method bind = TestWorldManager.class.getDeclaredMethod("bindReportJournal", File.class);
        bind.setAccessible(true);
        bind.invoke(null, saveDirectory);
    }

    private void acknowledge() throws Exception {
        Method acknowledge = TestWorldManager.class.getDeclaredMethod("acknowledgeFinalWrite");
        acknowledge.setAccessible(true);
        acknowledge.invoke(null);
    }

    private File target(File saveDirectory, String runId) {
        return new File(new File(saveDirectory, "gtswn-test-reports"), runId + ".json");
    }

    @Test
    public void failedSnapshotFromADoesNotBlockIdleBAndIsRetainedWhenReturningToA() throws Exception {
        String runId = "11111111-1111-1111-1111-111111111111";
        File saveA = directory.newFolder("save-a");
        File saveB = directory.newFolder("save-b");
        File blocked = new File(saveA, "gtswn-test-reports");
        Files.write(blocked.toPath(), new byte[] { 1 });
        TestWorldReport.startWriter();
        TestWorldReport.WriteReceipt receiptA = completed(runId).writeFinal(target(saveA, runId));
        TestWorldReport.shutdownWriter();
        reporting(receiptA);
        TestWorldData stateA = journal;
        bind(stateA, saveA);
        assertTrue(TestWorldManager.busy());

        TestWorldData stateB = new TestWorldData("save-b-journal");
        bind(stateB, saveB);
        assertFalse(TestWorldManager.busy());
        assertEquals(null, field(TestWorldManager.class, "finalWrite").get(null));
        assertEquals(runId, stateA.activeRun);

        bind(stateA, new File(saveA, "alias/.."));
        assertSame(receiptA, field(TestWorldManager.class, "finalWrite").get(null));
        assertTrue(TestWorldManager.busy());
        Files.delete(blocked.toPath());
        assertTrue(receiptA.retry());
        TestWorldReport.shutdownWriter();
        acknowledge();
        assertFalse(TestWorldManager.busy());
        assertEquals("", stateA.activeRun);
        assertEquals("COMPLETE", stateA.operation);
    }

    @Test
    public void queuedAReportWithSameRunIdCannotAcknowledgePendingB() throws Exception {
        String runId = "22222222-2222-2222-2222-222222222222";
        File saveA = directory.newFolder("queued-a");
        File saveB = directory.newFolder("pending-b");
        TestWorldReport.startWriter();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) field(TestWorldReport.class, "writer").get(null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestWorldReport.WriteReceipt receiptA;
        TestWorldReport.WriteReceipt receiptB;
        TestWorldData stateA;
        TestWorldData stateB;
        try {
            executor.execute(() -> {
                entered.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread()
                        .interrupt();
                }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            receiptA = completed(runId).writeFinal(target(saveA, runId));
            assertEquals(TestWorldReport.WriteReceipt.State.QUEUED, receiptA.state());
            reporting(receiptA);
            stateA = journal;
            bind(stateA, saveA);
            stateB = new TestWorldData("copied-save-b");
            stateB.operation = "REPORTING";
            stateB.activeRun = runId;
            stateB.lastRun = runId;
            stateB.lastOutcome = "PASS";
            stateB.lastReport = receiptA.target.getAbsolutePath(); // A copied save must not trust this path.
            bind(stateB, saveB);
            receiptB = (TestWorldReport.WriteReceipt) field(TestWorldManager.class, "finalWrite").get(null);
            assertNotSame(receiptA, receiptB);
            assertTrue(receiptB.matches(target(saveB, runId), runId));
            assertEquals(receiptB.target.getAbsolutePath(), stateB.lastReport);
        } finally {
            release.countDown();
            TestWorldReport.shutdownWriter();
        }
        assertEquals(TestWorldReport.WriteReceipt.State.WRITTEN, receiptA.state());
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, receiptB.state());
        acknowledge();
        assertTrue(TestWorldManager.busy());
        assertEquals(runId, stateB.activeRun);
        assertEquals("REPORT_FAILED", stateB.operation);
        bind(stateA, saveA);
        acknowledge();
        assertFalse(TestWorldManager.busy());
        assertEquals("", stateA.activeRun);
        assertEquals(runId, stateB.activeRun);
    }

    @Test
    public void foreignWrittenReceiptCannotClearTheCurrentSaveEvenWithIdenticalRunId() throws Exception {
        TestWorldReport.startWriter();
        TestWorldReport.WriteReceipt receiptA = completed().writeFinal(new File(directory.getRoot(), "a.json"));
        TestWorldReport.shutdownWriter();
        reporting(receiptA);
        replace(TestWorldManager.class, "boundReportTarget", new File(directory.getRoot(), "b.json"));
        acknowledge();
        assertTrue(TestWorldManager.busy());
        assertEquals("completed-run", journal.activeRun);
        assertEquals("REPORTING", journal.operation);
    }

    @Test
    public void stopAndShutdownPreserveAFailedLiveSnapshotAndActiveRun() throws Exception {
        File blocked = directory.newFile("blocked");
        TestWorldReport.WriteReceipt receipt = failedLiveReceipt(new File(blocked, "report.json"));
        reporting(receipt);
        TestWorldManager.stop("stopped by administrator");
        assertTrue(TestWorldManager.busy());
        assertEquals("PASS", journal.lastOutcome);
        assertEquals("completed-run", journal.activeRun);
        assertEquals("REPORT_FAILED", journal.operation);
        assertFalse(receipt.abandoned());
        TestWorldManager.shutdown();
        assertEquals("completed-run", journal.activeRun);
        assertSame(receipt, field(TestWorldManager.class, "finalWrite").get(null));
        assertEquals("PASS", receipt.outcome);
    }

    @Test
    public void statusDoesNotRetryAndReportExplicitlyRetriesOffThread() throws Exception {
        File blocked = directory.newFile("blocked");
        TestWorldReport.WriteReceipt receipt = failedLiveReceipt(new File(blocked, "report.json"));
        reporting(receipt);
        String status = TestWorldManager.status();
        assertTrue(status.contains("reportWrite=FAILED"));
        assertEquals(1, receipt.attempts());
        Files.delete(blocked.toPath());
        TestWorldReport.startWriter();
        assertTrue(
            TestWorldManager.report()
                .contains("retry=queued asynchronously"));
        TestWorldReport.shutdownWriter();
        assertEquals(2, receipt.attempts());
        assertEquals(TestWorldReport.WriteReceipt.State.WRITTEN, receipt.state());
        TestWorldManager.stop("stopped by administrator");
        assertFalse(TestWorldManager.busy());
        assertEquals("COMPLETE", journal.operation);
        assertEquals("", journal.activeRun);
        assertEquals("PASS", journal.lastOutcome);
    }

    @Test
    public void stopAcknowledgesWrittenEvidenceBeforeHandlingCancellation() throws Exception {
        TestWorldReport.startWriter();
        TestWorldReport.WriteReceipt receipt = completed().writeFinal(new File(directory.getRoot(), "written.json"));
        TestWorldReport.shutdownWriter();
        reporting(receipt);
        TestWorldManager.stop("stopped by administrator");
        assertFalse(TestWorldManager.busy());
        assertEquals("PASS", journal.lastOutcome);
        assertEquals("COMPLETE", journal.operation);
        assertEquals("", journal.activeRun);
        assertEquals("PASS", receipt.outcome);
    }

    @Test
    public void explicitStopCanAbandonLostRecoveryWithoutFabricatingAnotherOutcome() throws Exception {
        TestWorldReport.WriteReceipt receipt = failedRecovery();
        reporting(receipt);
        TestWorldManager.stop("stopped by administrator");
        assertFalse(TestWorldManager.busy());
        assertTrue(receipt.abandoned());
        assertEquals("REPORT_FAILED", journal.operation);
        assertEquals("PASS", journal.lastOutcome);
        assertEquals("", journal.activeRun);
        assertEquals(receipt.target.getAbsolutePath(), journal.lastReport);
        assertTrue(
            TestWorldManager.report()
                .contains("evidenceAbandoned=true"));
        assertEquals(1, receipt.attempts());
    }

    @Test
    public void shutdownDoesNotImplicitlyAbandonUnavailableRecovery() throws Exception {
        TestWorldReport.WriteReceipt receipt = failedRecovery();
        reporting(receipt);
        TestWorldManager.shutdown();
        assertFalse(receipt.abandoned());
        assertEquals("REPORT_FAILED", journal.operation);
        assertEquals("completed-run", journal.activeRun);
        assertEquals("PASS", journal.lastOutcome);
    }

    @Test
    public void abandonedFailureRemainsVisibleWhenOnlyTheSavedJournalSurvives() throws Exception {
        reporting(failedRecovery());
        journal.operation = "REPORT_FAILED";
        journal.activeRun = "";
        replace(TestWorldManager.class, "phase", "IDLE");
        replace(TestWorldManager.class, "finalWrite", null);
        String status = TestWorldManager.report();
        assertTrue(status.contains("status=PASS"));
        assertTrue(status.contains("reportWrite=FAILED"));
        assertTrue(status.contains("evidenceAbandoned=true"));
        assertTrue(status.contains(journal.lastReport));
        assertFalse(TestWorldManager.busy());
    }
}
