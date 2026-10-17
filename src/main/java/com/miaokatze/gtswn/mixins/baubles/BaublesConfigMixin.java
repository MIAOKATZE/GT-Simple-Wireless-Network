package com.miaokatze.gtswn.mixins.baubles;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.common.baubles.TerminalBaubleSlotPolicy;

/** Universal legacy baubles must not bypass the four terminal-only slot boundaries. */
@Pseudo
@Mixin(targets = "baubles.common.BaublesConfig", remap = false)
public abstract class BaublesConfigMixin {

    @Inject(method = "canTypeFitSlot", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gtswn$terminalSlots(String itemType, String slotType, CallbackInfoReturnable<Boolean> cir) {
        Boolean accepted = TerminalBaubleSlotPolicy.overrideAcceptance(itemType, slotType);
        if (accepted != null) cir.setReturnValue(accepted);
    }
}
