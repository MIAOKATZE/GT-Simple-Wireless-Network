package com.miaokatze.gtswn.common.util;

import static org.junit.Assert.*;

import java.math.BigInteger;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class EUDataSetRestoreTest {

    @Test
    public void missingSilentEndpointsCannotCrashTheNextMeasurement() {
        NBTTagCompound tag = savedSilentHistory();
        tag.getCompoundTag("history")
            .removeTag("m0");
        tag.getCompoundTag("history")
            .removeTag("m1");
        EUDataSet restored = restore(tag);
        restored.add(BigInteger.TEN, 6100);
        assertFalse(restored.isLongTermSilent());
        assertEquals(1, restored.size());
    }

    @Test
    public void invalidEndpointCannotLeaveAOnePointSilentFastPath() {
        NBTTagCompound tag = savedSilentHistory();
        tag.getCompoundTag("history")
            .getCompoundTag("m1")
            .setString("value", "invalid");
        EUDataSet restored = restore(tag);
        restored.add(BigInteger.TEN, 6100);
        assertFalse(restored.isLongTermSilent());
        assertEquals(2, restored.size());
    }

    @Test
    public void unequalEndpointsAreNotRestoredAsSilent() {
        NBTTagCompound tag = savedSilentHistory();
        tag.getCompoundTag("history")
            .getCompoundTag("m0")
            .setString("value", "0");
        EUDataSet restored = restore(tag);
        assertFalse(restored.isLongTermSilent());
        restored.add(BigInteger.TEN, 6100);
        assertEquals(3, restored.size());
    }

    @Test
    public void extraRowsCannotFreezeTheLastSampleBehindTheSilentFastPath() {
        NBTTagCompound tag = savedSilentHistory();
        NBTTagCompound history = tag.getCompoundTag("history");
        history.setInteger("count", 3);
        NBTTagCompound extra = (NBTTagCompound) history.getCompoundTag("m1")
            .copy();
        extra.setLong("tick", 6100);
        history.setTag("m2", extra);
        EUDataSet restored = restore(tag);
        assertFalse(restored.isLongTermSilent());
        restored.add(BigInteger.TEN, 6200);
        assertEquals(
            6200,
            restored.snapshot()
                .get(restored.size() - 1).tick);
    }

    @Test
    public void validSilentHistoryStillResumesItsCompressedWindow() {
        EUDataSet restored = restore(savedSilentHistory());
        assertTrue(restored.isLongTermSilent());
        restored.add(BigInteger.TEN, 6100);
        assertEquals(2, restored.size());
        assertEquals(
            6100,
            restored.snapshot()
                .get(1).tick);
        restored.add(BigInteger.valueOf(20), 6200);
        assertFalse(restored.isLongTermSilent());
        assertEquals(0.1, restored.calculateRecentEUT(), 0.0);
    }

    private static NBTTagCompound savedSilentHistory() {
        EUDataSet history = new EUDataSet();
        history.add(BigInteger.TEN, 0);
        history.add(BigInteger.TEN, 100);
        history.add(BigInteger.TEN, 6000);
        NBTTagCompound saved = new NBTTagCompound();
        history.saveToNBT(saved, "history");
        return saved;
    }

    private static EUDataSet restore(NBTTagCompound tag) {
        EUDataSet restored = new EUDataSet();
        restored.loadFromNBT(tag, "history");
        return restored;
    }
}
