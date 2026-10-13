package com.miaokatze.gtswn.mixins.gregtech;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.miaokatze.gtswn.common.device.DeviceFlowMonitor;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;

@Mixin(value = BaseMetaTileEntity.class, remap = false)
public abstract class DeviceControllerTickMixin {

    /** GT205 dispatches through the interface; GT183 dispatches through the concrete base class. */
    @Group(name = "gtswn$controllerPostTick", min = 1, max = 1)
    @Redirect(
        method = "doPostTick",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/interfaces/metatileentity/IMetaTileEntity;onPostTick(Lgregtech/api/interfaces/tileentity/IGregTechTileEntity;J)V"),
        require = 0)
    private void gtswn$observeController(IMetaTileEntity controller, IGregTechTileEntity tile, long tick) {
        gtswn$dispatchObservedTick(controller, tile, tick);
    }

    @Group(name = "gtswn$controllerPostTick", min = 1, max = 1)
    @Redirect(
        method = "doPostTick",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/metatileentity/MetaTileEntity;onPostTick(Lgregtech/api/interfaces/tileentity/IGregTechTileEntity;J)V"),
        require = 0)
    private void gtswn$observeLegacyController(MetaTileEntity controller, IGregTechTileEntity tile, long tick) {
        gtswn$dispatchObservedTick(controller, tile, tick);
    }

    @Unique
    private void gtswn$dispatchObservedTick(IMetaTileEntity controller, IGregTechTileEntity tile, long tick) {
        if (!tile.isServerSide()) {
            controller.onPostTick(tile, tick);
            return;
        }
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(controller);
        try {
            controller.onPostTick(tile, tick);
        } finally {
            DeviceFlowMonitor.leave(previous);
        }
    }
}
