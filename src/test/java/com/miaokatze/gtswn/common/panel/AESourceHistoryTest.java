package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.monitoring.SourceKey;

public class AESourceHistoryTest {

    @Test
    public void nativeBoundaryAndPanelReplacementNeverReuseCoordinateHistory() {
        UUID epoch = UUID.randomUUID(), panel = UUID.randomUUID();
        SourceKey first = new SourceKey(SourceKey.Kind.AE, epoch, "first-grid", 1);
        SourceKey second = new SourceKey(SourceKey.Kind.AE, epoch, "second-grid", 1);
        String oldKey = AEMonitorController.sourceDataKey(0, 1, 2, 3, panel, first);
        String newKey = AEMonitorController.sourceDataKey(0, 1, 2, 3, panel, second);
        assertNotEquals(oldKey, newKey);
        assertNotEquals(oldKey, AEMonitorController.sourceDataKey(0, 1, 2, 3, UUID.randomUUID(), first));
        assertNotEquals(oldKey, AEMonitorController.sourceDataKey(1, 1, 2, 3, panel, first));
        assertEquals(oldKey, AEMonitorController.sourceDataKey(0, 1, 2, 3, panel, first));
        assertNull(AEMonitorController.sourceDataKey(0, 1, 2, 3, panel, null));
    }

    @Test
    public void legacyAndPriorSourcesSurviveNewGenerationAndSerialization() {
        AEMonitorDataStore store = new AEMonitorDataStore("test");
        String legacy = "0:1:2:3";
        String current = AEMonitorController.sourceDataKey(
            0,
            1,
            2,
            3,
            UUID.randomUUID(),
            new SourceKey(SourceKey.Kind.AE, UUID.randomUUID(), "grid", 1));
        store.getOrCreate(legacy)
            .addSample("item", 123, 100, 1000);
        store.getOrCreate(current)
            .addSample("item", 456, 200, 2000);
        NBTTagCompound saved = new NBTTagCompound();
        store.writeToNBT(saved);
        AEMonitorDataStore restored = new AEMonitorDataStore("restored");
        restored.readFromNBT(saved);
        assertEquals(
            123,
            restored.getIfPresent(legacy)
                .newest("item").amount);
        assertEquals(
            456,
            restored.getIfPresent(current)
                .newest("item").amount);
        assertEquals(
            1000,
            restored.getIfPresent(legacy)
                .newest("item").timeMs);
    }

    @Test
    public void boundedProjectionIndexesTrackUpdatesClearsAndRestoredHistories() {
        AEMonitorDataSet data = new AEMonitorDataSet();
        data.addSample("b", 20, 100, 1000);
        data.addSample("a", 10, 100, 1000);
        data.addSample("z", 30, 100, 1000);
        assertEquals(3, data.sampledKeyCount());
        assertEquals(java.util.Arrays.asList("a", "b"), data.sampledKeys(2));
        assertTrue(
            data.sampledKeys(0)
                .isEmpty());
        assertEquals(100, data.projectionSampleTick(200));
        data.addSample("a", 11, 150, 1500);
        data.addSample("z", 31, 200, 2000);
        assertEquals(100, data.projectionSampleTick(200));
        data.clear("b");
        assertEquals(150, data.projectionSampleTick(200));
        assertEquals(0, data.projectionSampleTick(199)); // Future z cannot borrow a's valid timestamp.

        AEMonitorDataSet restored = new AEMonitorDataSet();
        restored.readFromNBT(data.toNBT());
        assertEquals(2, restored.sampledKeyCount());
        assertEquals(java.util.Arrays.asList("a", "z"), restored.sampledKeys(10));
        assertEquals(150, restored.projectionSampleTick(200));
        assertEquals(
            2,
            restored.query("a", 0)
                .size());
        restored.clear("a");
        assertEquals(200, restored.projectionSampleTick(200));
        restored.clear("missing");
        restored.clear();
        assertEquals(0, restored.sampledKeyCount());
        assertEquals(0, restored.projectionSampleTick(200));
    }

}
