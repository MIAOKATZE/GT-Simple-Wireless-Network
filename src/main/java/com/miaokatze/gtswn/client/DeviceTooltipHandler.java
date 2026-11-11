// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client;

import java.util.Arrays;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

import com.miaokatze.gtswn.common.assembly.MatrixAssemblyMember;
import com.miaokatze.gtswn.common.util.DeviceTooltip;
import com.miaokatze.gtswn.common.util.DeviceTooltipOrder;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.miaokatzecore.api.tooltip.TooltipPages;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.api.GregTechAPI;
import gregtech.common.blocks.ItemMachines;

/** Runs after native ItemStack and GT tooltips have appended their state and advanced details. */
@SideOnly(Side.CLIENT)
public class DeviceTooltipHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTooltip(ItemTooltipEvent event) {
        String footer = DeviceTooltip.addedByLine();
        if (!event.toolTip.contains(footer)) return;
        ItemStack stack = event.itemStack;
        int id = stack.getItemDamage();
        if (stack.getItem() instanceof ItemMachines && id >= 0
            && id < GregTechAPI.METATILEENTITIES.length
            && GregTechAPI.METATILEENTITIES[id] instanceof MatrixAssemblyMember) {
            List<String> own = Arrays.asList(GregTechAPI.METATILEENTITIES[id].getDescription());
            if (!DeviceTooltipOrder.matrixPage(event.toolTip, own, footer, TooltipPages.expanded(stack))) return;
        } else {
            String state = tr("gtswn.tooltip.section.state"), description = tr("gtswn.tooltip.section.description"),
                controls = tr("gtswn.tooltip.section.controls");
            if (!DeviceTooltipOrder.hasSections(event.toolTip, state, description, controls, footer)) return;
            DeviceTooltipOrder.normalize(event.toolTip, state, description, controls, footer);
            if (!TooltipPages.expanded(stack)) DeviceTooltipOrder.collapse(
                event.toolTip,
                description,
                controls,
                footer,
                Arrays.asList(
                    tr("gtswn.desc.wireless_monitor.line2"),
                    tr("gtswn.desc.wireless_monitor.line6"),
                    tr("gtswn.tooltip.tap.laser_pipe_cost"),
                    StatCollector.translateToLocalFormatted(
                        "gtswn.tooltip.quantum_node.power",
                        Config.quantumNodeIdlePowerUsage),
                    StatCollector.translateToLocalFormatted(
                        "gtswn.tooltip.quantum_incorporation.power",
                        Config.quantumIncorporationIdlePowerUsage)));
        }
        TooltipPages.hint(stack, event.toolTip.subList(0, event.toolTip.indexOf(footer)), false);
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
