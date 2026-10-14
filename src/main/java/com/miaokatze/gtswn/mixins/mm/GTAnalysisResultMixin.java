package com.miaokatze.gtswn.mixins.mm;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.miaokatze.gtswn.common.covers.GTswnCoverWirelessBase;
import com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless;
import com.miaokatze.gtswn.common.covers.WirelessCoverCopy;
import com.miaokatze.gtswn.common.items.GTSwnCoverDynamoWireless;
import com.miaokatze.gtswn.common.items.GTSwnCoverEnergyWireless;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.building.CoverData;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.covers.Cover;

@Pseudo
@Mixin(targets = "com.recursive_pineapple.matter_manipulator.common.building.GTAnalysisResult", remap = false)
public abstract class GTAnalysisResultMixin {

    @Redirect(
        method = { "installCover", "getRequiredItemsForExistingBlock", "getRequiredItemsForNewBlock" },
        at = @At(
            value = "INVOKE",
            target = "Lcom/recursive_pineapple/matter_manipulator/common/building/BlockAnalyzer$IBlockApplyContext;tryConsumeItems([Lnet/minecraft/item/ItemStack;)Z"))
    private boolean gtswn$coverCost(IBlockApplyContext context, ItemStack[] stacks) {
        List<ItemStack> ordinary = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack == null || !(stack.getItem() instanceof GTSwnCoverEnergyWireless
                || stack.getItem() instanceof GTSwnCoverDynamoWireless)) ordinary.add(stack);
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
        if (restored instanceof GTswn_Cover_EnergyWireless energy) energy.fundCopiedBuffer(context.getRealPlayer());
        tile.markDirty();
    }
}
