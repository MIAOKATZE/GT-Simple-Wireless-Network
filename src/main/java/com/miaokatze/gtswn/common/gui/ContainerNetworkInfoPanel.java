package com.miaokatze.gtswn.common.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;

public class ContainerNetworkInfoPanel extends Container {

    private final TileEntityNetworkInfoPanel panel;

    public ContainerNetworkInfoPanel(TileEntityNetworkInfoPanel panel) {
        this.panel = panel;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return panel != null && panel.getWorldObj()
            .getTileEntity(panel.xCoord, panel.yCoord, panel.zCoord) == panel
            && player.getDistanceSq(panel.xCoord + 0.5D, panel.yCoord + 0.5D, panel.zCoord + 0.5D) <= 64.0D;
    }
}
