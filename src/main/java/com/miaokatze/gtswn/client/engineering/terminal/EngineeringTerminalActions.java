package com.miaokatze.gtswn.client.engineering.terminal;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;

import com.miaokatze.gtswn.client.engineering.EngineeringModeController;
import com.miaokatze.gtswn.client.engineering.FactoryDisplayPreferences;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketEngineeringDeviceAction;

/** Factory display is a client preference; scans retain the server-validated terminal source. */
public final class EngineeringTerminalActions {

    private EngineeringTerminalActions() {}

    public static boolean canOpen(UUID terminalId) {
        return find(terminalId) != null;
    }

    public static void openGrid(UUID terminalId) {
        ItemStack stack = find(terminalId);
        if (stack == null) return;
        if (!FactoryDisplayPreferences.deviceVisualization()) toggleDeviceVisualization();
        EngineeringModeController.open();
    }

    public static void scanDevice() {
        send(PacketEngineeringDeviceAction.SCAN);
    }

    public static void toggleDeviceVisualization() {
        FactoryDisplayPreferences.toggleDeviceVisualization();
    }

    /** This preference never grants a normal terminal ACK/session or visual teleport permission. */
    public static boolean deviceVisualizationEnabled() {
        return FactoryDisplayPreferences.deviceVisualization();
    }

    public static UUID visualizationTerminalId() {
        if (!EngineeringModeController.isActive() || !deviceVisualizationEnabled()) return null;
        Source source = deviceSource();
        UUID id = source.present() ? ItemDeviceInfoTerminal.readTerminalId(source.stack) : null;
        return id != null && EngineeringTerminalHudSnapshot.visualizationSnapshot(id) != null ? id : null;
    }

    private static void send(byte action) {
        Source source = deviceSource();
        if (!source.present()) {
            EntityPlayer player = Minecraft.getMinecraft().thePlayer;
            if (player != null)
                player.addChatMessage(new ChatComponentTranslation("gtswn.engineering.device.source_unavailable"));
            return;
        }
        GTSWNPacketHandler.NETWORK.sendToServer(new PacketEngineeringDeviceAction(action, source));
    }

    private static Source deviceSource() {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        return player == null || player.isDead || player.getHealth() <= 0 ? Source.EMPTY
            : EngineeringTerminalSources.resolve(player, 2);
    }

    private static ItemStack find(UUID terminalId) {
        Source source = deviceSource();
        return terminalId != null && source.present()
            && terminalId.equals(ItemDeviceInfoTerminal.readTerminalId(source.stack)) ? source.stack : null;
    }
}
