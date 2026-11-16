// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.items.WirelessEnergyTap;

public class LinkModeTooltipTest {

    @Test
    public void wheelAndManualOutputStacksUseTheOutputPage() {
        ItemStack tap = new ItemStack(new WirelessEnergyTap());
        assertEquals("Wireless_Energy_Tap", DeviceTooltipHandler.authoredId(tap));
        tap.stackTagCompound = new NBTTagCompound();
        tap.stackTagCompound.setBoolean("OutputMode", true);
        tap.setItemDamage(1);
        assertEquals("Wireless_Energy_Tap:output", DeviceTooltipHandler.authoredId(tap));
        tap.stackTagCompound.setBoolean("OutputMode", false);
        assertEquals("Wireless_Energy_Tap", DeviceTooltipHandler.authoredId(tap));
    }
}
