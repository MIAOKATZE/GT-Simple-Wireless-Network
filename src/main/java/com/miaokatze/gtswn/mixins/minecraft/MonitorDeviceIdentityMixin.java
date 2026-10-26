package com.miaokatze.gtswn.mixins.minecraft;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.common.monitoring.MonitorDeviceIdentity;

/** No UUID is allocated for unobserved legacy tiles. The profile import authority assigns the marker. */
@Mixin(TileEntity.class)
public abstract class MonitorDeviceIdentityMixin implements MonitorDeviceIdentity {

    @Unique
    private String gtswn$monitorDeviceIdentity = "";

    @Override
    public String gtswn$getMonitorDeviceIdentity() {
        return gtswn$monitorDeviceIdentity;
    }

    @Override
    public void gtswn$setMonitorDeviceIdentity(String identity) {
        gtswn$monitorDeviceIdentity = identity == null ? "" : identity;
    }

    @Inject(method = "writeToNBT", at = @At("RETURN"))
    private void gtswn$writeMonitorIdentity(NBTTagCompound tag, CallbackInfo callback) {
        if (gtswn$monitorDeviceIdentity.isEmpty()) tag.removeTag("GTSWN_MonitorSource");
        else tag.setString("GTSWN_MonitorSource", gtswn$monitorDeviceIdentity);
    }

    @Inject(method = "readFromNBT", at = @At("RETURN"))
    private void gtswn$readMonitorIdentity(NBTTagCompound tag, CallbackInfo callback) {
        gtswn$monitorDeviceIdentity = tag.getString("GTSWN_MonitorSource");
    }
}
