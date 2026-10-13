package com.miaokatze.gtswn.mixins.gregtech;

import net.minecraftforge.fluids.FluidStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.common.device.DeviceFlowMonitor;
import com.miaokatze.gtswn.common.device.DeviceSteamAdapter;

@Mixin(
    value = gtPlusPlus.xmod.gregtech.api.metatileentity.implementations.base.MTESteamMultiBlockBase.class,
    remap = false)
public abstract class DeviceSteamMachineMixin {

    @Inject(method = "depleteInput(Lnet/minecraftforge/fluids/FluidStack;)Z", at = @At("RETURN"), require = 1)
    private void gtswn$steamConsumed(FluidStack fluid, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue()) && fluid != null && DeviceSteamAdapter.isSteam(fluid)) {
            DeviceFlowMonitor.steam(this, fluid.amount, false);
        }
    }
}
