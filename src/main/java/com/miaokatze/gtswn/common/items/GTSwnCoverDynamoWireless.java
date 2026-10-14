package com.miaokatze.gtswn.common.items;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.common.util.DeviceTooltip;

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

    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> lines, boolean advanced) {
        DeviceTooltip.state(lines);
        DeviceTooltip.add(lines, "gtswn.tooltip.cover.dynamo.state", "gtswn.tooltip.cover.item_state");
        DeviceTooltip.description(lines, "gtswn.tooltip.cover.dynamo.description", "gtswn.tooltip.cover.configuration");
        DeviceTooltip.controls(
            lines,
            "gtswn.tooltip.cover.usage.terminal",
            "gtswn.tooltip.cover.usage.status",
            "gtswn.tooltip.cover.usage.gui");
        DeviceTooltip.finish(lines);
    }
}
