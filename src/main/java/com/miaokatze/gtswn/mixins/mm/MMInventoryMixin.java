package com.miaokatze.gtswn.mixins.mm;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.miaokatze.gtswn.crossmod.mm.WirelessCoverMaterials;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

/** MM bypasses EntityItem spawning when it salvages covers into its pending inventory or ME storage. */
@Pseudo
@Mixin(targets = "com.recursive_pineapple.matter_manipulator.common.building.MMInventory", remap = false)
public abstract class MMInventoryMixin {

    @ModifyVariable(method = "givePlayerItems(Ljava/util/List;)V", at = @At("HEAD"), argsOnly = true, require = 1)
    private List<BigItemStack> gtswn$discardVirtualCovers(List<BigItemStack> items) {
        // Keep removal callbacks intact (including buffered EU return). Only discard the virtual item credit.
        // This also covers AbstractBuildable.removeCovers and captured drops, not just blueprint reconfiguration.
        return WirelessCoverMaterials.withoutVirtualCovers(items, BigItemStack::getItem);
    }
}
