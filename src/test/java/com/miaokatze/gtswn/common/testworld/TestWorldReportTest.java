package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class TestWorldReportTest {

    @Rule
    public final TestHarnessOptInRule harness = new TestHarnessOptInRule(true);

    @Rule
    public final TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void failureCannotBeOverwrittenByALaterSuccess() {
        TestWorldReport report = new TestWorldReport("terminal", 19871, "one");
        TestWorldReport.Result result = new TestWorldReport.Result("one");
        TestContext context = new TestContext(null, "terminal", report, result);
        context.check(false, "broken fixture");
        context.pass("unconditional success at end of callback");
        assertEquals("FAIL", context.outcome);
        assertEquals("broken fixture", context.detail);
    }

    @Test
    public void reportKeepsTimingAndExplicitCoverageWhenTraceBuffersOverflow() throws Exception {
        TestWorldReport report = new TestWorldReport("bounded", 19871, "all");
        report.startedAtMs = 1234L;
        report.endedAtMs = 5678L;
        report.managerWorkNanos = 123L;
        report.managerMaxWorkNanos = 78L;
        report.totalObservedTickWindowNanos = 456L;
        report.maxObservedTickWindowNanos = 345L;
        report.observedTickWindows = 2;
        TestWorldReport.Result completed = new TestWorldReport.Result("completed");
        completed.status = "PASS";
        completed.observedBusinessEvents.put("NEVER_EXERCISED", 0L);
        report.results.add(completed);
        report.results.add(new TestWorldReport.Result("pending"));
        for (int i = 0; i < 5000; i++) report.event(completed, i, "trace", "event " + i);
        assertEquals(TestWorldReport.EVENT_LIMIT, report.events.size());
        assertEquals(904, report.droppedEvents);
        assertEquals(TestWorldReport.RESULT_EVENT_LIMIT, completed.events.size());
        assertEquals(4744, completed.droppedEvents);
        assertEquals(904L, report.events.getFirst().tick);

        TestWorldReport.startWriter();
        File target = new File(directory.getRoot(), "bounded.json");
        assertTrue(report.write(target, false));
        TestWorldReport.shutdownWriter();
        JsonObject json = new JsonParser()
            .parse(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8))
            .getAsJsonObject();
        assertEquals(
            123L,
            json.get("managerWorkNanos")
                .getAsLong());
        assertEquals(
            78L,
            json.get("managerMaxWorkNanos")
                .getAsLong());
        assertEquals(
            456L,
            json.get("totalObservedTickWindowNanos")
                .getAsLong());
        assertEquals(
            345L,
            json.get("maxObservedTickWindowNanos")
                .getAsLong());
        assertEquals(
            2L,
            json.get("observedTickWindows")
                .getAsLong());
        assertEquals(
            3_000_000L,
            json.get("resetScanBudgetNanos")
                .getAsLong());
        assertEquals(
            1234L,
            json.get("startedAtMs")
                .getAsLong());
        assertEquals(
            5678L,
            json.get("endedAtMs")
                .getAsLong());
        assertEquals(
            "NOT_RUN",
            json.getAsJsonArray("results")
                .get(1)
                .getAsJsonObject()
                .get("status")
                .getAsString());
        assertEquals(
            0L,
            json.getAsJsonArray("results")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("observedBusinessEvents")
                .get("NEVER_EXERCISED")
                .getAsLong());
    }
}
