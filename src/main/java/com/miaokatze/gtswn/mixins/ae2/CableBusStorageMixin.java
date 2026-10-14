package com.miaokatze.gtswn.mixins.ae2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.implementations.parts.IPartCable;
import appeng.parts.CableBusContainer;
import appeng.parts.CableBusStorage;

/** Supply the host block's cable only while this same live container belongs to a quantum node. */
@Mixin(value = CableBusStorage.class, remap = false)
public abstract class CableBusStorageMixin {

    @Inject(method = "getCenter", at = @At("RETURN"), cancellable = true)
    private void gtswn$quantumCenter(CallbackInfoReturnable<IPartCable> callback) {
        if (callback.getReturnValue() == null && (Object) this instanceof CableBusContainer) {
            CableBusContainer container = (CableBusContainer) (Object) this;
            if (container.getTile() instanceof TileEntityNetworkQuantumNode) {
                TileEntityNetworkQuantumNode node = (TileEntityNetworkQuantumNode) container.getTile();
                if (node.getPartContainer() == container) callback.setReturnValue(node.getCablePart());
            }
        }
    }
}
