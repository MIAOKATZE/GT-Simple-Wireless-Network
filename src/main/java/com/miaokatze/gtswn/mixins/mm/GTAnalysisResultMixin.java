package com.miaokatze.gtswn.mixins.mm;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.common.covers.GTswnCoverWirelessBase;
import com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless;
import com.miaokatze.gtswn.common.covers.WirelessCoverCopy;
import com.miaokatze.gtswn.common.util.LaserHatchUtil;
import com.miaokatze.gtswn.crossmod.mm.CopyMaterialTransaction;
import com.miaokatze.gtswn.crossmod.mm.CopySummary;
import com.miaokatze.gtswn.crossmod.mm.WirelessCoverMaterials;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.building.CoverData;
import com.recursive_pineapple.matter_manipulator.common.building.GTAnalysisResult;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.covers.Cover;

@Pseudo
@Mixin(targets = "com.recursive_pineapple.matter_manipulator.common.building.GTAnalysisResult", remap = false)
public abstract class GTAnalysisResultMixin {

    @Unique
    private transient boolean gtswn$materialFailure;

    @Shadow
    private void installCover(IBlockApplyContext context, IGregTechTileEntity tile, ForgeDirection side,
        CoverData cover) {
        throw new AssertionError();
    }

    @Inject(method = "apply", at = @At("HEAD"))
    private void gtswn$resetMaterialFailure(IBlockApplyContext context, CallbackInfoReturnable<Boolean> cir) {
        gtswn$materialFailure = false;
    }

    @Inject(method = "apply", at = @At("RETURN"), cancellable = true)
    private void gtswn$reportMaterialFailure(IBlockApplyContext context, CallbackInfoReturnable<Boolean> cir) {
        if (gtswn$materialFailure) cir.setReturnValue(false);
    }

    @Redirect(
        method = "apply",
        at = @At(
            value = "INVOKE",
            target = "Lcom/recursive_pineapple/matter_manipulator/common/building/GTAnalysisResult;installCover(Lcom/recursive_pineapple/matter_manipulator/common/building/BlockAnalyzer$IBlockApplyContext;Lgregtech/api/interfaces/tileentity/IGregTechTileEntity;Lnet/minecraftforge/common/util/ForgeDirection;Lcom/recursive_pineapple/matter_manipulator/common/building/CoverData;)V"))
    private void gtswn$installLaserCover(GTAnalysisResult analysis, IBlockApplyContext context,
        IGregTechTileEntity tile, ForgeDirection side, CoverData data) {
        boolean wireless = WirelessCoverMaterials.isWireless(data.getCoverStack());
        boolean charge = wireless && LaserHatchUtil.isLaserHatch(tile.getMetaTileEntity())
            && !context.getRealPlayer().capabilities.isCreativeMode;
        if (!charge) {
            installCover(context, tile, side, data);
            if (wireless) {
                if (tile.getCoverAtSide(side) instanceof GTswnCoverWirelessBase
                    && ItemStack.areItemStacksEqual(tile.getCoverItemAtSide(side), data.getCoverStack()))
                    CopySummary.linkInstalled(context.getRealPlayer(), (TileEntity) tile, side.ordinal(), 0);
                else {
                    gtswn$materialFailure = true;
                    CopySummary.linkFailed(context.getRealPlayer(), (TileEntity) tile, side.ordinal(), false);
                }
            }
            return;
        }
        ItemStack pipe = LaserHatchUtil.getLaserPipeItemStack();
        boolean[] reserved = { false };
        boolean installed;
        try {
            installed = CopyMaterialTransaction
                .execute(() -> reserved[0] = context.tryConsumeItems(pipe.copy()), () -> {
                    installCover(context, tile, side, data);
                    return tile.getCoverAtSide(side) instanceof GTswnCoverWirelessBase
                        && ItemStack.areItemStacksEqual(tile.getCoverItemAtSide(side), data.getCoverStack());
                }, () -> {
                    // installCover can throw after attachment (for example while restoring settings).
                    // Remove that new cover before returning its reserved pipe.
                    if (tile.getCoverAtSide(side) instanceof GTswnCoverWirelessBase) tile.detachCover(side);
                    if (tile.getCoverAtSide(side) instanceof GTswnCoverWirelessBase)
                        throw new IllegalStateException("Cannot roll back an MM wireless cover installation");
                }, () -> context.givePlayerItems(pipe.copy()));
        } catch (RuntimeException | LinkageError failure) {
            gtswn$materialFailure = true;
            CopySummary.linkFailed(context.getRealPlayer(), (TileEntity) tile, side.ordinal(), false);
            GTSimpleWirelessNetwork.LOG.warn(
                "MM laser wireless cover installation failed at {},{},{} side {}",
                context.getX(),
                context.getY(),
                context.getZ(),
                side,
                failure);
            return;
        }
        if (!installed) {
            gtswn$materialFailure = true;
            CopySummary.linkFailed(context.getRealPlayer(), (TileEntity) tile, side.ordinal(), !reserved[0]);
        } else CopySummary.linkInstalled(context.getRealPlayer(), (TileEntity) tile, side.ordinal(), 1);
    }

    @Redirect(
        method = { "installCover", "getRequiredItemsForExistingBlock", "getRequiredItemsForNewBlock" },
        at = @At(
            value = "INVOKE",
            target = "Lcom/recursive_pineapple/matter_manipulator/common/building/BlockAnalyzer$IBlockApplyContext;tryConsumeItems([Lnet/minecraft/item/ItemStack;)Z"))
    private boolean gtswn$coverCost(IBlockApplyContext context, ItemStack[] stacks) {
        List<ItemStack> ordinary = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (!WirelessCoverMaterials.isWireless(stack)) ordinary.add(stack);
        }
        return ordinary.isEmpty() || context.tryConsumeItems(ordinary.toArray(new ItemStack[0]));
    }

    @Redirect(
        method = { "installCover", "updateCover" },
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/interfaces/tileentity/IGregTechTileEntity;updateAttachedCover(ILnet/minecraftforge/common/util/ForgeDirection;Lnet/minecraft/nbt/NBTTagCompound;)V"))
    private void gtswn$coverConfiguration(IGregTechTileEntity tile, int id, ForgeDirection side, NBTTagCompound data,
        IBlockApplyContext context, IGregTechTileEntity original, ForgeDirection originalSide, CoverData coverData) {
        Cover cover = tile.getCoverAtSide(side);
        if (!(cover instanceof GTswnCoverWirelessBase)) {
            tile.updateAttachedCover(id, side, data);
            return;
        }
        NBTTagCompound destination = cover.writeToNBT(new NBTTagCompound());
        if (WirelessCoverCopy.configuration(destination)
            .equals(WirelessCoverCopy.configuration(data))) return;
        tile.updateAttachedCover(id, side, WirelessCoverCopy.configurationPreservingBuffer(data, destination));
        Cover restored = tile.getCoverAtSide(side);
        CopySummary.coverConfigured(context.getRealPlayer(), (TileEntity) tile, side.ordinal());
        if (restored instanceof GTswn_Cover_EnergyWireless energy) {
            if (energy.tryFundCopiedBuffer(context.getRealPlayer()))
                CopySummary.refillCompleted(context.getRealPlayer(), (TileEntity) tile, side.ordinal());
            else CopySummary.refillDeferred(context.getRealPlayer(), (TileEntity) tile, side.ordinal());
        }
        tile.markDirty();
    }
}
