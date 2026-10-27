package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import org.junit.Test;

public class QuantumFullSnapshotReuseTest {

    @Test
    public void warmAssemblyPreservesSharedSnapshotIdentityAndValues() throws Exception {
        try (QuantumFullSnapshotFixture fixture = new QuantumFullSnapshotFixture()) {
            for (int i = 0; i < 8; i++) {
                QuantumNetworkData data = fixture.get();
                assertSame(fixture.expected, data);
                assertTrue(data.online);
                assertEquals(123, data.totalChannels);
            }
        }
    }

    @Test
    public void unreachableAnchorStillReturnsAnIndependentOfflineSnapshot() throws Exception {
        try (QuantumFullSnapshotFixture fixture = new QuantumFullSnapshotFixture()) {
            QuantumNetworkData data = QuantumNetworkData.assemble(37175, 999, 64, 0);
            assertFalse(data.online);
            assertEquals(37175, data.anchorDim);
            assertEquals(999, data.anchorX);
            assertEquals(64, data.anchorY);
            assertEquals(0, data.anchorZ);
            assertEquals(QuantumControllerRegistry.isChannelsInfinite(), data.channelsInfinite);
            assertTrue(data.entries.isEmpty());
            assertNotSame(fixture.expected, data);
            assertSame(fixture.expected, fixture.get());
        }
    }
}
