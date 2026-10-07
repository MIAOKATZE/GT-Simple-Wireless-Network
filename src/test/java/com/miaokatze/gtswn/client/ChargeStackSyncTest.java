package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import ic2.api.item.IElectricItem;

/** Guard against treating tool wear, a mode change or a replacement as a charge sync. */
public class ChargeStackSyncTest {

    @Test
    public void allowsGtAndIc2ChargeChangesWithoutMutatingInputs() {
        ItemStack current = stack();
        current.getTagCompound()
            .setLong("GT.ItemCharge", 10L);
        current.getTagCompound()
            .setDouble("charge", 10D);
        ItemStack incoming = current.copy();
        incoming.getTagCompound()
            .setLong("GT.ItemCharge", 42L);
        incoming.getTagCompound()
            .setDouble("charge", 42D);
        assertTrue(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        assertTrue(
            current.getTagCompound()
                .getLong("GT.ItemCharge") == 10L);
        assertTrue(
            incoming.getTagCompound()
                .getLong("GT.ItemCharge") == 42L);
    }

    @Test
    public void rejectsToolWearAndModeChangesAlongsideCharging() {
        ItemStack current = stack();
        NBTTagCompound stats = new NBTTagCompound();
        stats.setLong("Damage", 10L);
        current.getTagCompound()
            .setTag("GT.ToolStats", stats);
        ItemStack worn = current.copy();
        worn.getTagCompound()
            .getCompoundTag("GT.ToolStats")
            .setLong("Damage", 11L);
        worn.getTagCompound()
            .setLong("GT.ItemCharge", 100L);
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, worn));
        ItemStack changedMode = current.copy();
        changedMode.getTagCompound()
            .setInteger("mode", 1);
        changedMode.getTagCompound()
            .setDouble("charge", 100D);
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, changedMode));
    }

    @Test
    public void rejectsDifferentItemCountMetadataAndNonElectricItems() {
        ItemStack current = stack();
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, stack()));
        ItemStack changed = current.copy();
        changed.stackSize = 2;
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, changed));
        changed = current.copy();
        changed.setItemDamage(1);
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, changed));
        ItemStack ordinary = new ItemStack(new Item());
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(ordinary, ordinary.copy()));
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(null, current));
    }

    @Test
    public void allowsChargeTagCreationButRejectsOtherTagCreation() {
        ItemStack current = stack();
        current.setTagCompound(null);
        ItemStack incoming = current.copy();
        incoming.setTagCompound(new NBTTagCompound());
        incoming.getTagCompound()
            .setLong("GT.ItemCharge", 1L);
        assertTrue(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        incoming.getTagCompound()
            .setString("customName", "changed");
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
    }

    private static ItemStack stack() {
        ItemStack stack = new ItemStack(new ElectricTool());
        stack.setTagCompound(new NBTTagCompound());
        return stack;
    }

    private static class ElectricTool extends Item implements IElectricItem {

        @Override
        public boolean canProvideEnergy(ItemStack stack) {
            return false;
        }

        @Override
        public Item getChargedItem(ItemStack stack) {
            return this;
        }

        @Override
        public Item getEmptyItem(ItemStack stack) {
            return this;
        }

        @Override
        public double getMaxCharge(ItemStack stack) {
            return 1000D;
        }

        @Override
        public int getTier(ItemStack stack) {
            return 1;
        }

        @Override
        public double getTransferLimit(ItemStack stack) {
            return 32D;
        }
    }
}
