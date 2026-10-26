package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

public class AEMonitorClockLifecycleTest {

    @Test
    public void rollbackImmediatelyResumesWithoutJoiningRatesOrRollups() {
        AEMonitorDataSet data = new AEMonitorDataSet();
        for (int i = 0; i < 11; i++) data.addSample("item", 100 + i, 9000 + i * 100, i);
        assertTrue(data.tryAcquireSampleLock("item", 100, 100));
        data.addSample("item", 500, 100, 11000);
        assertEquals(1, data.size("item", AEMonitorDataSet.WINDOW_5_MIN));
        assertEquals(0, data.size("item", AEMonitorDataSet.WINDOW_1_HOUR));
        assertEquals(0.0D, data.newest("item").rate, 0.0D);
        assertFalse(data.tryAcquireSampleLock("item", 100, 100));
        assertFalse(data.tryAcquireSampleLock("item", 199, 100));
        assertTrue(data.tryAcquireSampleLock("item", 200, 100));
        data.addSample("item", 510, 200, 11100);
        assertEquals(120.0D, data.averageRate("item"), 0.0D);
        assertEquals(200, data.projectionSampleTick(200));

        NBTTagList archives = data.toNBT()
            .getTagList("clockArchives", 10);
        assertEquals(1, archives.tagCount());
        NBTTagCompound archive = archives.getCompoundTagAt(0);
        assertEquals("clock-rollback", archive.getString("boundary"));
        assertEquals(100, archive.getLong("resumeTick"));
        AEMonitorDataSet old = new AEMonitorDataSet();
        old.readFromNBT(archive);
        assertEquals(11, old.size("item", AEMonitorDataSet.WINDOW_5_MIN));
        assertEquals(10000, old.newest("item").tick);
        assertEquals(110, old.newest("item").amount);
    }

    @Test
    public void controllerDetectedRollbackBetweenSamplesBreaksContinuityToo() {
        AEMonitorDataSet data = new AEMonitorDataSet();
        data.addSample("item", 100, 100, 1000);
        // The controller observed 190, then 150: the old sample itself is not in the future.
        assertTrue(data.resetSampleClock(150));
        assertTrue(data.tryAcquireSampleLock("item", 150, 100));
        data.addSample("item", 500, 150, 1100);
        assertEquals(1, data.sampledKeyCount());
        assertEquals(1, data.size("item", AEMonitorDataSet.WINDOW_5_MIN));
        assertEquals(0.0D, data.averageRate("item"), 0.0D);
    }

    @Test
    public void persistedFutureLockAndAllSeriesResumeInOneNewTimeline() {
        AEMonitorDataSet original = new AEMonitorDataSet();
        original.addSample("a", 10, 10000, 1000);
        original.addSample("b", 20, 10000, 1000);
        AEMonitorDataSet loaded = new AEMonitorDataSet();
        loaded.readFromNBT(original.toNBT());
        assertTrue(loaded.tryAcquireSampleLock("a", 100, 100));
        loaded.addSample("a", 11, 100, 1100);
        assertNull(loaded.newest("b"));
        assertEquals(1, loaded.sampledKeyCount());
        assertTrue(loaded.tryAcquireSampleLock("b", 100, 100));
        loaded.addSample("b", 21, 100, 1100);
        assertEquals(2, loaded.sampledKeyCount());
        assertEquals(
            1,
            loaded.toNBT()
                .getTagList("clockArchives", 10)
                .tagCount());
        AEMonitorDataSet reloaded = new AEMonitorDataSet();
        reloaded.readFromNBT(loaded.toNBT());
        assertEquals(2, reloaded.sampledKeyCount());
        assertEquals(100, reloaded.projectionSampleTick(100));
        assertFalse(reloaded.tryAcquireSampleLock("a", 199, 100));
        assertTrue(reloaded.tryAcquireSampleLock("a", 200, 100));
        assertEquals(
            1,
            reloaded.toNBT()
                .getTagList("clockArchives", 10)
                .tagCount());
    }

    @Test
    public void archiveQuotaPausesWithoutDroppingAnyPriorWindow() {
        AEMonitorDataSet data = new AEMonitorDataSet();
        for (int i = 0; i < AEMonitorDataSet.MAX_CLOCK_ARCHIVES; i++) {
            data.addSample("item", i, 10000, i);
            assertTrue(data.resetSampleClock(100));
        }
        data.addSample("item", 999, 10000, 1000);
        assertFalse(data.resetSampleClock(100));
        assertFalse(data.tryAcquireSampleLock("item", 100, 100));
        data.addSample("item", 0, 100, 1001);
        assertEquals(999, data.newest("item").amount);
        NBTTagCompound saved = data.toNBT();
        assertEquals(
            AEMonitorDataSet.MAX_CLOCK_ARCHIVES,
            saved.getTagList("clockArchives", 10)
                .tagCount());
        assertTrue(saved.getBoolean("clockContinuityPaused"));
        AEMonitorDataSet loaded = new AEMonitorDataSet();
        loaded.readFromNBT(saved);
        assertFalse(loaded.tryAcquireSampleLock("item", 20000, 100));
        assertEquals(999, loaded.newest("item").amount);
    }

    @Test
    public void oldFormatAndDirectWritesStillDetectFutureHistory() {
        AEMonitorDataSet data = new AEMonitorDataSet();
        data.addSample("item", 20, 10000, 1000);
        NBTTagCompound legacy = data.toNBT();
        legacy.removeTag("observedTick");
        legacy.removeTag("clockArchives");
        legacy.removeTag("clockContinuityPaused");
        AEMonitorDataSet loaded = new AEMonitorDataSet();
        loaded.readFromNBT(legacy);
        loaded.addSample("item", 30, 100, 1100);
        assertEquals(1, loaded.size("item", AEMonitorDataSet.WINDOW_5_MIN));
        assertEquals(
            1,
            loaded.toNBT()
                .getTagList("clockArchives", 10)
                .tagCount());
    }
}
