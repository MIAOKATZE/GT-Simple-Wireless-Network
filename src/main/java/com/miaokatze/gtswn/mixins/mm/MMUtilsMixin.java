package com.miaokatze.gtswn.mixins.mm;

import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.miaokatze.gtswn.crossmod.mm.AEPartCopy;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;

/** Keep quantum blocks as blocks when MM checks whether a selection is an AE cable item. */
@Pseudo
@Mixin(targets = "com.recursive_pineapple.matter_manipulator.common.utils.MMUtils", remap = false)
public abstract class MMUtilsMixin {

    @Redirect(
        method = "getAECable",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/parts/IPartHost;getPart(Lnet/minecraftforge/common/util/ForgeDirection;)Lappeng/api/parts/IPart;"))
    private static IPart gtswn$transferableCable(IPartHost host, ForgeDirection side) {
        // Returning no transferable cable leaves the caller's BlockSpec unchanged and returns
        // false. In particular, exchanging matches the quantum block and never consumes a
        // fabricated cable item or converts the node into a TileCableBus.
        return AEPartCopy.transferablePart(host, side);
    }
}
