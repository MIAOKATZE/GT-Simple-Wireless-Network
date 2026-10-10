package com.miaokatze.gtswn.client;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.MouseEvent;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketTerminalGesture;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 链路终端 Alt 空气开UI；链路与设备终端 Alt+Shift 空气蓄力。 */
public class RevealTriggerHandler {

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (event.button != 1) return;
        if (!event.buttonstate) {
            TerminalRevealCharge.cancel();
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || !QuantumIncorporationClientHandler.isAltDown()) return;
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (held == null
            || !(held.getItem() instanceof WirelessEnergyTap || held.getItem() instanceof ItemDeviceInfoTerminal)
            || !TerminalRevealCharge.isPointingAtAir(mc)) return;
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
}
