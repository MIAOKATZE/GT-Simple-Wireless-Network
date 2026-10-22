package com.miaokatze.gtswn.mixins.journeymap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.client.engineering.EngineeringModeController;

/** Factory HUD owns the screen while active; JourneyMap's settings and mapping remain untouched. */
@Pseudo
@Mixin(targets = "journeymap.client.forge.event.MiniMapOverlayHandler", remap = false)
public abstract class FactoryMiniMapOverlayMixin {

    @Inject(
        method = "onRenderOverlay(Lnet/minecraftforge/client/event/RenderGameOverlayEvent;)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false)
    private void gtswn$hideFactoryMinimap(CallbackInfo ci) {
        if (EngineeringModeController.isActive()) ci.cancel();
    }
}
