package com.miaokatze.gtswn.mixins.ae2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.common.quantum.QuantumConnectionSecurity;
import com.miaokatze.gtswn.common.testworld.TestWorldProbes;
import com.miaokatze.gtswn.common.util.DebugTestGate;

import appeng.api.networking.IGridConnection;
import appeng.me.GridConnection;
import appeng.me.GridNode;
import appeng.util.Platform;

/** Keep ordinary AE security intact while allowing the explicit GTSWN quantum safety override. */
@Mixin(value = GridConnection.class, remap = false)
public abstract class QuantumConnectionSecurityMixin {

    @Unique
    private boolean gtswn$overrodeSafety;

    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/util/Platform;securityCheck(Lappeng/me/GridNode;Lappeng/me/GridNode;)Z"),
        require = 1)
    private boolean gtswn$checkQuantumConnection(GridNode first, GridNode second) {
        if (DebugTestGate.probesActive()) TestWorldProbes.aeConnectionAttempt(first, second);
        // Native true means rejection. No keys, cache entries, player IDs or providers are rewritten.
        if (QuantumConnectionSecurity.bypassesNativeCheck(first, second)) {
            gtswn$overrodeSafety = true;
            return false;
        }
        return !QuantumConnectionSecurity.permitsNativeConnection(first, second)
            || Platform.securityCheck(first, second);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void gtswn$rememberQuantumOverride(CallbackInfo callback) {
        QuantumConnectionSecurity.recordActual((IGridConnection) (Object) this, gtswn$overrodeSafety);
    }
}
