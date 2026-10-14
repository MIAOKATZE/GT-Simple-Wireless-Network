package com.miaokatze.gtswn.common.items;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.common.util.DeviceTooltip;
import com.miaokatze.gtswn.config.Config;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Single-use material for incorporating a full AE block into a quantum network. */
public class ItemQuantumIncorporationAnchor extends Item {

    public ItemQuantumIncorporationAnchor() {
        setUnlocalizedName("QuantumIncorporationAnchor_GTswn");
        setTextureName("gtswn:Quantum_Incorporation_Anchor");
        setCreativeTab(CreativeTabs.tabMisc);
        setMaxStackSize(64);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, EntityPlayer player, List list, boolean advanced) {
        DeviceTooltip.state(list);
        list.add(StatCollector.translateToLocalFormatted("gtswn.tooltip.quantum_anchor.count", stack.stackSize));
        DeviceTooltip.description(
            list,
            "gtswn.tooltip.quantum_anchor.description",
            "gtswn.tooltip.quantum_anchor.usage",
            "gtswn.tooltip.quantum_anchor.release");
        list.add(
            StatCollector.translateToLocalFormatted(
                "gtswn.tooltip.quantum_incorporation.power",
                Config.quantumIncorporationIdlePowerUsage));
        DeviceTooltip.controls(
            list,
            "gtswn.tooltip.quantum_anchor.usage.terminal",
            "gtswn.tooltip.quantum_anchor.usage.preload");
        DeviceTooltip.finish(list);
    }
}
