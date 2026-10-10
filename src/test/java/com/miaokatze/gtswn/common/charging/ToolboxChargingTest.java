package com.miaokatze.gtswn.common.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import gregtech.common.items.ItemGTToolbox;
import ic2.test.FakeRechargeableBattery;

public class ToolboxChargingTest {

    @Test
    public void openBrokenOrSwingingToolboxDoesNotCharge() {
        ItemStack box = new ItemStack(new Item());
        box.setTagCompound(new NBTTagCompound());
        assertTrue(ToolboxCharging.canCharge(box, false));
        assertEquals(false, ToolboxCharging.canCharge(box, true));
        box.getTagCompound()
            .setBoolean(ItemGTToolbox.TOOLBOX_OPEN_KEY, true);
        assertEquals(false, ToolboxCharging.canCharge(box, false));
        box.getTagCompound()
            .removeTag(ItemGTToolbox.TOOLBOX_OPEN_KEY);
        box.getTagCompound()
            .setLong(ItemGTToolbox.BROKEN_TOOL_ANIMATION_END_KEY, 100);
        assertEquals(false, ToolboxCharging.canCharge(box, false));
    }

    @Test
    public void toolWithoutBoxBatteryChargesAndDetachedContentsPersistOnSave() {
        Inventory inventory = new Inventory();
        inventory.stored[4] = new ItemStack(new FakeRechargeableBattery());
        ToolboxCharging box = new ToolboxCharging(inventory);
        assertEquals(1, box.targets.size());
        assertEquals(
            640,
            box.targets.get(0)
                .charge(1000, 1),
            0);
        assertEquals(0, charge(inventory.stored[4]), 0);
        box.save();
        assertEquals(640, charge(inventory.stored[4]), 0);
        assertEquals(1, inventory.saves);
    }

    @Test
    public void batterySlotIsExcludedAndSecondTerminalCannotChargeToolAgain() {
        Inventory inventory = new Inventory();
        inventory.stored[7] = new ItemStack(new FakeRechargeableBattery());
        inventory.stored[4] = new ItemStack(new FakeRechargeableBattery());
        ToolboxCharging box = new ToolboxCharging(inventory);
        assertEquals(1, box.targets.size());
        assertEquals(
            100,
            box.targets.get(0)
                .charge(100, 1),
            0);
        assertEquals(
            0,
            box.targets.get(0)
                .charge(1000, 1),
            0);
        box.save();
        assertEquals(0, charge(inventory.stored[7]), 0);
        assertEquals(100, charge(inventory.stored[4]), 0);
    }

    @Test
    public void twoBoxBudgetRotatesAcrossEveryBox() {
        boolean[] selected = new boolean[43];
        for (int tick = 0; tick < 440; tick += ToolboxCharging.INTERVAL) {
            int first = ToolboxCharging.firstBox(tick, selected.length);
            assertEquals(2, ToolboxCharging.selectedBoxes(selected.length));
            for (int b = 0; b < ToolboxCharging.selectedBoxes(selected.length); b++)
                selected[(first + b) % selected.length] = true;
        }
        for (boolean box : selected) assertTrue(box);
        assertEquals(0, ToolboxCharging.selectedBoxes(0));
        assertEquals(1, ToolboxCharging.selectedBoxes(1));
    }

    private static double charge(ItemStack stack) {
        return MonitorBattery.manager(stack)
            .getCharge(stack);
    }

    /** Models GT's handler API: detached mutable stacks, 14 slots, battery at 7, explicit NBT save. */
    private static class Inventory implements ToolboxCharging.Inventory {

        final ItemStack[] stored = new ItemStack[14];
        final ItemStack[] snapshot = new ItemStack[14];
        int saves;

        @Override
        public int slots() {
            return 14;
        }

        @Override
        public int batterySlot() {
            return 7;
        }

        @Override
        public boolean isToolbox(ItemStack stack) {
            return false;
        }

        @Override
        public ItemStack get(int slot) {
            snapshot[slot] = stored[slot] == null ? null : stored[slot].copy();
            return snapshot[slot];
        }

        @Override
        public void save() {
            saves++;
            for (int slot = 0; slot < 14; slot++) {
                if (slot == 7) continue;
                stored[slot] = snapshot[slot] == null ? null : snapshot[slot].copy();
            }
        }
    }
}
