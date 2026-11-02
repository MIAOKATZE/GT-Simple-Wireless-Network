package com.miaokatze.gtswn.common.items;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.common.util.DeviceTooltip;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** The independent, consumable payment item for device terminal travel. */
public final class ItemNetworkTeleportAnchor extends Item {

    public ItemNetworkTeleportAnchor() {
        setUnlocalizedName("NetworkTeleportAnchor_GTswn");
        setTextureName("gtswn:Network_Teleport_Anchor");
        setCreativeTab(CreativeTabs.tabMisc);
        setMaxStackSize(64);
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings("unchecked")
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        DeviceTooltip.state(tooltip);
        tooltip.add(StatCollector.translateToLocalFormatted("gtswn.tooltip.teleport_anchor.count", stack.stackSize));
        DeviceTooltip.description(tooltip, "gtswn.tooltip.teleport_anchor.1");
        DeviceTooltip.controls(tooltip, "gtswn.tooltip.teleport_anchor.2");
        DeviceTooltip.finish(tooltip);
    }
}
