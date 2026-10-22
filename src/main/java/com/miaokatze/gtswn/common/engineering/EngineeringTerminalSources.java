package com.miaokatze.gtswn.common.engineering;

import java.util.Arrays;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;

/** Shared read-only provider selection; the eight accessory slots remain actual accessories only. */
public final class EngineeringTerminalSources {

    public enum SourceOrigin {
        NONE,
        BAUBLE,
        INVENTORY
    }

    private EngineeringTerminalSources() {}

    public static Source resolve(EntityPlayer player, int slot) {
        return player == null ? Source.EMPTY
            : resolve(TerminalBaubles.terminals(player), player.inventory.mainInventory, slot);
    }

    static Source resolve(ItemStack[] accessories, ItemStack[] inventory, int slot) {
        if (slot < 0 || slot >= TerminalBaubles.SIZE) return Source.EMPTY;
        ItemStack accessory = accessories[slot];
        // An equipped but uninitialized terminal still occupies its real slot: never substitute another item.
        if (accessory != null) return new Source(accessory, SourceOrigin.BAUBLE);
        if (slot == 0 || slot == 2) for (ItemStack candidate : inventory) {
            if (TerminalBaubles.category(candidate) != slot) continue;
            Source source = new Source(candidate, SourceOrigin.INVENTORY);
            if (source.present()) return source;
        }
        return Source.EMPTY;
    }

    public static String binding(ItemStack stack) {
        int category = TerminalBaubles.category(stack);
        if (category == 0) {
            java.util.UUID id = MonitorBindings.id(stack);
            return id == null ? ""
                : id + ":" + MonitorBindings.revision(stack) + ":" + PortableWirelessNetworkMonitor.getOwnerUUID(stack);
        }
        if (category == 2) {
            java.util.UUID id = ItemDeviceInfoTerminal.readTerminalId(stack);
            return id == null ? "" : id.toString();
        }
        if (category == 3) return Arrays.toString(ItemNetworkQuantumTerminal.getAnchor(stack)) + ":"
            + (stack.hasTagCompound() ? stack.getTagCompound()
                .getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION) : "");
        return "";
    }

    public static final class Source {

        public static final Source EMPTY = new Source(null, SourceOrigin.NONE);
        public final ItemStack stack;
        public final SourceOrigin origin;
        /** Captured binding, so changing NBT on the same ItemStack invalidates the old source. */
        public final String binding;

        Source(ItemStack stack, SourceOrigin origin) {
            this.stack = stack;
            this.origin = origin;
            this.binding = EngineeringTerminalSources.binding(stack);
        }

        public boolean present() {
            int category = TerminalBaubles.category(stack);
            return category >= 0 && (category != 0 && category != 2 || !binding.isEmpty());
        }

        public boolean sameIdentity(Source current) {
            return current != null && present()
                && current.present()
                && origin == current.origin
                && stack.getItem() == current.stack.getItem()
                && binding.equals(current.binding);
        }
    }
}
