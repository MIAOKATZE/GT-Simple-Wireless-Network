package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;

import org.junit.Test;

public class EUCacheBridgeDataSetTest {

    @Test
    public void emptyReplacementDropsDeletedHistoryAndForcesAFullPublication() throws Exception {
        EUCacheBridge bridge = new EUCacheBridge(null, new PanelConfigStore(() -> {}));
        NetworkInfoDataSet previous = data(99);
        replace(bridge, previous);
        assertEquals(BigInteger.valueOf(99), bridge.getCachedEu());
        field(bridge, "needsDataRefresh").setBoolean(bridge, false);
        field(bridge, "fullSyncPending").setBoolean(bridge, false);
        field(bridge, "incrementalPushUntilTick").setLong(bridge, 500);
        replace(bridge, new NetworkInfoDataSet());
        assertEquals(BigInteger.ZERO, bridge.getCachedEu());
        assertEquals(0D, bridge.getCachedEut(), 0D);
        assertTrue(
            bridge.getCachedSamples()
                .isEmpty());
        assertEquals(-1L, field(bridge, "lastKnownSampleTick").getLong(bridge));
        assertTrue(field(bridge, "needsDataRefresh").getBoolean(bridge));
        assertTrue(field(bridge, "fullSyncPending").getBoolean(bridge));
        assertEquals(-1L, field(bridge, "incrementalPushUntilTick").getLong(bridge));
    }

    @Test
    public void sameTickReplacementStillReplacesScalarsAndChartWhileSameObjectIsStable() throws Exception {
        EUCacheBridge bridge = new EUCacheBridge(null, new PanelConfigStore(() -> {}));
        replace(bridge, data(99));
        NetworkInfoDataSet next = data(7);
        replace(bridge, next);
        assertEquals(BigInteger.valueOf(7), bridge.getCachedEu());
        assertEquals(
            BigInteger.valueOf(7),
            bridge.getCachedSamples()
                .get(0).eu);
        long seq = field(bridge, "syncSeq").getLong(bridge);
        field(bridge, "needsDataRefresh").setBoolean(bridge, false);
        field(bridge, "fullSyncPending").setBoolean(bridge, false);
        replace(bridge, next);
        assertEquals(seq, field(bridge, "syncSeq").getLong(bridge));
        assertFalse(field(bridge, "needsDataRefresh").getBoolean(bridge));
        assertFalse(field(bridge, "fullSyncPending").getBoolean(bridge));
    }

    private static NetworkInfoDataSet data(long value) {
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        data.addSample(BigInteger.valueOf(value), 100, 1000);
        return data;
    }

    private static void replace(EUCacheBridge bridge, NetworkInfoDataSet next) throws Exception {
        Method replace = EUCacheBridge.class.getDeclaredMethod("replaceOwnerDataSet", NetworkInfoDataSet.class);
        replace.setAccessible(true);
        replace.invoke(bridge, next);
    }

    private static Field field(EUCacheBridge bridge, String name) throws Exception {
        Field field = EUCacheBridge.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
