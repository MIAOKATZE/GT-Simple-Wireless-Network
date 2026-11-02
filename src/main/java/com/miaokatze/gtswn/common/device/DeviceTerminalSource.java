package com.miaokatze.gtswn.common.device;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;

/** Actual main-inventory / accessory location, using the existing negative accessory-slot convention. */
public final class DeviceTerminalSource {

    private DeviceTerminalSource() {}

    public static boolean validSlot(int slot) {
        return slot >= 0 && slot < 36 || slot <= -2;
    }

    public static ItemStack stack(EntityPlayer player, int slot) {
        if (slot >= 0 && slot < Math.min(36, player.inventory.mainInventory.length))
            return player.inventory.mainInventory[slot];
        int accessory = -slot - 2;
        IInventory inventory = slot <= -2 ? TerminalBaubles.inventory(player) : null;
        return inventory != null && accessory >= 0 && accessory < inventory.getSizeInventory()
            ? inventory.getStackInSlot(accessory)
            : null;
    }

    public static int location(EntityPlayer player, ItemStack terminal) {
        if (terminal == null) return -1;
        for (int slot = 0; slot < Math.min(36, player.inventory.mainInventory.length); slot++)
            if (player.inventory.mainInventory[slot] == terminal) return slot;
        IInventory accessories = TerminalBaubles.inventory(player);
        if (accessories != null) for (int slot = 0; slot < accessories.getSizeInventory(); slot++)
            if (accessories.getStackInSlot(slot) == terminal) return -slot - 2;
        return -1;
    }

    public static int find(EntityPlayer player, UUID terminal, boolean heldOnly) {
        if (terminal == null) return -1;
        if (heldOnly) return matches(player.getHeldItem(), terminal) ? player.inventory.currentItem : -1;
        for (int slot = 0; slot < Math.min(36, player.inventory.mainInventory.length); slot++)
            if (matches(player.inventory.mainInventory[slot], terminal)) return slot;
        IInventory accessories = TerminalBaubles.inventory(player);
        if (accessories != null) for (int slot = 0; slot < accessories.getSizeInventory(); slot++)
            if (matches(accessories.getStackInSlot(slot), terminal)) return -slot - 2;
        return -1;
    }

    public static boolean matches(ItemStack stack, UUID terminal) {
        return stack != null && stack.stackSize > 0
            && stack.getItem() instanceof ItemDeviceInfoTerminal
            && terminal != null
            && terminal.equals(ItemDeviceInfoTerminal.readTerminalId(stack));
    }
}
