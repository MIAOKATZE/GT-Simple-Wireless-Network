package com.miaokatze.gtswn.mixins.waila;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.client.engineering.EngineeringModeController;

/** Suppress only Waila's screen tooltip during the factory session, before it changes GL state. */
@Pseudo
@Mixin(targets = "mcp.mobius.waila.overlay.OverlayRenderer", remap = false)
public abstract class FactoryTooltipOverlayMixin {

    @Inject(
        method = "renderOverlay(Lmcp/mobius/waila/overlay/Tooltip;)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false)
    private static void gtswn$hideFactoryTooltip(CallbackInfo ci) {
        if (EngineeringModeController.isActive()) ci.cancel();
    }
}
