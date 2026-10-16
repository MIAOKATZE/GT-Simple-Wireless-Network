package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class TestWorldPerformanceTest {

    private static final class Clock implements TestWorldPerformance.Sources {

        long nanos;
        int resources;
        long nativeAttempts;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public long cpuTime() {
            return -1;
        }

        @Override
        public TestWorldPerformance.Resources resources() {
            resources++;
            return new TestWorldPerformance.Resources(resources, 2, 3, 4, 5);
        }

        @Override
        public long nativeAeConnectionAttempts() {
            return nativeAttempts;
        }
    }

    @Test
    public void wholeServerAndManagerDistributionsHaveIndependentCpuAndWallSamples() {
        TestWorldPerformance performance = new TestWorldPerformance(new Clock());
        for (int i = 1; i <= 100; i++) performance.tick(i * 100, i * 80, i * 10, i * 8);
        TestWorldPerformance.Snapshot result = performance.finish();
        assertEquals(100, result.naturalServerWindowWallNanos.sampleCount);
        assertEquals(Long.valueOf(5000), result.naturalServerWindowWallNanos.p50Nanos);
        assertEquals(Long.valueOf(9500), result.naturalServerWindowWallNanos.p95Nanos);
        assertEquals(10000, result.naturalServerWindowWallNanos.maxNanos);
        assertEquals(50500, result.managerHandlerWallNanos.totalNanos);
        assertEquals(8000, result.naturalServerThreadCpuNanos.maxNanos);
        assertEquals(800, result.managerHandlerCpuNanos.maxNanos);
        assertFalse(result.status.contains("PASS"));
    }

    @Test
    public void resourcesAreReadOnlyAtWindowBoundariesAndFinishIsIdempotent() {
        Clock clock = new Clock();
        TestWorldPerformance performance = new TestWorldPerformance(clock);
        assertEquals(1, clock.resources);
        for (int i = 0; i < 50; i++) performance.tick(100, -1, 10, -1);
        assertEquals(1, clock.resources);
        clock.nanos = 500;
        TestWorldPerformance.Snapshot snapshot = performance.finish();
        assertEquals(2, clock.resources);
        assertEquals(500, snapshot.elapsedWallNanos);
        assertEquals(1, snapshot.before.heapUsedBytes);
        assertEquals(2, snapshot.after.heapUsedBytes);
        assertSame(snapshot, performance.finish());
        assertEquals(2, clock.resources);
    }

    @Test
    public void nativeGateAttemptDeltaIsIndependentOfScenarioSessionCountsAndFreezesAtFinish() {
        Clock clock = new Clock();
        clock.nativeAttempts = 30;
        TestWorldPerformance performance = new TestWorldPerformance(clock);
        clock.nativeAttempts = 32;
        TestWorldPerformance.Snapshot snapshot = performance.finish();
        assertEquals(Long.valueOf(2), snapshot.nativeAeConnectionAttempts);
        clock.nativeAttempts = 99;
        assertSame(snapshot, performance.finish());
        assertEquals(Long.valueOf(2), snapshot.nativeAeConnectionAttempts);
        assertTrue(snapshot.nativeAeConnectionAttemptsScope.contains("before authorization verdict"));
    }

    @Test
    public void unsupportedCpuAndEmptyWindowAreNullWithoutInventingPass() {
        TestWorldPerformance.Snapshot result = new TestWorldPerformance(new Clock()).finish();
        assertEquals("NOT_OBSERVED", result.status);
        assertNull(result.naturalServerThreadCpuNanos);
        assertNull(result.managerHandlerCpuNanos);
        assertNull(result.naturalServerWindowWallNanos.p50Nanos);
        JsonObject json = new JsonParser().parse(
            new GsonBuilder().serializeNulls()
                .create()
                .toJson(result))
            .getAsJsonObject();
        assertTrue(
            json.get("naturalServerThreadCpuNanos")
                .isJsonNull());
    }

    @Test
    public void quantileStorageIsBoundedButCountTotalAndMaxRetainTheWholeWindow() {
        TestWorldPerformance.Samples samples = new TestWorldPerformance.Samples();
        samples.add(999999);
        for (int i = 0; i < TestWorldPerformance.SAMPLE_LIMIT * 3; i++) samples.add(10);
        TestWorldPerformance.Distribution snapshot = samples.snapshot();
        assertEquals(6145, snapshot.sampleCount);
        assertEquals(2048, snapshot.percentileSampleCount);
        assertEquals(999999 + 61440, snapshot.totalNanos);
        assertEquals(999999, snapshot.maxNanos);
        assertEquals(Long.valueOf(10), snapshot.p95Nanos);
    }

    @Test
    public void namedOperationsAreBoundedAndFrozenWithCompletedSnapshot() {
        TestWorldPerformance performance = new TestWorldPerformance(new Clock());
        for (int i = 0; i < 40; i++) performance.operation("operation-" + i, i);
        performance.operation("operation-0", 50);
        TestWorldPerformance.Snapshot result = performance.finish();
        assertEquals(32, result.namedOperationsWallNanos.size());
        assertEquals(8, result.droppedOperationNames);
        assertEquals(2, result.namedOperationsWallNanos.get("operation-0").sampleCount);
        performance.tick(1000, 100, 100, 10);
        performance.operation("operation-0", 9999);
        assertEquals(0, result.naturalServerWindowWallNanos.sampleCount);
        assertEquals(50, result.namedOperationsWallNanos.get("operation-0").maxNanos);
    }

    @Test
    public void detachedReportRetainsImmutablePerformanceAndLegacyBusinessCounts() throws Exception {
        TestWorldPerformance performance = new TestWorldPerformance(new Clock());
        performance.tick(200, -1, 20, -1);
        TestWorldReport report = new TestWorldReport("performance", 19871, "one");
        TestWorldReport.Result result = new TestWorldReport.Result("one");
        result.performance = performance.finish();
        result.observedBusinessEvents.put("NEVER_OBSERVED", 0L);
        report.performance = result.performance;
        report.resetPerformance.add(result.performance);
        report.results.add(result);
        Method snapshot = TestWorldReport.class.getDeclaredMethod("snapshot");
        snapshot.setAccessible(true);
        TestWorldReport detached = (TestWorldReport) snapshot.invoke(report);
        result.performance = null;
        result.observedBusinessEvents.put("NEVER_OBSERVED", 99L);
        report.resetPerformance.clear();
        assertEquals(1, detached.resetPerformance.size());
        assertEquals(200, detached.results.get(0).performance.naturalServerWindowWallNanos.maxNanos);
        assertEquals(Long.valueOf(0), detached.results.get(0).observedBusinessEvents.get("NEVER_OBSERVED"));
    }

    @Test
    public void invalidOrUnsupportedCpuCounterDeltasStayUnavailable() {
        assertEquals(-1, TestWorldPerformance.delta(-1, 100));
        assertEquals(-1, TestWorldPerformance.delta(100, -1));
        assertEquals(-1, TestWorldPerformance.delta(100, 90));
        assertEquals(10, TestWorldPerformance.delta(100, 110));
    }

    @Test
    public void managerOnlyPartialTickDoesNotInventANaturalServerWindow() {
        TestWorldPerformance performance = new TestWorldPerformance(new Clock());
        performance.tick(-1, -1, 100, 10);
        TestWorldPerformance.Snapshot result = performance.finish();
        assertEquals("NOT_OBSERVED", result.status);
        assertEquals(0, result.naturalServerWindowWallNanos.sampleCount);
        assertNull(result.naturalServerThreadCpuNanos);
        assertEquals(1, result.managerHandlerWallNanos.sampleCount);
        assertEquals(10, result.managerHandlerCpuNanos.maxNanos);
    }

    @Test
    public void normalAllExcludesHeavyScenariosButExplicitIdCanSelectThem() {
        TestScenario normal = scenario("normal", true);
        TestScenario heavy = scenario("heavy", false);
        assertEquals(Arrays.asList(normal), TestWorldManager.selectScenarios("all", Arrays.asList(normal, heavy)));
        assertEquals(Arrays.asList(heavy), TestWorldManager.selectScenarios("heavy", Arrays.asList(normal, heavy)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void explicitUnknownScenarioIsRejected() {
        TestWorldManager.selectScenarios("missing", Arrays.asList(scenario("normal", true)));
    }

    private TestScenario scenario(String id, boolean all) {
        return new TestScenario() {

            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean includeInAll() {
                return all;
            }

            @Override
            public void start(TestContext context) {}

            @Override
            public void tick(TestContext context) {}
        };
    }
}
