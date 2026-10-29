package com.miaokatze.gtswn.crossmod.mm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

import com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

import it.unimi.dsi.fastutil.booleans.BooleanObjectImmutablePair;

/** Verify actual MM extraction before committing a non-creative cover or incorporation material. */
public final class CopyMaterialReservation {

    private CopyMaterialReservation() {}

    public static boolean reserve(IPseudoInventory inventory, ItemStack material) {
        if (material == null || material.getItem() == null || material.stackSize <= 0) return false;
        BigItemStack request = BigItemStack.create(material.copy());
        // MM 0.1.59 can report true after a successful simulation even when a real provider under-delivers.
        // Keep our expected stack separate: the inventory API may mutate the submitted request.
        BooleanObjectImmutablePair<List<BigItemStack>> result = inventory
            .tryConsumeItems(Collections.singletonList(request.copy()), 0);
        List<BigItemStack> extracted = result == null ? null : result.right();
        boolean exact = result != null && result.leftBoolean() && extracted != null;
        long remaining = request.getStackSize();
        List<BigItemStack> returned = new ArrayList<>();
        if (extracted != null) {
            for (BigItemStack stack : extracted) {
                if (stack == null || stack.getItem() == null || stack.getStackSize() <= 0) {
                    exact = false;
                    continue;
                }
                BigItemStack received = stack.copy();
                returned.add(received);
                if (!request.isSameType(received) || received.getStackSize() > remaining) exact = false;
                else remaining -= received.getStackSize();
            }
        }
        if (exact && remaining == 0) return true;
        // Return only the authoritative receipt, never the requested amount. A failed reservation does
        // not enter the caller's installation transaction, so its ordinary rollback cannot refund twice.
        if (!returned.isEmpty()) inventory.givePlayerItems(returned);
        return false;
    }
}
