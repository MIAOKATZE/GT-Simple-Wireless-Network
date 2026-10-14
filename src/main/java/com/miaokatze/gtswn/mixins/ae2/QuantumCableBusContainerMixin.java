package com.miaokatze.gtswn.mixins.ae2;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.common.quantum.QuantumNodeCablePart;
import com.miaokatze.gtswn.common.quantum.QuantumNodePartRules;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.parts.IPart;
import appeng.parts.CableBusContainer;

/** Close native placement/removal entry points without changing ordinary AE2 hosts. */
@Mixin(value = CableBusContainer.class, remap = false)
public abstract class QuantumCableBusContainerMixin {

    @Inject(method = "canAddPart", at = @At("HEAD"), cancellable = true)
    private void gtswn$canAddPart(ItemStack stack, ForgeDirection side, CallbackInfoReturnable<Boolean> callback) {
        CableBusContainer container = (CableBusContainer) (Object) this;
        if (container.getTile() instanceof TileEntityNetworkQuantumNode
            && ((TileEntityNetworkQuantumNode) container.getTile()).getPartContainer() == container
            && (stack == null || QuantumNodePartRules.conflicts(stack))) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "addPart", at = @At("HEAD"), cancellable = true)
    private void gtswn$addPart(ItemStack stack, ForgeDirection side, EntityPlayer player,
        CallbackInfoReturnable<ForgeDirection> callback) {
        CableBusContainer container = (CableBusContainer) (Object) this;
        if (container.getTile() instanceof TileEntityNetworkQuantumNode
            && ((TileEntityNetworkQuantumNode) container.getTile()).getPartContainer() == container
            && (stack == null || QuantumNodePartRules.conflicts(stack))) {
            callback.setReturnValue(null);
        }
    }

    @Inject(method = "removePart", at = @At("HEAD"), cancellable = true)
    private void gtswn$keepQuantumCore(ForgeDirection side, boolean suppressUpdate, CallbackInfo callback) {
        CableBusContainer container = (CableBusContainer) (Object) this;
        if (side == ForgeDirection.UNKNOWN && container.getPart(side) instanceof QuantumNodeCablePart) {
            callback.cancel();
        }
    }

    @Redirect(
        method = { "isEmpty", "writeToNBT", "writeToStream", "getDrops", "getNoDrops" },
        at = @At(
            value = "INVOKE",
            target = "Lappeng/parts/CableBusContainer;getPart(Lnet/minecraftforge/common/util/ForgeDirection;)Lappeng/api/parts/IPart;"))
    private IPart gtswn$realPartsOnly(CableBusContainer container, ForgeDirection side) {
        IPart part = container.getPart(side);
        return part instanceof QuantumNodeCablePart ? null : part;
    }
}
