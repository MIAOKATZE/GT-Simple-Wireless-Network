package com.miaokatze.gtswn.mixins.mm;

import java.util.Objects;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.common.block.BlockNetworkQuantumNode;
import com.miaokatze.gtswn.crossmod.mm.MatterManipulatorCopyData;
import com.miaokatze.gtswn.crossmod.mm.QuantumCopy;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.building.ImmutableBlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;

@Pseudo
@Mixin(targets = "com.recursive_pineapple.matter_manipulator.common.building.PendingBlock", remap = false)
public abstract class PendingBlockMixin implements MatterManipulatorCopyData {

    @Shadow
    public ImmutableBlockSpec spec;

    /** Non-transient: MM's Gson/NBTJsonAdapter persists this along with the blueprint. */
    @Unique
    private NBTTagCompound gtswn$copyData;

    @Override
    public NBTTagCompound gtswn$getCopyData() {
        return gtswn$copyData;
    }

    @Override
    public void gtswn$setCopyData(NBTTagCompound data) {
        gtswn$copyData = data;
    }

    @Inject(method = "analyze", at = @At("RETURN"))
    private void gtswn$capture(TileEntity tile, int flags, CallbackInfoReturnable<PendingBlock> cir) {
        gtswn$copyData = QuantumCopy.capture(tile);
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void gtswn$reset(CallbackInfoReturnable<PendingBlock> cir) {
        gtswn$copyData = null;
    }

    @Inject(
        method = "clone()Lcom/recursive_pineapple/matter_manipulator/common/building/PendingBlock;",
        at = @At("RETURN"))
    private void gtswn$clone(CallbackInfoReturnable<PendingBlock> cir) {
        ((MatterManipulatorCopyData) cir.getReturnValue())
            .gtswn$setCopyData(gtswn$copyData == null ? null : (NBTTagCompound) gtswn$copyData.copy());
    }

    @Inject(method = "isFree", at = @At("RETURN"), cancellable = true)
    private void gtswn$nodeCost(CallbackInfoReturnable<Boolean> cir) {
        if (spec != null && spec.getBlock() instanceof BlockNetworkQuantumNode) cir.setReturnValue(true);
    }

    // This call is reached only when every tile integration succeeded. apply's return value means
    // property changes, not successful configuration, so it cannot gate restoring a binding.
    @Redirect(
        method = "apply",
        at = @At(
            value = "INVOKE",
            target = "Lcom/recursive_pineapple/matter_manipulator/common/building/BlockAnalyzer$IBlockApplyContext;getTileEntity()Lnet/minecraft/tileentity/TileEntity;"))
    private TileEntity gtswn$restore(IBlockApplyContext context) {
        TileEntity tile = context.getTileEntity();
        QuantumCopy.restore(tile, gtswn$copyData, context.getRealPlayer());
        return tile;
    }

    @Inject(method = "equals", at = @At("RETURN"), cancellable = true)
    private void gtswn$equals(Object other, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && other instanceof MatterManipulatorCopyData)
            cir.setReturnValue(Objects.equals(gtswn$copyData, ((MatterManipulatorCopyData) other).gtswn$getCopyData()));
    }

    @Inject(method = "hashCode", at = @At("RETURN"), cancellable = true)
    private void gtswn$hash(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(31 * cir.getReturnValue() + Objects.hashCode(gtswn$copyData));
    }
}
