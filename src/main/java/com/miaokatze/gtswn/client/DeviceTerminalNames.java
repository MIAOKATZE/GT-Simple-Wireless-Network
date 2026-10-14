package com.miaokatze.gtswn.client;

import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/** Translate machine names using this client's GT registry and active language. */
public final class DeviceTerminalNames {

    private DeviceTerminalNames() {}

    public static String localName(Entry entry) {
        return hasOverride(entry) ? styledName(entry.displayName, entry.nameColor, entry.nameBold) : defaultName(entry);
    }

    public static boolean hasOverride(Entry entry) {
        return entry.displayName != null && !entry.displayName.isEmpty();
    }

    public static String plainName(Entry entry) {
        return EnumChatFormatting.getTextWithoutFormattingCodes(localName(entry));
    }

    public static String styledName(String name, int color, boolean bold) {
        return "\u00a7" + "0123456789abcdef".charAt(Math.max(0, Math.min(15, color)))
            + (bold ? "\u00a7l" : "")
            + (name == null ? "" : name);
    }

    public static String defaultName(Entry entry) {
        int id = entry.metaTileId;
        if (id >= 0 && id < GregTechAPI.METATILEENTITIES.length) {
            IMetaTileEntity machine = GregTechAPI.METATILEENTITIES[id];
            if (machine != null) {
                ItemStack stack = machine.getStackForm(1L);
                String name = stack == null ? machine.getLocalName() : stack.getDisplayName();
                if (name != null && !name.isEmpty()) return name;
            }
        }
        return entry.name == null ? "" : entry.name;
    }
}
