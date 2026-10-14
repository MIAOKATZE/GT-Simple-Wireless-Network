package com.miaokatze.gtswn.client;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketTerminalGesture;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Link: Alt opens UI, Alt+Shift charges in air. Device gestures share mouse/Forge/item dispatch. */
public class RevealTriggerHandler {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent event) {
        if (event.button != 1) return;
        if (!event.buttonstate) {
            TerminalRevealCharge.cancel();
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.thePlayer == null || mc.theWorld == null) return;
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (held == null
            || !(held.getItem() instanceof WirelessEnergyTap || held.getItem() instanceof ItemDeviceInfoTerminal))
            return;
        if (held.getItem() instanceof ItemDeviceInfoTerminal) {
            if (DeviceTerminalVisualizationState.handleRightClick()) event.setCanceled(true);
            return;
        }
        boolean alt = QuantumIncorporationClientHandler.isAltDown();
        if (!alt || !TerminalRevealCharge.isPointingAtAir(mc)) return;
        if (!QuantumIncorporationClientHandler.isShiftDown()) {
            if (held.getItem() instanceof WirelessEnergyTap) {
                event.setCanceled(true);
                GTSWNPacketHandler.NETWORK.sendToServer(new PacketTerminalGesture(PacketTerminalGesture.OPEN_LINK));
            }
            return;
        }
        event.setCanceled(true);
        TerminalRevealCharge.start(mc);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!event.world.isRemote || event.entityPlayer != Minecraft.getMinecraft().thePlayer
            || (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK
                && event.action != PlayerInteractEvent.Action.RIGHT_CLICK_AIR))
            return;
        if (DeviceTerminalVisualizationState.handleRightClick()) event.setCanceled(true);
    }
}
