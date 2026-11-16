// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.common.util.AuthoredTooltips;
import com.miaokatze.gtswn.common.util.DeviceTooltip;
import com.miaokatze.gtswn.common.util.DeviceTooltipOrder;
import com.miaokatze.miaokatzecore.api.tooltip.component.NativeTooltipPage;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.common.blocks.ItemMachines;

/** Runs after native ItemStack and GT tooltips have appended their state and advanced details. */
@SideOnly(Side.CLIENT)
public class DeviceTooltipHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.itemStack;
        String id = authoredId(stack);
        if (id == null) return;
        if (stack.getItem() instanceof ItemMachines) {
            NativeTooltipPage page = AuthoredTooltips.page(stack, id, Collections.emptyList());
            // Core matches the exact owned GT description and preserves its native structure page.
            if (page != null) page.apply(stack, event.toolTip);
            return;
        }
        String state = tr("gtswn.tooltip.section.state"), description = tr("gtswn.tooltip.section.description"),
            controls = tr("gtswn.tooltip.section.controls"), footer = DeviceTooltip.addedByLine();
        if (!DeviceTooltipOrder.hasSections(event.toolTip, state, description, controls, footer)) return;
        List<String> liveState = new ArrayList<>(
            event.toolTip.subList(event.toolTip.indexOf(state) + 1, event.toolTip.indexOf(description)));
        NativeTooltipPage page = AuthoredTooltips.page(stack, id, liveState);
        if (page == null) return;
        // Remove only our complete section range. Baubles, charge and other mods' lines remain intact.
        List<String> candidate = new ArrayList<>(event.toolTip);
        candidate.subList(candidate.indexOf(state), candidate.indexOf(footer) + 1)
            .clear();
        if (page.appendTo(stack, candidate)) {
            event.toolTip.clear();
            event.toolTip.addAll(candidate);
        }
    }

    static String authoredId(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return null;
        // Switching modes also changes the icon metadata. NBT, not the registration meta, selects the tap page.
        if (stack.getItem() instanceof WirelessEnergyTap) {
            String tap = GTSWNItemList.Wireless_Energy_Tap.name();
            return WirelessEnergyTap.getOutputModeStatic(stack) ? tap + ":output" : tap;
        }
        for (GTSWNItemList entry : GTSWNItemList.values()) {
            if (entry.hasBeenSet() && entry.getItem() == stack.getItem() && entry.getMeta() == stack.getItemDamage()) {
                return entry.name();
            }
        }
        return null;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
