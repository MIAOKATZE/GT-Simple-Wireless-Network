package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.HashSet;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

public class QuantumNetworkColorTest {

    @Test
    public void legacyAndMalformedIndicesKeepPurple() {
        assertEquals(0, QuantumNetworkColor.DEFAULT);
        assertEquals(0, QuantumNetworkColor.normalize(-1));
        assertEquals(0, QuantumNetworkColor.normalize(16));
        assertEquals(0, QuantumNetworkColor.normalize(Integer.MAX_VALUE));
        assertEquals(QuantumNetworkColor.rgb(0), QuantumNetworkColor.rgb(-1));
    }

    @Test
    public void persistedColorsRoundTripAcrossNegativeCoordinatesAndMalformedData() {
        QuantumNetworkColorRegistry registry = new QuantumNetworkColorRegistry("test");
        long first = QuantumControllerRegistry.pack(-200, 64, 33);
        long second = QuantumControllerRegistry.pack(-201, 64, 33);
        registry.recolor(new HashSet<>(Arrays.asList(first, second)), 14);
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        QuantumNetworkColorRegistry restored = new QuantumNetworkColorRegistry("test");
        restored.readFromNBT(saved);
        assertEquals(14, restored.color(-200, 64, 33));
        assertEquals(14, restored.color(-201, 64, 33));
        assertEquals(0, restored.color(0, 0, 0));
        NBTTagCompound bad = new NBTTagCompound();
        bad.setLong("pos", first);
        bad.setInteger("color", 999);
        NBTTagList entries = new NBTTagList();
        entries.appendTag(bad);
        saved.setTag("colors", entries);
        restored.readFromNBT(saved);
        assertEquals(0, restored.color(-200, 64, 33));
        restored.readFromNBT(new NBTTagCompound());
        assertEquals(0, restored.color(-201, 64, 33));
    }

    @Test
    public void explicitFixtureColorResetPreservesOutsideColorsAndOnlyChangesEpochWhenVisible() {
        QuantumNetworkColorRegistry registry = new QuantumNetworkColorRegistry("test");
        long inside = QuantumControllerRegistry.pack(1, 65, 0);
        long outside = QuantumControllerRegistry.pack(80, 65, 0);
        long defaultColor = QuantumControllerRegistry.pack(2, 65, 0);
        registry.recolor(new HashSet<>(Arrays.asList(inside, outside)), 14);
        registry.recolor(new HashSet<>(Arrays.asList(defaultColor)), QuantumNetworkColor.DEFAULT);
        long epoch = QuantumTerminalSyncEpoch.current();
        long bindingEpoch = QuantumTerminalSyncEpoch.bindingEpoch();
        assertTrue(registry.forgetColors(position -> position == inside));
        assertEquals(epoch + 1, QuantumTerminalSyncEpoch.current());
        assertEquals(bindingEpoch, QuantumTerminalSyncEpoch.bindingEpoch());
        assertFalse(registry.forgetColors(position -> position == inside));
        assertTrue(registry.forgetColors(position -> position == defaultColor));
        assertEquals(epoch + 1, QuantumTerminalSyncEpoch.current());
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        assertEquals(
            1,
            saved.getTagList("colors", 10)
                .tagCount());
        QuantumNetworkColorRegistry restored = new QuantumNetworkColorRegistry("test");
        restored.readFromNBT(saved);
        assertEquals(QuantumNetworkColor.DEFAULT, restored.color(1, 65, 0));
        assertEquals(14, restored.color(80, 65, 0));
    }

    @Test
    public void sixteenPaletteEntriesAreDistinctAndValid() {
        HashSet<Integer> colors = new HashSet<>();
        for (int index = 0; index < 16; index++) {
            assertTrue(QuantumNetworkColor.isValid(index));
            assertEquals(index, QuantumNetworkColor.normalize(index));
            colors.add(QuantumNetworkColor.rgb(index));
        }
        assertEquals(16, colors.size());
    }
}
