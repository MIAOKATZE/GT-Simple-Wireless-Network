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
}
