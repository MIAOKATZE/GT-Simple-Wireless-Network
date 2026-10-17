package com.miaokatze.gtswn.mixins.mm;

import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.miaokatze.gtswn.crossmod.mm.AEPartCopy;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;

/** The quantum block supplies its cable protocol itself; it is not a transferable AE part item. */
@Pseudo
@Mixin(targets = "com.recursive_pineapple.matter_manipulator.common.building.AEAnalysisResult", remap = false)
public abstract class AEAnalysisResultMixin {

    @Redirect(
        method = { "<init>(Lnet/minecraft/tileentity/TileEntity;)V", "apply", "getRequiredItemsForExistingBlock",
            "removePart" },
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/parts/IPartHost;getPart(Lnet/minecraftforge/common/util/ForgeDirection;)Lappeng/api/parts/IPart;"))
    private IPart gtswn$transferablePart(IPartHost host, ForgeDirection side) {
        return AEPartCopy.transferablePart(host, side);
    }
}
