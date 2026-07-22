package com.miaokatze.gtswn.register;

import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.ME_Network_Quantum_Node;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Network_Info_Panel;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Network_Info_Panel_Extender;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.common.block.BlockNetworkInfoPanel;
import com.miaokatze.gtswn.common.block.BlockNetworkInfoPanelExtender;
import com.miaokatze.gtswn.common.block.BlockNetworkQuantumNode;
import com.miaokatze.gtswn.common.items.ItemBlockNetworkInfoPanel;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanelExtender;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.registry.GameRegistry;

public class BlockRegistrar {

    public static Block networkInfoPanel;
    public static Block networkInfoPanelExtender;
    /** ME 网络量子节点方块实例（T1 注册骨架） */
    public static Block networkQuantumNode;

    public static void init() {
        GTSimpleWirelessNetwork.LOG.info("Registering GTSWN blocks...");
        networkInfoPanel = new BlockNetworkInfoPanel();
        networkInfoPanelExtender = new BlockNetworkInfoPanelExtender();
        networkQuantumNode = new BlockNetworkQuantumNode();

        GameRegistry.registerBlock(networkInfoPanel, ItemBlockNetworkInfoPanel.class, "NetworkInfoPanel_GTswn");
        GameRegistry
            .registerBlock(networkInfoPanelExtender, ItemBlockNetworkInfoPanel.class, "NetworkInfoPanelExtender_GTswn");
        // 量子节点无特殊 ItemBlock 需求，使用默认 ItemBlock 注册
        GameRegistry.registerBlock(networkQuantumNode, "NetworkQuantumNode_GTswn");

        Network_Info_Panel.set(new ItemStack(networkInfoPanel, 1, 0));
        Network_Info_Panel_Extender.set(new ItemStack(networkInfoPanelExtender, 1, 0));
        ME_Network_Quantum_Node.set(new ItemStack(networkQuantumNode, 1, 0));
        CreativeTabManager.addItemToTab(Network_Info_Panel.get(1));
        CreativeTabManager.addItemToTab(Network_Info_Panel_Extender.get(1));
        CreativeTabManager.addItemToTab(ME_Network_Quantum_Node.get(1));

        GameRegistry.registerTileEntity(TileEntityNetworkInfoPanel.class, "gtswn.network_info_panel");
        GameRegistry.registerTileEntity(TileEntityNetworkInfoPanelExtender.class, "gtswn.network_info_panel_extender");
        GameRegistry.registerTileEntity(TileEntityNetworkQuantumNode.class, "gtswn.network_quantum_node");
        GTSimpleWirelessNetwork.LOG.info("GTSWN blocks registered.");
    }
}
