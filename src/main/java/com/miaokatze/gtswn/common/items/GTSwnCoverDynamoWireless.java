package com.miaokatze.gtswn.common.items;

import net.minecraft.item.Item;

/**
 * GTswn 无线动力覆盖板物品（输出模式）
 */
public class GTSwnCoverDynamoWireless extends Item {

    public GTSwnCoverDynamoWireless() {
        super();
        setUnlocalizedName("GTswn_Cover_Dynamo_Wireless");
        setTextureName("gtswn:covers/wireless_connector_output");
        setMaxStackSize(64);
    }
}
