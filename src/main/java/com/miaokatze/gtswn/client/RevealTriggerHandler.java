package com.miaokatze.gtswn.client;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.MouseEvent;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketDeviceTerminalVisualAction;
import com.miaokatze.gtswn.network.PacketTerminalGesture;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Link: Alt opens UI, Alt+Shift charges. Device: Alt charges in air. */
public class RevealTriggerHandler {

    @SubscribeEvent
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
        boolean device = held.getItem() instanceof ItemDeviceInfoTerminal;
        boolean alt = QuantumIncorporationClientHandler.isAltDown();
        boolean shift = QuantumIncorporationClientHandler.isShiftDown();
        if (TerminalGestureRules.canToggleVisualization(device, alt, shift)) {
            event.setCanceled(true);
            TerminalRevealCharge.cancel();
            java.util.UUID id = ItemDeviceInfoTerminal.readTerminalId(held);
            GTSWNPacketHandler.NETWORK
                .sendToServer(PacketDeviceTerminalVisualAction.toggle(id == null ? new java.util.UUID(0L, 0L) : id));
            return;
        }
        if (device && DeviceTerminalVisualizationState.isActiveHeld() && !alt && !shift) {
            String target = DeviceTerminalVisualizationState.currentTargetKey();
            if (target != null) {
                event.setCanceled(true);
                TerminalRevealCharge.cancel();
                GTSWNPacketHandler.NETWORK.sendToServer(
                    PacketDeviceTerminalVisualAction.teleport(ItemDeviceInfoTerminal.readTerminalId(held), target));
                return;
            }
        }
        if (!alt || !TerminalRevealCharge.isPointingAtAir(mc)) return;
        if (held.getItem() instanceof ItemDeviceInfoTerminal) {
            event.setCanceled(true);
            TerminalRevealCharge.start(mc);
            return;
        }
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
