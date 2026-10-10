package com.miaokatze.gtswn.common.gui;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class TerminalSupplyStoreTest {

    @Test
    public void fullSuppliesSurviveDiskSerializationAndDeathPersistentCopy() throws Exception {
        NBTTagCompound persisted = new NBTTagCompound();
        persisted.setString("otherMod", "retain");
        TerminalSupplyStore.set(persisted, TerminalSupplyStore.Kind.ANCHOR, 640);
        TerminalSupplyStore.set(persisted, TerminalSupplyStore.Kind.TUBE, 129);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(persisted, bytes);
        NBTTagCompound restored = CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes.toByteArray()));
        NBTTagCompound clone = (NBTTagCompound) restored.copy();
        assertEquals(640, TerminalSupplyStore.count(clone, TerminalSupplyStore.Kind.ANCHOR));
        assertEquals(129, TerminalSupplyStore.count(clone, TerminalSupplyStore.Kind.TUBE));
        assertEquals("retain", clone.getString("otherMod"));
        TerminalSupplyStore.set(clone, TerminalSupplyStore.Kind.ANCHOR, 639);
        assertEquals(640, TerminalSupplyStore.count(restored, TerminalSupplyStore.Kind.ANCHOR));
        assertEquals(129, TerminalSupplyStore.count(clone, TerminalSupplyStore.Kind.TUBE));
    }

    @Test
    public void malformedCountsCannotCreateNegativeOrOversizedSupplies() {
        NBTTagCompound persisted = new NBTTagCompound();
        persisted.setInteger("GTSWN_Supply_ANCHOR", Integer.MAX_VALUE);
        persisted.setInteger("GTSWN_Supply_TUBE", Integer.MIN_VALUE);
        assertEquals(640, TerminalSupplyStore.count(persisted, TerminalSupplyStore.Kind.ANCHOR));
        assertEquals(0, TerminalSupplyStore.count(persisted, TerminalSupplyStore.Kind.TUBE));
        TerminalSupplyStore.set(persisted, TerminalSupplyStore.Kind.ANCHOR, -1);
        TerminalSupplyStore.set(persisted, TerminalSupplyStore.Kind.TUBE, 641);
        assertEquals(0, TerminalSupplyStore.count(persisted, TerminalSupplyStore.Kind.ANCHOR));
        assertEquals(640, TerminalSupplyStore.count(persisted, TerminalSupplyStore.Kind.TUBE));
    }
}
