package com.miaokatze.gtswn.mixins.ae2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.common.quantum.QuantumSecurityTopology;

import appeng.api.networking.events.MENetworkEvent;
import appeng.api.networking.events.MENetworkSecurityChange;
import appeng.me.Grid;
import appeng.me.GridNode;

/** Observe the native membership boundary, including direct destruction and initial grid creation. */
@Mixin(value = Grid.class, remap = false)
public abstract class QuantumSecurityGridMixin implements QuantumSecurityTopology.TrackedGrid {

    @Inject(method = { "add", "remove" }, at = @At("HEAD"), remap = false, require = 2)
    private void gtswn$membershipChanged(GridNode node, CallbackInfo callback) {
        QuantumSecurityTopology.membershipChanged(node);
    }

    @Inject(method = "postEvent", at = @At("HEAD"), remap = false, require = 1)
    private void gtswn$securityChanged(MENetworkEvent event, CallbackInfoReturnable<MENetworkEvent> callback) {
        if (event instanceof MENetworkSecurityChange) QuantumSecurityTopology.changed();
    }
}
