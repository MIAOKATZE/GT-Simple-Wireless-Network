package com.miaokatze.gtswn.common.covers;

import static org.junit.Assert.*;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.google.common.io.ByteStreams;

import gregtech.api.covers.CoverContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class WirelessCoverCurrentTest {

    private CoverContext context() {
        return new CoverContext(null, ForgeDirection.NORTH, null);
    }

    private NBTTagCompound energyNbt() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setInteger("voltage", 32);
        nbt.setInteger("amperage", 3);
        nbt.setLong("capacity", 76800);
        nbt.setLong("storedEU", 10000);
        nbt.setBoolean("configured", true);
        nbt.setLong("ticksSinceLastRefill", 123);
        return nbt;
    }

    @Test
    public void oldIntegerCurrentLoadsAndEditingPreservesEnergyAndClock() {
        GTswn_Cover_EnergyWireless cover = new GTswn_Cover_EnergyWireless(context());
        cover.readDataFromNbt(energyNbt());
        assertEquals(3, cover.getAmperage(), 0);
        cover.setAmperage(0.1);
        NBTTagCompound saved = (NBTTagCompound) cover.saveDataToNbt();
        assertEquals(0.1, saved.getDouble("amperageDecimal"), 0);
        assertEquals(10000, saved.getLong("storedEU"));
        assertEquals(123, saved.getLong("ticksSinceLastRefill"));
        assertTrue(saved.getLong("capacity") < saved.getLong("storedEU"));
        assertEquals(32, saved.getInteger("voltage"));
        cover.setAmperage(Double.NaN);
        cover.setAmperage(0.09);
        assertEquals(0.1, cover.getAmperage(), 0);
    }

    @Test
    public void fractionalEnergyAndDecimalCurrentRoundTripPacket() {
        GTswn_Cover_EnergyWireless source = new GTswn_Cover_EnergyWireless(context());
        NBTTagCompound tag = energyNbt();
        tag.setDouble("amperageDecimal", 0.15);
        tag.setDouble("fractionalEU", 0.8);
        source.readDataFromNbt(tag);
        ByteBuf buffer = Unpooled.buffer();
        try {
            source.writeDataToByteBuf(buffer);
            byte[] data = new byte[buffer.readableBytes()];
            buffer.readBytes(data);
            GTswn_Cover_EnergyWireless target = new GTswn_Cover_EnergyWireless(context());
            target.readDataFromPacket(ByteStreams.newDataInput(data));
            NBTTagCompound saved = (NBTTagCompound) target.saveDataToNbt();
            assertEquals(0.15, target.getAmperage(), 0);
            assertEquals(0.8, saved.getDouble("fractionalEU"), 0);
            assertEquals(10000, saved.getLong("storedEU"));
            assertEquals(123, saved.getLong("ticksSinceLastRefill"));
        } finally {
            buffer.release();
        }
    }

    @Test
    public void oldDynamoStaysOnHostCurrentUntilEditedAndPersistsNewLimit() {
        GTswn_Cover_DynamoWireless cover = new GTswn_Cover_DynamoWireless(context());
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("configured", true);
        tag.setLong("storedEU", 900);
        tag.setLong("ticksSinceLastUpload", 42);
        cover.readDataFromNbt(tag);
        assertFalse(((NBTTagCompound) cover.saveDataToNbt()).hasKey("amperageDecimal"));
        cover.setAmperage(0.25);
        NBTTagCompound saved = (NBTTagCompound) cover.saveDataToNbt();
        assertEquals(900, saved.getLong("storedEU"));
        assertEquals(42, saved.getLong("ticksSinceLastUpload"));
        GTswn_Cover_DynamoWireless restored = new GTswn_Cover_DynamoWireless(context());
        restored.readDataFromNbt(saved);
        assertEquals(0.25, restored.getAmperage(), 0);
    }
}
