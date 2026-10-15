package com.miaokatze.gtswn.common.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import gregtech.api.enums.GTValues;

public class GTTierUtilTest {

    @Test
    public void monitorUsesNativeTierColorsIncludingHighTierStyles() {
        for (int tier = 0; tier < GTTierUtil.TIER_NAMES.length; tier++) {
            assertEquals(GTValues.TIER_COLORS[tier], GTTierUtil.TIER_COLORS[tier]);
            assertEquals(
                GTValues.TIER_COLORS[tier] + "4.0A " + GTTierUtil.TIER_NAMES[tier],
                GTTierUtil.formatGTPower(4D * GTValues.V[tier]));
        }
        assertEquals("§e-2.0A HV", GTTierUtil.formatGTPowerDecimal(-1024));
    }

    @Test
    public void textureRgbMatchesMinecraftPaletteAndIgnoresTextStyles() {
        int[] colors = { 0xFF5555, 0x00AA00, 0xFFAA00, 0xFFFF55, 0x555555, 0x5555FF, 0xFF55FF, 0x55FFFF, 0x00AA00,
            0xAA0000, 0xAA00AA, 0x0000AA, 0xFF5555, 0xAA0000, 0xFFFFFF };
        for (int tier = 0; tier < colors.length; tier++) {
            assertEquals(colors[tier], GTTierUtil.tierRgb(tier));
        }
        assertEquals(-1, GTTierUtil.tierRgb(-1));
        assertEquals(-1, GTTierUtil.tierRgb(15));
        assertEquals(-1, GTTierUtil.tierRgb(Integer.MAX_VALUE));
    }
}
