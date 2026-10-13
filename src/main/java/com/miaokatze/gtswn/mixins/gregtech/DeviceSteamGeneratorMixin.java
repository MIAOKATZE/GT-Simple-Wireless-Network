package com.miaokatze.gtswn.mixins.gregtech;

import net.minecraftforge.fluids.FluidStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.common.device.DeviceFlowMonitor;
import com.miaokatze.gtswn.common.device.DeviceSteamAdapter;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicGenerator;

@Mixin(value = MTEBasicGenerator.class, remap = false)
public abstract class DeviceSteamGeneratorMixin {

    @Unique
    private FluidStack gtswn$steamBefore;
    @Unique
    private int gtswn$steamAmount;

    @Inject(method = "onPostTick", at = @At("HEAD"), require = 1)
    private void gtswn$beforeFuel(IGregTechTileEntity tile, long tick, CallbackInfo ci) {
        gtswn$steamBefore = null;
        if (!DeviceFlowMonitor.isWatched(this)) return;
        FluidStack fluid = ((MTEBasicGenerator) (Object) this).getFluid();
        if (DeviceSteamAdapter.isSteam(fluid)) {
            gtswn$steamBefore = fluid;
            gtswn$steamAmount = fluid.amount;
        }
    }

    @Inject(method = "onPostTick", at = @At("RETURN"), require = 1)
    private void gtswn$afterFuel(IGregTechTileEntity tile, long tick, CallbackInfo ci) {
        if (gtswn$steamBefore != null) {
            DeviceFlowMonitor.steam(this, Math.max(0, gtswn$steamAmount - gtswn$steamBefore.amount), false);
            gtswn$steamBefore = null;
        }
    }
}
