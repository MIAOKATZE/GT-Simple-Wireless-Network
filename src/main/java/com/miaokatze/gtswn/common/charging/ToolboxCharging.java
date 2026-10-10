package com.miaokatze.gtswn.common.charging;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

import gregtech.api.enums.ToolboxSlot;
import gregtech.common.items.ItemGTToolbox;
import gregtech.common.items.toolbox.ToolboxItemStackHandler;
import gregtech.common.items.toolbox.ToolboxUtil;
import ic2.api.item.IElectricItem;
import ic2.api.item.ISpecialElectricItem;

/** Only GT's dedicated toolbox is supported; no arbitrary nested inventory traversal. */
final class ToolboxCharging {

    static final int INTERVAL = 20;
    static final int BOXES_PER_PASS = 2;

    interface Inventory {

        int slots();

        int batterySlot();

        ItemStack get(int slot);

        boolean isToolbox(ItemStack stack);

        void save();
    }

    private final Inventory inventory;
    final List<ChargingTarget> targets = new ArrayList<>();

    ToolboxCharging(ItemStack toolbox) {
        this(new Inventory() {

            private final ToolboxItemStackHandler handler = new ToolboxItemStackHandler(toolbox);

            public int slots() {
                return handler.getSlots();
            }

            public int batterySlot() {
                return ToolboxSlot.BATTERY.getSlotID();
            }

            public ItemStack get(int slot) {
                return handler.getStackInSlot(slot);
            }

            public boolean isToolbox(ItemStack stack) {
                return stack.getItem() instanceof ItemGTToolbox;
            }

            public void save() {
                ToolboxUtil.saveToolbox(toolbox, handler);
            }
        });
    }

    ToolboxCharging(Inventory inventory) {
        this.inventory = inventory;
        for (int slot = 0; slot < inventory.slots(); slot++) {
            // The outer toolbox electric manager already charges its battery at the original per-tick rate.
            if (slot == inventory.batterySlot()) continue;
            ItemStack stack = inventory.get(slot);
            if (stack != null && !inventory.isToolbox(stack)
                && (stack.getItem() instanceof IElectricItem || stack.getItem() instanceof ISpecialElectricItem))
                targets.add(new ChargingTarget(stack, INTERVAL));
        }
    }

    static boolean canCharge(ItemStack stack, boolean swinging) {
        return !swinging && ToolboxUtil.canCharge(stack);
    }

    static int selectedBoxes(int count) {
        return Math.min(BOXES_PER_PASS, count);
    }

    static int firstBox(int tick, int count) {
        return Math.floorMod(tick / INTERVAL * BOXES_PER_PASS, count);
    }

    void save() {
        // Handler stacks are detached from toolbox NBT. Persist all charged copies together, once per box.
        inventory.save();
    }
}
