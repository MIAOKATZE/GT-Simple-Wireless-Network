package com.miaokatze.gtswn.client.testworld;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.miaokatze.gtswn.common.testworld.TestWorldPerformance;

/** One explicitly selected GUI case; all measurements are CPU-side wall observations. */
final class UiPerformance {

    private final long started = System.nanoTime();
    private final Map<String, TestWorldPerformance.Samples> timings = new LinkedHashMap<>();
    private final long presetDataWaitNanos;
    private long caseDataWaitNanos;
    private Map<String, Object> completed;

    UiPerformance(long presetDataWaitNanos) {
        this.presetDataWaitNanos = presetDataWaitNanos;
    }

    void add(String name, long nanos) {
        if (completed != null || nanos < 0) return;
        TestWorldPerformance.Samples samples = timings.get(name);
        if (samples == null) {
            samples = new TestWorldPerformance.Samples();
            timings.put(name, samples);
        }
        samples.add(nanos);
    }

    void dataWait(long nanos) {
        if (completed == null) caseDataWaitNanos += Math.max(0, nanos);
    }

    Map<String, Object> finish() {
        if (completed != null) return completed;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", timings.isEmpty() ? "NOT_OBSERVED" : "OBSERVED_NO_PERFORMANCE_ASSERTION");
        result.put(
            "scope",
            "CPU-side wall time only. naturalRenderWindow measures this RenderTick START to END handler entry and includes unrelated rendering; it excludes screenshot/inspect work in END. No GPU timing or FPS claim. Handler/PNG/inspect costs are separately named; no mod-exclusive or causal attribution. Client handler samples cover callbacks completed before case freeze (terminal callback may be absent). Capture handler ends before JSON/index serialization.");
        result.put("elapsedWallNanos", System.nanoTime() - started);
        result.put("sharedPresetDataWaitWallNanos", presetDataWaitNanos);
        result.put("caseDataWaitWallNanos", caseDataWaitNanos);
        Map<String, Object> distributions = new LinkedHashMap<>();
        for (Map.Entry<String, TestWorldPerformance.Samples> entry : timings.entrySet()) distributions.put(
            entry.getKey(),
            entry.getValue()
                .snapshot());
        result.put("timings", Collections.unmodifiableMap(distributions));
        completed = Collections.unmodifiableMap(result);
        return completed;
    }
}
