package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import com.miaokatze.gtswn.common.device.DeviceRegistryData;
import com.miaokatze.gtswn.common.testworld.TestWorldProbes.Type;

/** Isolation contracts: these probes observe live business hooks but cannot leak between runs. */
public class TestWorldProbesTest {

    @Rule
    public final TestHarnessOptInRule harness = new TestHarnessOptInRule(true);

    @After
    public void stop() {
        TestWorldProbes.stop();
    }

    @Test
    public void idleHooksIgnoreInvalidInputsAndDoNotInvokePriorSink() {
        List<String> events = new ArrayList<>();
        TestWorldProbes.start((type, detail) -> events.add(type), 17);
        TestWorldProbes.addPosition(1, 64, 2);
        TestWorldProbes.stop();
        TestWorldProbes.at(17, 1, 64, 2, Type.DEVICE_REGISTER, "");
        TestWorldProbes.eu(null, null, false);
        TestWorldProbes.deviceKey("malformed", Type.DEVICE_REGISTER);
        TestWorldProbes.flow(new Object(), 0, 10);
        assertFalse(TestWorldProbes.enabled());
        assertTrue(events.isEmpty());
        assertEquals(0L, TestWorldProbes.queueToken(17, 1, 64, 2));
    }

    @Test
    public void onlyEnrolledCoordinatesAndSyntheticOwnersAreObserved() {
        List<String> events = new ArrayList<>();
        TestWorldProbes.start((type, detail) -> events.add(type + ";" + detail), 17);
        TestWorldProbes.addPosition(1, 64, 2);
        UUID synthetic = UUID.randomUUID(), unrelated = UUID.randomUUID();
        TestWorldProbes.addOwner(synthetic);
        TestWorldProbes.at(0, 1, 64, 2, Type.DEVICE_REGISTER, "");
        TestWorldProbes.at(17, 2, 64, 2, Type.DEVICE_REGISTER, "");
        TestWorldProbes.deviceKey("bad:coordinate", Type.DEVICE_REGISTER);
        TestWorldProbes.eu(unrelated, BigInteger.TEN, true);
        TestWorldProbes.deviceKey("17:1:64:2", Type.DEVICE_REGISTER);
        TestWorldProbes.eu(synthetic, BigInteger.TEN, true);
        TestWorldProbes.eu(synthetic, BigInteger.valueOf(-20), false);
        assertEquals(3, events.size());
        assertEquals(1L, TestWorldProbes.count(Type.DEVICE_REGISTER));
        assertEquals(1L, TestWorldProbes.count(Type.EU_COMMIT));
        assertEquals(1L, TestWorldProbes.count(Type.EU_REJECT));
        assertEquals(
            Long.valueOf(0),
            TestWorldProbes.counts()
                .get(Type.QUANTUM_MIGRATE));
        assertFalse(
            events.toString()
                .contains(synthetic.toString()));
        assertFalse(
            events.toString()
                .contains(unrelated.toString()));
    }

    @Test
    public void queuedWorkCannotBeAttributedToAnotherScenarioOrDimension() {
        List<String> first = new ArrayList<>(), second = new ArrayList<>();
        TestWorldProbes.start((type, detail) -> first.add(type), 17);
        TestWorldProbes.addPosition(1, 64, 2);
        long oldToken = TestWorldProbes.queueToken(17, 1, 64, 2);
        assertNotEquals(0L, oldToken);
        TestWorldProbes.queue(oldToken, 17, 1, 64, 2, false);
        TestWorldProbes.stop();
        TestWorldProbes.start((type, detail) -> second.add(type), 17);
        TestWorldProbes.addPosition(1, 64, 2);
        TestWorldProbes.queue(oldToken, 17, 1, 64, 2, true);
        long newToken = TestWorldProbes.queueToken(17, 1, 64, 2);
        TestWorldProbes.queue(newToken, 0, 1, 64, 2, true);
        assertTrue(second.isEmpty());
        TestWorldProbes.queue(newToken, 17, 1, 64, 2, false);
        TestWorldProbes.queue(newToken, 17, 1, 64, 2, true);
        assertEquals(1, first.size());
        assertEquals(2, second.size());
        assertEquals(1L, TestWorldProbes.count(Type.QUEUE_ENQUEUE));
        assertEquals(1L, TestWorldProbes.count(Type.QUEUE_EXECUTE));
    }

    @Test
    public void failingDiagnosticSinkCannotFailBusinessHook() {
        TestWorldProbes.start((type, detail) -> { throw new IllegalStateException("sink"); }, 17);
        TestWorldProbes.addPosition(1, 64, 2);
        TestWorldProbes.at(17, 1, 64, 2, Type.DEVICE_REGISTER, "");
        assertEquals(1L, TestWorldProbes.count(Type.DEVICE_REGISTER));
    }

    @Test
    public void realRegistryMutationEmitsOnceAndIdempotentCallsStaySilent() {
        List<String> events = new ArrayList<>();
        TestWorldProbes.start((type, detail) -> events.add(type), 17);
        TestWorldProbes.addPosition(1, 64, 2);
        DeviceRegistryData registry = new DeviceRegistryData("probe_fixture");
        UUID owner = UUID.randomUUID();
        assertTrue(registry.register("17:1:64:2", owner, "fixture", 1));
        assertFalse(registry.register("17:1:64:2", owner, "fixture", 1));
        assertTrue(registry.register("0:1:64:2", owner, "unrelated", 1));
        assertTrue(registry.unregister("17:1:64:2"));
        assertFalse(registry.unregister("17:1:64:2"));
        assertEquals(2, events.size());
        assertEquals(1L, TestWorldProbes.count(Type.DEVICE_REGISTER));
        assertEquals(1L, TestWorldProbes.count(Type.DEVICE_REMOVE));
    }
}
