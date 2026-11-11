// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.mixins.mm;

import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.miaokatze.gtswn.crossmod.mm.AEPartCopy;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;

/** The quantum block supplies its cable protocol itself; it is not a transferable AE part item. */
@Pseudo
@Mixin(targets = "com.recursive_pineapple.matter_manipulator.common.building.AEAnalysisResult", remap = false)
public abstract class AEAnalysisResultMixin {

    @WrapOperation(
        method = { "<init>(Lnet/minecraft/tileentity/TileEntity;)V", "apply", "getRequiredItemsForExistingBlock",
            "removePart" },
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/parts/IPartHost;getPart(Lnet/minecraftforge/common/util/ForgeDirection;)Lappeng/api/parts/IPart;"))
    private static IPart gtswn$transferablePart(IPartHost host, ForgeDirection side, Operation<IPart> original) {
        return AEPartCopy.filterTransferablePart(original.call(host, side), side);
    }
}
