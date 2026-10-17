package com.miaokatze.gtswn.mixins.mm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.common.block.BlockNetworkQuantumNode;
import com.miaokatze.gtswn.common.util.LaserHatchUtil;
import com.miaokatze.gtswn.crossmod.mm.CopySummary;
import com.miaokatze.gtswn.crossmod.mm.MatterManipulatorCopyData;
import com.miaokatze.gtswn.crossmod.mm.QuantumCopy;
import com.miaokatze.gtswn.crossmod.mm.WirelessCoverMaterials;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.building.CoverData;
import com.recursive_pineapple.matter_manipulator.common.building.GTAnalysisResult;
import com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory;
import com.recursive_pineapple.matter_manipulator.common.building.ITileAnalysisIntegration;
import com.recursive_pineapple.matter_manipulator.common.building.ImmutableBlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

@Pseudo
@Mixin(targets = "com.recursive_pineapple.matter_manipulator.common.building.PendingBlock", remap = false)
public abstract class PendingBlockMixin implements MatterManipulatorCopyData {

    @Shadow
    public ImmutableBlockSpec spec;

    @Shadow
    public ITileAnalysisIntegration gt;

    @Unique
    private transient boolean gtswn$materialFailure;

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

    @Inject(method = "getRequiredItemsForNewBlock", at = @At("HEAD"), cancellable = true)
    private void gtswn$newMaterials(IBlockApplyContext context, CallbackInfoReturnable<Boolean> cir) {
        if (!gtswn$checkMaterials(context, true, false)) cir.setReturnValue(false);
    }

    @Inject(method = "getRequiredItemsForExistingBlock", at = @At("HEAD"), cancellable = true)
    private void gtswn$existingMaterials(IBlockApplyContext context, CallbackInfoReturnable<Boolean> cir) {
        if (!gtswn$checkMaterials(context, false, false)) cir.setReturnValue(false);
    }

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true)
    private void gtswn$preflightMaterials(IBlockApplyContext context, World world,
        CallbackInfoReturnable<Boolean> cir) {
        gtswn$materialFailure = false;
        // Inspect the complete per-block bill before GT can remove or replace any existing cover.
        // This is simulation only; each successful installation reserves and commits its own item.
        if (!gtswn$checkMaterials(context, false, true)) {
            gtswn$materialFailure = true;
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "apply", at = @At("RETURN"), cancellable = true)
    private void gtswn$materialResult(IBlockApplyContext context, World world, CallbackInfoReturnable<Boolean> cir) {
        if (gtswn$materialFailure) cir.setReturnValue(false);
    }

    @Unique
    private boolean gtswn$checkMaterials(IBlockApplyContext context, boolean newBlock, boolean reportFailure) {
        if (context.getRealPlayer() != null && context.getRealPlayer().capabilities.isCreativeMode) return true;
        TileEntity tile = newBlock ? null : context.getTileEntity();
        List<BigItemStack> materials = new ArrayList<>();
        ItemStack[] expected = new ItemStack[ForgeDirection.VALID_DIRECTIONS.length];
        ItemStack[] existing = new ItemStack[expected.length];
        ItemStack pipe = null;
        if (gt instanceof GTAnalysisResult analysis) {
            boolean laser = newBlock && spec != null
                ? WirelessCoverMaterials.isLaserMachine(spec.getBlock(), spec.getItemMeta())
                : LaserHatchUtil.isLaserHatch(tile);
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                CoverData cover = analysis.mCovers == null ? null : analysis.mCovers[side.ordinal()];
                if (cover != null) expected[side.ordinal()] = cover.getCoverStack();
                if (tile instanceof IGregTechTileEntity machine)
                    existing[side.ordinal()] = machine.getCoverItemAtSide(side);
            }
            int tubes = WirelessCoverMaterials.requiredTubes(laser, expected, existing);
            if (tubes > 0) {
                pipe = LaserHatchUtil.getLaserPipeItemStack();
                pipe.stackSize = tubes;
                materials.add(BigItemStack.create(pipe));
            }
        }
        boolean incorporation = newBlock ? QuantumCopy.hasIncorporationBinding(gtswn$copyData)
            : QuantumCopy.needsIncorporation(tile, gtswn$copyData);
        ItemStack anchor = null;
        if (incorporation) {
            anchor = QuantumCopy.incorporationMaterial();
            if (anchor == null || anchor.getItem() == null) {
                if (reportFailure) CopySummary.incorporationFailed(context.getRealPlayer(), tile, false);
                return false;
            }
            materials.add(BigItemStack.create(anchor));
        }
        boolean available = materials.isEmpty()
            || context.tryConsumeItems(materials, IPseudoInventory.CONSUME_SIMULATED)
                .leftBoolean();
        if (!available && reportFailure) {
            if (pipe != null && !gtswn$available(context, pipe)) {
                for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                    if (WirelessCoverMaterials.isWireless(expected[side.ordinal()])
                        && !ItemStack.areItemStacksEqual(expected[side.ordinal()], existing[side.ordinal()]))
                        CopySummary.linkFailed(context.getRealPlayer(), tile, side.ordinal(), true);
                }
            }
            if (anchor != null && !gtswn$available(context, anchor))
                CopySummary.incorporationFailed(context.getRealPlayer(), tile, true);
        }
        return available;
    }

    @Unique
    private static boolean gtswn$available(IBlockApplyContext context, ItemStack material) {
        List<BigItemStack> request = new ArrayList<>();
        request.add(BigItemStack.create(material.copy()));
        return context.tryConsumeItems(request, IPseudoInventory.CONSUME_SIMULATED)
            .leftBoolean();
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
        if (!QuantumCopy.restore(tile, gtswn$copyData, context)) {
            gtswn$materialFailure = true;
            return null;
        }
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
