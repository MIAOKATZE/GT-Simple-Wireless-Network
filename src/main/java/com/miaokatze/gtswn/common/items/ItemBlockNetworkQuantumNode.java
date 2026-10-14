package com.miaokatze.gtswn.common.items;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.common.util.DeviceTooltip;
import com.miaokatze.gtswn.config.Config;

/** Tooltip only; placement keeps the standard ItemBlock behavior. */
public class ItemBlockNetworkQuantumNode extends ItemBlock {

    public ItemBlockNetworkQuantumNode(Block block) {
        super(block);
    }

    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> lines, boolean advanced) {
        DeviceTooltip.state(lines);
        DeviceTooltip.add(lines, "gtswn.tooltip.quantum_node.item_state");
        DeviceTooltip.description(lines, "gtswn.tooltip.quantum_node.description", "gtswn.tooltip.quantum_node.loaded");
        lines.add(
            StatCollector
                .translateToLocalFormatted("gtswn.tooltip.quantum_node.power", Config.quantumNodeIdlePowerUsage));
        DeviceTooltip.controls(
            lines,
            "gtswn.tooltip.quantum_node.usage.place",
            "gtswn.tooltip.quantum_node.usage.status",
            "gtswn.tooltip.quantum_node.usage.remove");
        DeviceTooltip.finish(lines);
    }
}
