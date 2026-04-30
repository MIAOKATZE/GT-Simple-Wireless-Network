package com.miaokatze.gtswn.common.items;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;

/**
 * GTswn 无线能量覆盖板物品（输入模式）
 */
public class GTSwnCoverEnergyWireless extends Item {
    public GTSwnCoverEnergyWireless() {
        super();
        setUnlocalizedName("GTswn_Cover_Energy_Wireless");
        setTextureName("gtswn:covers/wireless_connector_input");
        setMaxStackSize(64);
    }
}