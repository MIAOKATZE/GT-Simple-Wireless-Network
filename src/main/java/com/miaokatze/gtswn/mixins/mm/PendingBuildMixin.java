package com.miaokatze.gtswn.mixins.mm;

import java.util.Deque;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.crossmod.mm.CopySummary;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator.ManipulatorTier;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;

@Pseudo
@Mixin(targets = "com.recursive_pineapple.matter_manipulator.common.building.PendingBuild", remap = false)
public abstract class PendingBuildMixin {

    @Shadow
    @Final
    private Deque<PendingBlock> pendingBlocks;

    @Unique
    private CopySummary.Batch gtswn$summary;

    @Unique
    private EntityPlayer gtswn$summaryPlayer;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void gtswn$begin(EntityPlayer player, MMState state, ManipulatorTier tier, List<PendingBlock> blocks,
        CallbackInfo ci) {
        gtswn$summaryPlayer = player;
        gtswn$summary = CopySummary.begin(player);
    }

    @Inject(method = "tryPlaceBlocks", at = @At("RETURN"))
    private void gtswn$completed(ItemStack stack, EntityPlayer player, CallbackInfo ci) {
        // MM can discard a protected/unloaded block too: "processed" does not claim every restore succeeded.
        if (pendingBlocks.isEmpty()) CopySummary.finish(gtswn$summaryPlayer, gtswn$summary, true);
    }

    @Inject(method = "onStopped", at = @At("RETURN"))
    private void gtswn$stopped(CallbackInfo ci) {
        CopySummary.finish(gtswn$summaryPlayer, gtswn$summary, pendingBlocks.isEmpty());
    }
}
