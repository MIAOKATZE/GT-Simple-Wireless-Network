package com.miaokatze.gtswn.common.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.IGuiHandler;

public class GTSWNGuiHandler implements IGuiHandler {

    public static final int QUANTUM_TERMINAL = 20;
    public static final int LINK_TERMINAL = 21;

    public GTSWNGuiHandler() {
        TerminalGuiFactory.init();
        CoverUiRedirectQueue.init();
    }

    public static void openTerminal(EntityPlayer player, boolean quantum) {
        if (player instanceof EntityPlayerMP serverPlayer) TerminalGuiFactory.open(serverPlayer, quantum);
    }

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return null;
    }
}
