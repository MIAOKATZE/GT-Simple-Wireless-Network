package com.miaokatze.gtswn.client;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtswn.common.charging.MonitorBattery;

import baubles.api.BaublesApi;
import ic2.api.item.IElectricItem;

/** Preserve the held stack reference for charge-only updates, including full inventory syncs. */
public final class ChargeStackSync {

    private ChargeStackSync() {}

    public static ItemStack mergeHeldCharge(EntityPlayer player, ItemStack current, ItemStack incoming) {
        if (!isChargeOnlyUpdate(current, incoming) || !hasCharger(player)) {
            return incoming;
        }
        // Both mining's currentItemHittingBlock and itemInUse point to this object.
        // Updating it in place retains those references while accepting authoritative server data.
        current.setItemDamage(incoming.getItemDamage());
        current.setTagCompound(
            incoming.hasTagCompound() ? (NBTTagCompound) incoming.getTagCompound()
                .copy() : null);
        current.animationsToGo = incoming.animationsToGo;
        return current;
    }

    static boolean isChargeOnlyUpdate(ItemStack current, ItemStack incoming) {
        if (current == null || incoming == null
            || current.getItem() != incoming.getItem()
            || current.stackSize != incoming.stackSize
            || !(current.getItem() instanceof IElectricItem)) {
            return false;
        }
        if (current.getItemDamage() != incoming.getItemDamage()) {
            if (isGtTool(
                current.getItem()
                    .getClass())) {
                // GT tools reserve the low metadata bit for their charged/empty variant.
                if (current.getItemDamage() / 2 != incoming.getItemDamage() / 2) {
                    return false;
                }
            } else if (!current.getItem()
                .getClass()
                .getName()
                .startsWith("ic2.") || current.getHasSubtypes() || !current.isItemStackDamageable()) {
                    return false;
                }
        }
        return withoutCharge(current).equals(withoutCharge(incoming));
    }

    private static boolean isGtTool(Class<?> type) {
        // Avoid loading the GT tool class and its optional Forestry interfaces for other items.
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if ("gregtech.api.items.MetaGeneratedTool".equals(current.getName())) {
                return true;
            }
        }
        return false;
    }

    private static NBTTagCompound withoutCharge(ItemStack stack) {
        NBTTagCompound tag = stack.hasTagCompound() ? (NBTTagCompound) stack.getTagCompound()
            .copy() : new NBTTagCompound();
        tag.removeTag("GT.ItemCharge");
        tag.removeTag("charge");
        return tag;
    }

    private static boolean hasCharger(EntityPlayer player) {
        for (ItemStack stack : player.inventory.mainInventory) {
            if (MonitorBattery.hasBattery(stack)) {
                return true;
            }
        }
        try {
            IInventory baubles = BaublesApi.getBaubles(player);
            if (baubles != null) {
                for (int i = 0; i < baubles.getSizeInventory(); i++) {
                    if (MonitorBattery.hasBattery(baubles.getStackInSlot(i))) {
                        return true;
                    }
                }
            }
        } catch (NoClassDefFoundError ignored) {
            // Optional Baubles dependency.
        }
        return false;
    }
}
