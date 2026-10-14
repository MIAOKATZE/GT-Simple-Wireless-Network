package com.miaokatze.gtswn.common.device;

import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.covers.GTswnCoverWirelessBase;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gtPlusPlus.xmod.gregtech.api.metatileentity.implementations.base.GTPPMultiBlockBase;

/** Identity metadata is sampled outside tick hot paths; association is taken from controller hatch lists. */
public final class DeviceMachineIdentity {

    private DeviceMachineIdentity() {}

    public static byte linkNodeType(IMetaTileEntity controller) {
        if (controller == null) return 0;
        int flags = flags(controller.getBaseMetaTileEntity());
        if (controller instanceof MTEMultiBlockBase) {
            MTEMultiBlockBase multi = (MTEMultiBlockBase) controller;
            flags |= hatchFlags(multi.mEnergyHatches);
            flags |= hatchFlags(multi.mDynamoHatches);
            // GT205 exposes these on the common base. Virtual calls retain TT overrides without
            // inspecting a concrete MTE's unrelated (potentially client-only) method signatures.
            flags |= hatchFlags(multi.getExoticAndNormalEnergyHatchList());
            flags |= hatchFlags(multi.getExoticDynamoHatches());
            if (multi instanceof GTPPMultiBlockBase) {
                GTPPMultiBlockBase<?> gtpp = (GTPPMultiBlockBase<?>) multi;
                flags |= hatchFlags(gtpp.mTecTechEnergyHatches);
                flags |= hatchFlags(gtpp.mTecTechDynamoHatches);
            }
        }
        return (byte) flags;
    }

    private static int hatchFlags(Object hatches) {
        int result = 0;
        if (hatches instanceof Iterable<?>) {
            for (Object hatch : (Iterable<?>) hatches) {
                if (hatch instanceof IMetaTileEntity)
                    result |= flags(((IMetaTileEntity) hatch).getBaseMetaTileEntity());
                if (result == 3) break;
            }
        }
        return result;
    }

    private static int flags(IGregTechTileEntity tile) {
        if (tile == null) return 0;
        int result = 0;
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            Object cover = tile.getCoverAtSide(side);
            if (cover instanceof GTswnCoverWirelessBase) {
                result |= ((GTswnCoverWirelessBase) cover).nodeTypeId() == 0 ? 1 : 2;
            }
        }
        return result;
    }
}
