package com.miaokatze.gtswn.register;

import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Network_Info_Panel;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Network_Info_Panel_Extender;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.common.block.BlockNetworkInfoPanel;
import com.miaokatze.gtswn.common.block.BlockNetworkInfoPanelExtender;
import com.miaokatze.gtswn.common.items.ItemBlockNetworkInfoPanel;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanelExtender;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.registry.GameRegistry;

public class BlockRegistrar {

    public static Block networkInfoPanel;
    public static Block networkInfoPanelExtender;

    public static void init() {
        GTSimpleWirelessNetwork.LOG.info("Registering GTSWN blocks...");
        networkInfoPanel = new BlockNetworkInfoPanel();
        networkInfoPanelExtender = new BlockNetworkInfoPanelExtender();

        GameRegistry.registerBlock(networkInfoPanel, ItemBlockNetworkInfoPanel.class, "NetworkInfoPanel_GTswn");
        GameRegistry
            .registerBlock(networkInfoPanelExtender, ItemBlockNetworkInfoPanel.class, "NetworkInfoPanelExtender_GTswn");

        Network_Info_Panel.set(new ItemStack(networkInfoPanel, 1, 0));
        Network_Info_Panel_Extender.set(new ItemStack(networkInfoPanelExtender, 1, 0));
        CreativeTabManager.addItemToTab(Network_Info_Panel.get(1));
        CreativeTabManager.addItemToTab(Network_Info_Panel_Extender.get(1));

        GameRegistry.registerTileEntity(TileEntityNetworkInfoPanel.class, "gtswn.network_info_panel");
        GameRegistry.registerTileEntity(TileEntityNetworkInfoPanelExtender.class, "gtswn.network_info_panel_extender");
        GTSimpleWirelessNetwork.LOG.info("GTSWN blocks registered.");
    }
}
