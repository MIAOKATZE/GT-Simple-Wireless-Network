package com.miaokatze.gtswn.mixins.ae2;

import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;

import appeng.api.networking.IGridNode;
import appeng.me.GridNode;

/** Keep orientation updates from reopening physical AE connections on incorporated blocks. */
@Mixin(value = GridNode.class, remap = false)
public abstract class QuantumIncorporationGridNodeMixin {

    @Inject(method = "canConnect", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtswn$isolate(GridNode from, ForgeDirection direction, CallbackInfoReturnable<Boolean> result) {
        if (direction != ForgeDirection.UNKNOWN
            && (QuantumIncorporationRegistry.blocksPhysicalConnection((IGridNode) (Object) this)
                || QuantumIncorporationRegistry.blocksPhysicalConnection(from)))
            result.setReturnValue(false);
    }
}
