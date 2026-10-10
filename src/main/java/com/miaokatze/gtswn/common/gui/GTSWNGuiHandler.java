package com.miaokatze.gtswn.common.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.IGuiHandler;

public class GTSWNGuiHandler implements IGuiHandler {

    public static final int QUANTUM_TERMINAL = 20;
    public static final int LINK_TERMINAL = 21;

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id != QUANTUM_TERMINAL && id != LINK_TERMINAL) return null;
        ContainerTerminal container = new ContainerTerminal(player, id == QUANTUM_TERMINAL);
        return container.canInteractWith(player) ? container : null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id != QUANTUM_TERMINAL && id != LINK_TERMINAL) return null;
        ContainerTerminal container = new ContainerTerminal(player, id == QUANTUM_TERMINAL);
        return container.canInteractWith(player) ? new com.miaokatze.gtswn.client.gui.GuiQuantumTerminal(container)
            : null;
    }
}
