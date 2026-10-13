package com.miaokatze.gtswn.mixins.gregtech;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.common.device.DeviceFlowMonitor;

import gregtech.common.tileentities.boilers.MTEBoiler;

/** The native method has already consumed water and added exactly this many liters to its steam buffer. */
@Mixin(value = MTEBoiler.class, remap = false)
public abstract class DeviceSteamBoilerMixin {

    @Inject(method = "produceSteam", at = @At("RETURN"), require = 1)
    private void gtswn$steamProduced(int amount, CallbackInfo ci) {
        DeviceFlowMonitor.steam(this, amount, true);
    }
}
