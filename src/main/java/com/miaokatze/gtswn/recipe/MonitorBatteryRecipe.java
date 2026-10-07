package com.miaokatze.gtswn.recipe;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.charging.MonitorBattery;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;

/** Pure dynamic shapeless recipe: exactly one monitor and one rechargeable battery. */
public final class MonitorBatteryRecipe implements IRecipe {

    public static ItemStack[] inputs(InventoryCrafting inventory) {
        ItemStack monitor = null;
        ItemStack battery = null;
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack == null) continue;
            if (stack.getItem() instanceof PortableWirelessNetworkMonitor && monitor == null && stack.stackSize == 1) {
                monitor = stack;
            } else if (MonitorBattery.isBattery(stack) && battery == null) {
                battery = stack;
            } else return null;
        }
        return monitor == null || battery == null ? null : new ItemStack[] { monitor, battery };
    }

    @Override
    public boolean matches(InventoryCrafting inventory, World world) {
        return inputs(inventory) != null;
    }

    @Override
    public ItemStack getCraftingResult(InventoryCrafting inventory) {
        ItemStack[] inputs = inputs(inventory);
        return inputs == null ? null : MonitorBattery.install(inputs[0], inputs[1]);
    }

    @Override
    public int getRecipeSize() {
        return 2;
    }

    @Override
    public ItemStack getRecipeOutput() {
        return GTSWNItemList.Portable_Wireless_Network_Monitor.get(1);
    }
}
