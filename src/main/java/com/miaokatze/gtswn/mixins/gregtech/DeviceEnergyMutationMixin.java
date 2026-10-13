package com.miaokatze.gtswn.mixins.gregtech;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.common.device.DeviceFlowMonitor;

import gregtech.api.metatileentity.MetaTileEntity;

@Mixin(value = MetaTileEntity.class, remap = false)
public abstract class DeviceEnergyMutationMixin {

    @Shadow
    public abstract long getEUVar();

    @Shadow
    public abstract long getSteamVar();

    @Inject(method = "setEUVar", at = @At("HEAD"), require = 1)
    private void gtswn$electricChange(long energy, CallbackInfo ci) {
        if (DeviceFlowMonitor.active()) DeviceFlowMonitor.energyChange(this, getEUVar(), energy, false);
    }

    @Inject(method = "setSteamVar", at = @At("HEAD"), require = 1)
    private void gtswn$steamChange(long steam, CallbackInfo ci) {
        if (DeviceFlowMonitor.active()) DeviceFlowMonitor.energyChange(this, getSteamVar(), steam, true);
    }
}
