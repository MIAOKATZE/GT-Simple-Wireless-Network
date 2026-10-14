package com.miaokatze.gtswn.common.quantum;

import net.minecraft.item.ItemStack;

import appeng.api.implementations.parts.IPartCable;
import appeng.api.parts.BusSupport;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartItem;
import appeng.parts.networking.PartQuartzFiber;

/** One placement rule for the host and direct AE2 container entry points. */
public final class QuantumNodePartRules {

    private QuantumNodePartRules() {}

    public static boolean conflicts(IPart part) {
        return part instanceof IPartCable || part instanceof PartQuartzFiber
            || (part != null && !part.canBePlacedOn(BusSupport.CABLE));
    }

    public static boolean conflicts(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof IPartItem)) return false;
        ItemStack probe = stack.copy();
        probe.stackSize = 1;
        return conflicts(((IPartItem) stack.getItem()).createPartFromItemStack(probe));
    }
}
