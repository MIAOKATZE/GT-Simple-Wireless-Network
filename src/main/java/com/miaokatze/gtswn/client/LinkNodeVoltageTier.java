package com.miaokatze.gtswn.client;

import com.miaokatze.gtswn.common.util.GTTierUtil;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTETieredMachineBlock;

/** Uses static machine identity available on both sides, never unsynchronized runtime EU values. */
public final class LinkNodeVoltageTier {

    private LinkNodeVoltageTier() {}

    public static int rgb(Object host) {
        if (!(host instanceof IGregTechTileEntity tile)) return -1;
        return GTTierUtil.tierRgb(tier(tile.getMetaTileEntity()));
    }

    static int tier(IMetaTileEntity machine) {
        // Includes laser hatches, whose base tile output voltage is gated off for the ordinary EU network.
        // Non-tiered hosts have no reliable client-side voltage contract; retain the node's type color.
        return machine instanceof MTETieredMachineBlock tiered ? tiered.mTier : -1;
    }
}
