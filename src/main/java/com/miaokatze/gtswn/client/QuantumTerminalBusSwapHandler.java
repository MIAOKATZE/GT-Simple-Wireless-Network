package com.miaokatze.gtswn.client;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumBusTarget;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketQuantumTerminalSwapBus;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Intercepts terminal bus placement and controlled core removal before AE2 part interaction. */
public class QuantumTerminalBusSwapHandler {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!event.entityPlayer.worldObj.isRemote || event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK
            || event.face < 0
            || event.face >= 6) {
            return;
        }
        EntityPlayer player = event.entityPlayer;
        if (GTSimpleWirelessNetwork.proxy.isQuantumIncorporationMode()) {
            return;
        }
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemNetworkQuantumTerminal)) {
            return;
        }
        boolean removeCore = player.isSneaking()
            && player.worldObj.getTileEntity(event.x, event.y, event.z) instanceof TileEntityNetworkQuantumNode;
        if (!removeCore && findSwapTarget(player, event.x, event.y, event.z, event.face) == null) {
            return;
        }
        event.setCanceled(true);
        GTSWNPacketHandler.NETWORK
            .sendToServer(new PacketQuantumTerminalSwapBus(event.x, event.y, event.z, event.face, removeCore));
    }

    /** The preview, native item path and server packet use the same clicked/adjacent target resolver. */
    public static QuantumBusTarget findSwapTarget(EntityPlayer player, int x, int y, int z, int side) {
        if (GTSimpleWirelessNetwork.proxy.isQuantumIncorporationMode() || player.isSneaking()) {
            return null;
        }
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemNetworkQuantumTerminal)
            || !ItemNetworkQuantumTerminal.isBound(held)) {
            return null;
        }
        if (QuantumIncorporationClientState.registered(player.worldObj, x, y, z)) return null;
        QuantumBusTarget target = QuantumBusTarget.resolve(player.worldObj, x, y, z, side);
        return target != null
            && !QuantumIncorporationClientState.registered(player.worldObj, target.x, target.y, target.z) ? target
                : null;
    }
}
