package com.miaokatze.gtswn.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.miaokatze.gtswn.common.gui.ContainerTerminal;
import com.miaokatze.gtswn.common.gui.TerminalGuiFactory;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestQuantumTerminalData;

/** Client screen for the server-opened MUI2 terminal and its authoritative snapshot protocol. */
public class GuiQuantumTerminal extends ModularScreen {

    private static QuantumNetworkData latestData;
    private static final int POLL_INTERVAL_TICKS = 10;
    private final EntityPlayer player;
    private int pollTimer;

    public GuiQuantumTerminal(EntityPlayer player, ModularPanel panel) {
        super("gtswn", panel);
        this.player = player;
        receiveData(null);
    }

    public static void receiveData(QuantumNetworkData data) {
        latestData = data;
        TerminalGuiFactory.receiveData(data);
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (!(player.openContainer instanceof ContainerTerminal terminalContainer)
            || !terminalContainer.canInteractWith(player)) {
            Minecraft.getMinecraft().thePlayer.closeScreen();
            return;
        }
        ItemStack held = player.getHeldItem();
        if (terminalContainer.quantum && pollTimer++ % POLL_INTERVAL_TICKS == 0
            && held != null
            && ItemNetworkQuantumTerminal.isBound(held))
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestQuantumTerminalData());
    }

    static boolean canToggleSafety(QuantumNetworkData data) {
        return TerminalGuiFactory.canToggleSafety(data);
    }

    static String safetyReason(QuantumNetworkData data) {
        return TerminalGuiFactory.safetyReason(data);
    }
}
