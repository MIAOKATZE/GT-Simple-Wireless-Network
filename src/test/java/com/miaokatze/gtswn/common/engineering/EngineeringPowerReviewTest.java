package com.miaokatze.gtswn.common.engineering;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Map;

import org.junit.Test;

import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Power;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Review;

public class EngineeringPowerReviewTest {

    private static Power power(double value) {
        return new Power(value, 0, value, 0);
    }

    private static Map<String, Power> samples(String key, Power value) {
        return Collections.singletonMap(key, value);
    }

    private static Map<String, Power> samples(String first, Power a, String second, Power b) {
        Map<String, Power> result = new java.util.LinkedHashMap<>();
        result.put(first, a);
        result.put(second, b);
        return result;
    }

    private static EngineeringPowerResult result(Map<String, Power> samples, boolean discovering) {
        Power total = Power.ZERO;
        for (Power value : samples.values()) total = total.plus(value);
        return new EngineeringPowerResult(
            total,
            Collections.emptyList(),
            samples,
            0,
            samples.size(),
            false,
            false,
            1,
            1,
            1,
            0,
            discovering);
    }

    @Test
    public void losingCoverageDoesNotManufactureAFallingPowerDelta() {
        EngineeringPowerReview review = new EngineeringPowerReview();
        review.observe(result(samples("0:0:64:0@1", power(100), "0:16:64:0@2", power(200)), false), 20);
        Review current = review.observe(result(samples("0:0:64:0@1", power(100)), false), 40);
        assertEquals(1, current.comparableMonitored);
        assertEquals(1, current.removedMonitored);
        assertEquals(0, current.addedMonitored);
        assertEquals(0, current.comparableCurrent.minus(current.comparableBefore).instIn, 0);
        assertEquals(300, review.before().instIn, 0);
        assertEquals(20, current.baselineTick);
    }

    @Test
    public void replacementAndOtherDimensionsAreDifferentObservationMembers() {
        EngineeringPowerReview review = new EngineeringPowerReview();
        review.observe(result(samples("0:-16:64:0@1", power(100)), false), 20);
        Review replaced = review
            .observe(result(samples("0:-16:64:0@2", power(999), "-1:-16:64:0@1", power(50)), false), 40);
        assertFalse(replaced.comparable);
        assertEquals(0, replaced.comparableMonitored);
        assertEquals(2, replaced.addedMonitored);
        assertEquals(1, replaced.removedMonitored);
        assertEquals(0, replaced.comparableCurrent.instIn, 0);
    }

    @Test
    public void discoveryAndWarmupWaitForARealBaselineAndResetStartsANewReview() {
        EngineeringPowerReview review = new EngineeringPowerReview();
        assertFalse(review.observe(result(Collections.emptyMap(), false), 20).comparable);
        assertFalse(review.observe(result(samples("A", power(10)), true), 40).comparable);
        Review ready = review.observe(result(samples("A", power(20)), false), 60);
        assertEquals(60, ready.baselineTick);
        assertTrue(ready.comparable);
        assertEquals(0, ready.addedMonitored);
        Review changed = review.observe(result(samples("A", power(15), "B", power(1000)), false), 80);
        assertEquals(-5, changed.comparableCurrent.minus(changed.comparableBefore).instIn, 0);
        assertEquals(1, changed.addedMonitored);
        review.reset();
        assertEquals(100, review.observe(result(samples("B", power(1000)), false), 100).baselineTick);
    }

    @Test
    public void legalAggregateOverflowSaturatesAndNegativeDeltaKeepsItsSign() {
        Power huge = power(Double.MAX_VALUE);
        assertEquals(Double.MAX_VALUE, huge.plus(huge).instIn, 0);
        assertEquals(
            -Double.MAX_VALUE,
            Power.ZERO.minus(huge)
                .minus(huge).instIn,
            0);
        assertEquals(-25, power(75).minus(power(100)).instIn, 0);
    }
}
