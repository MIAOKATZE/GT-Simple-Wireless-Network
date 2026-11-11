// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.mixins.baubles;

import java.util.Arrays;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import baubles.common.container.InventoryBaubles;
import baubles.common.lib.PlayerHandler;

/** An open Baubles container and the wheel must continue sharing the same inventory after a full sync. */
@Pseudo
@Mixin(targets = "baubles.common.network.PacketSyncAllBauble", remap = false)
public abstract class PacketSyncAllBaubleMixin {

    @Redirect(
        method = "onMessage(Lbaubles/common/network/PacketSyncAllBauble;Lcpw/mods/fml/common/network/simpleimpl/MessageContext;)Lcpw/mods/fml/common/network/simpleimpl/IMessage;",
        at = @At(
            value = "INVOKE",
            target = "Lbaubles/common/lib/PlayerHandler;clearPlayerBaubles(Lnet/minecraft/entity/player/EntityPlayer;)V"),
        remap = false)
    private void gtswn$preserveOpenInventory(EntityPlayer player) {
        InventoryBaubles inventory = PlayerHandler.getPlayerBaubles(player);
        if (inventory.player.get() == player) Arrays.fill(inventory.stackList, null);
        else PlayerHandler.clearPlayerBaubles(player);
    }
}
