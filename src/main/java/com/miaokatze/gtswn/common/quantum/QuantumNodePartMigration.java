package com.miaokatze.gtswn.common.quantum;

import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartItem;
import appeng.api.parts.PartItemStack;
import appeng.parts.networking.PartQuartzFiber;

/** Remove only the old cable slot and quartz fiber, using AE2's normal break drops. */
public final class QuantumNodePartMigration {

    private QuantumNodePartMigration() {}

    public static NBTTagCompound stripConflicts(NBTTagCompound saved, List<ItemStack> recovered) {
        NBTTagCompound result = (NBTTagCompound) saved.copy();
        for (ForgeDirection side : ForgeDirection.values()) {
            String definitionKey = "def:" + side.ordinal();
            if (!result.hasKey(definitionKey)) continue;
            ItemStack stack = ItemStack.loadItemStackFromNBT(result.getCompoundTag(definitionKey));
            if (stack == null) continue;
            IPart part = stack.getItem() instanceof IPartItem
                ? ((IPartItem) stack.getItem()).createPartFromItemStack(stack.copy())
                : null;
            if (side != ForgeDirection.UNKNOWN && !(part instanceof PartQuartzFiber)) continue;
            // Instantiate only the removed part's saved state. Every remaining side definition
            // and inventory is still read exactly once by the original CableBusContainer.
            if (part != null) {
                part.readFromNBT(result.getCompoundTag("extra:" + side.ordinal()));
                collectDrops(part, recovered);
            } else {
                recovered.add(stack.copy());
            }
            result.removeTag(definitionKey);
            result.removeTag("extra:" + side.ordinal());
        }
        return result;
    }

    public static void collectDrops(IPart part, List<ItemStack> recovered) {
        ItemStack stack = part.getItemStack(PartItemStack.Break);
        if (stack != null) recovered.add(stack.copy());
        java.util.ArrayList<ItemStack> contents = new java.util.ArrayList<>();
        part.getDrops(contents, false);
        for (ItemStack content : contents) {
            if (content != null) recovered.add(content.copy());
        }
    }
}
