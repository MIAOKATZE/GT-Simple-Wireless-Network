package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;

public class PacketTerminalBaubleStateTest {

    @Test
    public void borrowedSessionPayloadPreservesCategoryAndCopiesAuthoritativeStack() {
        ItemStack original = new ItemStack(new WirelessEnergyTap());
        original.stackTagCompound = new NBTTagCompound();
        original.stackTagCompound.setInteger("OutputMode", 1);
        PacketTerminalBaubleState state = new PacketTerminalBaubleState(
            PacketTerminalBaubleState.SESSION,
            1,
            6,
            original);
        assertEquals(6, state.heldSlot);
        assertEquals(1, state.selection);
        assertNotSame(original, state.stack);
        assertEquals(1, TerminalBaubles.category(state.stack));
        original.stackTagCompound.setInteger("OutputMode", 2);
        assertEquals(1, state.stack.stackTagCompound.getInteger("OutputMode"));
    }
}
