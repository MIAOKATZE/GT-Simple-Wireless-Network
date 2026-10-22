package com.miaokatze.gtswn.client.engineering.terminal;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.client.engineering.EngineeringModeController;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketDeviceTerminalVisualAction;

/** A bounded GUI intent; enabling visual data still requires the normal server acknowledgement. */
public final class EngineeringTerminalActions {

    private EngineeringTerminalActions() {}

    public static boolean canOpen(UUID terminalId) {
        return find(terminalId) != null;
    }

    public static void openGrid(UUID terminalId) {
        ItemStack stack = find(terminalId);
        if (stack == null) return;
        if (!ItemDeviceInfoTerminal.isVisualizationEnabled(stack))
            GTSWNPacketHandler.NETWORK.sendToServer(PacketDeviceTerminalVisualAction.toggle(terminalId));
        EngineeringModeController.open();
    }

    private static ItemStack find(UUID terminalId) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null || terminalId == null || player.isDead || player.getHealth() <= 0) return null;
        IInventory baubles = TerminalBaubles.inventory(player);
        if (baubles != null) for (int i = 0; i < baubles.getSizeInventory(); i++) {
            ItemStack candidate = baubles.getStackInSlot(i);
            if (matches(candidate, terminalId)) return candidate;
        }
        for (ItemStack candidate : player.inventory.mainInventory) if (matches(candidate, terminalId)) return candidate;
        return null;
    }

    private static boolean matches(ItemStack stack, UUID terminalId) {
        return stack != null && stack.getItem() instanceof ItemDeviceInfoTerminal
            && terminalId.equals(ItemDeviceInfoTerminal.readTerminalId(stack));
    }
}
