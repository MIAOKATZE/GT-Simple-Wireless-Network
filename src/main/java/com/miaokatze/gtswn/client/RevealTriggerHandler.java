package com.miaokatze.gtswn.client;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketDeviceTerminalVisualAction;
import com.miaokatze.gtswn.network.PacketTerminalGesture;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Link: Alt opens UI, Alt+Shift charges. Device: Alt charges in air. */
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
        boolean device = held.getItem() instanceof ItemDeviceInfoTerminal;
        boolean alt = QuantumIncorporationClientHandler.isAltDown();
        boolean shift = QuantumIncorporationClientHandler.isShiftDown();
        if (TerminalGestureRules.canToggleVisualization(device, alt, shift)) {
            event.setCanceled(true);
            TerminalRevealCharge.cancel();
            net.minecraft.client.settings.KeyBinding
                .setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            java.util.UUID id = ItemDeviceInfoTerminal.readTerminalId(held);
            GTSWNPacketHandler.NETWORK
                .sendToServer(PacketDeviceTerminalVisualAction.toggle(id == null ? new java.util.UUID(0L, 0L) : id));
            return;
        }
        if (device && DeviceTerminalVisualizationState.handleRightClick()) {
            event.setCanceled(true);
            TerminalRevealCharge.cancel();
            return;
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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!event.world.isRemote || event.entityPlayer != Minecraft.getMinecraft().thePlayer
            || (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK
                && event.action != PlayerInteractEvent.Action.RIGHT_CLICK_AIR))
            return;
        if (DeviceTerminalVisualizationState.handleRightClick()) event.setCanceled(true);
    }
}
