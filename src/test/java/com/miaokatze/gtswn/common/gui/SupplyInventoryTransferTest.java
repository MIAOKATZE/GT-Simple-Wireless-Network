package com.miaokatze.gtswn.common.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class SupplyInventoryTransferTest {

    private final Item supply = new Item().setMaxStackSize(64);
    private final Item other = new Item().setMaxStackSize(64);

    private ItemStack reference() {
        return new ItemStack(supply, 1, 0);
    }

    private InventoryBasic inventory() {
        return new InventoryBasic("test", false, 36);
    }

    @Test
    public void shiftDepositStopsAtCapacityAndTransfersAtMost64() {
        InventoryBasic inventory = inventory();
        ItemStack oversized = new ItemStack(supply, 128, 0);
        // Direct insertion models another mod's existing oversized inventory stack.
        inventory.setInventorySlotContents(0, oversized);
        oversized.stackSize = 128;
        assertEquals(64, SupplyInventoryTransfer.deposit(inventory, 0, reference(), 0, 8));
        assertEquals(64, inventory.getStackInSlot(0).stackSize);
        assertEquals(640, SupplyInventoryTransfer.deposit(inventory, 0, reference(), 639, 8));
        assertEquals(63, inventory.getStackInSlot(0).stackSize);
        assertEquals(640, SupplyInventoryTransfer.deposit(inventory, 0, reference(), 640, 8));
        assertEquals(63, inventory.getStackInSlot(0).stackSize);
    }

    @Test
    public void shiftWithdrawTransfersAtMost64AndClearsExactRemainder() {
        InventoryBasic inventory = inventory();
        assertEquals(576, SupplyInventoryTransfer.withdraw(inventory, reference(), 640, 8));
        assertEquals(64, inventory.getStackInSlot(0).stackSize);
        assertEquals(0, SupplyInventoryTransfer.withdraw(inventory, reference(), 7, 8));
        assertEquals(7, inventory.getStackInSlot(1).stackSize);
    }

    @Test
    public void fullInventoryIsLosslessAndPartialSpaceConsumesOnlyAcceptedItems() {
        InventoryBasic inventory = inventory();
        for (int i = 0; i < 36; i++) inventory.setInventorySlotContents(i, new ItemStack(other, 64, 0));
        assertEquals(640, SupplyInventoryTransfer.withdraw(inventory, reference(), 640, 8));
        for (int i = 0; i < 36; i++) assertEquals(64, inventory.getStackInSlot(i).stackSize);
        inventory.setInventorySlotContents(3, new ItemStack(supply, 62, 0));
        assertEquals(638, SupplyInventoryTransfer.withdraw(inventory, reference(), 640, 8));
        assertEquals(64, inventory.getStackInSlot(3).stackSize);
    }

    @Test
    public void wrongItemDamageAndNbtAreRejectedWithoutMutation() {
        InventoryBasic inventory = inventory();
        ItemStack[] invalid = { new ItemStack(other, 64, 0), new ItemStack(supply, 64, 1),
            new ItemStack(supply, 64, 0) };
        invalid[2].setTagCompound(new NBTTagCompound());
        invalid[2].getTagCompound()
            .setString("displayName", "custom");
        for (ItemStack stack : invalid) {
            inventory.setInventorySlotContents(0, stack);
            assertEquals(5, SupplyInventoryTransfer.deposit(inventory, 0, reference(), 5, 8));
            assertSame(stack, inventory.getStackInSlot(0));
            assertEquals(64, stack.stackSize);
        }
    }

    @Test
    public void heldSlotCannotBeDepositedIntoOrUsedForWithdrawal() {
        InventoryBasic inventory = inventory();
        inventory.setInventorySlotContents(8, new ItemStack(supply, 64, 0));
        assertEquals(0, SupplyInventoryTransfer.deposit(inventory, 8, reference(), 0, 8));
        assertEquals(64, inventory.getStackInSlot(8).stackSize);
        for (int i = 0; i < 36; i++) if (i != 8) inventory.setInventorySlotContents(i, new ItemStack(other, 64, 0));
        inventory.setInventorySlotContents(8, null);
        assertEquals(640, SupplyInventoryTransfer.withdraw(inventory, reference(), 640, 8));
        assertNull(inventory.getStackInSlot(8));
        assertEquals(640, SupplyInventoryTransfer.withdraw(inventory, null, 640, 8));
    }
}
