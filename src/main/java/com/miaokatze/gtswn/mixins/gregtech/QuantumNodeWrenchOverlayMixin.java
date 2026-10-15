package com.miaokatze.gtswn.mixins.gregtech;

import net.minecraftforge.client.event.DrawBlockHighlightEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.common.block.BlockNetworkQuantumNode;

import gregtech.client.BlockOverlayRenderer;

/** Keep native wrench compatibility without drawing a rotation grid on the directionless node. */
@Mixin(value = BlockOverlayRenderer.class, remap = false)
public abstract class QuantumNodeWrenchOverlayMixin {

    @Inject(method = "drawGrid", at = @At("HEAD"), cancellable = true, require = 0)
    private static void gtswn$skipNodeWrenchGrid(DrawBlockHighlightEvent event, boolean showCoverConnections,
        boolean isWrench, boolean isSneaking, CallbackInfo callback) {
        if (isWrench && event.player.worldObj.getBlock(
            event.target.blockX,
            event.target.blockY,
            event.target.blockZ) instanceof BlockNetworkQuantumNode) {
            // Return before GT cancels the event, so vanilla still draws the selected core or attached part box.
            callback.cancel();
        }
    }
}
