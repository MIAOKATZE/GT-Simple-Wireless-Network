package com.miaokatze.gtswn.common.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.junit.Test;

public class HudLifecycleTest {

    private static final String OWNER = UUID.randomUUID()
        .toString();

    @Test
    public void sameDimensionNewSaveWithLowerTickClearsEverythingAndCanScanImmediately() throws Exception {
        HudController controller = new HudController();
        Object oldWorld = new Object(), oldPlayer = new Object(), oldConnection = new Object();
        controller.synchronizeSession(oldWorld, oldPlayer, oldConnection);
        HudState state = state(controller);
        seed(state);
        assertTrue(controller.synchronizeSession(new Object(), new Object(), new Object()));
        assertCleared(state);
        assertTrue(0 - state.lastInventoryCheckTick >= 20);
        assertTrue(0 - state.lastUpdateTick >= 50);
        state.selectMonitor(null, null, 0);
        assertCleared(state);
    }

    @Test
    public void disconnectClearsWithoutAnyFurtherRenderedFrameAndLateOldDisconnectDoesNotClearNewSession()
        throws Exception {
        HudController controller = new HudController();
        Object oldConnection = new Object();
        controller.synchronizeSession(new Object(), new Object(), oldConnection);
        HudState state = state(controller);
        seed(state);
        assertTrue(controller.disconnect(oldConnection));
        assertCleared(state);
        Object nextConnection = new Object();
        controller.synchronizeSession(new Object(), new Object(), nextConnection);
        seed(state);
        assertFalse(controller.disconnect(oldConnection));
        assertTrue(state.hudEnabled);
        assertEquals("900", state.syncedEuStr);
    }

    @Test
    public void sameConnectionDimensionSwitchRetainsHistoryOnlyAfterInventoryConfirmsSameOwner() throws Exception {
        HudController controller = new HudController();
        Object oldWorld = new Object(), connection = new Object();
        controller.synchronizeSession(oldWorld, new Object(), connection);
        HudState state = state(controller);
        seed(state);
        assertTrue(controller.unloadWorld(oldWorld));
        assertFalse(state.hudEnabled);
        assertNull(state.monitorStack);
        assertNull(state.cachedOwnerUUID);
        assertNull(state.syncedEuStr);
        assertEquals("", state.cachedEUTText);
        assertEquals(2, state.dataSet.size());
        controller.synchronizeSession(new Object(), new Object(), connection);
        assertFalse(controller.unloadWorld(oldWorld));
        state.selectMonitor(new ItemStack(new Item()), OWNER, 1);
        assertTrue(state.hudEnabled);
        assertEquals(2, state.dataSet.size());
        assertNull(state.syncedEuStr);
    }

    @Test
    public void dimensionArrivalWithoutMonitorOrWithDifferentOwnerCannotReuseHistory() throws Exception {
        for (boolean absent : new boolean[] { true, false }) {
            HudController controller = new HudController();
            Object connection = new Object();
            controller.synchronizeSession(new Object(), new Object(), connection);
            HudState state = state(controller);
            seed(state);
            controller.synchronizeSession(new Object(), new Object(), connection);
            state.selectMonitor(
                null,
                absent ? null
                    : UUID.randomUUID()
                        .toString(),
                absent ? 0 : 1);
            assertEquals(0, state.dataSet.size());
            assertNull(state.syncedEuStr);
            assertEquals("", state.cachedEUTText);
            assertEquals("", state.cachedRealtimeEUTText);
            assertEquals(!absent, state.hudEnabled);
        }
    }

    @Test
    public void noMonitorAndOwnerSwitchClearOldNumericAndPowerTextEvenWithinSameSession() {
        HudState state = new HudState();
        seed(state);
        state.selectMonitor(
            null,
            UUID.randomUUID()
                .toString(),
            1);
        assertNull(state.syncedEuStr);
        assertFalse(state.cachedEUText.contains("900"));
        assertEquals("", state.cachedEUTText);
        assertEquals("", state.cachedRealtimeEUTText);
        state.selectMonitor(null, null, 0);
        assertCleared(state);
    }

    private static void seed(HudState state) {
        state.selectMonitor(new ItemStack(new Item()), OWNER, 1);
        state.syncedEuStr = "900";
        state.cachedEUText = "old balance 900";
        state.cachedEUTText = "old average 100";
        state.cachedRealtimeEUTText = "old instant 200";
        state.dataSet.add(BigInteger.valueOf(800), 100000);
        state.dataSet.add(BigInteger.valueOf(900), 100050);
        state.lastInventoryCheckTick = 100050;
        state.lastUpdateTick = 100050;
        state.lastUpdateRealTimeMs = System.currentTimeMillis();
    }

    private static void assertCleared(HudState state) {
        assertFalse(state.hudEnabled);
        assertEquals(0, state.displayMode);
        assertNull(state.monitorStack);
        assertNull(state.cachedOwnerUUID);
        assertNull(state.parsedOwnerUuid);
        assertNull(state.historyOwnerUUID);
        assertNull(state.syncedEuStr);
        assertFalse(state.cachedEUText.contains("900"));
        assertEquals("", state.cachedEUTText);
        assertEquals("", state.cachedRealtimeEUTText);
        assertEquals(0, state.dataSet.size());
        assertEquals(0, state.lastUpdateRealTimeMs);
    }

    private static HudState state(HudController controller) throws Exception {
        Field field = HudController.class.getDeclaredField("state");
        field.setAccessible(true);
        return (HudState) field.get(controller);
    }
}
