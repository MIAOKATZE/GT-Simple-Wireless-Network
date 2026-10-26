package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.Test;

public class AESharedSamplingTest {

    private static Set<String> keys(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static Function<Set<String>, Map<String, Long>> counts(AtomicInteger calls) {
        return requested -> {
            calls.incrementAndGet();
            Map<String, Long> values = new HashMap<>();
            for (String key : requested) values.put(key, 9007199254740993L);
            return values;
        };
    }

    @Test
    public void commonKeysReadOnceButEachPanelOnlySeesItsBindings() {
        AESharedSampling sampling = new AESharedSampling();
        AtomicInteger calls = new AtomicInteger();
        Object world = new Object(), grid = new Object();
        Map<String, Long> first = sampling.collect(world, grid, 100, keys("a", "private"), counts(calls));
        Map<String, Long> second = sampling.collect(world, grid, 100, keys("a"), counts(calls));
        assertEquals(1, calls.get());
        assertEquals(keys("a"), second.keySet());
        assertEquals(Long.valueOf(9007199254740993L), second.get("a"));
        first.clear();
        assertEquals(
            keys("a"),
            sampling.collect(world, grid, 100, keys("a"), counts(calls))
                .keySet());
        assertEquals(1, calls.get());
    }

    @Test
    public void additionalBindingsCollectOnlyMissingKeys() {
        AESharedSampling sampling = new AESharedSampling();
        Object world = new Object(), grid = new Object();
        sampling.collect(world, grid, 100, keys("a"), counts(new AtomicInteger()));
        Map<String, Long> next = sampling.collect(world, grid, 100, keys("a", "b"), missing -> {
            assertEquals(keys("b"), missing);
            return Collections.singletonMap("b", 2L);
        });
        assertEquals(Long.valueOf(2), next.get("b"));
        assertEquals(2, next.size());
    }

    @Test
    public void worldGridAndTickAreAllIdentityBoundaries() {
        AESharedSampling sampling = new AESharedSampling();
        AtomicInteger calls = new AtomicInteger();
        Object world = new Object(), grid = new Object();
        sampling.collect(world, grid, 100, keys("a"), counts(calls));
        sampling.collect(new Object(), grid, 100, keys("a"), counts(calls));
        sampling.collect(world, new Object(), 100, keys("a"), counts(calls));
        sampling.collect(world, grid, 101, keys("a"), counts(calls));
        sampling.collect(world, grid, 1, keys("a"), counts(calls));
        assertEquals(5, calls.get());
    }

    @Test
    public void equalObjectsDoNotProveNativeIdentity() {
        AESharedSampling sampling = new AESharedSampling();
        AtomicInteger calls = new AtomicInteger();
        Object world = new Object();
        Object first = new String("grid"), second = new String("grid");
        sampling.collect(world, first, 100, keys("a"), counts(calls));
        sampling.collect(world, second, 100, keys("a"), counts(calls));
        assertEquals(2, calls.get());
    }

    @Test
    public void unknownIdentityNeverSharesAndFailuresDoNotBecomeZero() {
        AESharedSampling sampling = new AESharedSampling();
        AtomicInteger calls = new AtomicInteger();
        Object world = new Object(), grid = new Object();
        sampling.collect(world, null, 100, keys("a"), counts(calls));
        sampling.collect(world, null, 100, keys("a"), counts(calls));
        assertEquals(2, calls.get());
        assertTrue(
            sampling.collect(world, grid, 100, keys("a"), missing -> null)
                .isEmpty());
        assertTrue(
            sampling.collect(world, grid, 100, keys("a"), missing -> Collections.emptyMap())
                .isEmpty());
        assertEquals(
            Long.valueOf(9007199254740993L),
            sampling.collect(world, grid, 100, keys("a"), counts(calls))
                .get("a"));
    }

    @Test
    public void cacheBudgetOverflowNeverReturnsNullCounts() {
        AESharedSampling sampling = new AESharedSampling();
        Object world = new Object(), grid = new Object();
        Set<String> full = new HashSet<>();
        for (int i = 0; i < 4096; i++) full.add("key" + i);
        sampling.collect(world, grid, 100, full, counts(new AtomicInteger()));
        Map<String, Long> result = sampling.collect(world, grid, 100, keys("key0", "new"), counts(new AtomicInteger()));
        assertEquals(keys("key0", "new"), result.keySet());
        assertFalse(result.containsValue(null));
    }

    @Test
    public void failureDoesNotPublishPartialCachedAndFreshMix() {
        AESharedSampling sampling = new AESharedSampling();
        Object world = new Object(), grid = new Object();
        sampling.collect(world, grid, 100, keys("a"), counts(new AtomicInteger()));
        assertTrue(
            sampling.collect(world, grid, 100, keys("a", "b"), missing -> Collections.emptyMap())
                .isEmpty());
        assertEquals(
            keys("a"),
            sampling.collect(world, grid, 100, keys("a"), counts(new AtomicInteger()))
                .keySet());
    }

    @Test
    public void periodAlignmentKeepsImmediateInitialSampleAndConfiguredCadence() {
        assertTrue(AEMonitorController.sampleDue(-1, 133, 100));
        assertFalse(AEMonitorController.sampleDue(133, 199, 100));
        assertTrue(AEMonitorController.sampleDue(133, 200, 100));
        assertFalse(AEMonitorController.sampleDue(200, 299, 100));
        assertTrue(AEMonitorController.sampleDue(200, 300, 100));
        assertTrue(AEMonitorController.sampleDue(300, 10, 100));
    }
}
