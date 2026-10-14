package com.miaokatze.gtswn.common.items;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.common.util.DeviceTooltip;

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

    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> lines, boolean advanced) {
        DeviceTooltip.state(lines);
        DeviceTooltip.add(lines, "gtswn.tooltip.cover.energy.state", "gtswn.tooltip.cover.item_state");
        DeviceTooltip.description(lines, "gtswn.tooltip.cover.energy.description", "gtswn.tooltip.cover.configuration");
        DeviceTooltip.controls(
            lines,
            "gtswn.tooltip.cover.usage.terminal",
            "gtswn.tooltip.cover.usage.status",
            "gtswn.tooltip.cover.usage.gui");
        DeviceTooltip.finish(lines);
    }
}
