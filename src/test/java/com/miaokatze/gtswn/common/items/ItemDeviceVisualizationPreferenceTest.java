package com.miaokatze.gtswn.common.items;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class ItemDeviceVisualizationPreferenceTest {

    @Test
    public void preferenceSurvivesInventoryStackCopyAndExplicitDisableWithoutChangingIdentity() {
        ItemDeviceInfoTerminal item = new ItemDeviceInfoTerminal();
        ItemStack stack = new ItemStack(item);
        UUID id = ItemDeviceInfoTerminal.getOrCreateTerminalId(stack);
        assertFalse(ItemDeviceInfoTerminal.isVisualizationEnabled(stack));
        assertFalse(item.hasEffect(stack, 0));
        ItemDeviceInfoTerminal.setVisualizationEnabled(stack, true);
        ItemStack synchronizedCopy = stack.copy();
        assertTrue(ItemDeviceInfoTerminal.isVisualizationEnabled(synchronizedCopy));
        assertTrue(item.hasEffect(synchronizedCopy, 0));
        assertEquals(id, ItemDeviceInfoTerminal.readTerminalId(synchronizedCopy));
        ItemDeviceInfoTerminal.setVisualizationEnabled(synchronizedCopy, false);
        assertFalse(ItemDeviceInfoTerminal.isVisualizationEnabled(synchronizedCopy));
        assertFalse(item.hasEffect(synchronizedCopy, 0));
        assertEquals(id, ItemDeviceInfoTerminal.readTerminalId(synchronizedCopy));
        assertTrue(ItemDeviceInfoTerminal.isVisualizationEnabled(stack));
    }

    @Test
    public void oldStacksRemainOffAndUnrelatedItemCannotEnableDeviceMode() {
        ItemStack old = new ItemStack(new ItemDeviceInfoTerminal());
        assertFalse(ItemDeviceInfoTerminal.isVisualizationEnabled(old));
        assertFalse(ItemDeviceInfoTerminal.isVisualizationEnabled(null));
        ItemStack ordinary = new ItemStack(new Item());
        ordinary.setTagCompound(new NBTTagCompound());
        ordinary.stackTagCompound.setBoolean(ItemDeviceInfoTerminal.NBT_VISUAL_ENABLED, true);
        assertFalse(ItemDeviceInfoTerminal.isVisualizationEnabled(ordinary));
    }

    @Test
    public void multipleTerminalsKeepCurrentUntilAnotherEnabledTerminalIsHeldOrCurrentIsLost() {
        ItemDeviceInfoTerminal item = new ItemDeviceInfoTerminal();
        ItemStack first = new ItemStack(item), second = new ItemStack(item);
        UUID firstId = ItemDeviceInfoTerminal.getOrCreateTerminalId(first);
        UUID secondId = ItemDeviceInfoTerminal.getOrCreateTerminalId(second);
        ItemDeviceInfoTerminal.setVisualizationEnabled(first, true);
        ItemDeviceInfoTerminal.setVisualizationEnabled(second, true);
        ItemStack[] inventory = { new ItemStack(new Item()), first, null, second };
        assertEquals(3, ItemDeviceInfoTerminal.findEnabledTerminalSlot(inventory, 0, secondId));
        assertEquals(1, ItemDeviceInfoTerminal.findEnabledTerminalSlot(inventory, 1, secondId));
        assertEquals(3, ItemDeviceInfoTerminal.findEnabledTerminalSlot(inventory, 3, firstId));
        inventory[3] = null;
        assertEquals(1, ItemDeviceInfoTerminal.findEnabledTerminalSlot(inventory, 0, secondId));
        inventory[2] = inventory[1];
        inventory[1] = null;
        assertEquals(2, ItemDeviceInfoTerminal.findEnabledTerminalSlot(inventory, 0, firstId));
        ItemDeviceInfoTerminal.setVisualizationEnabled(first, false);
        assertEquals(-1, ItemDeviceInfoTerminal.findEnabledTerminalSlot(inventory, 0, firstId));
    }
}
