package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class TestWorldReportWriteTest {

    @Rule
    public final TestHarnessOptInRule harness = new TestHarnessOptInRule(true);

    @Rule
    public final TemporaryFolder directory = new TemporaryFolder();

    @After
    public void drainWriter() {
        TestWorldReport.shutdownWriter();
    }

    private TestWorldReport completed(String runId) {
        TestWorldReport report = new TestWorldReport(runId, 19871, "one");
        report.status = "PASS";
        report.endedAtMs = System.currentTimeMillis();
        TestWorldReport.Result result = new TestWorldReport.Result("one");
        result.status = "PASS";
        result.detail = "original evidence";
        report.results.add(result);
        return report;
    }

    private JsonObject read(File target) throws Exception {
        return new JsonParser().parse(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8))
            .getAsJsonObject();
    }

    private TestWorldReport.WriteReceipt.State recover(String overall, String failure, String... results)
        throws Exception {
        TestWorldReport report = new TestWorldReport("semantic-recovery", 19871, "all");
        report.status = overall;
        report.failure = failure;
        report.endedAtMs = System.currentTimeMillis();
        for (int i = 0; i < results.length; i++) {
            TestWorldReport.Result result = new TestWorldReport.Result("scenario_" + i);
            result.status = results[i];
            report.results.add(result);
        }
        File target = directory.newFile();
        Files.write(
            target.toPath(),
            new GsonBuilder().create()
                .toJson(report)
                .getBytes(StandardCharsets.UTF_8));
        TestWorldReport.WriteReceipt receipt = TestWorldReport.recoverFinal(target, report.runId, overall);
        TestWorldReport.shutdownWriter();
        return receipt.state();
    }

    @Test
    public void receiptIdentityRequiresBothNormalizedAbsoluteTargetAndRunId() throws Exception {
        File folder = directory.newFolder("identity");
        File target = new File(folder, "alias/../report.json");
        TestWorldReport.WriteReceipt receipt = completed("identity").writeFinal(target);
        TestWorldReport.shutdownWriter();
        assertTrue(receipt.matches(new File(folder, "./report.json"), "identity"));
        assertFalse(receipt.matches(new File(directory.getRoot(), "other/report.json"), "identity"));
        assertFalse(receipt.matches(new File(folder, "report.json"), "different-run"));
    }

    @Test
    public void recoveryRejectsPassWithFailedUnexecutedOrOnlySkippedScenarios() throws Exception {
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, recover("PASS", null, "PASS", "FAIL"));
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, recover("PASS", null, "PASS", "NOT_RUN"));
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, recover("PASS", null, "NOT_RUN"));
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, recover("PASS", null, "SKIP", "SKIP"));
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, recover("PASS", "runner failed", "PASS"));
    }

    @Test
    public void recoveryRejectsSkipUnlessEveryScenarioWasSkipped() throws Exception {
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, recover("SKIP", null, "SKIP", "PASS"));
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, recover("SKIP", null, "SKIP", "FAIL"));
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, recover("SKIP", null, "SKIP", "NOT_RUN"));
    }

    @Test
    public void recoveryRejectsImpossibleScenarioStatesAndExecutionOrdering() throws Exception {
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, recover("STOPPED", "admin stop", "STOPPED"));
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, recover("FAIL", "runner failed", "RUNNING"));
        assertEquals(
            TestWorldReport.WriteReceipt.State.FAILED,
            recover("FAIL", "runner failed", "NOT_RUN", "PASS", "FAIL"));
    }

    @Test
    public void recoveryRequiresFailureOrInterruptionEvidenceForFailAndStopped() throws Exception {
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, recover("FAIL", null, "PASS"));
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, recover("FAIL", null, "FAIL", "NOT_RUN"));
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, recover("STOPPED", null, "NOT_RUN"));
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, recover("STOPPED", "admin stop", "PASS", "SKIP"));
        assertEquals(
            TestWorldReport.WriteReceipt.State.WRITTEN,
            recover("FAIL", "reset failed", "PASS", "FAIL", "NOT_RUN"));
        assertEquals(TestWorldReport.WriteReceipt.State.WRITTEN, recover("FAIL", "reset failed", "NOT_RUN"));
        assertEquals(TestWorldReport.WriteReceipt.State.WRITTEN, recover("STOPPED", "admin stop", "PASS", "NOT_RUN"));
    }

    @Test
    public void recoveryAcceptsCompletedPassSkipAndFailAggregates() throws Exception {
        assertEquals(TestWorldReport.WriteReceipt.State.WRITTEN, recover("PASS", null, "PASS", "SKIP"));
        assertEquals(TestWorldReport.WriteReceipt.State.WRITTEN, recover("SKIP", null, "SKIP", "SKIP"));
        assertEquals(TestWorldReport.WriteReceipt.State.WRITTEN, recover("FAIL", null, "PASS", "FAIL"));
    }

    @Test
    public void ioFailureRetainsTheOriginalSnapshotForAnAsynchronousRetry() throws Exception {
        File blockedParent = directory.newFile("blocked-parent");
        File target = new File(blockedParent, "final.json");
        TestWorldReport report = completed("io-retry");
        TestWorldReport.startWriter();
        TestWorldReport.WriteReceipt receipt = report.writeFinal(target);
        TestWorldReport.shutdownWriter();
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, receipt.state());
        assertFalse(
            receipt.error()
                .isEmpty());
        assertEquals(1, receipt.attempts());
        assertFalse(target.exists());

        report.status = "STOPPED";
        report.results.get(0).detail = "mutated after final snapshot";
        Files.delete(blockedParent.toPath());
        TestWorldReport.startWriter();
        assertTrue(receipt.retry());
        TestWorldReport.shutdownWriter();
        assertEquals(TestWorldReport.WriteReceipt.State.WRITTEN, receipt.state());
        assertEquals(2, receipt.attempts());
        assertEquals("", receipt.error());
        JsonObject json = read(target);
        assertEquals(
            "PASS",
            json.get("status")
                .getAsString());
        assertEquals(
            "original evidence",
            json.getAsJsonArray("results")
                .get(0)
                .getAsJsonObject()
                .get("detail")
                .getAsString());
    }

    @Test
    public void fullQueueRejectionRetainsAFailedReceiptAndCanBeRetried() throws Exception {
        File target = new File(directory.getRoot(), "queued.json");
        TestWorldReport.startWriter();
        Field writerField = TestWorldReport.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) writerField.get(null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestWorldReport.WriteReceipt receipt;
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
            for (int i = 0; i < 16; i++) executor.execute(() -> {});
            receipt = completed("queue-retry").writeFinal(target);
            assertEquals(TestWorldReport.WriteReceipt.State.FAILED, receipt.state());
            assertTrue(
                receipt.error()
                    .contains("RejectedExecutionException"));
            assertFalse(target.exists());
        } finally {
            release.countDown();
            TestWorldReport.shutdownWriter();
        }
        TestWorldReport.startWriter();
        assertTrue(receipt.retry());
        TestWorldReport.shutdownWriter();
        assertEquals(TestWorldReport.WriteReceipt.State.WRITTEN, receipt.state());
        assertEquals(
            "PASS",
            read(target).get("status")
                .getAsString());
    }

    @Test
    public void restartRecoveryAcknowledgesOnlyAnExistingMatchingTerminalReport() throws Exception {
        File target = new File(directory.getRoot(), "recovered.json");
        TestWorldReport.startWriter();
        completed("recover-good").writeFinal(target);
        TestWorldReport.shutdownWriter();
        TestWorldReport.startWriter();
        TestWorldReport.WriteReceipt receipt = TestWorldReport.recoverFinal(target, "recover-good", "PASS");
        TestWorldReport.shutdownWriter();
        assertEquals(TestWorldReport.WriteReceipt.State.WRITTEN, receipt.state());
        assertFalse(receipt.abandonRecovery());
    }

    @Test
    public void restartingWriterCannotRaceAnOlderCheckpointIntoOverwritingFinalEvidence() throws Exception {
        File target = new File(directory.getRoot(), "single-writer.json");
        TestWorldReport.startWriter();
        Field writerField = TestWorldReport.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) writerField.get(null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestWorldReport.WriteReceipt receipt;
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
            TestWorldReport report = completed("single-writer");
            report.status = "RUNNING";
            assertTrue(report.write(target, false));
            executor.shutdown(); // Emulate a shutdown whose old I/O has not finished yet.
            report.status = "PASS";
            receipt = report.writeFinal(target);
            assertEquals(TestWorldReport.WriteReceipt.State.FAILED, receipt.state());
            assertFalse(target.exists());
        } finally {
            release.countDown();
            TestWorldReport.shutdownWriter();
        }
        assertTrue(receipt.retry());
        TestWorldReport.shutdownWriter();
        assertEquals(TestWorldReport.WriteReceipt.State.WRITTEN, receipt.state());
        assertEquals(
            "PASS",
            read(target).get("status")
                .getAsString());
    }

    @Test
    public void restartRecoveryRejectsMissingCheckpointAndMalformedEvidence() throws Exception {
        File target = new File(directory.getRoot(), "lost.json");
        TestWorldReport.startWriter();
        TestWorldReport.WriteReceipt missing = TestWorldReport.recoverFinal(target, "recover-bad", "PASS");
        TestWorldReport.shutdownWriter();
        assertEquals(TestWorldReport.WriteReceipt.State.FAILED, missing.state());

        String[] invalid = { "{bad json", "{\"runId\":\"recover-bad\",\"status\":\"PASS\"}",
            "{\"runId\":\"recover-bad\",\"status\":\"RUNNING\"}" };
        for (String text : invalid) {
            Files.write(target.toPath(), text.getBytes(StandardCharsets.UTF_8));
            TestWorldReport.startWriter();
            assertTrue(missing.retry());
            TestWorldReport.shutdownWriter();
            assertEquals(TestWorldReport.WriteReceipt.State.FAILED, missing.state());
        }
        assertTrue(missing.abandonRecovery());
        assertFalse(missing.retry());
    }
}
