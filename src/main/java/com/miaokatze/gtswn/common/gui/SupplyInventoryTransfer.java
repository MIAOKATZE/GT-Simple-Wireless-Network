package com.miaokatze.gtswn.common.gui;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/** The container's bounded inventory transactions, shared with focused behavior tests. */
public final class SupplyInventoryTransfer {

    private SupplyInventoryTransfer() {}

    public static boolean matches(ItemStack stack, ItemStack reference) {
        return stack != null && reference != null
            && stack.stackSize > 0
            && stack.getItem() == reference.getItem()
            && stack.getItemDamage() == reference.getItemDamage()
            && ItemStack.areItemStackTagsEqual(stack, reference);
    }

    public static int deposit(IInventory inventory, int slot, ItemStack reference, int count, int lockedSlot) {
        if (slot < 0 || slot >= Math.min(36, inventory.getSizeInventory()) || slot == lockedSlot) return count;
        ItemStack source = inventory.getStackInSlot(slot);
        if (!matches(source, reference)) return count;
        int moved = Math.min(Math.min(64, source.stackSize), Math.max(0, TerminalSupplyStore.CAPACITY - count));
        if (moved == 0) return count;
        inventory.decrStackSize(slot, moved);
        inventory.markDirty();
        return count + moved;
    }

    public static int withdraw(IInventory inventory, ItemStack reference, int count, int lockedSlot) {
        if (reference == null || count <= 0) return count;
        int remaining = Math.min(count, Math.min(64, reference.getMaxStackSize()));
        int requested = remaining;
        int slots = Math.min(36, inventory.getSizeInventory());
        // Fill compatible stacks before occupying empty slots, as InventoryPlayer does.
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            for (int slot = 0; slot < slots && remaining > 0; slot++) {
                if (slot == lockedSlot || !inventory.isItemValidForSlot(slot, reference)) continue;
                ItemStack target = inventory.getStackInSlot(slot);
                if ((pass == 0 && !matches(target, reference)) || (pass == 1 && target != null)) continue;
                int limit = Math.min(64, Math.min(reference.getMaxStackSize(), inventory.getInventoryStackLimit()));
                int moved = Math.min(remaining, Math.max(0, limit - (target == null ? 0 : target.stackSize)));
                if (moved == 0) continue;
                if (target == null) {
                    target = reference.copy();
                    target.stackSize = moved;
                    inventory.setInventorySlotContents(slot, target);
                } else {
                    target.stackSize += moved;
                }
                remaining -= moved;
            }
        }
        if (remaining != requested) inventory.markDirty();
        return count - requested + remaining;
    }
}
