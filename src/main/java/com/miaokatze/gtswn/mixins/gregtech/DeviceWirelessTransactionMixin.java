package com.miaokatze.gtswn.mixins.gregtech;

import java.math.BigInteger;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.common.device.DeviceFlowMonitor;

import gregtech.common.misc.WirelessNetworkManager;

/** The int/long overloads delegate here; neither global balances nor hatch ownership are inferred. */
@Mixin(value = WirelessNetworkManager.class, remap = false)
public abstract class DeviceWirelessTransactionMixin {

    @Inject(method = "addEUToGlobalEnergyMap(Ljava/util/UUID;Ljava/math/BigInteger;)Z", at = @At("RETURN"), require = 1)
    private static void gtswn$committedControllerTransaction(UUID owner, BigInteger amount,
        CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) DeviceFlowMonitor.wirelessTransaction(amount, true);
    }
}
