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
