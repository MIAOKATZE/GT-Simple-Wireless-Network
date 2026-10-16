package com.miaokatze.gtswn.mixins.ae2;

import java.util.List;

import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.common.quantum.QuantumConnectionMembership;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;

import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.me.GridNode;

/** Block incorporated-device cable edges while preserving AE2 native checks for every other physical peer. */
@Mixin(value = GridNode.class, remap = false)
public abstract class QuantumIncorporationGridNodeMixin implements QuantumConnectionMembership.TrackedNode {

    @Redirect(
        method = "addConnection",
        at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"),
        remap = false,
        require = 1)
    private boolean gtswn$attach(List<IGridConnection> connections, Object connection) {
        return QuantumConnectionMembership.add(connections, (IGridConnection) connection, (IGridNode) (Object) this);
    }

    @Redirect(
        method = "removeConnection",
        at = @At(value = "INVOKE", target = "Ljava/util/List;remove(Ljava/lang/Object;)Z"),
        remap = false,
        require = 1)
    private boolean gtswn$detach(List<IGridConnection> connections, Object connection) {
        return QuantumConnectionMembership.remove(connections, (IGridConnection) connection, (IGridNode) (Object) this);
    }

    @Inject(method = "canConnect", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtswn$isolate(GridNode from, ForgeDirection direction, CallbackInfoReturnable<Boolean> result) {
        if (direction != ForgeDirection.UNKNOWN
            && QuantumIncorporationRegistry.blocksCableConnection((IGridNode) (Object) this, from))
            result.setReturnValue(false);
    }
}
