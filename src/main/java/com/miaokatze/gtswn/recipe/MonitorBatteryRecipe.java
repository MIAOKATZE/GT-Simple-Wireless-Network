package com.miaokatze.gtswn.recipe;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.charging.MonitorBattery;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;

/** Pure dynamic shapeless recipe for installing, replacing or removing the battery. */
public final class MonitorBatteryRecipe implements IRecipe {

    public static ItemStack[] inputs(InventoryCrafting inventory) {
        ItemStack monitor = null;
        ItemStack battery = null;
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack == null) continue;
            if (stack.getItem() instanceof PortableWirelessNetworkMonitor && monitor == null && stack.stackSize == 1) {
                monitor = stack;
            } else if (battery == null && (MonitorBattery.isBattery(stack) || isCrowbar(stack))) {
                battery = stack;
            } else return null;
        }
        if (monitor == null || battery == null) return null;
        if (!MonitorBattery.isBattery(battery) && !MonitorBattery.hasBattery(monitor)) return null;
        return new ItemStack[] { monitor, battery };
    }

    public static boolean isCrowbar(ItemStack stack) {
        if (stack == null || stack.stackSize != 1) return false;
        boolean gtTool = false;
        for (Class<?> type = stack.getItem()
            .getClass(); type != null; type = type.getSuperclass()) {
            if ("gregtech.api.items.MetaGeneratedTool".equals(type.getName())) {
                gtTool = true;
                break;
            }
        }
        if (!gtTool) return false;
        // GT's usability check may initialise tool NBT, so inspect a detached copy during preview.
        ItemStack probe = stack.copy();
        for (int id : OreDictionary.getOreIDs(probe)) {
            if ("craftingToolCrowbar".equals(OreDictionary.getOreName(id))) return probe.getItem()
                .hasContainerItem(probe);
        }
        return false;
    }

    @Override
    public boolean matches(InventoryCrafting inventory, World world) {
        return inputs(inventory) != null;
    }

    @Override
    public ItemStack getCraftingResult(InventoryCrafting inventory) {
        ItemStack[] inputs = inputs(inventory);
        if (inputs == null) return null;
        if (MonitorBattery.isBattery(inputs[1])) return MonitorBattery.install(inputs[0], inputs[1]);
        return MonitorBattery.withoutBattery(inputs[0]);
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
