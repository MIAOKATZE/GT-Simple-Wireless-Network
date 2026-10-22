package com.miaokatze.gtswn.common.engineering;

import java.util.HashMap;
import java.util.Map;

import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Power;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Review;

/** Session-local fixed baseline, with explicit coverage changes and no interpolation or energy integration. */
public final class EngineeringPowerReview {

    private Map<String, Power> baseline;
    private long baselineTick;
    private Power before = Power.ZERO;

    public void reset() {
        baseline = null;
        before = Power.ZERO;
    }

    public Power before() {
        return before;
    }

    public Review observe(EngineeringPowerResult current, long tick) {
        if (baseline == null && !current.discovering && !current.samples.isEmpty()) {
            baseline = new HashMap<>(current.samples);
            baselineTick = tick;
            before = current.total;
        }
        Power comparedBefore = Power.ZERO, comparedCurrent = Power.ZERO;
        int comparable = 0, added = 0, removed = 0;
        if (baseline != null) {
            for (Map.Entry<String, Power> entry : current.samples.entrySet()) {
                Power old = baseline.get(entry.getKey());
                if (old == null) added++;
                else {
                    comparable++;
                    comparedBefore = comparedBefore.plus(old);
                    comparedCurrent = comparedCurrent.plus(entry.getValue());
                }
            }
            for (String key : baseline.keySet()) if (!current.samples.containsKey(key)) removed++;
        }
        return new Review(
            baseline == null ? tick : baselineTick,
            comparable,
            added,
            removed,
            comparedBefore,
            comparedCurrent,
            current.loadedChunks,
            current.totalChunks,
            current.scannedChunks,
            current.modeled,
            comparable > 0 && !current.discovering,
            current.discovering);
    }
}
