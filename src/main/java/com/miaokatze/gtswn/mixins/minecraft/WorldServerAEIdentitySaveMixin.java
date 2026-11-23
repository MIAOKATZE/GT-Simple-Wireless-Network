// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.mixins.minecraft;

import net.minecraft.util.IProgressUpdate;
import net.minecraft.world.WorldServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.common.monitoring.ae.AEGridIdentityData;

@Mixin(WorldServer.class)
public abstract class WorldServerAEIdentitySaveMixin {

    @Inject(
        method = "saveLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/storage/MapStorage;saveAllData()V", ordinal = 0),
        require = 1)
    private void gtswn$saveAEIdentitiesBeforeChunks(CallbackInfo ci) {
        AEGridIdentityData.beforeWorldSave((WorldServer) (Object) this);
    }

    @Inject(method = "saveAllChunks", at = @At("TAIL"), require = 1)
    private void gtswn$confirmAEIdentityWorldSave(boolean all, IProgressUpdate progress, CallbackInfo ci) {
        AEGridIdentityData.afterWorldSave((WorldServer) (Object) this);
    }
}
