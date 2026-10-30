package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.charging.MonitorBattery;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;

import gregtech.common.items.ItemGTToolbox;
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

    @Test
    public void allowsOnlyInstalledMonitorRuntimeChangesWithoutMutatingInputs() {
        ItemStack current = monitor();
        ItemStack incoming = current.copy();
        MonitorBattery.data(incoming)
            .setDouble("Charge", 900);
        MonitorBattery.data(incoming)
            .setInteger("Status", 2);
        MonitorBattery.data(incoming)
            .setInteger("Quiet", 20);
        assertTrue(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        assertTrue(
            MonitorBattery.data(current)
                .getDouble("Charge") == 1000);
        assertTrue(
            MonitorBattery.data(incoming)
                .getDouble("Charge") == 900);
        assertTrue(
            MonitorBattery.data(current)
                .getInteger("Quiet") == 0);
        assertTrue(
            MonitorBattery.data(incoming)
                .getInteger("Quiet") == 20);
    }

    @Test
    public void exactChargeCanChangeWhileItsDoubleProjectionIsUnchanged() {
        ItemStack current = monitor();
        MonitorBattery.data(current).setDouble("Charge", (double) Long.MAX_VALUE);
        MonitorBattery.data(current).setString("ChargeExact", "9223372036854775807");
        ItemStack incoming = current.copy();
        MonitorBattery.data(incoming).setString("ChargeExact", "9223372036854775775");
        assertTrue(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        MonitorBattery.data(incoming).setString("CapacityExact", "9223372036854775807");
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
    }

    @Test
    public void eachMonitorRuntimeFieldCanSyncIndependently() {
        ItemStack current = monitor();
        for (String key : new String[] { "Charge", "Status", "Quiet" }) {
            ItemStack incoming = current.copy();
            MonitorBattery.data(incoming)
                .removeTag(key);
            assertTrue(key, ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        }
    }

    @Test
    public void rejectsInstalledBatteryAndMonitorIdentityChanges() {
        ItemStack current = monitor();
        for (String key : new String[] { "Capacity", "Tier", "custom" }) {
            ItemStack incoming = current.copy();
            MonitorBattery.data(incoming)
                .setInteger(key, 99);
            assertFalse(key, ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        }
        ItemStack changedBattery = current.copy();
        MonitorBattery.data(changedBattery)
            .getCompoundTag("Item")
            .setString("id", "otherBattery");
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, changedBattery));
        ItemStack removed = current.copy();
        removed.getTagCompound()
            .removeTag(MonitorBattery.NBT_BATTERY);
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, removed));
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(removed, current));
        for (String key : new String[] { PortableWirelessNetworkMonitor.NBT_OWNER_UUID,
            PortableWirelessNetworkMonitor.NBT_HUD_MODE }) {
            ItemStack incoming = current.copy();
            incoming.getTagCompound()
                .setString(key, "changed");
            assertFalse(key, ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        }
    }

    @Test
    public void doesNotIgnoreNestedBatteryOrToolboxContentsForOtherElectricItems() {
        ItemStack current = stack();
        current.getTagCompound()
            .setTag(
                MonitorBattery.NBT_BATTERY,
                MonitorBattery.data(monitor())
                    .copy());
        ItemStack incoming = current.copy();
        incoming.getTagCompound()
            .getCompoundTag(MonitorBattery.NBT_BATTERY)
            .setDouble("Charge", 1);
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        incoming = current.copy();
        incoming.getTagCompound()
            .setTag(ItemGTToolbox.CONTENTS_KEY, new NBTTagCompound());
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
    }

    private static ItemStack monitor() {
        ItemStack stack = new ItemStack(new PortableWirelessNetworkMonitor());
        stack.setTagCompound(new NBTTagCompound());
        NBTTagCompound data = new NBTTagCompound();
        NBTTagCompound battery = new NBTTagCompound();
        battery.setString("id", "battery");
        data.setTag("Item", battery);
        data.setDouble("Capacity", 1000);
        data.setInteger("Tier", 1);
        data.setDouble("Charge", 1000);
        data.setInteger("Status", 1);
        data.setInteger("Quiet", 0);
        stack.getTagCompound()
            .setTag(MonitorBattery.NBT_BATTERY, data);
        return stack;
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
