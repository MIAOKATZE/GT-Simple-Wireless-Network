package com.miaokatze.gtswn.client;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;

/** Server ACK is the only source of an enabled travel session. */
public final class DeviceTerminalVisualizationState {

    private static UUID terminalId;

    private DeviceTerminalVisualizationState() {}

    public static void apply(UUID id, boolean enabled) {
        if (id == null) return;
        if (enabled) terminalId = id;
        else if (id.equals(terminalId)) terminalId = null;
    }

    public static boolean active(UUID id) {
        return id != null && id.equals(terminalId);
    }

    public static UUID heldTerminalId() {
        Minecraft mc = Minecraft.getMinecraft();
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        return held != null && held.getItem() instanceof ItemDeviceInfoTerminal
            ? ItemDeviceInfoTerminal.readTerminalId(held)
            : null;
    }

    public static boolean isActiveHeld() {
        return active(heldTerminalId());
    }

    public static String currentTargetKey() {
        return isActiveHeld() ? DeviceTerminalAnchorRenderer.INSTANCE.currentTargetKey() : null;
    }

    public static void clear() {
        terminalId = null;
    }
}
