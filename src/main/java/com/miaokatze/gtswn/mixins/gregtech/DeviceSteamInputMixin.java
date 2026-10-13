package com.miaokatze.gtswn.mixins.gregtech;

import net.minecraftforge.fluids.FluidStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.common.device.DeviceFlowMonitor;
import com.miaokatze.gtswn.common.device.DeviceSteamAdapter;

import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

@Mixin(value = MTEMultiBlockBase.class, remap = false)
public abstract class DeviceSteamInputMixin {

    @Inject(method = "depleteInputQuantity", at = @At("RETURN"), require = 1)
    private void gtswn$steamDrained(FluidStack fluid, boolean simulate, CallbackInfoReturnable<Integer> cir) {
        if (!simulate && fluid != null && DeviceSteamAdapter.isSteam(fluid)) {
            DeviceFlowMonitor.steam(this, cir.getReturnValue(), false);
        }
    }
}
