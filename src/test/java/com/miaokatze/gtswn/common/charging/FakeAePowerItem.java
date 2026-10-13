package com.miaokatze.gtswn.common.charging;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import appeng.api.config.AccessRestriction;
import appeng.api.implementations.items.IAEItemPowerStorage;

/** AE's native storage remains available when its optional IC2/RF interfaces are stripped. */
class FakeAePowerItem extends Item implements IAEItemPowerStorage {

    double capacity = 1000;
    double acceptance = Double.MAX_VALUE;
    AccessRestriction flow = AccessRestriction.WRITE;
    int injections;

    @Override
    public double injectAEPower(ItemStack stack, double amount) {
        injections++;
        double accepted = Math.min(amount, Math.min(acceptance, capacity - getAECurrentPower(stack)));
        if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
        stack.getTagCompound()
            .setDouble("ae", getAECurrentPower(stack) + accepted);
        return amount - accepted;
    }

    @Override
    public double extractAEPower(ItemStack stack, double amount) {
        return 0;
    }

    @Override
    public double getAEMaxPower(ItemStack stack) {
        return capacity;
    }

    @Override
    public double getAECurrentPower(ItemStack stack) {
        return stack.hasTagCompound() ? stack.getTagCompound()
            .getDouble("ae") : 0;
    }

    @Override
    public AccessRestriction getPowerFlow(ItemStack stack) {
        return flow;
    }
}
