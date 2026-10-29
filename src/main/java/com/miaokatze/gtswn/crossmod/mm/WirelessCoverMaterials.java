package com.miaokatze.gtswn.crossmod.mm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.common.items.GTSwnCoverDynamoWireless;
import com.miaokatze.gtswn.common.items.GTSwnCoverEnergyWireless;
import com.miaokatze.gtswn.common.util.LaserHatchUtil;

import gregtech.api.GregTechAPI;

/** One vacuum pipe per newly installed wireless cover on a laser hatch, including replacements. */
public final class WirelessCoverMaterials {

    private WirelessCoverMaterials() {}

    public static boolean isWireless(ItemStack stack) {
        return stack != null && isWirelessItem(stack.getItem());
    }

    private static boolean isWirelessItem(Item item) {
        return item instanceof GTSwnCoverEnergyWireless || item instanceof GTSwnCoverDynamoWireless;
    }

    /** Virtual covers are neither a cost nor salvage; preserve every ordinary stack without mutating the caller. */
    public static <T> List<T> withoutVirtualCovers(List<T> stacks, Function<T, Item> itemOf) {
        List<T> ordinary = new ArrayList<>(stacks.size());
        for (T stack : stacks) {
            if (stack == null || !isWirelessItem(itemOf.apply(stack))) ordinary.add(stack);
        }
        return ordinary;
    }

    public static boolean isLaserMachine(Block block, int metadata) {
        return block == GregTechAPI.sBlockMachines && metadata >= 0
            && metadata < GregTechAPI.METATILEENTITIES.length
            && LaserHatchUtil.isLaserHatch(GregTechAPI.METATILEENTITIES[metadata]);
    }

    public static int requiredTubes(boolean laser, ItemStack[] expected, ItemStack[] existing) {
        if (!laser || expected == null) return 0;
        int count = 0;
        for (int side = 0; side < expected.length; side++) {
            ItemStack actual = existing == null || side >= existing.length ? null : existing[side];
            if (isWireless(expected[side]) && !ItemStack.areItemStacksEqual(expected[side], actual)) count++;
        }
        return count;
    }
}
