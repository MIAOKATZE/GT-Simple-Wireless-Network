package com.miaokatze.gtswn.mixins.gregtech;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.miaokatze.gtswn.common.covers.GTswnCoverWirelessBase;
import com.miaokatze.gtswn.common.covers.WirelessCoverCopy;

import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.common.covers.Cover;
import gregtech.common.items.behaviors.BehaviourCoverTool;

/** Old clipboards are sanitized on paste too; enabling configuration copy cannot clone EU. */
@Mixin(value = BehaviourCoverTool.class, remap = false)
public abstract class CoverToolCopyMixin {

    @Redirect(
        method = "saveCoverToNBT",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/common/covers/Cover;writeToNBT(Lnet/minecraft/nbt/NBTTagCompound;)Lnet/minecraft/nbt/NBTTagCompound;"))
    private NBTTagCompound gtswn$copy(Cover cover, NBTTagCompound data) {
        NBTTagCompound result = cover.writeToNBT(data);
        return cover instanceof GTswnCoverWirelessBase ? WirelessCoverCopy.configuration(result) : result;
    }

    @Redirect(
        method = "onItemUseFirst",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/interfaces/tileentity/ICoverable;updateAttachedCover(ILnet/minecraftforge/common/util/ForgeDirection;Lnet/minecraft/nbt/NBTTagCompound;)V"))
    private void gtswn$paste(ICoverable tile, int id, ForgeDirection side, NBTTagCompound copied) {
        Cover cover = tile.getCoverAtSide(side);
        if (cover instanceof GTswnCoverWirelessBase)
            copied = WirelessCoverCopy.configurationPreservingBuffer(copied, cover.writeToNBT(new NBTTagCompound()));
        tile.updateAttachedCover(id, side, copied);
    }
}
