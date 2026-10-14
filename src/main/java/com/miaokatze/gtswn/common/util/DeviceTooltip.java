package com.miaokatze.gtswn.common.util;

import java.util.List;

import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

/** Shared device tooltip sections and the GTSR-style, common-side-safe brand footer. */
public final class DeviceTooltip {

    private DeviceTooltip() {}

    public static void state(List<String> lines) {
        lines.add(StatCollector.translateToLocal("gtswn.tooltip.section.state"));
    }

    public static void description(List<String> lines, String... keys) {
        section(lines, "description", keys);
    }

    public static void controls(List<String> lines, String... keys) {
        section(lines, "controls", keys);
    }

    private static void section(List<String> lines, String section, String... keys) {
        lines.add("");
        lines.add(StatCollector.translateToLocal("gtswn.tooltip.section." + section));
        add(lines, keys);
    }

    public static void add(List<String> lines, String... keys) {
        for (String key : keys) lines.add(StatCollector.translateToLocal(key));
    }

    public static void finish(List<String> lines) {
        lines.add("");
        lines.add(addedByLine());
    }

    /** Matches GTSRUtils.getAddedByLine's segmented EnumChatFormatting style. */
    public static String addedByLine() {
        return EnumChatFormatting.WHITE + StatCollector.translateToLocal("gtswn.tooltip.added_by")
            + " "
            + EnumChatFormatting.AQUA
            + "GT"
            + EnumChatFormatting.GREEN
            + "-"
            + EnumChatFormatting.GOLD
            + "Simple"
            + EnumChatFormatting.RED
            + "-"
            + EnumChatFormatting.BLUE
            + "Wireless"
            + EnumChatFormatting.LIGHT_PURPLE
            + "-"
            + EnumChatFormatting.AQUA
            + "Network"
            + EnumChatFormatting.RESET;
    }
}
