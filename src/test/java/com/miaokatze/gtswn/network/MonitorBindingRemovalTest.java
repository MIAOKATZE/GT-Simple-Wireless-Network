package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;

public class MonitorBindingRemovalTest {

    @Test
    public void retryAndStaleIndexNeverDeleteTheScreenShiftedIntoRemovedSlot() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
        ItemStack stack = new ItemStack(new PortableWirelessNetworkMonitor());
        stack.stackTagCompound = new NBTTagCompound();
        stack.stackTagCompound.setBoolean(PortableWirelessNetworkMonitor.NBT_INITIALIZED, true);
        stack.stackTagCompound.setString(
            PortableWirelessNetworkMonitor.NBT_OWNER_UUID,
            UUID.randomUUID()
                .toString());
        NBTTagList bindings = new NBTTagList();
        for (UUID id : new UUID[] { first, second, third }) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("id", id.toString());
            bindings.appendTag(entry);
        }
        stack.stackTagCompound.setTag("MonitorPanels", bindings);
        assertTrue(MonitorBindings.remove(stack, second));
        assertFalse(MonitorBindings.remove(stack, second));
        assertEquals(
            2,
            MonitorBindings.read(stack)
                .size());
        assertEquals(
            third,
            MonitorBindings.read(stack)
                .get(1).panelId);
        assertTrue(MonitorBindings.remove(stack, first));
        assertTrue(MonitorBindings.remove(stack, third));
        assertTrue(
            MonitorBindings.read(stack)
                .isEmpty());
    }
}
