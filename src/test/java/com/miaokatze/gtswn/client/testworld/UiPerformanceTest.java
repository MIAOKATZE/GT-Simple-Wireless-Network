package com.miaokatze.gtswn.client.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.miaokatze.gtswn.common.testworld.TestWorldPerformance;

public class UiPerformanceTest {

    @Test
    public void renderInspectAndPngCostsRemainSeparateFromDataWait() {
        UiPerformance performance = new UiPerformance(500);
        performance.add("naturalRenderWindowWallNanos", 200);
        performance.add("layoutInspectWallNanos", 30);
        performance.add("pngCaptureWallNanos", 80);
        performance.dataWait(900);
        Map<String, Object> result = performance.finish();
        assertEquals(500L, result.get("sharedPresetDataWaitWallNanos"));
        assertEquals(900L, result.get("caseDataWaitWallNanos"));
        Map<?, ?> timings = (Map<?, ?>) result.get("timings");
        assertEquals(200, ((TestWorldPerformance.Distribution) timings.get("naturalRenderWindowWallNanos")).maxNanos);
        assertEquals(30, ((TestWorldPerformance.Distribution) timings.get("layoutInspectWallNanos")).maxNanos);
        assertEquals(80, ((TestWorldPerformance.Distribution) timings.get("pngCaptureWallNanos")).maxNanos);
        assertTrue(
            result.get("scope")
                .toString()
                .contains("No GPU timing or FPS claim"));
        assertFalse(
            result.get("status")
                .toString()
                .contains("PASS"));
    }

    @Test
    public void completedCaseIsDetachedFromSubsequentFramesAndWaits() {
        UiPerformance performance = new UiPerformance(0);
        performance.add("naturalRenderWindowWallNanos", 10);
        Map<String, Object> result = performance.finish();
        performance.add("naturalRenderWindowWallNanos", 999);
        performance.dataWait(999);
        assertSame(result, performance.finish());
        assertEquals(0L, result.get("caseDataWaitWallNanos"));
        Map<?, ?> timings = (Map<?, ?>) result.get("timings");
        assertEquals(1, ((TestWorldPerformance.Distribution) timings.get("naturalRenderWindowWallNanos")).sampleCount);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void completedMetricMapCannotBeChangedByWriterConsumers() {
        new UiPerformance(0).finish()
            .put("status", "PASS");
    }
}
