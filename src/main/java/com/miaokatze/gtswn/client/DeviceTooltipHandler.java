package com.miaokatze.gtswn.client;

import net.minecraft.util.StatCollector;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

import com.miaokatze.gtswn.common.util.DeviceTooltip;
import com.miaokatze.gtswn.common.util.DeviceTooltipOrder;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Runs after native ItemStack and GT tooltips have appended their state and advanced details. */
@SideOnly(Side.CLIENT)
public class DeviceTooltipHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTooltip(ItemTooltipEvent event) {
        DeviceTooltipOrder.normalize(
            event.toolTip,
            StatCollector.translateToLocal("gtswn.tooltip.section.state"),
            StatCollector.translateToLocal("gtswn.tooltip.section.description"),
            StatCollector.translateToLocal("gtswn.tooltip.section.controls"),
            DeviceTooltip.addedByLine());
    }
}
