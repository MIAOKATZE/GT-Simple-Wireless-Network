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

    @Test
    public void numericProjectionSharesOriginalSamplerAndCachesUntilAnAcceptedValueChanges() throws Exception {
        HudController controller = new HudController();
        HudState state = state(controller);
        seed(state);
        // No Item lookup or icon construction belongs to this numeric cache test.
        state.monitorStack = null;
        Field receipt = HudController.class.getDeclaredField("lastResponseMillis");
        receipt.setAccessible(true);
        receipt.setLong(controller, System.currentTimeMillis());
        HudController.WirelessReading first = controller.wirelessReading();
        assertEquals(BigInteger.valueOf(900), first.stored);
        assertEquals(state.dataSet.calculateRecentEUT(), first.history.get(1).eut, 0);
        org.junit.Assert.assertSame(first, controller.wirelessReading());
        org.junit.Assert.assertSame(first.history, state.dataSet.snapshot());
        state.dataSet.add(BigInteger.valueOf(1000), 100100);
        state.syncedEuStr = "1000";
        HudController.WirelessReading next = controller.wirelessReading();
        org.junit.Assert.assertNotSame(first, next);
        assertEquals(2, first.history.size());
        assertEquals(3, next.history.size());
        assertEquals(BigInteger.valueOf(1000), next.stored);
        state.syncedEuStr = "invalid";
        assertNull(controller.wirelessReading());
        assertNull(controller.wirelessReading());
        state.syncedEuStr = "1000";
        receipt.setLong(controller, 1);
        assertNull(controller.wirelessReading());
        receipt.setLong(controller, System.currentTimeMillis());
        state.selectMonitor(
            null,
            UUID.randomUUID()
                .toString(),
            1);
        assertNull(controller.wirelessReading());
    }

    @Test
    public void reopeningFactoryRequiresNewReceiptEvenIfOldTransportAgeIsFresh() throws Exception {
        HudController controller = new HudController();
        HudState state = state(controller);
        seed(state);
        Field time = HudController.class.getDeclaredField("lastResponseMillis");
        time.setAccessible(true);
        time.setLong(controller, System.currentTimeMillis());
        Field revision = HudController.class.getDeclaredField("responseRevision");
        revision.setAccessible(true);
        revision.setLong(controller, 7);
        controller.setFactoryReading(true);
        assertTrue(controller.fresh());
        assertFalse(controller.factoryFresh());
        revision.setLong(controller, 8);
        assertTrue(controller.factoryFresh());
        controller.setFactoryReading(false);
        assertFalse(controller.factoryFresh());
        controller.setFactoryReading(true);
        assertFalse(controller.factoryFresh());
        revision.setLong(controller, 9);
        assertTrue(controller.factoryFresh());
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
