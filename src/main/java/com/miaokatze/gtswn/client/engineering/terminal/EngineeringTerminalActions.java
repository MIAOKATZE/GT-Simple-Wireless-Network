package com.miaokatze.gtswn.client.engineering.terminal;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;

import com.miaokatze.gtswn.client.DeviceTerminalVisualizationState;
import com.miaokatze.gtswn.client.engineering.EngineeringModeController;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketEngineeringDeviceAction;

/** Factory intent uses the resolved provider; visual state changes only after the normal server ACK. */
public final class EngineeringTerminalActions {

    private EngineeringTerminalActions() {}

    public static boolean canOpen(UUID terminalId) {
        return find(terminalId) != null;
    }

    public static void openGrid(UUID terminalId) {
        ItemStack stack = find(terminalId);
        if (stack == null) return;
        if (!ItemDeviceInfoTerminal.isVisualizationEnabled(stack)) toggleDeviceVisualization();
        EngineeringModeController.open();
    }

    public static void scanDevice() {
        send(PacketEngineeringDeviceAction.SCAN);
    }

    public static void toggleDeviceVisualization() {
        send(PacketEngineeringDeviceAction.TOGGLE_VISUALIZATION);
    }

    /** A saved item flag alone is not an acknowledged rendering session. */
    public static boolean deviceVisualizationEnabled() {
        Source source = deviceSource();
        UUID id = source.present() ? ItemDeviceInfoTerminal.readTerminalId(source.stack) : null;
        return id != null && id.equals(DeviceTerminalVisualizationState.visualizationTerminalId());
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
