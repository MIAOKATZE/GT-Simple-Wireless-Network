package com.miaokatze.gtswn.client;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.MouseEvent;

import com.miaokatze.gtswn.common.items.WirelessEnergyTap;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Client-only Alt + right-click charge gesture for the wireless link terminal. */
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
        if (held == null || !(held.getItem() instanceof WirelessEnergyTap)) return;
        event.setCanceled(true);
        TerminalRevealCharge.start(mc);
    }
}
